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

*Evidence, configs, and reproduction steps: see [docs/amd-usage.md](docs/amd-usage.md)* (WIP).

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

## Status

Hackathon MVP under active development — submission target: **July 11, 2026**.

## License

[MIT](LICENSE)
