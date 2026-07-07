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

Sent by a Node after connecting (and again on any capability change). Payload is
**namespaced per capability**:

```jsonc
{
  "speech": { "available": true, "providers": ["dot-tts"] },
  "llm.chat": { "available": true, "providers": ["gemma-local"], "hardware": "gfx1200" },
  "notification.send": { "available": true }
}
```

*(Shape of the existing production implementation; field-level schema TBD in §5.)*

### 2.2 Act — request/response (TBD)

Generalization of the existing `speech.synthesize.request` desktop-session flow:
Brain → Node action request, Node → Brain result. One pattern for all capabilities.

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
