# Desktop Node — Local Services (reference copies)

The Desktop Node is a modified Hermes desktop client (Electron). The client app as a
whole builds on the upstream [Hermes Agent](https://github.com/NousResearch/hermes-agent)
project; this folder holds reference copies of the **Iris-specific work** inside it —
the Local Services subsystem that turns the user's machine into a Body:

1. The desktop manages background processes (start/stop/health) described by a
   generic descriptor (`local-services.json` in userData).
2. Each service declares `capabilities` (e.g. `speech`, `llm`) and optionally a
   declarative `panel` — a schema the desktop renders as a full CRUD UI (the
   Voices panel) without any service-specific frontend code.
3. Healthy capabilities are announced to the Brain over the gateway WebSocket
   (`hermes.capabilities.announce`), so routing is never staler than the last
   health flip — including servers started/stopped outside the app.

| File | What it is |
|---|---|
| [`local-services-main-excerpt.cjs`](local-services-main-excerpt.cjs) | Main-process excerpt: registry + lifecycle + health watch + declarative panel proxy, the LM Studio integration behind the `llm.chat` capability (native catalog, model load/swap with lifecycle toasts, cold-start heuristics), and the IPC surface. |
| [`local-services-settings.tsx`](local-services-settings.tsx) | The Settings panel (React): service CRUD with one-time command authorization, status pills, and the schema-driven panel sub-view (Voices). |
| [`local-services-capabilities.ts`](local-services-capabilities.ts) | Renderer-side announce: recomputes this desktop's capabilities from the registry and pushes them to the Gateway on ready/toggle/health-change. |
| [`local-services-validation.cjs`](local-services-validation.cjs) | Descriptor validation shared by the form and IPC (args parsing, loopback-only health URLs, id slugs). |

Copies are verbatim from the private client repo except for the excerpt's header and
section markers. They are for reading, not standalone execution — surrounding
helpers live in the full client. Prebuilt installers of the complete client are on
the site's Downloads page and the public releases repo.
