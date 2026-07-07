"""Fireworks AI provider profile — serverless fallback tier for llm.chat.

Drop this directory into $HERMES_HOME/plugins/model-providers/fireworks/
on the Brain. No core changes required.
"""

from providers import register_provider
from providers.base import ProviderProfile

fireworks = ProviderProfile(
    name="fireworks",
    aliases=("fireworks-ai",),
    display_name="Fireworks AI",
    description="Serverless OpenAI-compatible inference (Gemma fallback tier)",
    signup_url="https://fireworks.ai/account/api-keys",
    env_vars=("FIREWORKS_API_KEY",),
    base_url="https://api.fireworks.ai/inference/v1",
    default_aux_model="accounts/fireworks/models/gemma-4-26b-a4b-it",
)

register_provider(fireworks)
