# Local tier — Gemma on the AMD Radeon (gfx1200)

The Desktop Node serves Gemma on the user's own GPU and announces it as an
`llm.chat` capability. Setup on Diego's machine (LM Studio already installed):

## 1. Serve Gemma locally

1. LM Studio → download a Gemma instruct model that fits 16 GB VRAM alongside the
   TTS load (12B-class at Q4, or 4B-class for headroom — pick after checking VRAM
   with the F5-TTS service running).
2. Runtime: **Vulkan** (works on gfx1200 today) — try ROCm runtime if available.
3. Developer tab → Start server → confirm it listens on `127.0.0.1:1234`.
   API key: any string ("lm-studio" convention).
4. Smoke test: `curl http://127.0.0.1:1234/v1/models`

Headless alternative (managed by the Desktop as a Local Service): `lms server start`.

## 2. Register as a Local Service (Desktop UI)

Settings → Local Services → Add service:

| Field | Value |
|---|---|
| Name | Gemma (Local GPU) |
| Command | `lms` |
| Arguments | `server start --port 1234` |
| Health URL | `http://127.0.0.1:1234/v1/models` |
| Auto-start | on |

Capabilities payload (the announce ties this service to `llm.chat` — same pattern
as the existing dot-tts `speech` capability):

```json
"capabilities": {
  "llm": { "localUrl": "http://127.0.0.1:1234/v1", "model": "<lm-studio-model-id>", "hardware": "gfx1200" }
}
```

## 3. Reaching it from the Brain (VPS)

Two options, decided in the Orchestrator work (Phase 1):
- **Tunnel**: `cloudflared tunnel` from the desktop → stable `gemma.dberan.dev`
  ingress (same pattern as gtd/adria tunnels; infra already exists).
- **Relay**: reuse the desktop-session pattern the speech capability uses
  (Brain → gateway WS → Desktop). More elegant, more work — only if time allows.

Demo kill-switch = stop this Local Service in the Desktop UI (on camera).
