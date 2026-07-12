import { type ReactNode, useCallback, useEffect, useState } from 'react'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import type { LocalServiceDescriptor, LocalServicePanelField } from '@/global'
import { useI18n } from '@/i18n'
import { ChevronLeft, Cpu, FolderOpen, Loader2, Pencil, Play, Plus, Settings, Square, Trash2 } from '@/lib/icons'
import { announceLocalCapabilities } from '@/lib/local-services-capabilities'
import { notify, notifyError } from '@/store/notifications'

import { EmptyState, ListRow, LoadingState, Pill, SectionHeading, SettingsContent } from './primitives'

type ServiceStatus = { healthy: boolean; running: boolean; lastError: string | null }
type PendingAction = 'start' | 'stop' | null

const STATUS_POLL_MS = 4000

interface ServiceFormDialogProps {
  open: boolean
  onClose: () => void
  onSave: (descriptor: Partial<LocalServiceDescriptor>) => Promise<void>
  initialData?: LocalServiceDescriptor | null
  // True when the service being edited is currently running, so the form can
  // warn that changes only take effect on the next start.
  running?: boolean
}

function ServiceFormDialog({ open, onClose, onSave, initialData, running = false }: ServiceFormDialogProps) {
  const { t } = useI18n()
  const s = t.settings.localServices

  const [name, setName] = useState('')
  const [command, setCommand] = useState('')
  const [args, setArgs] = useState('')
  const [cwd, setCwd] = useState('')
  const [healthUrl, setHealthUrl] = useState('')
  const [autoStart, setAutoStart] = useState(false)
  const [busy, setBusy] = useState(false)
  // Descriptor awaiting the user's command authorization (see handleSubmit).
  const [confirmDescriptor, setConfirmDescriptor] = useState<null | Partial<LocalServiceDescriptor>>(null)

  useEffect(() => {
    if (open) {
      if (initialData) {
        setName(initialData.name || '')
        setCommand(initialData.command || '')
        setArgs((initialData.args || []).join(' '))
        setCwd(initialData.cwd || '')
        setHealthUrl(initialData.healthUrl || '')
        setAutoStart(Boolean(initialData.autoStart))
      } else {
        setName('')
        setCommand('')
        setArgs('')
        setCwd('')
        setHealthUrl('')
        setAutoStart(false)
      }

      setConfirmDescriptor(null)
    }
  }, [open, initialData])

  const pickCommand = async () => {
    const paths = await window.hermesDesktop?.selectPaths?.({
      directories: false,
      title: s.pickExecutable,
      filters: [
        { name: s.filterExecutables, extensions: ['exe', 'bat', 'cmd', 'sh', 'py'] },
        { name: s.filterAll, extensions: ['*'] }
      ]
    })

    if (paths && paths.length > 0) {
      setCommand(paths[0])
    }
  }

  const pickCwd = async () => {
    const paths = await window.hermesDesktop?.selectPaths?.({
      directories: true,
      title: s.pickDirectory
    })

    if (paths && paths.length > 0) {
      setCwd(paths[0])
    }
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()

    if (!name || !command) {return}

    // Split args respecting quotes. `kind` and `capabilities` are deliberately
    // NOT sent: the main process preserves whatever the existing entry has
    // (capabilities are hand-configured, not form-editable) and defaults kind.
    const parsedArgs = args.trim() ? args.match(/(?:[^\s"']+|"[^"]*"|'[^']*')+/g)?.map(arg => {
      if (arg.startsWith('"') && arg.endsWith('"')) {return arg.slice(1, -1)}

      if (arg.startsWith("'") && arg.endsWith("'")) {return arg.slice(1, -1)}

      return arg
    }) || [] : []

    const descriptor: Partial<LocalServiceDescriptor> = {
      name,
      command,
      args: parsedArgs,
      cwd: cwd || undefined,
      healthUrl: healthUrl || undefined,
      autoStart
    }

    if (initialData) {
      descriptor.id = initialData.id
    }

    // Authorize the command once, at registration time — the descriptor is an
    // arbitrary executable this app will spawn, so the user must see exactly
    // what they are approving. Start/stop afterwards never re-prompts.
    setConfirmDescriptor(descriptor)
  }

  const confirmSave = async () => {
    const descriptor = confirmDescriptor

    if (!descriptor) {return}

    setConfirmDescriptor(null)
    setBusy(true)

    try {
      await onSave(descriptor)
      onClose()
    } catch (err) {
      notifyError(err, s.saveFailed)
    } finally {
      setBusy(false)
    }
  }

  const fullCommand = args.trim() ? `${command} ${args.trim()}` : command

  return (
    <Dialog onOpenChange={onClose} open={open}>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>{initialData ? s.editService : s.addService}</DialogTitle>
        </DialogHeader>
        <form className="space-y-4" onSubmit={handleSubmit}>
          <div className="space-y-1">
            <label className="text-xs font-semibold text-muted-foreground">{s.fieldLabelName}</label>
            <Input onChange={e => setName(e.target.value)} required value={name} />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-semibold text-muted-foreground">{s.fieldLabelCommand}</label>
            <div className="flex gap-2">
              <Input className="flex-1" onChange={e => setCommand(e.target.value)} required value={command} />
              <Button onClick={pickCommand} size="sm" type="button" variant="outline">
                <FolderOpen className="size-4" />
                {s.browse}
              </Button>
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-semibold text-muted-foreground">{s.fieldLabelArgs}</label>
            <Input onChange={e => setArgs(e.target.value)} value={args} />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-semibold text-muted-foreground">{s.fieldLabelCwd}</label>
            <div className="flex gap-2">
              <Input className="flex-1" onChange={e => setCwd(e.target.value)} value={cwd} />
              <Button onClick={pickCwd} size="sm" type="button" variant="outline">
                <FolderOpen className="size-4" />
                {s.browse}
              </Button>
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-semibold text-muted-foreground">{s.fieldLabelHealthUrl}</label>
            <Input onChange={e => setHealthUrl(e.target.value)} placeholder="http://127.0.0.1:8123/health" value={healthUrl} />
          </div>

          <div className="flex items-center gap-6 pt-2">
            <label className="flex items-center gap-2 text-sm font-medium text-foreground cursor-pointer select-none">
              <input checked={autoStart} className="rounded border-input text-primary focus:ring-primary size-4" onChange={e => setAutoStart(e.target.checked)} type="checkbox" />
              <span>{s.fieldLabelAutoStart}</span>
            </label>
          </div>

          {initialData && running ? <p className="text-xs text-muted-foreground">{s.editRestartHint}</p> : null}

          <DialogFooter className="pt-4">
            <Button disabled={busy} onClick={onClose} type="button" variant="ghost">
              {s.cancel}
            </Button>
            <Button disabled={busy} type="submit">
              {busy ? <Loader2 className="animate-spin size-4" /> : null}
              {s.save}
            </Button>
          </DialogFooter>
        </form>

        <ConfirmDialog
          body={<code className="break-all text-xs">{fullCommand}</code>}
          confirmLabel={s.save}
          onClose={() => setConfirmDescriptor(null)}
          onConfirm={() => void confirmSave()}
          open={confirmDescriptor !== null}
          title={s.confirmRunTitle}
        />
      </DialogContent>
    </Dialog>
  )
}

interface ConfirmDialogProps {
  open: boolean
  onClose: () => void
  onConfirm: () => void
  title: string
  body: ReactNode
  confirmLabel: string
  destructive?: boolean
}

// Shared confirmation dialog for the destructive actions in this panel
// (delete a service, delete a panel item) and for the one-time command
// authorization when a service descriptor is saved.
function ConfirmDialog({ open, onClose, onConfirm, title, body, confirmLabel, destructive }: ConfirmDialogProps) {
  const { t } = useI18n()
  const s = t.settings.localServices

  return (
    <Dialog onOpenChange={onClose} open={open}>
      <DialogContent className="sm:max-w-[420px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription className="pt-2">{body}</DialogDescription>
        </DialogHeader>
        <DialogFooter className="pt-4">
          <Button onClick={onClose} type="button" variant="ghost">
            {s.cancel}
          </Button>
          <Button
            className={destructive ? 'bg-destructive hover:bg-destructive/90 text-white border-0' : undefined}
            onClick={onConfirm}
            type="button"
          >
            {confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

interface ItemFormDialogProps {
  open: boolean
  onClose: () => void
  onCreate: (fields: Record<string, unknown>) => Promise<void>
  fields: LocalServicePanelField[]
}

function ItemFormDialog({ open, onClose, onCreate, fields }: ItemFormDialogProps) {
  const { t } = useI18n()
  const s = t.settings.localServices
  const [values, setValues] = useState<Record<string, unknown>>({})
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (open) {
      setValues({})
    }
  }, [open])

  const handleBrowseFile = async (fieldId: string, accept?: string) => {
    const filters = accept ? [{ name: s.filterAllowed, extensions: accept.replace(/\*/g, '').split(',').map(ext => ext.trim().replace(/^\./, '')) }] : undefined

    const paths = await window.hermesDesktop?.selectPaths?.({
      directories: false,
      title: s.pickFile,
      filters
    })

    if (paths && paths.length > 0) {
      setValues(prev => ({ ...prev, [fieldId]: paths[0] }))
    }
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)

    try {
      await onCreate(values)
      onClose()
    } catch (err) {
      notifyError(err, s.itemCreateFailed)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog onOpenChange={onClose} open={open}>
      <DialogContent className="sm:max-w-[420px]">
        <DialogHeader>
          <DialogTitle>{s.addItem}</DialogTitle>
        </DialogHeader>
        <form className="space-y-4" onSubmit={handleSubmit}>
          {fields.map(field => {
            const val = values[field.id] || ''

            return (
              <div className="space-y-1" key={field.id}>
                <label className="text-xs font-semibold text-muted-foreground">{field.label}</label>
                {field.type === 'file' ? (
                  <div className="flex gap-2">
                    <Input className="flex-1" readOnly required={field.required} value={typeof val === 'string' ? val : ''} />
                    <Button onClick={() => handleBrowseFile(field.id, field.accept)} size="sm" type="button" variant="outline">
                      <FolderOpen className="size-4" />
                      {s.browse}
                    </Button>
                  </div>
                ) : field.type === 'textarea' ? (
                  <textarea
                    className="w-full rounded-md border border-input bg-transparent px-3 py-1.5 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring min-h-[80px]"
                    onChange={e => setValues(prev => ({ ...prev, [field.id]: e.target.value }))}
                    required={field.required}
                    value={typeof val === 'string' ? val : ''}
                  />
                ) : field.type === 'toggle' ? (
                  <label className="flex items-center gap-2 cursor-pointer select-none py-1">
                    <input
                      checked={Boolean(val)}
                      className="rounded border-input text-primary focus:ring-primary size-4"
                      onChange={e => setValues(prev => ({ ...prev, [field.id]: e.target.checked }))}
                      type="checkbox"
                    />
                    <span>{field.label}</span>
                  </label>
                ) : field.type === 'select' ? (
                  <select
                    className="w-full rounded-md border border-input bg-transparent px-3 py-1.5 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring h-9"
                    onChange={e => setValues(prev => ({ ...prev, [field.id]: e.target.value }))}
                    required={field.required}
                    value={typeof val === 'string' ? val : ''}
                  >
                    <option disabled value="">{s.selectOption}</option>
                    {field.options?.map(opt => (
                      <option key={opt} value={opt}>{opt}</option>
                    ))}
                  </select>
                ) : (
                  <Input
                    onChange={e => setValues(prev => ({ ...prev, [field.id]: e.target.value }))}
                    required={field.required}
                    type="text"
                    value={typeof val === 'string' ? val : ''}
                  />
                )}
              </div>
            )
          })}
          <DialogFooter className="pt-4">
            <Button disabled={busy} onClick={onClose} type="button" variant="ghost">
              {s.cancel}
            </Button>
            <Button disabled={busy} type="submit">
              {busy ? <Loader2 className="animate-spin size-4" /> : null}
              {s.save}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

interface PanelSubViewProps {
  svc: LocalServiceDescriptor
  onBack: () => void
}

function PanelSubView({ svc, onBack }: PanelSubViewProps) {
  const { t } = useI18n()
  const s = t.settings.localServices
  const bridge = window.hermesDesktop?.localServices

  const [loading, setLoading] = useState(true)
  const [items, setItems] = useState<Record<string, unknown>[]>([])
  const [formOpen, setFormOpen] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<null | { id: string; label: string }>(null)
  // null until the first status probe lands; drives the "start it first" state
  // instead of dumping a bare request-failed toast when the service is off.
  const [svcStatus, setSvcStatus] = useState<null | ServiceStatus>(null)
  const [startPending, setStartPending] = useState(false)

  const healthy = svcStatus?.healthy === true

  // Track service health while the panel is open (2s while unhealthy so a
  // just-started service picks up quickly, relaxed once it's serving).
  useEffect(() => {
    if (!bridge) {return}

    let cancelled = false

    const probe = () => {
      void bridge.status(svc.id).then(next => {
        if (!cancelled) {
          setSvcStatus(next)
        }
      })
    }

    probe()
    const interval = setInterval(probe, healthy ? STATUS_POLL_MS : 2000)

    return () => {
      cancelled = true
      clearInterval(interval)
    }
  }, [bridge, svc.id, healthy])

  const loadItems = useCallback(async () => {
    if (!bridge) {return}
    setLoading(true)

    try {
      const res = await bridge.panelRequest({ serviceId: svc.id, action: 'list' })

      if (res.ok) {
        if (Array.isArray(res.data)) {
          setItems(res.data)
        } else if (res.data && Array.isArray(res.data.voices)) {
          setItems(res.data.voices)
        } else {
          setItems([])
        }
      } else {
        setItems([])
        notifyError(new Error(res.error || s.itemsLoadFailed), s.itemsLoadFailed)
      }
    } catch (err) {
      notifyError(err, s.itemsLoadFailed)
    } finally {
      setLoading(false)
    }
  }, [bridge, svc.id, s.itemsLoadFailed])

  // Fetch the list only once the service is actually serving.
  useEffect(() => {
    if (healthy) {
      void loadItems()
    }
  }, [healthy, loadItems])

  const startService = async () => {
    if (!bridge) {return}
    setStartPending(true)

    try {
      const res = await bridge.start(svc.id)

      if (!res.ok) {
        notifyError(new Error(res.error || s.startFailed), s.startFailed)
      }
      // Success: the status poll flips `healthy` once the service is serving,
      // which triggers the item load — nothing else to do here.
    } finally {
      setStartPending(false)
    }
  }

  const handleCreate = async (fields: Record<string, unknown>) => {
    if (!bridge) {return}
    const res = await bridge.panelRequest({ serviceId: svc.id, action: 'create', fields })

    if (res.ok) {
      notify({ kind: 'success', title: s.itemAdded, message: '' })
      void loadItems()
    } else {
      throw new Error(res.error || s.itemCreateFailed)
    }
  }

  const handleDeleteConfirmed = async () => {
    const target = deleteTarget

    if (!bridge || !target) {return}

    setDeleteTarget(null)

    try {
      const res = await bridge.panelRequest({ serviceId: svc.id, action: 'delete', itemId: target.id })

      if (res.ok) {
        notify({ kind: 'success', title: s.itemDeleted, message: '' })
        void loadItems()
      } else {
        notifyError(new Error(res.error || s.itemDeleteFailed), s.itemDeleteFailed)
      }
    } catch (err) {
      notifyError(err, s.itemDeleteFailed)
    }
  }

  const panel = svc.panel

  if (!panel) {return null}

  const displayPrimary = panel.display?.primary || 'name'
  const displaySecondary = panel.display?.secondary || 'id'

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <Button onClick={onBack} size="icon-sm" variant="ghost">
          <ChevronLeft className="size-4" />
        </Button>
        <div>
          <h2 className="text-lg font-semibold tracking-tight">{panel.title}</h2>
          <p className="text-xs text-muted-foreground">{svc.name}</p>
        </div>
        <Button className="ml-auto" disabled={!healthy} onClick={() => setFormOpen(true)} size="sm">
          <Plus className="size-3.5 mr-1" />
          {s.addItem}
        </Button>
      </div>

      {!healthy ? (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-border/40 bg-muted/5 px-6 py-10 text-center">
          <p className="text-sm font-medium">{s.notRunningTitle}</p>
          <p className="text-xs text-muted-foreground">{s.notRunningDesc}</p>
          <Button disabled={startPending || svcStatus === null} onClick={() => void startService()} size="sm">
            {startPending || (svcStatus?.running && !healthy) ? (
              <Loader2 className="size-4 animate-spin" />
            ) : (
              <Play className="size-3.5" />
            )}
            {startPending || (svcStatus?.running && !healthy) ? s.starting : s.start}
          </Button>
          {svcStatus?.lastError ? (
            <p className="text-xs text-destructive">{s.lastErrorPrefix}{svcStatus.lastError}</p>
          ) : null}
        </div>
      ) : loading ? (
        <LoadingState label={s.loading} />
      ) : items.length === 0 ? (
        <EmptyState description={s.noItems} title={panel.title} />
      ) : (
        <div className="divide-y divide-border/30 border rounded-xl overflow-hidden bg-muted/5">
          {items.map((item, idx) => {
            const idVal = typeof item.id === 'string' && item.id ? item.id : String(idx)
            const rawTitle = item[displayPrimary]
            const titleVal = typeof rawTitle === 'string' && rawTitle ? rawTitle : idVal
            const rawSub = item[displaySecondary]
            const subVal = rawSub !== undefined && rawSub !== null && String(rawSub) !== titleVal ? String(rawSub) : null

            return (
              <ListRow
                action={
                  panel.resource.delete ? (
                    <Button
                      className="text-destructive hover:bg-destructive/10"
                      onClick={() => setDeleteTarget({ id: idVal, label: titleVal })}
                      size="icon-sm"
                      variant="ghost"
                    >
                      <Trash2 className="size-4" />
                    </Button>
                  ) : undefined
                }
                description={subVal || undefined}
                key={idVal}
                title={titleVal}
              />
            )
          })}
        </div>
      )}

      <ItemFormDialog
        fields={panel.fields}
        onClose={() => setFormOpen(false)}
        onCreate={handleCreate}
        open={formOpen}
      />

      <ConfirmDialog
        body={
          <>
            {s.confirmDeleteItem} <strong>{deleteTarget?.label}</strong>
          </>
        }
        confirmLabel={s.deleteItem}
        destructive
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => void handleDeleteConfirmed()}
        open={deleteTarget !== null}
        title={s.deleteItem}
      />
    </div>
  )
}

export function LocalServicesSettings() {
  const { t } = useI18n()
  const s = t.settings.localServices
  const bridge = window.hermesDesktop?.localServices

  const [loading, setLoading] = useState(true)
  const [services, setServices] = useState<LocalServiceDescriptor[]>([])
  const [statuses, setStatuses] = useState<Record<string, ServiceStatus>>({})
  const [pending, setPending] = useState<Record<string, PendingAction>>({})

  const [formOpen, setFormOpen] = useState(false)
  const [formInitialData, setFormInitialData] = useState<LocalServiceDescriptor | null>(null)
  const [confirmDeleteOpen, setConfirmDeleteOpen] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<LocalServiceDescriptor | null>(null)
  const [activePanelService, setActivePanelService] = useState<LocalServiceDescriptor | null>(null)

  const reloadServices = useCallback(async () => {
    if (!bridge) {return}

    try {
      const list = await bridge.list()
      setServices(list)
    } catch (err) {
      notifyError(err, s.servicesLoadFailed)
    }
  }, [bridge, s.servicesLoadFailed])

  useEffect(() => {
    if (!bridge) {
      setLoading(false)

      return
    }

    let cancelled = false

    bridge.list().then(list => {
      if (!cancelled) {
        setServices(list)
        setLoading(false)
      }
    })

    return () => {
      cancelled = true
    }
  }, [bridge])

  useEffect(() => {
    if (!bridge || services.length === 0) {
      return
    }

    let cancelled = false

    const poll = () => {
      Promise.all(
        services.map(async svc => {
          try {
            const status = await bridge.status(svc.id)

            return [svc.id, status] as const
          } catch {
            return [svc.id, { healthy: false, running: false, lastError: null }] as const
          }
        })
      ).then(entries => {
        if (!cancelled) {
          setStatuses(Object.fromEntries(entries))
        }
      })
    }

    poll()
    const interval = setInterval(poll, STATUS_POLL_MS)

    return () => {
      cancelled = true
      clearInterval(interval)
    }
  }, [bridge, services])

  const toggle = async (svc: LocalServiceDescriptor) => {
    if (!bridge) {return}

    const running = statuses[svc.id]?.running
    const action: 'start' | 'stop' = running ? 'stop' : 'start'

    setPending(prev => ({ ...prev, [svc.id]: action }))

    try {
      const result = action === 'start' ? await bridge.start(svc.id) : await bridge.stop(svc.id)

      if (!result.ok) {
        notifyError(
          new Error(result.error || 'unknown error'),
          action === 'start' ? s.startFailed : s.stopFailed
        )

        return
      }

      const next = await bridge.status(svc.id)
      setStatuses(prev => ({ ...prev, [svc.id]: next }))
      void announceLocalCapabilities()
    } finally {
      setPending(prev => ({ ...prev, [svc.id]: null }))
    }
  }

  const handleSave = async (descriptor: Partial<LocalServiceDescriptor>) => {
    if (!bridge) {return}
    const res = await bridge.upsert(descriptor)

    if (res.ok) {
      notify({ kind: 'success', title: s.serviceSaved, message: '' })
      void reloadServices()
    } else {
      throw new Error(res.error || s.saveFailed)
    }
  }

  const handleDeleteConfirm = async () => {
    if (!bridge || !deleteTarget) {return}

    try {
      const res = await bridge.remove(deleteTarget.id)

      if (res.ok) {
        notify({ kind: 'success', title: s.serviceRemoved, message: '' })
        setConfirmDeleteOpen(false)
        setDeleteTarget(null)
        void reloadServices()
      } else {
        notifyError(new Error(res.error || s.removeFailed), s.removeFailed)
      }
    } catch (err) {
      notifyError(err, s.removeFailed)
    }
  }

  if (!bridge) {
    return (
      <SettingsContent>
        <EmptyState description={s.unavailableDesc} title={s.unavailableTitle} />
      </SettingsContent>
    )
  }

  if (loading) {
    return (
      <SettingsContent>
        <LoadingState label={s.loading} />
      </SettingsContent>
    )
  }

  if (activePanelService) {
    return (
      <SettingsContent>
        <PanelSubView onBack={() => setActivePanelService(null)} svc={activePanelService} />
      </SettingsContent>
    )
  }

  return (
    <SettingsContent>
      <div className="flex items-center gap-3">
        <SectionHeading icon={Cpu} title={s.title} />
        <Button className="ml-auto" onClick={() => { setFormInitialData(null); setFormOpen(true); }} size="sm">
          <Plus className="size-3.5 mr-1" />
          {s.addService}
        </Button>
      </div>
      <p className="mb-3 text-[length:var(--conversation-caption-font-size)] leading-(--conversation-caption-line-height) text-(--ui-text-tertiary)">
        {s.intro}
      </p>

      {services.length === 0 ? (
        <EmptyState description={s.empty} title={s.title} />
      ) : (
        <div className="divide-y divide-border/30">
          {services.map(svc => {
            const status = statuses[svc.id]
            const busy = pending[svc.id]

            const statusLabel = status?.healthy
              ? s.statusHealthy
              : status?.running
                ? s.statusUnhealthy
                : s.statusStopped

            return (
              <ListRow
                action={
                  <div className="flex items-center gap-2">
                    {svc.panel && (
                      <Button onClick={() => setActivePanelService(svc)} size="sm" variant="outline">
                        <Settings className="size-3.5 mr-1" />
                        {s.manage}
                      </Button>
                    )}
                    <Button
                      disabled={Boolean(busy)}
                      onClick={() => void toggle(svc)}
                      size="sm"
                      variant={status?.running ? 'outline' : 'default'}
                    >
                      {busy ? (
                        <Loader2 className="size-4 animate-spin" />
                      ) : status?.running ? (
                        <Square className="size-3.5" />
                      ) : (
                        <Play className="size-3.5" />
                      )}
                      {busy === 'start'
                        ? s.starting
                        : busy === 'stop'
                          ? s.stopping
                          : status?.running
                            ? s.stop
                            : s.start}
                    </Button>
                    <Button
                      onClick={() => {
                        setFormInitialData(svc)
                        setFormOpen(true)
                      }}
                      size="icon-sm"
                      variant="ghost"
                    >
                      <Pencil className="size-4" />
                    </Button>
                    <Button
                      className="text-destructive hover:bg-destructive/10"
                      onClick={() => {
                        setDeleteTarget(svc)
                        setConfirmDeleteOpen(true)
                      }}
                      size="icon-sm"
                      variant="ghost"
                    >
                      <Trash2 className="size-4" />
                    </Button>
                  </div>
                }
                description={
                  <div className="space-y-1">
                    {svc.autoStart && <p className="text-xs text-muted-foreground">{s.autoStartHint}</p>}
                    {status?.lastError && (
                      <p className="text-xs text-destructive font-medium leading-normal">
                        {s.lastErrorPrefix}{status.lastError}
                      </p>
                    )}
                  </div>
                }
                hint={svc.healthUrl}
                key={svc.id}
                title={
                  <span className="flex items-center gap-2">
                    {svc.name}
                    <Pill tone={status?.healthy ? 'primary' : status?.running ? undefined : 'muted'}>{statusLabel}</Pill>
                  </span>
                }
              />
            )
          })}
        </div>
      )}

      <ServiceFormDialog
        initialData={formInitialData}
        onClose={() => setFormOpen(false)}
        onSave={handleSave}
        open={formOpen}
        running={formInitialData ? Boolean(statuses[formInitialData.id]?.running) : false}
      />

      <ConfirmDialog
        body={
          <>
            {s.confirmDelete} <strong>{deleteTarget?.name}</strong>
          </>
        }
        confirmLabel={s.deleteService}
        destructive
        onClose={() => setConfirmDeleteOpen(false)}
        onConfirm={() => void handleDeleteConfirm()}
        open={confirmDeleteOpen}
        title={s.deleteService}
      />
    </SettingsContent>
  )
}
