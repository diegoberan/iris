"""Iris Orchestrator: one OpenAI-compatible loopback endpoint that routes
llm.chat across capability tiers.

Tier 1 — a connected client (Desktop Node) that announced an ``llm``
capability via ``hermes.capabilities.announce``: the request is relayed
over the client's own gateway WebSocket (``llm.generate.request``), the
client runs it against its local OpenAI-compatible server (its own GPU)
and answers with ``llm.generate.response``. Same act pattern as the
desktop-session speech provider, second capability.

Tier 2 — ``GEMMA_AMD_BASE_URL``: straight HTTP proxy to a remote
OpenAI-compatible server (vLLM on an AMD GPU cloud pod for the hackathon).

Tier 3 — last-resort cloud fallback (``IRIS_FALLBACK_BASE_URL`` /
``IRIS_FALLBACK_API_KEY`` / ``IRIS_FALLBACK_MODEL``): any OpenAI-compatible
serverless provider. Unlike tiers 1–2 this is NOT an Iris/Gemma tier — when
it answers, the ``iris.tier.used`` broadcast carries ``IRIS_FALLBACK_PROVIDER``
(the provider's Hermes slug, e.g. ``opencode-go``) so the Desktop commits a
REAL session switch to it instead of the orchestrator silently proxying
forever while the session config still claims a local model. Legacy
``FIREWORKS_*`` variables keep working when the new ones are absent.

A tier failing (no capable client, timeout, HTTP error) advances the
chain — a provider's own timeout doubles as its liveness probe, same
design as the speech router. The winning tier is reported in the
``X-Iris-Tier`` response header so the demo can show the failover live.

Opt-in: the server only starts when ``IRIS_ORCHESTRATOR=1`` (or a port in
``IRIS_ORCHESTRATOR_PORT``) is present in the gateway's environment, and
binds 127.0.0.1 only. Point the tenant's ``model.base_url`` at
``http://127.0.0.1:<port>/v1``; the agent core sees just another
OpenAI-compatible endpoint and needs no changes.
"""

import json
import logging
import os
import sys
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

logger = logging.getLogger(__name__)

DEFAULT_PORT = 8092

# Per-tier request timeouts (seconds). The desktop tier covers consumer-GPU
# generation speeds; remote tiers are conventional HTTP inference calls.
DESKTOP_TIMEOUT = float(os.environ.get("IRIS_DESKTOP_LLM_TIMEOUT", "120"))
PROXY_TIMEOUT = float(os.environ.get("IRIS_PROXY_LLM_TIMEOUT", "120"))


def _proxy(base_url: str, api_key: str | None, body: dict) -> dict:
    """POST *body* to an OpenAI-compatible /chat/completions and return JSON."""
    req = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key or 'none'}",
            # Some providers sit behind WAFs that reject the default
            # Python-urllib UA outright (observed: 403 from opencode.ai).
            "User-Agent": "iris-orchestrator/0.1",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=PROXY_TIMEOUT) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _with_model(body: dict, *env_vars: str) -> dict:
    """Copy *body* with the tier's own model id when configured.

    Each tier serves a different model under a different id (the local 12B,
    the pod's fp8 26B, the fallback provider's hosted id) — the model named
    by the agent config only has to exist on the tier that wins. The first
    env var that is set wins, so a tier can layer a new name over a legacy one.
    """
    model = next((os.environ.get(v) for v in env_vars if os.environ.get(v)), None)
    if not model:
        return body
    out = dict(body)
    out["model"] = model
    return out


def route(body: dict) -> tuple[dict | None, str]:
    """Try each tier in order; return (response_json, tier_name)."""
    # Tier 1: a live Node that announced llm.chat. Deferred import — the
    # server module is the caller's parent, fully imported by now.
    from tui_gateway.server import request_desktop_llm

    try:
        result = request_desktop_llm(body, timeout=DESKTOP_TIMEOUT)
        if result:
            return result, "desktop-local"
    except Exception:
        logger.warning("desktop-local tier failed", exc_info=True)

    amd_url = os.environ.get("GEMMA_AMD_BASE_URL")
    if amd_url:
        try:
            return (
                _proxy(amd_url, os.environ.get("GEMMA_AMD_API_KEY"), _with_model(body, "GEMMA_AMD_MODEL")),
                "amd-cloud",
            )
        except Exception:
            logger.warning("amd-cloud tier failed", exc_info=True)

    # Tier 3: last resort. IRIS_FALLBACK_* wins; legacy FIREWORKS_* is the
    # default when the new vars are absent. Either way the tier reports as
    # "cloud-fallback" -- it's a role, not a brand, and the Desktop treats it
    # specially (real session switch instead of transparent proxying).
    fb_url = os.environ.get("IRIS_FALLBACK_BASE_URL")
    fb_key = os.environ.get("IRIS_FALLBACK_API_KEY")
    if not fb_url and os.environ.get("FIREWORKS_API_KEY"):
        fb_url = os.environ.get("FIREWORKS_BASE_URL", "https://api.fireworks.ai/inference/v1")
        fb_key = os.environ.get("FIREWORKS_API_KEY")
    if fb_url:
        try:
            return (
                _proxy(fb_url, fb_key, _with_model(body, "IRIS_FALLBACK_MODEL", "FIREWORKS_MODEL")),
                "cloud-fallback",
            )
        except Exception:
            logger.warning("cloud-fallback tier failed", exc_info=True)

    return None, "none"


