from django.db import models


class Signup(models.Model):
    """One customer's onboarding request. Provisioning is manual (hackathon
    scope -- see D6 in the execution plan): this row is the queue Diego works
    off of, not an automated pipeline. Both plans are paid -- BYOK is cheaper
    because it only covers hosting + fork maintenance, not AMD compute time;
    managed compute costs more because it includes inference on Diego's pod.
    """

    class Plan(models.TextChoices):
        BYOK = "byok", "Bring your own key"
        MANAGED = "managed", "Managed compute"

    class Status(models.TextChoices):
        PENDING_PAYMENT = "pending_payment", "Pending payment"
        PENDING_KEY = "pending_key", "Waiting for API key"
        READY = "ready", "Ready to provision"
        PROVISIONED = "provisioned", "Provisioned"

    email = models.EmailField()
    plan = models.CharField(max_length=16, choices=Plan.choices)
    api_key = models.CharField(max_length=256, blank=True, default="")
    stripe_customer_id = models.CharField(max_length=64, blank=True, default="")
    stripe_session_id = models.CharField(max_length=128, unique=True)
    status = models.CharField(
        max_length=20, choices=Status.choices, default=Status.PENDING_PAYMENT
    )
    created_at = models.DateTimeField(auto_now_add=True)
    provisioned_at = models.DateTimeField(null=True, blank=True)

    def __str__(self) -> str:
        return f"{self.email} ({self.get_plan_display()}, {self.get_status_display()})"

    def masked_api_key(self) -> str:
        if not self.api_key:
            return ""
        return f"...{self.api_key[-4:]}" if len(self.api_key) > 4 else "****"
