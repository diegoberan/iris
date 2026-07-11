import os
import shutil
import subprocess
import threading
from django.utils import timezone
from .models import Signup, AllowedProvisionEmail

HERMES_LAB_ROOT = os.getenv("HERMES_LAB_ROOT", "/root/hermes-lab")
SECRETS_DIR = os.getenv("SECRETS_DIR", "/root/.hermes-provision-secrets")

def trigger_provisioning(signup: Signup) -> None:
    # 1. Check case-insensitive allowlist
    is_allowed = AllowedProvisionEmail.objects.filter(email__iexact=signup.email).exists()
    if not is_allowed:
        return

    # 2. Atomic claim update
    updated = Signup.objects.filter(pk=signup.pk, status=Signup.Status.READY).update(
        status=Signup.Status.PROVISIONING
    )
    
    if updated == 1:
        # Spawn daemon thread to prevent blocking the HTTP request
        thread = threading.Thread(target=_run_provisioning, args=(signup.pk,), daemon=True)
        thread.start()

def _run_provisioning(signup_id: int) -> None:
    try:
        signup = Signup.objects.get(pk=signup_id)
        port = 9100 + (signup.id % 500)
        
        base_overlays_dir = os.path.join(HERMES_LAB_ROOT, "provisioning", "overlays")
        template_name = "demo-managed-template" if signup.plan == Signup.Plan.MANAGED else "demo-byok-template"
        template_dir = os.path.join(base_overlays_dir, template_name)
        target_dir = os.path.join(base_overlays_dir, f"signup_{signup.id}")
        
        # Clean and copy template overlay
        if os.path.exists(target_dir):
            shutil.rmtree(target_dir)
        shutil.copytree(template_dir, target_dir)
        
        # Write deterministic port
        with open(os.path.join(target_dir, "port"), "w") as f:
            f.write(str(port))

        # The OS/dashboard identity provision-user.sh derives from the
        # synthetic email's local-part -- substitute it into every text
        # file the overlay ships (persona/*.md, config.d/*.yaml, ...) so
        # {{USER_NAME}} placeholders like /home/{{USER_NAME}}/.hermes/...
        # resolve correctly.
        os_username = f"signup_{signup.id}"
        for dirpath, _dirnames, filenames in os.walk(target_dir):
            for fname in filenames:
                if fname in ("port",):
                    continue
                fpath = os.path.join(dirpath, fname)
                try:
                    with open(fpath, "r") as f:
                        content = f.read()
                except UnicodeDecodeError:
                    continue
                if "{{USER_NAME}}" in content:
                    with open(fpath, "w") as f:
                        f.write(content.replace("{{USER_NAME}}", os_username))


        # Determine credentials
        username = signup.provision_username or signup.user.username
        password = signup.provision_password
        if not password:
            import secrets
            password = secrets.token_hex(6)
            
        signup.provision_username = username
        signup.provision_password = password
        signup.save()

        # Write credentials and BYOK API key if needed
        env_path = os.path.join(target_dir, "env.d")
        mode = "a" if os.path.exists(env_path) else "w"
        with open(env_path, mode) as f:
            f.write(f"\nHERMES_DASHBOARD_BASIC_AUTH_USERNAME={username}\n")
            f.write(f"\nHERMES_DASHBOARD_BASIC_AUTH_PASSWORD={password}\n")
            if signup.plan == Signup.Plan.BYOK:
                provider_vars = {
                    Signup.Provider.OPENROUTER: "OPENROUTER_API_KEY",
                    Signup.Provider.OPENAI: "OPENAI_API_KEY",
                    Signup.Provider.ANTHROPIC: "ANTHROPIC_API_KEY",
                    Signup.Provider.FIREWORKS: "FIREWORKS_API_KEY",
                    Signup.Provider.GEMINI: "GEMINI_API_KEY",
                }
                var_name = provider_vars.get(signup.provider, "OPENROUTER_API_KEY")
                f.write(f"\n{var_name}={signup.api_key}\n")
        
        # Run provisioning script
        script_path = os.path.join(HERMES_LAB_ROOT, "provisioning", "scripts", "provision-user.sh")
        email_arg = f"signup_{signup.id}@dberan.dev"
        overlay_arg = f"signup_{signup.id}"
        
        subprocess.run(
            [script_path, email_arg, overlay_arg, "en"],
            capture_output=True,
            text=True,
            check=True
        )
        
        # Overwrite the generated password secret file to match custom credentials
        password_file = os.path.join(SECRETS_DIR, f"signup_{signup.id}-dashboard-password")
        with open(password_file, "w") as f:
            f.write(password)
        os.chmod(password_file, 0o600)

        # Give every new tenant the shared Iris Labs Google OAuth client
        # secret so the AGENTS.md instructions above can jump straight to
        # generating an auth link instead of asking the user to create
        # their own Google Cloud project. This is just the OAuth *client*
        # (Desktop app type, not confidential) -- each customer still does
        # their own individual consent/authorization producing their own
        # token, nobody gets access to anyone else's Google account.
        shared_client_secret = os.path.join(SECRETS_DIR, "google_client_secret.json")
        tenant_hermes_dir = f"/home/{os_username}/.hermes"
        if os.path.exists(shared_client_secret) and os.path.isdir(tenant_hermes_dir):
            dest = os.path.join(tenant_hermes_dir, "google_client_secret.json")
            shutil.copyfile(shared_client_secret, dest)
            shutil.chown(dest, user=os_username, group=os_username)
            os.chmod(dest, 0o600)

        # Give the tenant its own HTTPS subdomain instead of a bare IP:port.
        # Caddy auto-issues a Let's Encrypt cert per-hostname (HTTP-01, no
        # wildcard cert / DNS API needed) as long as *.iris.dberan.dev
        # already resolves to this pod.
        #
        # Random slug, not the sequential signup id -- a predictable
        # u1/u2/u3... pattern lets anyone enumerate every customer's
        # dashboard hostname by just walking the counter.
        import secrets as _secrets
        slug = _secrets.token_hex(4)
        hostname = f"{slug}.iris.dberan.dev"
        # The dashboard bounces an unauthenticated visitor to
        # /auth/login?provider=basic, but the actual login page lives at
        # /login -- redirect it at the edge, else the bare URL dead-ends on a
        # blank /auth/login (a judge clicking the link would just see it fail).
        site_block = (
            f"{hostname} {{\n"
            f"    @basic_auth_login {{\n"
            f"        path /auth/login\n"
            f"        query provider=basic\n"
            f"    }}\n"
            f"    redir @basic_auth_login /login?{{query}} 302\n"
            f"    reverse_proxy localhost:{port}\n"
            f"}}\n"
        )
        sites_dir = "/etc/caddy/sites.d"
        os.makedirs(sites_dir, exist_ok=True)
        with open(os.path.join(sites_dir, f"signup_{signup.id}.caddy"), "w") as f:
            f.write(site_block)
        subprocess.run(["systemctl", "reload", "caddy"], capture_output=True)

        # Update signup object
        signup.provision_url = f"https://{hostname}/"
        signup.status = Signup.Status.PROVISIONED
        signup.provisioned_at = timezone.now()
        signup.save()
        
        # Notify
        from .notify import notify_signup
        notify_signup(signup)
        
    except Exception as e:
        try:
            signup = Signup.objects.get(pk=signup_id)
            signup.status = Signup.Status.PROVISION_FAILED
            signup.save()
        except Exception:
            pass
            
        import traceback
        print(f"PROVISIONING FAILED for signup {signup_id}:")
        traceback.print_exc()


