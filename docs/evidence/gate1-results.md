# Gate 1 evidence — tool-calling quality across inference tiers

Harness: [`tools/gate1_toolcall_test.py`](../../tools/gate1_toolcall_test.py) — four
deterministic scenarios (single call, enum discipline, no-tool discipline, multi-turn
chain), scored programmatically. No LLM judge, no self-reported numbers.

## Results (2026-07-07)

| Tier | Endpoint | Model | Quality | Malformed JSON | Warm latency (avg / max) |
|---|---|---|---|---|---|
| Local — **AMD Radeon gfx1200** | LM Studio (Vulkan, flash attn, KV q4_0) | `google/gemma-4-12b-qat` Q4 | **100%** (32/32 calls) | 0 | 6.5s / 17.5s |
| Baseline (calibration) | OpenCode Zen | `deepseek-v4-flash` | 100% (12/12) | 0 | 2.9s / 6.7s |
| Cloud — AMD Instinct (vLLM) | pending pod access | `gemma-4-26b-a4b-it` fp8 · `gemma-4-31b-it` fp8 | — | — | — |
| Local — Ornith family | LM Studio | `ornith-1.0-9b` Q4 | queued | — | — |

## Verdict

**Gate 1 GREEN: Gemma 4 drives the agent loop.** Tool-calling quality is not the risk;
remaining work is latency tuning and the AMD Instinct tier measurement.

## Orchestrator failover — live cycle (2026-07-07, production Brain on Oracle VPS)

The Orchestrator (`feat/iris-orchestrator` branch) routed the same request across
capability tiers as the local body was killed and resurrected on camera-equivalent
conditions. Tier reported by the `X-Iris-Tier` response header:

| Body state | Winning tier | Model that answered |
|---|---|---|
| LM Studio alive, Desktop announced | `desktop-local` (WS relay to the user's Radeon) | `google/gemma-4-12b-qat` |
| LM Studio killed | `fireworks` (serverless fallback) | `accounts/fireworks/models/kimi-k2p6` |
| LM Studio restarted, Desktop re-announced | `desktop-local` | `google/gemma-4-12b-qat` |

The `amd-instinct` tier (vLLM on the AMD GPU cloud pod) slots between these two —
pending pod access.

**Real-world agentic validation (same day):** on a live "read my emails" request,
the production Brain — running the full agent loop on Gemma 4 12B on the user's own
Radeon via the desktop-local tier — first selected the Workspace tool, detected the
expired Google OAuth, and explained the failure cleanly; after re-authentication it
identified the token and **read the user's inbox end-to-end**. Both paths validated
in the real loop: graceful failure handling and the happy path. The email content
was processed by a model on the user's own GPU — it never touched a commercial LLM
API.

Additional data:
- Ornith-1.0-9B (community agentic model, Qwen-based) on the same local tier:
  100% Gate-1, 4.9s avg — kept as a benchmark; not Gemma-based, so not the
  announced demo model.
- 128K-context fill on the local tier: VRAM flat (KV pre-allocated by llama.cpp);
  prompt processing 883→574 tok/s from 12K→80K tokens.

## Local-tier tuning log

- One model at a time on the 16 GB card: co-resident models spill to system RAM and
  starve the GPU (observed: 29s avg with Ornith-9B co-loaded → 6.5s solo).
- Flash attention + KV cache quantization (q4_0) cut prompt-processing latency sharply.
- The QAT build ships with thinking mode enabled (`reasoning_content` in responses) —
  hidden reasoning tokens dominate latency on short tool-call turns. Disabled via the
  model's inference settings.
- **Prefix cache is the decisive lever** (LM Studio 0.4.18, ROCm runtime): with
  Max Concurrent Predictions = 1, an 8K-token prompt drops from 9.1s (cold) to
  **1.1s (warm, 8.4×)** on repeat — the agent's stable system-prompt prefix is paid
  once per session, each turn only processes its delta. Short turns: 0.5s.
