# Iris Orchestrator: loopback OpenAI-compatible endpoint (port is derived
# per tenant by provision-user.sh as dashboard port + 1000, substituted into
# {{ORCH_PORT}}) behind the "LM Studio Bridge" provider (config.d/vllm.yaml).
# Tier 1: relay over the gateway WebSocket to a connected Desktop node that
#         announced an llm capability (customer's own GPU via LM Studio).
# Tier 2: this host's vLLM (AMD Instinct) when no desktop node answers.
IRIS_ORCHESTRATOR=1
IRIS_ORCHESTRATOR_PORT={{ORCH_PORT}}
GEMMA_AMD_BASE_URL=http://localhost:8000/v1
GEMMA_AMD_MODEL=google/gemma-4-31B-it
IRIS_DESKTOP_LLM_TIMEOUT=300
