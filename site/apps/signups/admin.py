from django.contrib import admin
from django.utils.html import format_html

from .models import Signup, AllowedProvisionEmail


@admin.register(AllowedProvisionEmail)
class AllowedProvisionEmailAdmin(admin.ModelAdmin):
    list_display = ("email", "note", "created_at")
    search_fields = ("email", "note")
    ordering = ("-created_at",)


@admin.register(Signup)
class SignupAdmin(admin.ModelAdmin):
    list_display = ("email", "user", "plan", "status_badge", "created_at", "masked_key")
    list_filter = ("plan", "status")
    search_fields = ("email", "stripe_customer_id", "stripe_session_id", "user__username")
    readonly_fields = (
        "email",
        "user",
        "plan",
        "stripe_customer_id",
        "stripe_session_id",
        "created_at",
        "reveal_api_key",
    )
    fields = (
        "email",
        "user",
        "plan",
        "status",
        "reveal_api_key",
        "stripe_customer_id",
        "stripe_session_id",
        "provision_username",
        "provision_password",
        "provision_url",
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
            Signup.Status.PROVISIONING: "#c86cc9",
            Signup.Status.PROVISIONED: "#4a7fc9",
            Signup.Status.PROVISION_FAILED: "#a84040",
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
        return obj.api_key or "(BYOK key not submitted yet)" if obj.plan == Signup.Plan.BYOK else "(managed compute -- no customer key)"

    @admin.action(description="Mark selected signups as provisioned")
    def mark_provisioned(self, request, queryset):
        from django.utils import timezone

        updated = queryset.exclude(status=Signup.Status.PROVISIONED).update(
            status=Signup.Status.PROVISIONED, provisioned_at=timezone.now()
        )
        self.message_user(request, f"{updated} signup(s) marked as provisioned.")
