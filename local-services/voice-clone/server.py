import os
import io
import re

# F5-TTS on ROCm: efficient attention + hybrid MIOpen kernel-search mode.
# Without these, attention falls into a slow path (RTF ~3-4x instead of <1x) on gfx1200.
os.environ.setdefault("TORCH_ROCM_AOTRITON_ENABLE_EXPERIMENTAL", "1")
os.environ.setdefault("MIOPEN_FIND_MODE", "2")

import numpy as np
import soundfile as sf
from fastapi import FastAPI, Response, HTTPException, Header, UploadFile, Form
from pydantic import BaseModel
from f5_tts.api import F5TTS

# Pre-TTS translation/normalization layer (fail-safe). Same module used on the VPS.
# If the file or key is missing it becomes the identity function -> the server never breaks.
try:
    from tts_normalize import normalize_text
except Exception:
    def normalize_text(t):
        return t

MAX_CHUNK = 450  # The GPU handles more than the VPS; 450 reduces chunk count without degrading quality
_NOOP = lambda *a, **k: None  # silences f5_tts internal prints (a BOM in a ref txt crashes the Windows console)

print("Loading F5-TTS (F5TTS_v1_Base, ROCm)...", flush=True)
tts = F5TTS(model="F5TTS_v1_Base")
SR = tts.target_sample_rate

# ── Voices ───────────────────────────────────────────────────────────────────
import json
import shutil

_HERE = os.path.dirname(os.path.abspath(__file__))
VOICES_DIR = os.path.join(_HERE, "voices")
VOICES_JSON_PATH = os.path.join(_HERE, "voices.json")

def load_voices_config():
    os.makedirs(VOICES_DIR, exist_ok=True)
    if not os.path.exists(VOICES_JSON_PATH):
        old_wav = os.path.join(_HERE, "dot_ref_8s.wav")
        old_txt = os.path.join(_HERE, "dot_ref_8s.txt")
        dest_wav = os.path.join(VOICES_DIR, "dot.wav")
        dest_txt = os.path.join(VOICES_DIR, "dot.txt")
        if os.path.exists(old_wav):
            shutil.copy(old_wav, dest_wav)
        if os.path.exists(old_txt):
            shutil.copy(old_txt, dest_txt)
        initial_voices = [
            {
                "id": "dot",
                "name": "dot",
                "ref_wav": "voices/dot.wav",
                "ref_txt": "voices/dot.txt"
            }
        ]
        with open(VOICES_JSON_PATH, "w", encoding="utf-8") as f:
            json.dump(initial_voices, f, indent=2)
    with open(VOICES_JSON_PATH, "r", encoding="utf-8") as f:
        return json.load(f)

VOICES = {}

def reload_voices():
    global VOICES
    voices_data = load_voices_config()
    new_voices = {}
    for v in voices_data:
        id_val = v["id"]
        ref_wav_rel = v["ref_wav"]
        ref_txt_rel = v["ref_txt"]
        ref_wav_abs = os.path.join(_HERE, ref_wav_rel)
        ref_txt_abs = os.path.join(_HERE, ref_txt_rel)
        ref_text_content = ""
        if os.path.exists(ref_txt_abs):
            with open(ref_txt_abs, "r", encoding="utf-8-sig") as f:
                ref_text_content = f.read().strip()
        new_voices[id_val] = {
            "id": id_val,
            "name": v["name"],
            "ref_file": ref_wav_abs,
            "ref_text": ref_text_content,
            "ref_wav_rel": ref_wav_rel,
            "ref_txt_rel": ref_txt_rel
        }
    VOICES = new_voices

# Initial load
reload_voices()

# Warmup: pays the first kernel-compile cost (MIOpen/AOTriton) outside the real request path.
try:
    warmup_voice = next(iter(VOICES.values()))
    tts.infer(ref_file=warmup_voice["ref_file"], ref_text=warmup_voice["ref_text"], gen_text="Warmup.",
              show_info=_NOOP, progress=None)
    print("warmup ok", flush=True)
except Exception as e:
    print("warmup failed:", e, flush=True)



# ── Helpers ──────────────────────────────────────────────────────────────────

def chunk(text, maxlen=MAX_CHUNK):
    # Split on sentences first; only cut mid-sentence when a sentence > maxlen
    parts = re.split(r"(?<=[.!?])\s+", text.strip())
    chunks, cur = [], ""
    for p in parts:
        p = p.strip()
        if not p:
            continue
        if len(cur) + len(p) + 1 <= maxlen:
            cur = (cur + " " + p).strip()
        else:
            if cur:
                chunks.append(cur)
                cur = ""
            while len(p) > maxlen:
                cut = p.rfind(" ", 0, maxlen)
                if cut <= 0:
                    cut = maxlen
                chunks.append(p[:cut].strip())
                p = p[cut:].strip()
            cur = p
    if cur:
        chunks.append(cur)
    return chunks or [text.strip()]