def update_provisioned_config(signup):
    """Update API key and provider environment variables in the VM overlay and restart services."""
    if signup.plan != Signup.Plan.BYOK or signup.status != Signup.Status.PROVISIONED:
        return

    provider_vars = {
        Signup.Provider.OPENROUTER: "OPENROUTER_API_KEY",
        Signup.Provider.OPENAI: "OPENAI_API_KEY",
        Signup.Provider.ANTHROPIC: "ANTHROPIC_API_KEY",
        Signup.Provider.FIREWORKS: "FIREWORKS_API_KEY",
        Signup.Provider.GEMINI: "GEMINI_API_KEY",
    }

    var_name = provider_vars.get(signup.provider, "OPENROUTER_API_KEY")
    new_line = f"{var_name}={signup.api_key}"

    def _replace_provider_line(path: str) -> None:
        lines = []
        if os.path.exists(path):
            with open(path, "r") as f:
                for raw_line in f:
                    line_str = raw_line.rstrip("\n")
                    if line_str.strip() and not any(
                        line_str.startswith(var) for var in provider_vars.values()
                    ):
                        lines.append(line_str)
        lines.append(new_line)
        with open(path, "w") as f:
            f.write("\n".join(lines) + "\n")

    # 1. Update env.d in the overlay (so a future re-provision/update-user.sh
    #    run picks up the right key -- this is provisioning-time input, the
    #    running gateway never reads it directly).
    target_dir = os.path.join(HERMES_LAB_ROOT, "provisioning", "overlays", f"signup_{signup.id}")
    _replace_provider_line(os.path.join(target_dir, "env.d"))

    # 2. Update the LIVE .env the running gateway/dashboard actually reads.
    user_env_path = f"/home/signup_{signup.id}/.hermes/.env"
    if os.path.exists(user_env_path):
        _replace_provider_line(user_env_path)
        shutil.chown(user_env_path, user=f"signup_{signup.id}", group=f"signup_{signup.id}")

    # 3. Restart the systemd services to pick up the new key
    services = [f"signup_{signup.id}-dashboard.service", f"signup_{signup.id}-gateway.service"]
    subprocess.run(["systemctl", "restart"] + services, capture_output=True)

