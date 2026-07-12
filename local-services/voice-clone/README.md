# Voice Clone — F5-TTS on the local AMD GPU (reference copy)

This is the **Local Service** behind the demo's cloned voice: a small FastAPI server
that runs **F5-TTS (F5TTS_v1_Base) on the user's own AMD Radeon via torch-ROCm**
(gfx1200 / RX 9060 XT in the demo) and exposes voice cloning to the Brain.

The Desktop Node manages it through the Local Services panel (start/stop/health) and
announces it as the `speech.synthesis` capability with `voices: ["dot"]`. When the
desktop is online, the Brain routes TTS here — your voice never leaves your machine.
When it is not, the speech router falls back to a generic cloud voice.

## Files

| File | What it is |
|---|---|
| [`server.py`](server.py) | FastAPI server: `/health`, `/voices` (list/create/delete — schema-driven UI in the desktop app), `/speak` (chunked synthesis, WAV out). ROCm attention flags + warmup included. |
| [`tts_normalize.py`](tts_normalize.py) | Optional fail-safe pre-TTS layer: translates/normalizes text for listening comprehension via any OpenAI-compatible LLM. Disabled = passthrough. |
| [`requirements-f5.txt`](requirements-f5.txt) | Pinned deps + the ROCm torch install order (AMD wheel index, not PyPI). |

## Run

```bash
# after the venv setup described in requirements-f5.txt:
uvicorn server:app --host 127.0.0.1 --port 8123
curl http://127.0.0.1:8123/health
```

Then register it in the desktop app (Settings → Local Services) with health URL
`http://127.0.0.1:8123/health`. Voice reference audio (8–15s WAV + exact transcript)
is uploaded through the desktop's Voices panel — reference recordings are personal
data and are not part of this repo.

> Reference copy of the private `dot-tts-local` service so reviewers can read the
> exact code that served the demo. Comments were translated to English; a personal
> home path was generalized; behavior is identical.
