# Íris

**One Brain. Multiple Bodies.**

Íris is an embodied AI platform: a persistent personal agent whose **brain lives in the
cloud** and whose **bodies are your devices**. Devices announce what they can do — GPU
inference, speech, sensors, notifications — and the brain decides where each action runs.

> Built for the **AMD Developer Hackathon: ACT II — Track 3 (Unicorn)**, featuring
> **AMD-hosted Gemma** on two tiers of AMD compute: a consumer Radeon GPU (ROCm) and
> AMD Instinct in the cloud (vLLM).

---

## Why this is different

| Typical AI assistants | Íris |
|---|---|
| Talk in a chat window | **Lives** — an always-on brain, not a session |
| One device, one app | **Many bodies** — desktop, phone, watch… and counting |
| Locked to one model | **Any model** — Gemma today, anything OpenAI-compatible tomorrow |
| Locked to one cloud | **Your GPU first, cloud when needed** — routed live, with failover |
| Apps expose interfaces | **Devices announce capabilities** — the brain orchestrates them |
| The vendor owns your agent | **You own the brain** — memory, data, models, infrastructure |

Read [The Embodied AI Manifesto](docs/MANIFESTO.md).

---

## Architecture

```
                        ┌─────────────────────────┐
                        │          BRAIN          │
                        │  persistent agent (VPS) │
                        │  Orchestrator + Registry│
                        └───────────┬─────────────┘
                 capability protocol │ (announce / act / health)
        ┌──────────────────┬────────┴─────────┬──────────────────┐
   ┌────┴─────┐      ┌─────┴─────┐      ┌─────┴─────┐      ┌─────┴─────┐
   │ DESKTOP  │      │  ANDROID  │      │   WATCH   │      │ AMD CLOUD │
   │  NODE    │      │   NODE    │      │   NODE    │      │ (Instinct)│
   ├──────────┤      ├───────────┤      ├───────────┤      ├───────────┤
   │llm.chat  │      │notification│     │(companion │      │ llm.chat  │
   │ (Gemma on│      │location   │      │  app +    │      │ (Gemma on │
   │  Radeon) │      │           │      │  mirror)  │      │  vLLM)    │
   │speech.   │      │           │      │           │      │           │
   │ synthesis│      │           │      │           │      │           │
   └──────────┘      └───────────┘      └───────────┘      └───────────┘
```

- **Brain** — a persistent Hermes-based agent instance, one per user, auto-provisioned.
- **Node** — any connected device. Nodes are bodies, not clients.
- **Capability** — anything a Node can do (`llm.chat`, `speech.synthesis`,
  `notification.send`, `location.current`, …).
- **Provider** — a concrete implementation of a Capability.
- **Orchestrator** — picks the best Provider for each action, with live failover.

Protocol details: [RFC 0001 — Capability Protocol](docs/rfc/0001-capability-protocol.md).

---

## AMD Compute Usage

This project runs **AMD-hosted Gemma** at two tiers, orchestrated live:

1. **Local tier — AMD Radeon (RDNA4, gfx1200) via ROCm.** The Desktop Node serves Gemma on
   the user's own GPU and announces it as an `llm.chat` capability. Voice synthesis
   (F5-TTS voice cloning) also runs on the same AMD GPU via torch-ROCm.
2. **Cloud tier — AMD Instinct via vLLM.** Gemma served with vLLM on an AMD GPU cloud
   instance (AMD Developer Cloud / hackathon pod). When the local body disappears, the
   Orchestrator reroutes here — the demo kills the local service on camera.
3. **Serverless fallback — Fireworks AI.** Resilience tier (not AMD-hosted; used only when
   both AMD tiers are unreachable).

**Hardware, measured throughput, configs, and reproduction steps:
[docs/amd-usage.md](docs/amd-usage.md).**

---

## Built on open source

