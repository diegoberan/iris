import json
import stripe
from django.conf import settings
from django.contrib.auth.decorators import login_required
from django.http import HttpRequest, HttpResponse, JsonResponse
from django.shortcuts import render, redirect
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_POST

from .models import Signup
from .notify import notify_signup
from .provisioning import trigger_provisioning

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
    if request.user.is_authenticated:
        return redirect("account")
    return redirect("register")

@login_required
@require_POST
def create_checkout(request: HttpRequest) -> JsonResponse:
    body = json.loads(request.body or "{}")
    plan = body.get("plan")

    if plan not in PLAN_PRICING:
        return JsonResponse({"error": "Invalid plan"}, status=400)

    pricing = PLAN_PRICING[plan]
    session = stripe.checkout.Session.create(
        mode="subscription",
        customer_email=request.user.email,
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
        success_url=f"{settings.SITE_URL}/account/",
        cancel_url=f"{settings.SITE_URL}/account/",
    )

    # Reuse the site password as the dashboard password when we have it in
    # this session (set at register/login); otherwise generate a fresh
    # random one -- never fall back to a shared constant.
    import secrets
    provision_password = request.session.get("user_plain_password") or secrets.token_hex(6)

    # Link the Signup to the User
    Signup.objects.create(
        user=request.user,
        email=request.user.email,
        plan=plan,
        stripe_session_id=session.id,
        status=Signup.Status.PENDING_PAYMENT,
        provision_username=request.user.username,
        provision_password=provision_password,
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
    except (ValueError, stripe.SignatureVerificationError) as e:
        import logging
        logger = logging.getLogger(__name__)
        logger.error(f"STRIPE WEBHOOK VERIFICATION ERROR: {e}")
        return HttpResponse(status=400)

    if event["type"] == "checkout.session.completed":
        session = event["data"]["object"]
        try:
            signup_obj = Signup.objects.get(stripe_session_id=session["id"])
        except Signup.DoesNotExist:
            return HttpResponse(status=200)

        signup_obj.stripe_customer_id = session["customer"] or ""
        signup_obj.status = (
            Signup.Status.PENDING_KEY if signup_obj.plan == Signup.Plan.BYOK else Signup.Status.READY
        )
        signup_obj.save()
        notify_signup(signup_obj)

        # Trigger VM provisioning immediately for Managed plans
        if signup_obj.status == Signup.Status.READY:
            trigger_provisioning(signup_obj)

    return HttpResponse(status=200)

@login_required
@require_POST
def submit_api_key(request: HttpRequest) -> HttpResponse:
    api_key = request.POST.get("api_key", "").strip()
    provider = request.POST.get("provider", "").strip()
    
    # Fetch active BYOK plan pending key submission
    signup_obj = Signup.objects.filter(
        user=request.user, 
        plan=Signup.Plan.BYOK, 
        status=Signup.Status.PENDING_KEY
    ).order_by("-created_at").first()

    if signup_obj and api_key and provider in Signup.Provider.values:
        signup_obj.api_key = api_key
        signup_obj.provider = provider
        signup_obj.status = Signup.Status.READY
        signup_obj.save()
        notify_signup(signup_obj)
        
        # Trigger VM provisioning after key submission
        trigger_provisioning(signup_obj)

    return redirect("account")

@login_required
def account(request: HttpRequest) -> HttpResponse:
    signup_obj = Signup.objects.filter(user=request.user).order_by("-created_at").first()
    
    # Pricing info for the checkout screen
    context = {
        "byok_price": settings.BYOK_PRICE_USD,
        "managed_price": settings.MANAGED_COMPUTE_PRICE_USD,
        "stripe_publishable_key": settings.STRIPE_PUBLISHABLE_KEY,
        "signup": signup_obj,
        "providers": Signup.Provider.choices,
    }

    return render(request, "signups/account.html", context)

@login_required
def signup_status(request: HttpRequest) -> JsonResponse:
    signup_obj = Signup.objects.filter(user=request.user).order_by("-created_at").first()
    if not signup_obj:
        return JsonResponse({"error": "No signup found"}, status=404)
        
    return JsonResponse({
        "status": signup_obj.status,
        "provision_username": signup_obj.provision_username,
        "provision_password": signup_obj.provision_password,
        "provision_url": signup_obj.provision_url,
    })

@login_required
def billing_portal(request: HttpRequest) -> HttpResponse:
    signup_obj = Signup.objects.filter(user=request.user).exclude(stripe_customer_id="").order_by("-created_at").first()
    if not signup_obj:
        return redirect("account")
        
    session = stripe.billing_portal.Session.create(
        customer=signup_obj.stripe_customer_id,
        return_url=request.build_absolute_uri("/account/"),
    )
    return redirect(session.url)

def downloads(request: HttpRequest) -> HttpResponse:
    return render(request, "signups/downloads.html")

def oauth_callback(request: HttpRequest) -> HttpResponse:
    # Public, stateless landing page for Google's OAuth redirect. We don't
    # know or care which tenant/instance started this flow -- we just show
    # the code so the user can paste it back into their Hermes chat. No
    # tenant correlation, no database writes.
    return render(
        request,
        "signups/oauth_callback.html",
        {
            "code": request.GET.get("code", ""),
            "error": request.GET.get("error", ""),
        },
    )


@login_required
@require_POST
def update_config(request: HttpRequest) -> HttpResponse:
    signup_obj = Signup.objects.filter(user=request.user, status=Signup.Status.PROVISIONED).order_by("-created_at").first()
    if signup_obj:
        provider = request.POST.get("provider")
        api_key = request.POST.get("api_key", "").strip()
        if provider in Signup.Provider.values:
            signup_obj.provider = provider
            # Save key only if user provided a new, unmasked key
            if api_key and not api_key.startswith("..."):
                signup_obj.api_key = api_key
            signup_obj.save()
            
            # Apply configuration updates immediately on the pod
            from .provisioning import update_provisioned_config
            update_provisioned_config(signup_obj)
            
    return redirect("account")


@login_required
def retry_provisioning(request: HttpRequest) -> HttpResponse:
    signup_obj = Signup.objects.filter(user=request.user, status=Signup.Status.PROVISION_FAILED).order_by("-created_at").first()
    if signup_obj:
        if signup_obj.plan == Signup.Plan.BYOK:
            signup_obj.status = Signup.Status.PENDING_KEY
            signup_obj.save()
        else:
            signup_obj.status = Signup.Status.READY
            signup_obj.save()
            from .provisioning import trigger_provisioning
            trigger_provisioning(signup_obj)
            
    return redirect("account")

