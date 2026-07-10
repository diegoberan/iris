# RFC 0001 — Capability Protocol

*The protocol that gives a Brain its Bodies.*

> Status: **Draft** — being extracted from the working implementation
> (speech.synthesis capability, in production) and generalized during the hackathon.

## 1. Overview

A **Node** (device) connects to the **Brain** (persistent agent) over the existing gateway
WebSocket transport and **announces** its capabilities. The Brain maintains a live
**Capability Registry** and an **Orchestrator** routes each action to the best available
**Provider**, with failover.

## 2. Messages

### 2.1 Announce — `hermes.capabilities.announce`

Sent by a Node after connecting (and again on any capability change). Payload carries a
static `device` field identifying the Node type, plus capability blocks **namespaced per
capability**:

```jsonc
{
  "device": "desktop",
  "speech": { "available": true, "localUrl": "http://127.0.0.1:8123", "voices": ["dot"] },
  "llm": { "available": true, "localUrl": "http://127.0.0.1:1234/v1", "model": "google/gemma-4-12b-qat" }
}
```

The Android Node (`android/` in this repo) announces the same way:

```jsonc
{
  "device": "android",
  "notification": { "available": true },
  "location": { "available": true }
}
```

`device` disambiguates *who* announced a capability once more than one Node type can offer
the same one. Kept as a flat string for now (`"desktop"`, `"android"`, future `"watch"`);
a richer per-Node identity (stable id, display name) is a later revision if the Registry
needs to show/target individual Nodes rather than just capability types.

*(Shape of the existing production implementation; field-level schema TBD in §5.)*

### 2.2 Act — request/response

Generalization of the `speech.synthesize.request` desktop-session flow — Brain → Node
action request, Node → Brain result over the Node's own gateway WebSocket. One pattern,
four capabilities in production: `speech.synthesize`, `llm.generate`,
`notification.send`, `location.current`.

Request (Brain → Node, as a gateway event):

```jsonc
{ "type": "notification.send.request",
  "payload": { "title": "…", "body": "…", "request_id": "ab12cd34" } }
```

Response (Node → Brain, as an RPC). Device capabilities answer on the shared
`device.action.response` method; correlation is the `request_id`:

```jsonc
{ "method": "device.action.response",
  "params": { "request_id": "ab12cd34", "success": true, "device": "android" } }
```

The request is broadcast to every Node whose announce carries the capability; the first
response for a `request_id` wins (mirrors the speech MVP). Pull capabilities
(`location.current`) return their result fields (`latitude`, `longitude`, `accuracy`)
in the same response envelope.

### 2.3 Health / presence

Node disconnect ⇒ its capabilities leave the Registry. A Provider failure during an act
⇒ the Orchestrator advances to the next Provider in the chain (a provider's own timeout
doubles as its liveness probe — same design as the speech router).

## 3. Orchestrator

- Ordered provider chain per capability (config-driven, mirrors `tts.router.providers`).
- Registry-aware: providers announced by live Nodes join/leave the chain dynamically.
- Failover between chain entries is invisible between turns; in-flight failures retry on
  the next provider.

## 4. Non-goals (this revision)

- Capability Graph (ecosystem-wide map) — future work.
- Versioned capability schemas / negotiation — future work.
- Trust & permissions model per capability — sketched, not specified.

## 5. Open questions

- Field-level schema and versioning of announce payloads.
- Act message envelope: correlation ids, streaming results, partial failure.
- AuthZ: which Brain identities may invoke which Node capabilities.