def _broadcast_tier_used(tier: str, model: str) -> None:
    """Fire-and-forget notice to every connected Node: this is who answered.

    Purely informational -- the native "current model" indicator in the
    Desktop UI reflects the session's static config (agent.model), never the
    per-turn tier the Orchestrator actually picked, so without this a tier
    switch (kill-switch failover, or manually swapping the local model) is
    invisible in the UI. Best-effort: a write failure here must never affect
    the response already decided by route().
    """
    try:
        from tui_gateway.server import _ws_clients, _ws_clients_lock

        with _ws_clients_lock:
            clients = list(_ws_clients)
    except Exception:
        return

    payload = {"tier": tier, "model": model}
    if tier == "cloud-fallback":
        # The last-resort tier is a real Hermes provider, not an Iris tier.
        # Naming its provider slug here is the signal for the Desktop to
        # commit an actual session switch (config.set) to it, so the session
        # config stops claiming a local model that isn't answering.
        provider = os.environ.get("IRIS_FALLBACK_PROVIDER")
        if provider:
            payload["provider"] = provider

    frame = {
        "jsonrpc": "2.0",
        "method": "event",
        "params": {"type": "iris.tier.used", "payload": payload},
    }
    # stderr on purpose: the gateway's INFO logging doesn't reach the journal,
    # and whether this payload carried `provider` (and to how many clients) is
    # exactly what debugging a mute Desktop needs.
    print(f"[iris-orchestrator] broadcast {payload} -> {len(clients)} client(s)", file=sys.stderr, flush=True)
    for client in clients:
        try:
            client.write(frame)
        except Exception:
            pass


