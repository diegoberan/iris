from django.shortcuts import render, redirect
from django.contrib.auth import login, logout
from django.http import HttpRequest, HttpResponse
from .forms import UserRegistrationForm

def register(request: HttpRequest) -> HttpResponse:
    if request.user.is_authenticated:
        return redirect("account")
    
    if request.method == "POST":
        form = UserRegistrationForm(request.POST)
        if form.is_valid():
            user = form.save(commit=False)
            user.set_password(form.cleaned_data["password"])
            user.save()
            login(request, user)
            return redirect("account")
    else:
        form = UserRegistrationForm()
        
    return render(request, "accounts/register.html", {"form": form})

def logout_view(request: HttpRequest) -> HttpResponse:
    logout(request)
    return redirect("/")
