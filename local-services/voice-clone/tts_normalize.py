#!/usr/bin/env python3
"""Text normalizer for TTS (Speech Text Normalization).

Reads text on stdin -> returns speech-optimized text on stdout.
- TTS-engine independent (NeuTTS/F5/Qwen/any): runs BEFORE synthesis.
- Gated by ENABLE_TTS_NORMALIZER (off = passthrough = current behavior).
- LLM: configurable OpenAI-compatible endpoint.
- sqlite cache keyed by hash(prompt_version|model|text).
- FAIL-SAFE: any error/empty result -> returns the ORIGINAL text. Never breaks TTS.

Config: reads tts_normalizer.env (next to this file) + the key from ~/.hermes/.env.
"""

import os
import sys
import json
import sqlite3
import hashlib
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ENV_MAIN = os.path.expanduser("~/.hermes/.env")
CFG_FILE = os.path.join(HERE, "tts_normalizer.env")
CACHE_DB = os.path.join(HERE, "tts_norm_cache.db")
PROMPT_VERSION = "v2-en"

SYSTEM_PROMPT = """You are a text normalizer for speech synthesis (text-to-speech).
Your job: take the input text (usually Brazilian Portuguese) and output natural ENGLISH optimized to be spoken aloud by a TTS engine.

Main goal: maximize listening comprehension. The text will be read aloud by a virtual assistant inspired by Auntie Dot from Halo Reach. The listener must perfectly understand tasks, appointments, times, projects, acronyms, names, and operational instructions just by hearing the audio.

Rules:
- TRANSLATE the meaning faithfully into natural, fluent English.
- Preserve all information. Do not summarize.
- Do not explain. Do not answer the content. Output only the spoken English text.
- No markdown.
- Keep proper nouns (names of people, places, products) unless they have a common English form.
- Expand or rephrase elements that hurt listening comprehension.
- Spell out acronyms letter by letter when it improves pronunciation.
- Convert dates, numbers, and times to natural spoken English forms.
- Return ONLY the final English text, nothing else.

Examples:
Input: Diego, você tem 3 próximas ações para hoje.
Output: Diego, you have three next actions for today.
Input: Revisar PR do GTD Board.
Output: Review the P R on the G T D Board.
Input: Ligar para o Leopoldo às 14:30.
Output: Call Leopoldo at two thirty PM.
Input: Reunião na Unicamp em 23/06/2026.
Output: Meeting at Unicamp on June twenty third, twenty twenty six.
Input: Consultar ATA do CONSU.
Output: Check the minutes from the C O N S U.
Input: https://site.com
Output: site dot com."""


def _load_env(path):
    d = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                d[k.strip()] = v.strip()
    except Exception:
        pass
    return d


def _cfg():
    env = _load_env(CFG_FILE)
    main = _load_env(ENV_MAIN)
    enabled = env.get("ENABLE_TTS_NORMALIZER", "false").lower() == "true"
    model = env.get("NORMALIZER_MODEL", "deepseek-v4-flash")
    base = env.get("NORMALIZER_BASE_URL", "https://opencode.ai/zen/go/v1")
    key = env.get("OPENCODE_GO_API_KEY") or main.get("OPENCODE_GO_API_KEY", "")
    return enabled, model, base, key


def _cache_get(h):
    try:
        con = sqlite3.connect(CACHE_DB)
        con.execute("CREATE TABLE IF NOT EXISTS norm (h TEXT PRIMARY KEY, out TEXT)")
        row = con.execute("SELECT out FROM norm WHERE h=?", (h,)).fetchone()
        con.close()
        return row[0] if row else None
    except Exception:
        return None


def _cache_put(h, out):
    try:
        con = sqlite3.connect(CACHE_DB)
        con.execute("CREATE TABLE IF NOT EXISTS norm (h TEXT PRIMARY KEY, out TEXT)")
        con.execute("INSERT OR REPLACE INTO norm (h, out) VALUES (?,?)", (h, out))
        con.commit()
        con.close()
    except Exception:
        pass


def _llm(text, model, base, key):
    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": text},
        ],
        "temperature": 0.2,
        "max_tokens": 2048,
        "stream": False,
    }).encode("utf-8")
    req = urllib.request.Request(
        base.rstrip("/") + "/chat/completions",
        data=body,
        headers={
            "Authorization": "Bearer " + key,
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": "curl/8.5.0",  # urllib's default UA gets blocked by Cloudflare (403)
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as r:
        data = json.loads(r.read().decode("utf-8"))
    return (data["choices"][0]["message"]["content"] or "").strip()


def normalize_text(text):
    """Translates pt->EN + normalizes for speech. FAIL-SAFE: any error/disabled/empty
    -> returns the ORIGINAL text. Never raises. Usable as an import (server.py)
    or via main() (stdin/stdout, VPS)."""
    raw = (text or "").strip()
    enabled, model, base, key = _cfg()

    # gate / nothing to do -> passthrough
    if not enabled or not raw or not key:
        return text

    h = hashlib.sha256(("|".join([PROMPT_VERSION, model, raw])).encode("utf-8")).hexdigest()
    cached = _cache_get(h)
    if cached is not None:
        return cached

    try:
        out = _llm(raw, model, base, key)
        if not out:  # empty response -> safe passthrough
            return text
        _cache_put(h, out)
        return out
    except Exception as e:
        sys.stderr.write("tts_normalize fail (passthrough): %s\n" % e)
        return text


def main():
    sys.stdout.write(normalize_text(sys.stdin.read()))


if __name__ == "__main__":
    main()
