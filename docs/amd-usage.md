# AMD Compute Usage

Íris runs **AMD-hosted Gemma** across three tiers, chosen live per turn by the
`llm.chat` Orchestrator. This document is the machine-readable record of *what*
runs on *which* AMD hardware, *how* it is served, and *how to reproduce* it.

> Summary for pre-screening: Gemma runs on **AMD Radeon (RDNA4 / ROCm)** on the
> user's own machine and on **AMD Instinct MI300 (vLLM)** in the cloud. Both are
> real, measured, and demonstrated live in the video (the kill-switch failover).
> Fireworks is a non-AMD resilience fallback and is only used when both AMD tiers
> are unreachable.

---

## Tier 1 — Local: AMD Radeon RX 9060 XT (RDNA4, `gfx1200`) via ROCm

The Desktop Node serves Gemma on the user's own AMD GPU and announces it to the
Brain as an `llm.chat` capability. The same GPU also runs F5-TTS voice cloning
(torch-ROCm) for the `speech.synthesis` capability.

- **Hardware:** Radeon RX 9060 XT, 16 GB, `gfx1200` (RDNA4).
- **Runtime:** LM Studio (llama.cpp backend), OpenAI-compatible endpoint on
  `127.0.0.1:1234`. ROCm and Vulkan backends both validated.
- **Models:** `google/gemma-4-12b-qat` (Q4), `ornith-1.0-9b` (Q4), others hot-swappable.
- **Measured (`gfx1200`, llama.cpp, Ornith-9B Q4, 262K ctx):**
  ROCm ≈ **1,070 tok/s prefill · 35.3 tok/s generation**; Vulkan ≈ 1,143 / 36.5
  (statistical tie — backend choice is not load-bearing).
- **Config:** [`infra/local-radeon/README.md`](../infra/local-radeon/README.md),
  provider plugin [`providers/gemma-local/plugin.yaml`](../providers/gemma-local/plugin.yaml).

## Tier 2 — Cloud: AMD Instinct MI300 via vLLM

When the local body is offline, the Orchestrator reroutes here. This is the
**AMD-hosted** tier that keeps the assistant answering.

- **Hardware:** AMD Instinct MI300 (AMD Developer Cloud instance).
- **Runtime:** vLLM inside a ROCm container, serving `google/gemma-4-31B-it` on
  `:8000/v1` (`--max-model-len 262144`, gemma4 tool-call + reasoning parsers).
- **Measured:** ≈ **71 tok/s generation** — roughly 2× the local 12B — at ~262K context.
- **Config:** [`infra/amd-pod/serve_gemma.sh`](../infra/amd-pod/serve_gemma.sh),
  provider plugin [`providers/gemma-amd/plugin.yaml`](../providers/gemma-amd/plugin.yaml).

## Tier 3 — Fallback: Fireworks AI (approved compute, NOT AMD-hosted)

Serverless resilience, used only when **both** AMD tiers are unreachable. It is a
hackathon-**approved** compute provider (the Track 3 brief lists "AMD / Fireworks /
approved compute"), so it is a legitimate part of the stack — but it is explicitly
**not** counted toward the AMD-hosted claim. Why it matters: the cloud Gemma tier
runs *inside* the AMD MI300 pod, so if that pod goes down (e.g. compute credits
expire), the MI300 tier goes with it — Fireworks is what keeps the assistant
answering in that case.

- **Config:** [`providers/fireworks/plugin.yaml`](../providers/fireworks/plugin.yaml).

## Additional AMD validation — Radeon PRO W7900

Gemma was also brought up on a **Radeon PRO W7900** workstation (48 GB, RDNA3) via
llama.cpp with a Gemma Q4 model, serving the same OpenAI-compatible `/v1` contract —
proving the local-tier route on AMD **workstation** hardware in addition to the
consumer Radeon and the Instinct cloud. This node is not currently online (the build
lives on the team notebook's drive); it is documented here as a validated third AMD
form factor. Across the three, Íris demonstrably runs Gemma on **consumer (RX 9060
XT), workstation (PRO W7900), and datacenter (Instinct MI300)** AMD GPUs under one
routing contract.

---

## How routing works (one contract, three tiers)

Every tier exposes the **same OpenAI-compatible `/v1/chat/completions`**, so the
agent core never changes provider-specific code. The Orchestrator
(`tui_gateway/orchestrator.py` in the Hermes brain) tries, in order:

```
desktop-local (Radeon/ROCm)  →  amd-cloud (Instinct/vLLM)  →  cloud-fallback (Fireworks)
```

A tier that is absent, times out, or errors advances the chain **for the next
turn** — conversation state stays in the Brain; there is no mid-token migration.
The tier that actually answered is broadcast (`iris.tier.used`) so every client's
UI can show whether the reply came from the user's own GPU or AMD cloud.

## Reproduce it

**Local tier (your Radeon):** install LM Studio, load `gemma-4-12b-qat`, start the
server on `:1234`. The Desktop app's Local Services panel health-checks it and
announces the `llm.chat` capability to the Brain.

**Cloud tier (AMD Instinct):** on an AMD Developer Cloud MI300 instance with a ROCm
container, run [`infra/amd-pod/serve_gemma.sh`](../infra/amd-pod/serve_gemma.sh)
(needs `HF_TOKEN`). Point the Brain's `GEMMA_AMD_BASE_URL` at the resulting `:8000/v1`.

**Failover demo:** with the local tier serving, kill LM Studio; the next answer comes
from the MI300 tier automatically (`X-Iris-Tier: amd-cloud`), then returns to local
when it comes back — no user action.

## Evidence

- Tool-calling quality + latency by tier: [`docs/evidence/gate1-results.md`](evidence/gate1-results.md)
- Android Node validation on real hardware: [`docs/evidence/gate2-android-node.md`](evidence/gate2-android-node.md)
