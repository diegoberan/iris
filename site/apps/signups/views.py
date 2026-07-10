import json

import stripe
from django.conf import settings
from django.http import HttpRequest, HttpResponse, JsonResponse
from django.shortcuts import render, redirect
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_POST

from .models import Signup
from .notify import notify_signup

stripe.api_key = settings.STRIPE_SECRET_KEY

PLAN_PRICING = {
    Signup.Plan.BYOK: {
        "amount_usd": settings.BYOK_PRICE_USD,
        "name": "Iris Labs — Hosting (BYOK)",
        "description": "Hosting + fork maintenance. You supply your own model provider API key.",
    },
    Signup.Plan.MANAGED: {
        "amount_usd": settings.MANAGED_COMPUTE_PRICE_USD,
        "name": "Iris Labs — Managed Compute",
        "description": "Hosting + AMD-hosted inference (Gemma). No API key needed.",
    },
}


def landing(request: HttpRequest) -> HttpResponse:
    return render(request, "signups/landing.html")


def signup(request: HttpRequest) -> HttpResponse:
    return render(
        request,
        "signups/signup.html",
        {
            "byok_price": settings.BYOK_PRICE_USD,
            "managed_price": settings.MANAGED_COMPUTE_PRICE_USD,
            "stripe_publishable_key": settings.STRIPE_PUBLISHABLE_KEY,
        },
    )


@require_POST
def create_checkout(request: HttpRequest) -> JsonResponse:
    body = json.loads(request.body or "{}")
    email = (body.get("email") or "").strip()
    plan = body.get("plan")

    if "@" not in email:
        return JsonResponse({"error": "Valid email required"}, status=400)
    if plan not in PLAN_PRICING:
        return JsonResponse({"error": "Invalid plan"}, status=400)

    pricing = PLAN_PRICING[plan]
    session = stripe.checkout.Session.create(
        mode="subscription",
        customer_email=email,
        line_items=[
            {
                "price_data": {
                    "currency": "usd",
                    "product_data": {
                        "name": pricing["name"],
                        "description": pricing["description"],
                    },
                    "unit_amount": pricing["amount_usd"] * 100,
                    "recurring": {"interval": "month"},
                },
                "quantity": 1,
            }
        ],
        success_url=f"{settings.SITE_URL}/signup/success?session_id={{CHECKOUT_SESSION_ID}}",
        cancel_url=f"{settings.SITE_URL}/signup",
    )

    Signup.objects.create(
        email=email,
        plan=plan,
        stripe_session_id=session.id,
        status=Signup.Status.PENDING_PAYMENT,
    )

    return JsonResponse({"url": session.url})


@csrf_exempt
def stripe_webhook(request: HttpRequest) -> HttpResponse:
    payload = request.body
    sig_header = request.META.get("HTTP_STRIPE_SIGNATURE", "")

    if not settings.STRIPE_WEBHOOK_SECRET:
        return HttpResponse(status=500)

    try:
        event = stripe.Webhook.construct_event(payload, sig_header, settings.STRIPE_WEBHOOK_SECRET)
    except (ValueError, stripe.SignatureVerificationError):
        return HttpResponse(status=400)

    if event["type"] == "checkout.session.completed":
        session = event["data"]["object"]
        try:
            signup_obj = Signup.objects.get(stripe_session_id=session["id"])
        except Signup.DoesNotExist:
            return HttpResponse(status=200)

        # StripeObject supports attribute/bracket access, not dict-style .get() --
        # session["customer"] raises AttributeError-wrapped-KeyError if used
        # via .get(), so index directly (the field is always present on a
        # completed session, value is None or the customer id).
        signup_obj.stripe_customer_id = session["customer"] or ""
        signup_obj.status = (
            Signup.Status.PENDING_KEY if signup_obj.plan == Signup.Plan.BYOK else Signup.Status.READY
        )
        signup_obj.save()
        notify_signup(signup_obj)

    return HttpResponse(status=200)


def signup_success(request: HttpRequest) -> HttpResponse:
    session_id = request.GET.get("session_id", "")
    signup_obj = Signup.objects.filter(stripe_session_id=session_id).first()
    return render(request, "signups/success.html", {"signup": signup_obj})


@require_POST
def submit_api_key(request: HttpRequest) -> HttpResponse:
    session_id = request.POST.get("session_id", "")
    api_key = request.POST.get("api_key", "").strip()
    signup_obj = Signup.objects.filter(stripe_session_id=session_id).first()

    if signup_obj and api_key:
        signup_obj.api_key = api_key
        signup_obj.status = Signup.Status.READY
        signup_obj.save()
        notify_signup(signup_obj)

    return redirect(f"/signup/success?session_id={session_id}")


def downloads(request: HttpRequest) -> HttpResponse:
    return render(request, "signups/downloads.html")
