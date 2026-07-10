from django.shortcuts import render, redirect
from django.contrib.auth import login, logout
from django.http import HttpRequest, HttpResponse
from django.contrib.auth.views import LoginView
from .forms import UserRegistrationForm

class CustomLoginView(LoginView):
    template_name = "accounts/login.html"
    
    def form_valid(self, form):
        self.request.session["user_plain_password"] = form.cleaned_data.get("password")
        return super().form_valid(form)

def register(request: HttpRequest) -> HttpResponse:
    if request.user.is_authenticated:
        return redirect("account")
    
    if request.method == "POST":
        form = UserRegistrationForm(request.POST)
        if form.is_valid():
            user = form.save(commit=False)
            user.set_password(form.cleaned_data["password"])
            user.save()
            # Capture plain password for automatic VM setup
            request.session["user_plain_password"] = form.cleaned_data["password"]
            login(request, user)
            return redirect("account")
    else:
        form = UserRegistrationForm()
        
    return render(request, "accounts/register.html", {"form": form})

def logout_view(request: HttpRequest) -> HttpResponse:
    logout(request)
    return redirect("/")
