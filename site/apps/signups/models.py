from django.db import models
from django.contrib.auth.models import User

class Signup(models.Model):
    """One customer's onboarding request."""

    class Plan(models.TextChoices):
        BYOK = "byok", "Bring your own key"
        MANAGED = "managed", "Managed compute"

    class Provider(models.TextChoices):
        OPENROUTER = "openrouter", "OpenRouter (Recommended)"
        OPENAI = "openai", "OpenAI"
        ANTHROPIC = "anthropic", "Anthropic"
        FIREWORKS = "fireworks", "Fireworks"
        GEMINI = "gemini", "Google Gemini"

    class Status(models.TextChoices):
        PENDING_PAYMENT = "pending_payment", "Pending payment"
        PENDING_KEY = "pending_key", "Waiting for API key"
        READY = "ready", "Ready to provision"
        PROVISIONING = "provisioning", "Provisioning"
        PROVISIONED = "provisioned", "Provisioned"
        PROVISION_FAILED = "provision_failed", "Provisioning failed"

    user = models.ForeignKey(User, null=True, blank=True, on_delete=models.SET_NULL, related_name="signups")
    email = models.EmailField()
    plan = models.CharField(max_length=16, choices=Plan.choices)
    provider = models.CharField(max_length=32, choices=Provider.choices, default=Provider.OPENROUTER)
    api_key = models.CharField(max_length=256, blank=True, default="")
    stripe_customer_id = models.CharField(max_length=64, blank=True, default="")
    stripe_session_id = models.CharField(max_length=128, unique=True)
    status = models.CharField(
        max_length=20, choices=Status.choices, default=Status.PENDING_PAYMENT
    )
    
    # VM provisioning credentials
    provision_username = models.CharField(max_length=64, blank=True, default="")
    provision_password = models.CharField(max_length=128, blank=True, default="")
    provision_url = models.CharField(max_length=256, blank=True, default="")
    
    created_at = models.DateTimeField(auto_now_add=True)
    provisioned_at = models.DateTimeField(null=True, blank=True)

    def __str__(self) -> str:
        return f"{self.email} ({self.get_plan_display()}, {self.get_status_display()})"

    def masked_api_key(self) -> str:
        if not self.api_key:
            return ""
        return f"...{self.api_key[-4:]}" if len(self.api_key) > 4 else "****"


class AllowedProvisionEmail(models.Model):
    """Emails that are authorized to trigger automatic VM provisioning."""
    email = models.EmailField(unique=True)
    note = models.CharField(max_length=255, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        verbose_name = "Allowed Provision Email"
        verbose_name_plural = "Allowed Provision Emails"

    def __str__(self) -> str:
        return f"{self.email} ({self.note})"


class PendingGoogleAuth(models.Model):
    """A Google OAuth flow started by a tenant's own Hermes environment,
    waiting for the callback. `state` is the correlation key Google echoes
    back; `code_verifier` is the PKCE secret needed to complete the token
    exchange. Single-use -- deleted once the callback consumes it.
    """

    state = models.CharField(max_length=64, unique=True)
    tenant_username = models.CharField(max_length=64)
    code_verifier = models.CharField(max_length=128)
    created_at = models.DateTimeField(auto_now_add=True)

    def __str__(self) -> str:
        return f"{self.tenant_username} ({self.state[:8]}...)"
