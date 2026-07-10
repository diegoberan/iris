# Gate 2 — Android Node (Fase 2A)

## Wire trail validated end-to-end before the app existed (2026-07-09)

A simulated Node (Python, same auth + protocol the app uses) ran against the
**production Brain** on the VPS:

1. `POST /auth/password-login` (provider `basic`) → session cookies
2. `POST /api/auth/ws-ticket` → single-use WS ticket
3. `wss://…/api/ws?ticket=` → `hermes.capabilities.announce`
   `{device: "android", notification: {available}, location: {available}}`
4. `POST http://127.0.0.1:8092/iris/notify` (Orchestrator loopback surface)
   → `notification.send.request` over the Node's WS →
   `device.action.response` ack → HTTP `200 {"delivered": true, "device": "android-sim"}`
5. `GET http://127.0.0.1:8092/iris/location` → `location.current.request` →
   fix returned → HTTP `200 {"latitude": …, "longitude": …, "accuracy": …}`

Both directions of the act pattern (push Brain→body, pull body→Brain) answered
correctly, and both endpoints return `503` with a clear reason when no Node
announced the capability.

## Agent integration

Skill `iris-body` (installed on the Brain) drives both endpoints through the
terminal tool — no core tool registration, the Orchestrator's loopback HTTP is
the whole integration surface.

## App

`android/` — Kotlin, Ktor WS client, foreground service, ~500 lines total:
announce on every (re)connect, `notification.send` posts a high-priority OS
notification (auto-mirrors to a paired Watch), `location.current` serves a GPS
fix via `LocationManager.getCurrentLocation`. `assembleDebug` builds clean on
the same toolchain as the (production-validated) Wear app.

Pending live run: install on the physical phone, connect to the production
gateway, re-run steps 4–5 against the real device, capture footage
(notification arriving + Watch vibration mirror).