Íris builds on [Hermes Agent](https://github.com/NousResearch/hermes-agent) (MIT) by Nous
Research. The upstream core is not forked for this project's new work — capabilities,
providers, and orchestration extend it through its official plugin and configuration
surfaces.

**Our contributions** (this repo + linked components):
- The Capability Protocol (announce / act / health) and its RFC
- The `llm.chat` Orchestrator with capability-aware failover across AMD tiers
- Gemma model-provider plugins (local ROCm · AMD Instinct vLLM · Fireworks)
- Android Node (minimal embodiment: notifications + location)
- Multi-tenant provisioning layer and self-service onboarding
- Local Services: declarative device capabilities with a schema-driven desktop UI

## Where the code lives

This umbrella repo holds the Íris-specific work; the persistent Brain builds on
[Hermes Agent](https://github.com/NousResearch/hermes-agent).

| Component | Path | What it is |
|---|---|---|
| Gemma providers | [`providers/`](providers/) | Declarative plugins: `gemma-local` (Radeon/ROCm), `gemma-amd` (Instinct/vLLM), `fireworks` (fallback). One `plugin.yaml` + `__init__.py` each. |
| AMD cloud serving | [`infra/amd-pod/serve_gemma.sh`](infra/amd-pod/serve_gemma.sh) | vLLM-on-MI300 launch script (Gemma, 262K ctx). |
| Local GPU serving | [`infra/local-radeon/README.md`](infra/local-radeon/README.md) | LM Studio on Radeon `gfx1200` notes. |
| Android Node | [`android/`](android/) | Kotlin/Compose body (`ai.iris.node`): `notification.send`, `location.current` over a Ktor WebSocket. |
| Voice Clone service | [`local-services/voice-clone/`](local-services/voice-clone/) | F5-TTS on the local Radeon via torch-ROCm: FastAPI `/speak`, voice management API, pre-TTS normalizer. Announced as `speech.synthesis`. |
| Desktop Local Services | [`desktop/`](desktop/) | The desktop-client subsystem behind the Bodies: service registry + lifecycle, schema-driven panels, capability announce, LM Studio `llm.chat` backend. |
| SaaS + provisioning | [`site/`](site/) | Django site: signup, Stripe checkout, and automated per-tenant Brain provisioning. |
| Capability Protocol | [`docs/rfc/0001-capability-protocol.md`](docs/rfc/0001-capability-protocol.md) | announce / act / health, first-response-wins routing, failover. |
| Orchestrator | [`brain/orchestrator.py`](brain/orchestrator.py) | The `llm.chat` tier router: desktop-local → amd-cloud → fallback (reference copy; runs as `tui_gateway/orchestrator.py` inside the Brain). |
| Slides + technical deck | [`docs/deck/`](docs/deck/) | The 6-slide submission deck and the 14-page technical appendix (architecture, protocol, AMD tiers, product path). |

## Setup

Each component runs independently; you don't need all of them to see the core idea.

**Gemma on your AMD GPU (local tier):** install [LM Studio](https://lmstudio.ai),
load `gemma-4-12b-qat`, and start its server on `127.0.0.1:1234`. The desktop
client's Local Services panel discovers it and announces the `llm.chat` capability.

**Gemma on AMD Instinct (cloud tier):** on an AMD Developer Cloud MI300 instance with
a ROCm container, run `bash infra/amd-pod/serve_gemma.sh` (needs `HF_TOKEN`); point the
Brain's `GEMMA_AMD_BASE_URL` at the resulting `:8000/v1`.

**The SaaS site:** `cd site && uv sync && python manage.py migrate && python manage.py
runserver`. Config via `site/.env` (`SECRET_KEY`, `STRIPE_*`, `USE_SQLITE`). Signup
triggers automated per-tenant Brain provisioning ([`site/apps/signups/provisioning.py`](site/apps/signups/provisioning.py)).

**Android Node:** open [`android/`](android/) in Android Studio and run the `app`
module, or sideload the prebuilt APK from the site's Downloads page. Point it at your
Brain's gateway URL.

Full AMD tiers, throughput numbers, and failover reproduction:
**[docs/amd-usage.md](docs/amd-usage.md)**.

## External services

- **AMD Developer Cloud** (MI300) — cloud Gemma tier (vLLM).
- **Fireworks AI** — serverless LLM fallback only (not AMD-hosted).
- **Stripe** (test mode) — checkout for managed-compute / BYOK plans.
- **Google Workspace OAuth** — optional per-tenant Gmail/Calendar/Drive integration.
- **Cloudflare + Caddy** — DNS and automatic per-tenant TLS.

## Status

Hackathon MVP — **AMD Developer Hackathon ACT II, Track 3**. Desktop, Android, and the
AMD-tier failover are demonstrated live in the video; Wear OS is an active companion
client.

## License

[MIT](LICENSE)