def _to_sse_chunks(result: dict) -> list[dict]:
    """Convert a complete chat.completion into chat.completion.chunk frames.

    The tiers themselves run non-streaming (the WS relay returns one JSON
    blob, same shape as the speech provider's single WAV); this shim lets
    SSE clients — the Hermes agent transport requests ``stream: true`` —
    consume the orchestrator without a client-side change. The whole answer
    arrives as one content delta rather than token-by-token; acceptable for
    v1, revisit if per-token streaming ever becomes the demo bottleneck.
    """
    choice = (result.get("choices") or [{}])[0]
    msg = choice.get("message") or {}
    base = {
        "id": result.get("id", "iris-chunk"),
        "object": "chat.completion.chunk",
        "created": result.get("created", 0),
        "model": result.get("model", ""),
    }

    def chunk(delta: dict, finish=None) -> dict:
        return {**base, "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}

    frames = [chunk({"role": "assistant"})]
    if msg.get("reasoning_content"):
        frames.append(chunk({"reasoning_content": msg["reasoning_content"]}))
    if msg.get("content"):
        frames.append(chunk({"content": msg["content"]}))
    tool_calls = msg.get("tool_calls") or []
    if tool_calls:
        deltas = [{**tc, "index": i} for i, tc in enumerate(tool_calls)]
        frames.append(chunk({"tool_calls": deltas}))
    final = chunk({}, finish=choice.get("finish_reason") or "stop")
    if result.get("usage"):
        final["usage"] = result["usage"]
    frames.append(final)
    return frames


class _Handler(BaseHTTPRequestHandler):
    def _json_reply(self, status: int, obj: dict) -> None:
        payload = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):  # noqa: N802 (http.server API)
        # Device act surface (Android Node): loopback-only HTTP the agent's
        # skill drives via the terminal tool -- no core tool registration
        # needed. The relay itself lives in tui_gateway.server (same act
        # pattern as speech/llm).
        if self.path.rstrip("/") == "/iris/notify":
            try:
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length) or b"{}")
            except (ValueError, json.JSONDecodeError):
                self._json_reply(400, {"error": "invalid JSON body"})
                return
            from tui_gateway.server import request_device_notification

            result = request_device_notification(
                str(body.get("title") or "Hermes"), str(body.get("body") or "")
            )
            if result is None:
                self._json_reply(
                    503, {"delivered": False, "error": "no connected Node announced the notification capability"}
                )
                return
            self._json_reply(200, {"delivered": True, "device": result.get("device", "")})
            return

        if self.path.rstrip("/") != "/v1/chat/completions":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            self.send_error(400, "invalid JSON body")
            return

        want_stream = bool(body.get("stream"))
        if want_stream:
            body = {k: v for k, v in body.items() if k not in ("stream", "stream_options")}

            # The tiers run NON-streaming (one JSON blob at the end), so a
            # long generation used to mean minutes of total HTTP silence --
            # the caller's idle timeout fired and it RETRIED, stacking
            # duplicate in-flight completions on the local model server (the
            # observed LM Studio request queue). Send SSE headers immediately
            # and keepalive comments while the tier grinds, so the connection
            # is never idle. The X-Iris-Tier header is unavailable this early;
            # tier visibility for streaming callers is the iris.tier.used
            # broadcast (which every client already consumes).
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()

            box: dict = {}

            def _run() -> None:
                box["result"] = route(body)

            worker = threading.Thread(target=_run, daemon=True, name="iris-orchestrator-turn")
            worker.start()
            while worker.is_alive():
                worker.join(10)
                if worker.is_alive():
                    try:
                        self.wfile.write(b": keepalive\n\n")
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError, OSError):
                        # Caller hung up; the tier finishes on its own but
                        # there is nobody to answer anymore.
                        return

            result, tier = box.get("result", (None, "none"))
            if result is None:
                try:
                    self.wfile.write(
                        b'data: ' + json.dumps(
                            {"error": {"message": "all inference tiers failed", "type": "iris_orchestrator"}}
                        ).encode("utf-8") + b"\n\n"
                    )
                    self.wfile.write(b"data: [DONE]\n\n")
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, OSError):
                    pass
                return

            _broadcast_tier_used(tier, result.get("model", ""))
            try:
                for frame in _to_sse_chunks(result):
                    self.wfile.write(b"data: " + json.dumps(frame).encode("utf-8") + b"\n\n")
                self.wfile.write(b"data: [DONE]\n\n")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            logger.info("orchestrator served llm.chat via tier=%s stream=True", tier)
            return

        result, tier = route(body)
        if result is None:
            self.send_error(502, "all inference tiers failed")
            return

        _broadcast_tier_used(tier, result.get("model", ""))

        payload = json.dumps(result).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("X-Iris-Tier", tier)
        self.end_headers()
        self.wfile.write(payload)
        logger.info("orchestrator served llm.chat via tier=%s stream=False", tier)

    def do_GET(self):  # noqa: N802
        if self.path.rstrip("/") == "/iris/location":
            from tui_gateway.server import request_device_location

            result = request_device_location()
            if result is None:
                self._json_reply(
                    503, {"error": "no connected Node announced the location capability"}
                )
                return
            self._json_reply(
                200,
                {
                    "latitude": result.get("latitude"),
                    "longitude": result.get("longitude"),
                    "accuracy": result.get("accuracy"),
                    "device": result.get("device", ""),
                },
            )
            return

        if self.path.rstrip("/") != "/v1/models":
            self.send_error(404)
            return
        # Live model catalog: ask the connected Desktop node what its local
        # server can actually serve (llm.models relay), so the Brain's model
        # picker mirrors the machine's real LM Studio catalog. With no
        # desktop connected, fall back to the static tier-2/3 model so
        # OpenAI clients probing the endpoint still work.
        models: list[str] = []
        try:
            from tui_gateway.server import request_desktop_llm_models

            models = request_desktop_llm_models() or []
        except Exception:
            logger.warning("desktop model listing failed", exc_info=True)
        if not models:
            models = [
                os.environ.get("GEMMA_AMD_MODEL")
                or os.environ.get("FIREWORKS_MODEL")
                or "iris-orchestrated"
            ]
        payload = json.dumps(
            {"object": "list", "data": [{"id": m, "object": "model"} for m in models]}
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):  # silence per-request stderr noise
        pass


_server: ThreadingHTTPServer | None = None
_server_lock = threading.Lock()


def maybe_start_orchestrator() -> ThreadingHTTPServer | None:
    """Start the loopback Orchestrator if the environment opts in.

    Idempotent: both host paths call this (tui_gateway.entry.main for the
    spawned-subprocess gateway, tui_gateway.ws import for the dashboard's
    in-memory gateway) and only the first call binds the port.
    """
    global _server
    with _server_lock:
        if _server is not None:
            return _server
    port_env = os.environ.get("IRIS_ORCHESTRATOR_PORT")
    if not port_env and os.environ.get("IRIS_ORCHESTRATOR") != "1":
        return None
    port = int(port_env or DEFAULT_PORT)
    try:
        server = ThreadingHTTPServer(("127.0.0.1", port), _Handler)
    except OSError:
        logger.warning("orchestrator port %s unavailable — not started", port)
        return None
    thread = threading.Thread(target=server.serve_forever, daemon=True, name="iris-orchestrator")
    thread.start()
    logger.info("iris orchestrator listening on 127.0.0.1:%s", port)
    with _server_lock:
        _server = server
    return server
