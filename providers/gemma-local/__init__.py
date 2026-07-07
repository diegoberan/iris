"""Local Gemma provider profile — the Desktop Node's own AMD Radeon GPU.

Points at an OpenAI-compatible server on the user's machine (LM Studio /
llama.cpp with ROCm or Vulkan on gfx1200). The Desktop Node announces this
as an llm.chat capability; when the Brain runs on the same machine this
default loopback URL works as-is, otherwise set GEMMA_LOCAL_BASE_URL to the
tunnel/relay address.

Local servers usually accept any API key — "lm-studio" is the convention.

Drop into $HERMES_HOME/plugins/model-providers/gemma-local/.
"""

import os

from providers import register_provider
from providers.base import ProviderProfile

gemma_local = ProviderProfile(
    name="gemma-local",
    aliases=("gemma-radeon", "local-gpu"),
    display_name="Gemma on local Radeon",
    description="Gemma on the user's own AMD GPU (ROCm/Vulkan, OpenAI-compatible)",
    signup_url="https://lmstudio.ai",
    env_vars=("GEMMA_LOCAL_API_KEY", "GEMMA_LOCAL_BASE_URL"),
    base_url=os.environ.get("GEMMA_LOCAL_BASE_URL", "http://127.0.0.1:1234/v1"),
    default_aux_model=os.environ.get("GEMMA_LOCAL_MODEL", "gemma-4-12b-it"),
)

register_provider(gemma_local)
