from django.contrib import admin
from django.utils.html import format_html

from .models import Signup


@admin.register(Signup)
class SignupAdmin(admin.ModelAdmin):
    list_display = ("email", "plan", "status_badge", "created_at", "masked_key")
    list_filter = ("plan", "status")
    search_fields = ("email", "stripe_customer_id", "stripe_session_id")
    readonly_fields = (
        "email",
        "plan",
        "stripe_customer_id",
        "stripe_session_id",
        "created_at",
        "reveal_api_key",
    )
    fields = (
        "email",
        "plan",
        "status",
        "reveal_api_key",
        "stripe_customer_id",
        "stripe_session_id",
        "created_at",
        "provisioned_at",
    )
    actions = ["mark_provisioned"]

    @admin.display(description="Status")
    def status_badge(self, obj: Signup) -> str:
        colors = {
            Signup.Status.PENDING_PAYMENT: "#888",
            Signup.Status.PENDING_KEY: "#c8963c",
            Signup.Status.READY: "#3c9c6b",
            Signup.Status.PROVISIONED: "#4a7fc9",
        }
        return format_html(
            '<span style="color:{}; font-weight:600">{}</span>',
            colors.get(obj.status, "#888"),
            obj.get_status_display(),
        )

    @admin.display(description="API key (masked)")
    def masked_key(self, obj: Signup) -> str:
        return obj.masked_api_key() or "—"

    @admin.display(description="API key")
    def reveal_api_key(self, obj: Signup) -> str:
        # Shown in full only on the detail page (not the list), since it has
        # to be legible to copy-paste into the new tenant's .env during
        # manual provisioning (hermes-admin user create).
        return obj.api_key or "(BYOK key not submitted yet)" if obj.plan == Signup.Plan.BYOK else "(managed compute -- no customer key)"

    @admin.action(description="Mark selected signups as provisioned")
    def mark_provisioned(self, request, queryset):
        from django.utils import timezone

        updated = queryset.exclude(status=Signup.Status.PROVISIONED).update(
            status=Signup.Status.PROVISIONED, provisioned_at=timezone.now()
        )
        self.message_user(request, f"{updated} signup(s) marked as provisioned.")
