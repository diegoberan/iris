# Deployment topology

How a live Íris deployment is laid out. This documents the **shape** of the system
for reproducibility — it deliberately contains **no secrets, IPs, keys, or
credentials** (those live only in the server's environment files, never in the repo).

## Host

A single AMD GPU instance (AMD Developer Cloud, MI300) running Ubuntu. Everything
below runs on that one host, managed by `systemd`, fronted by Caddy.

## Services (systemd units)

| Unit | Bind | Role |
|---|---|---|
| `vllm` | `:8000` | Gemma served by vLLM inside a ROCm container (`docker exec`). `--max-model-len 262144`, gemma4 tool-call + reasoning parsers. This is the **AMD cloud** inference tier. |
| `django-iris` | `127.0.0.1:8080` | The SaaS site (gunicorn, 3 workers, `core.wsgi`). Reads `site/.env` via systemd `EnvironmentFile`. SQLite by default. |
| `caddy` | `:80/:443` | Reverse proxy + automatic TLS (see below). |
| `<tenant>-dashboard` / `<tenant>-gateway` | `:9100+` | One Hermes **Brain** per provisioned tenant (the dashboard UI + the agent gateway). Created by the provisioning script. |

## Edge (Caddy)

- `iris.dberan.dev` → serves `/static/*` from `/var/www/iris/static` (including the
  app download binaries) and reverse-proxies everything else to the Django app on
  `:8080`.
- `*.iris.dberan.dev` → per-tenant hostnames. Each provisioned tenant gets a
  `sites.d/<tenant>.caddy` block reverse-proxying to its dashboard port, with a
  redirect from the bare auth path to the login page. Caddy auto-issues a
  Let's Encrypt cert per hostname (HTTP-01), so a wildcard DNS record pointing at the
  host is all that's needed — no wildcard cert / DNS API.

## Provisioning flow (signup → live Brain)

1. Customer signs up on the site and completes Stripe checkout (test mode).
2. The Stripe webhook marks the signup ready; [`site/apps/signups/provisioning.py`](../site/apps/signups/provisioning.py)
   claims it (email allowlist–gated) and runs the provisioning script in a daemon thread.
3. The script (`provisioning/scripts/provision-user.sh`, in the Hermes repo) creates a
   dedicated Unix user, clones the Brain, applies a per-tenant overlay (persona, config,
   the Iris Orchestrator with a per-tenant loopback port), generates a random dashboard
   password, and registers the two systemd units.
4. A random subdomain slug is written as a Caddy site block; Caddy is reloaded.
5. The customer receives their URL + credentials.

## Model routing on the host

The tenant's Brain points its `model.base_url` at the **Iris Orchestrator** loopback
(`tui_gateway/orchestrator.py`), which routes each turn:
`desktop-local (customer's Radeon)` → `amd-cloud (this host's vLLM on MI300)` →
`cloud-fallback (Fireworks)`. See [amd-usage.md](amd-usage.md).

## Secrets (never in the repo)

Held only in server-side environment files / a secrets directory: Django `SECRET_KEY`,
Stripe keys + webhook secret, the shared Google OAuth client secret, per-tenant
dashboard passwords, and any BYOK API keys. A `.env.template` documents the required
keys without values.

## Backup

The authoritative backup of a running deployment is a **disk snapshot** of the host
(captures code, databases, tenant state, and configs together). The source code is on
GitHub; the *running state* (SQLite DBs, per-tenant `~/.hermes`, env files) is not and
must be snapshotted or dumped separately.
