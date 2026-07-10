from django.urls import path

from . import views

urlpatterns = [
    path("", views.landing, name="landing"),
    path("signup", views.signup, name="signup"),
    path("signup/checkout", views.create_checkout, name="create_checkout"),
    path("signup/success", views.signup_success, name="signup_success"),
    path("signup/submit-key", views.submit_api_key, name="submit_api_key"),
    path("downloads", views.downloads, name="downloads"),
    path("webhooks/stripe", views.stripe_webhook, name="stripe_webhook"),
]
