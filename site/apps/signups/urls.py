from django.urls import path
from . import views

urlpatterns = [
    path("", views.landing, name="landing"),
    path("signup", views.signup, name="signup"),
    path("signup/checkout", views.create_checkout, name="create_checkout"),
    path("signup/submit-key", views.submit_api_key, name="submit_api_key"),
    path("account/", views.account, name="account"),
    path("account/status", views.signup_status, name="signup_status"),
    path("account/billing", views.billing_portal, name="billing_portal"),
    path("account/update-config", views.update_config, name="update_config"),
    path("account/retry-provision", views.retry_provisioning, name="retry_provisioning"),
    path("downloads", views.downloads, name="downloads"),
    path("the-lab", views.lab, name="lab"),
    path("oauth/callback", views.oauth_callback, name="oauth_callback"),
    path("oauth/google/start", views.oauth_google_start, name="oauth_google_start"),
    path("webhooks/stripe", views.stripe_webhook, name="stripe_webhook"),
]
