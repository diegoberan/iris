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

## Live run on the physical device (2026-07-09, same night)

Installed on a Galaxy S20 FE (SM-G780G) over adb; the app authenticated against
the production gateway at `https://diego.dberan.dev` (through Cloudflare) and
announced. Same two calls, now answered by real hardware:

- `POST /iris/notify` → `200 {"delivered": true, "device": "android"}` — the
  notification posted on the phone.
- `GET /iris/location` → `200 {"latitude": -22.82…, "longitude": -47.08…,
  "accuracy": 30.8, "device": "android"}` — a live GPS fix from the phone,
  served to the Brain on demand.

One field bug found and fixed on the way: kotlinx.serialization omits
default-valued fields unless `encodeDefaults = true`, so the login body shipped
without `provider: "basic"` and 422'd (surfaced as "login rejected").

**Agent-driven run (same night):** with the `iris-body` skill loaded, the user
asked the production agent for a phone notification and for their current
location in a live session — both actions completed end-to-end on the physical
device. Gate 2 closed green.

Remaining for the video: footage capture. Notification scene can run on TWO
phones at once (the act request is broadcast to every Node announcing the
capability — install the same APK on the second phone), with the Watch
mirroring from its paired phone; the Watch also appears as its own body via
the native GTD app, independent of notification mirroring.