def say(text, voice):
    def gen():
        wav, _, _ = tts.infer(
            ref_file=voice["ref_file"], ref_text=voice["ref_text"], gen_text=text,
            show_info=_NOOP, progress=None,
        )
        return np.asarray(wav, dtype=np.float32).flatten()

    wav = gen()
    tries = 0
    # Retry when the audio is too short (the model truncated)
    while len(wav) < int(0.8 * SR) and len(text) > 12 and tries < 3:
        tries += 1
        wav = gen()
    return wav


# ── App ───────────────────────────────────────────────────────────────────────

app = FastAPI()


class Req(BaseModel):
    voice: str = ""
    text: str = ""


@app.get("/health")
def health():
    return {"ok": True}


@app.get("/voices")
def get_voices():
    voices_data = load_voices_config()
    return [{"id": v["id"], "name": v["name"]} for v in voices_data]


@app.post("/voices")
async def create_voice(
    name: str = Form(...),
    ref_wav: UploadFile = Form(...),
    ref_text: str = Form(...)
):
    name_stripped = name.strip()
    if not name_stripped:
        raise HTTPException(status_code=400, detail="Voice name is required")
    voice_id = re.sub(r'[^a-z0-9]+', '-', name_stripped.lower()).strip('-')
    if not voice_id:
        voice_id = "voice"

    voices_data = load_voices_config()
    original_id = voice_id
    counter = 1
    while any(v["id"] == voice_id for v in voices_data):
        voice_id = f"{original_id}-{counter}"
        counter += 1

    wav_filename = f"{voice_id}.wav"
    txt_filename = f"{voice_id}.txt"
    wav_path = os.path.join(VOICES_DIR, wav_filename)
    txt_path = os.path.join(VOICES_DIR, txt_filename)

    contents = await ref_wav.read()
    with open(wav_path, "wb") as f:
        f.write(contents)

    with open(txt_path, "w", encoding="utf-8") as f:
        f.write(ref_text.strip())

    new_voice = {
        "id": voice_id,
        "name": name_stripped,
        "ref_wav": f"voices/{wav_filename}",
        "ref_txt": f"voices/{txt_filename}"
    }
    voices_data.append(new_voice)
    with open(VOICES_JSON_PATH, "w", encoding="utf-8") as f:
        json.dump(voices_data, f, indent=2)

    reload_voices()
    return {"ok": True, "id": voice_id}


@app.delete("/voices/{id}")
def delete_voice(id: str):
    voices_data = load_voices_config()
    voice_to_delete = None
    for v in voices_data:
        if v["id"] == id:
            voice_to_delete = v
            break
    if not voice_to_delete:
        raise HTTPException(status_code=404, detail="Voice not found")
    if len(voices_data) <= 1:
        raise HTTPException(status_code=400, detail="Cannot delete the last remaining voice")

    wav_path = os.path.join(_HERE, voice_to_delete["ref_wav"])
    txt_path = os.path.join(_HERE, voice_to_delete["ref_txt"])
    if os.path.exists(wav_path):
        try:
            os.remove(wav_path)
        except Exception as e:
            print(f"Error removing {wav_path}: {e}")
    if os.path.exists(txt_path):
        try:
            os.remove(txt_path)
        except Exception as e:
            print(f"Error removing {txt_path}: {e}")

    voices_data = [v for v in voices_data if v["id"] != id]
    with open(VOICES_JSON_PATH, "w", encoding="utf-8") as f:
        json.dump(voices_data, f, indent=2)

    reload_voices()
    return {"ok": True}


@app.post("/speak")
def speak(r: Req, x_pretranslated: str = Header(default=None)):
    voice_id = r.voice
    if not voice_id:
        if VOICES:
            voice_id = next(iter(VOICES.keys()))
        else:
            raise HTTPException(status_code=400, detail="No voices available")

    voice = VOICES.get(voice_id)
    if not voice:
        raise HTTPException(status_code=404, detail="voice not found")

    text = (r.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="text required")

    # The desktop path sends raw Portuguese -> translate pt->EN here. The VPS already
    # translated at the source (sends X-Pretranslated) -> skip, avoids translating twice.
    if not x_pretranslated:
        text = (normalize_text(text) or text).strip()

    parts = chunk(text)
    sil = np.zeros(int(0.15 * SR), dtype=np.float32)
    pieces = []
    for i, c in enumerate(parts):
        pieces.append(say(c, voice))
        if i != len(parts) - 1:
            pieces.append(sil)

    wav = np.concatenate(pieces) if pieces else np.zeros(1, dtype=np.float32)
    buf = io.BytesIO()
    sf.write(buf, wav, SR, format="WAV")
    return Response(content=buf.getvalue(), media_type="audio/wav")
