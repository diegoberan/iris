from django.urls import path
from django.contrib.auth import views as auth_views
from . import views

urlpatterns = [
    path("register/", views.register, name="register"),
    path("login/", auth_views.LoginView.as_view(template_name="accounts/login.html"), name="login"),
    path("logout/", views.logout_view, name="logout"),
    path("account/password-change/", auth_views.PasswordChangeView.as_view(
        template_name="accounts/password_change.html",
        success_url="/account/"
    ), name="password_change"),
]
