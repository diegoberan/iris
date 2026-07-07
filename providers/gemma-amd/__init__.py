"""AMD-hosted Gemma provider profile — cloud tier (vLLM on AMD Instinct).

Points at the vLLM OpenAI-compatible server running on the AMD GPU cloud
pod (see infra/amd-pod/serve_gemma.sh). The tunnel URL changes per pod
session — set GEMMA_AMD_BASE_URL in the Brain's ~/.hermes/.env.

Drop into $HERMES_HOME/plugins/model-providers/gemma-amd/.
"""

import os

from providers import register_provider
from providers.base import ProviderProfile

gemma_amd = ProviderProfile(
    name="gemma-amd",
    aliases=("gemma-instinct", "amd-cloud"),
    display_name="Gemma on AMD Instinct",
    description="AMD-hosted Gemma via vLLM on AMD GPU cloud (Instinct)",
    signup_url="https://notebooks.amd.com/hackathon",
    env_vars=("GEMMA_AMD_API_KEY", "GEMMA_AMD_BASE_URL"),
    base_url=os.environ.get("GEMMA_AMD_BASE_URL", "http://127.0.0.1:8000/v1"),
    default_aux_model=os.environ.get("GEMMA_AMD_MODEL", "google/gemma-4-26b-a4b-it"),
)

register_provider(gemma_amd)
