"""Optional Telegram ping on new signups -- convenience only. The admin panel
(django.contrib.admin on the Signup model) is the actual source of truth;
this just saves Diego from having to keep the admin tab open. Silently
no-ops if the bot isn't configured -- a missing notification must never
break the checkout/signup flow itself.
"""
import urllib.request
import urllib.parse
import json

from django.conf import settings

from .models import Signup


def notify_signup(signup: Signup) -> None:
    if not settings.TELEGRAM_BOT_TOKEN or not settings.TELEGRAM_CHAT_ID:
        return

    lines = [
        "New Iris signup",
        f"Email: {signup.email}",
        f"Plan: {signup.get_plan_display()}",
        f"Status: {signup.get_status_display()}",
        "",
        "Review in /admin/signups/signup/",
    ]
    payload = json.dumps({"chat_id": settings.TELEGRAM_CHAT_ID, "text": "\n".join(lines)}).encode()
    url = f"https://api.telegram.org/bot{settings.TELEGRAM_BOT_TOKEN}/sendMessage"
    req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"})
    try:
        urllib.request.urlopen(req, timeout=5)
    except Exception:
        pass
