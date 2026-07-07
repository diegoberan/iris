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

## Local-tier tuning log

- One model at a time on the 16 GB card: co-resident models spill to system RAM and
  starve the GPU (observed: 29s avg with Ornith-9B co-loaded → 6.5s solo).
- Flash attention + KV cache quantization (q4_0) cut prompt-processing latency sharply.
- The QAT build ships with thinking mode enabled (`reasoning_content` in responses) —
  hidden reasoning tokens dominate latency on short tool-call turns.
