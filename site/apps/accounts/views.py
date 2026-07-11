from django.shortcuts import render, redirect
from django.contrib.auth import login, logout
from django.http import HttpRequest, HttpResponse
from django.contrib.auth.views import LoginView
from .forms import UserRegistrationForm

class CustomLoginView(LoginView):
    template_name = "accounts/login.html"
    # NOTE: we intentionally do NOT capture the login password. The tenant
    # dashboard gets its own randomly generated password (shown on the account
    # page) so the customer's reusable login credential never lives in
    # plaintext in the session, DB, or the tenant env file.

def register(request: HttpRequest) -> HttpResponse:
    if request.user.is_authenticated:
        return redirect("account")
    
    if request.method == "POST":
        form = UserRegistrationForm(request.POST)
        if form.is_valid():
            user = form.save(commit=False)
            user.set_password(form.cleaned_data["password"])
            user.save()
            # Do NOT persist the plaintext password. The dashboard password is
            # generated independently at provisioning time (views.py:
            # secrets.token_hex fallback) and surfaced on the account page.
            login(request, user)
            return redirect("account")
    else:
        form = UserRegistrationForm()
        
    return render(request, "accounts/register.html", {"form": form})

def logout_view(request: HttpRequest) -> HttpResponse:
    logout(request)
    return redirect("/")
