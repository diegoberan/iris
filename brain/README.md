# Brain — the Iris Orchestrator (reference copy)

The **Brain** is a persistent agent, one per user, built on
[Hermes Agent](https://github.com/NousResearch/hermes-agent) (MIT). It holds memory,
tools, and orchestration and stays reachable 24/7 while devices (Bodies) come and go.

This folder is a **read-only reference copy** of the single most important
Iris-specific file in the Brain, so reviewers can read the core routing logic without
cloning the full Hermes-based Brain:

## [`orchestrator.py`](orchestrator.py) — the `llm.chat` tier router

One loopback OpenAI-compatible endpoint that routes every `llm.chat` across AMD compute
tiers, with recovery between turns:

```
desktop-local (customer's AMD Radeon / ROCm)
  → amd-cloud (AMD Instinct MI300 / vLLM)
    → cloud-fallback (Fireworks, approved compute, not AMD-hosted)
```

- A tier that is absent, times out, or errors advances the chain **for the next turn** —
  conversation state stays in the Brain; there is no mid-token migration.
- The tier that actually answered is broadcast (`iris.tier.used`) so every client shows
  whether the reply came from the user's own GPU or AMD cloud.
- Runs only when opted in (`IRIS_ORCHESTRATOR=1` / `IRIS_ORCHESTRATOR_PORT`), binds
  `127.0.0.1` only. The agent core sees just another OpenAI-compatible endpoint.

It relays to a connected Desktop Node over the gateway WebSocket
(`request_desktop_llm` in the Brain's `tui_gateway/server.py`) for the local tier, and
proxies straight HTTP for the cloud tiers. The device-capability plumbing
(announce / act / health) lives in the same gateway; see
[the Capability Protocol RFC](../docs/rfc/0001-capability-protocol.md) and
[AMD usage](../docs/amd-usage.md).

> This copy is for reading. The Brain runs the file in its Hermes runtime context, not
> from this repo.
