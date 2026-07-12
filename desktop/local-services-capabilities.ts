import { $gateway } from '@/store/gateway'
import { notify } from '@/store/notifications'

// Recomputes this Desktop's capabilities from the Local Services registry
// and announces them to the Gateway (hermes.capabilities.announce). Called
// once on gateway.ready (gateway-event.ts) and again whenever a service is
// toggled from either Local Services surface (Settings panel, statusbar
// popover) -- the Gateway's routing decision should never be more stale
// than "the last time you flipped the switch".
//
// Ported from the speech-router lineage (announceSpeechCapabilities) and
// generalized: one healthy service per capability kind wins, and the
// payload stays namespaced per capability so new kinds keep being additive.
//
// $gateway.set() happens in setActive() (store/gateway.ts), which is not
// guaranteed to have run yet the instant gateway.ready's handler fires --
// that event is the server's first message after ws.accept(), and the
// primary connection's own bookkeeping (setPrimaryGateway then setActive)
// can still be mid-flight at that exact tick. A few short retries is
// simpler and more robust than chasing the exact ordering.
async function waitForGateway(): Promise<ReturnType<typeof $gateway.get>> {
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const gateway = $gateway.get()

    if (gateway) {
      return gateway
    }

    await new Promise(resolve => setTimeout(resolve, 200))
  }

  return $gateway.get()
}

interface SpeechAnnounce {
  available: boolean
  localUrl?: string
  voices?: string[]
}

interface LlmAnnounce {
  available: boolean
  localUrl?: string
  model?: string
  hardware?: string
  /** Live catalog of the local server (LM Studio), shipped with the announce
   *  so the gateway can answer /v1/models without a relay round-trip — a
   *  relay reply can never be processed while THIS connection's message
   *  loop is blocked inside a model.options RPC (the composer-picker path). */
  models?: string[]
}

export async function announceLocalCapabilities(): Promise<void> {
  const bridge = window.hermesDesktop?.localServices

  if (!bridge) {
    return
  }

  const gateway = await waitForGateway()

  if (!gateway) {
    return
  }

  const services = await bridge.list()

  const speech: SpeechAnnounce = { available: false }

  for (const svc of services.filter(s => s.kind === 'speech')) {
    const status = await bridge.status(svc.id)

    if (status.healthy) {
      const caps = svc.capabilities?.speech as { localUrl?: string; voices?: string[] } | undefined
      speech.available = true
      speech.localUrl = caps?.localUrl
      speech.voices = caps?.voices ?? []

      break
    }
  }

  const llm: LlmAnnounce = { available: false }

  for (const svc of services.filter(s => s.kind === 'llm')) {
    const status = await bridge.status(svc.id)

    if (status.healthy) {
      const caps = svc.capabilities?.llm as { localUrl?: string; model?: string; hardware?: string } | undefined
      llm.available = true
      llm.localUrl = caps?.localUrl
      llm.model = caps?.model
      llm.hardware = caps?.hardware

      const catalog = await window.hermesDesktop.listLocalLlmModels?.().catch(() => null)

      if (catalog?.ok && catalog.models?.length) {
        llm.models = catalog.models
      }

      break
    }
  }

  // Static per-Node identity, distinct from the dynamic capability blocks
  // above. With only a Desktop Node today, capability keys alone disambiguate
  // "who announced this" by elimination -- that stops working once a second
  // Node type (Android, Watch) can announce the same capability, so this rides
  // along now rather than becoming a breaking payload-shape change later.
  void gateway.request('hermes.capabilities.announce', { device: 'desktop', speech, llm })
}

// The main process watches service health (5s loop behind the tray) and
// emits a change event whenever a service's real state flips -- including
// servers started/stopped OUTSIDE Hermes (e.g. LM Studio's own GUI). Wiring
// that event to a re-announce keeps the Gateway's routing view eventually
// consistent with reality instead of only as fresh as the last UI toggle.
let changeListenerInstalled = false

export function installLocalServicesAnnounceListener(): void {
  if (changeListenerInstalled) {
    return
  }

  const bridge = window.hermesDesktop?.localServices

  if (!bridge?.onChanged) {
    return
  }

  changeListenerInstalled = true
  bridge.onChanged(() => {
    void announceLocalCapabilities()
  })
}

// LM Studio load/unload lifecycle, pushed from main as ensureLocalModelLoaded()
// swaps models ahead of a completion (see electron/main.cjs). Purely local --
// doesn't touch the Gateway -- so it's a plain toast, not a capability re-announce.
let lifecycleListenerInstalled = false

export function installLlmLifecycleListener(): void {
  if (lifecycleListenerInstalled) {
    return
  }

  const bridge = window.hermesDesktop

  if (!bridge?.onLlmLifecycle) {
    return
  }

  lifecycleListenerInstalled = true
  bridge.onLlmLifecycle(event => {
    if (event.kind === 'loading') {
      notify({
        kind: 'info',
        message: event.ejecting
          ? `Swapping GPU model: unloading ${event.ejecting}, loading ${event.model}…`
          : `Loading ${event.model} into GPU memory…`,
        durationMs: 6000
      })
    } else if (event.kind === 'loaded') {
      // A fresh load IS the cold start -- fold the warm-up note in here
      // instead of stacking a second toast on top of this one.
      notify({
        kind: 'success',
        message: `${event.model} loaded — first response may be slower while it warms up`,
        durationMs: 5000
      })
    } else if (event.kind === 'cold-start') {
      // Model already resident, but the server hasn't served since it
      // (re)started: empty prompt cache, so this first turn pays the full
      // prefill. Distinct from the load toast -- nothing was loaded here.
      notify({
        kind: 'info',
        message: `${event.model}: first response after a restart is slower — building the prompt cache`,
        durationMs: 8000
      })
    } else if (event.kind === 'load-failed') {
      notify({ kind: 'error', message: `Failed to load ${event.model}: ${event.error || 'unknown error'}`, durationMs: 6000 })
    }
  })
}
