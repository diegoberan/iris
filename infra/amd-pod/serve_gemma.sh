#!/usr/bin/env bash
# AMD hackathon pod — serve Gemma with vLLM + expose it through a quick tunnel.
# Paste into a terminal on the Jupyter pod (notebooks.amd.com/hackathon).
#
# GPU budget: ~48 GB. Model sizing notes (validate empirically, this is Gate 1b):
#   - gemma-4-26b-a4b-it  bf16 weights ~52 GB -> does NOT fit; try --quantization fp8
#     (MI300-class + ROCm vLLM supports fp8) -> ~26 GB weights, leaves room for KV.
#   - a 12B-class Gemma bf16 ~24 GB fits comfortably with 64k context.
#   - the *-nvfp4 variant is an NVIDIA format — assume unsupported on ROCm, skip.
#   - Hermes needs >= 64k context; drop --max-model-len last if memory is tight.
#
# Gemma weights are LICENSE-GATED on Hugging Face: accept the license with your HF
# account first, then set HF_TOKEN below. Keep the model in /workspace (persistent,
# 25 GB — one quantized model only; clean old snapshots if switching).

set -euo pipefail

export HF_TOKEN="${HF_TOKEN:?set HF_TOKEN (HF account with Gemma license accepted)}"
export HF_HOME=/workspace/hf          # persistent cache — survives pod restarts
MODEL="${GEMMA_MODEL:-google/gemma-4-26b-a4b-it}"
PORT="${PORT:-8000}"
EXTRA_ARGS="${VLLM_EXTRA_ARGS:---quantization fp8 --max-model-len 65536}"

echo ">>> serving $MODEL on :$PORT (extra: $EXTRA_ARGS)"
nohup vllm serve "$MODEL" --port "$PORT" \
    --gpu-memory-utilization 0.92 $EXTRA_ARGS \
    > /workspace/vllm.log 2>&1 &

echo ">>> waiting for vLLM to come up (first run downloads weights — can take a while)"
until curl -sf "http://127.0.0.1:$PORT/v1/models" > /dev/null; do
    sleep 10; tail -1 /workspace/vllm.log || true
done
echo ">>> vLLM is up:"
curl -s "http://127.0.0.1:$PORT/v1/models" | head -c 400; echo

# --- public tunnel (egress test — Gate 1b) ---------------------------------
if [ ! -x /workspace/cloudflared ]; then
    curl -L -o /workspace/cloudflared \
        https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
    chmod +x /workspace/cloudflared
fi
echo ">>> starting quick tunnel — copy the https://*.trycloudflare.com URL below"
echo ">>> then on the Brain: set GEMMA_AMD_BASE_URL=https://<url>/v1"
/workspace/cloudflared tunnel --url "http://127.0.0.1:$PORT" 2>&1 | grep -E "trycloudflare|error" &

# --- smoke test -------------------------------------------------------------
sleep 5
curl -s "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H "Content-Type: application/json" \
    -d "{\"model\": \"$MODEL\", \"messages\": [{\"role\":\"user\",\"content\":\"Say OK.\"}], \"max_tokens\": 5}" \
    | head -c 400; echo
echo ">>> record this terminal + 'watch rocm-smi' for the demo video footage (do it NOW, day 1)"
