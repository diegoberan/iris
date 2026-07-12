function parseArgs(args) {
  if (Array.isArray(args)) {
    return args.map(String)
  }
  if (typeof args !== 'string') {
    return []
  }
  const result = []
  const regex = /"([^"]*)"|'([^']*)'|([^\s"']+)/g
  let match
  while ((match = regex.exec(args)) !== null) {
    if (match[1] !== undefined) {
      result.push(match[1])
    } else if (match[2] !== undefined) {
      result.push(match[2])
    } else if (match[3] !== undefined) {
      result.push(match[3])
    }
  }
  return result
}

function slugify(name) {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/(^-|-$)/g, '')
}

function isLoopbackUrl(urlStr) {
  try {
    const url = new URL(urlStr)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      return false
    }
    const hostname = url.hostname.toLowerCase()
    if (hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]' || hostname === '::1') {
      return true
    }
    if (/^127\.\d+\.\d+\.\d+$/.test(hostname)) {
      return true
    }
    return false
  } catch {
    return false
  }
}

function validateDescriptor(descriptor, existingServices = []) {
  if (!descriptor || typeof descriptor !== 'object') {
    return { ok: false, error: 'Invalid descriptor' }
  }

  const name = typeof descriptor.name === 'string' ? descriptor.name.trim() : ''
  if (!name) {
    return { ok: false, error: 'Service name is required' }
  }

  const command = typeof descriptor.command === 'string' ? descriptor.command.trim() : ''
  if (!command) {
    return { ok: false, error: 'Command is required' }
  }

  const args = parseArgs(descriptor.args)
  const cwd = typeof descriptor.cwd === 'string' ? descriptor.cwd.trim() : undefined
  const healthUrl = typeof descriptor.healthUrl === 'string' ? descriptor.healthUrl.trim() : undefined

  if (healthUrl && !isLoopbackUrl(healthUrl)) {
    return { ok: false, error: 'Health URL must be a valid HTTP/HTTPS loopback URL (localhost or 127.0.0.1)' }
  }

  let id = descriptor.id
  let existingIndex = -1

  if (id) {
    existingIndex = existingServices.findIndex(s => s.id === id)
    if (existingIndex === -1) {
      return { ok: false, error: `Service with ID '${id}' not found` }
    }
  } else {
    let slug = slugify(name)
    if (!slug) slug = 'service'
    let uniqueId = slug
    let counter = 1
    while (existingServices.some(s => s.id === uniqueId)) {
      uniqueId = `${slug}-${counter}`
      counter++
    }
    id = uniqueId
  }

  const existing = existingIndex !== -1 ? existingServices[existingIndex] : {}

  const service = {
    id,
    name,
    kind: descriptor.kind || existing.kind || 'custom',
    command,
    args,
    autoStart: Boolean(descriptor.autoStart),
    ...(cwd ? { cwd } : {}),
    ...(healthUrl ? { healthUrl } : {}),
    ...(existing.capabilities ? { capabilities: existing.capabilities } : {}),
    ...(existing.panel ? { panel: existing.panel } : {}),
    ...(existing.stopCommand ? { stopCommand: existing.stopCommand } : {}),
    ...(existing.stopArgs ? { stopArgs: existing.stopArgs } : {})
  }

  return { ok: true, service, existingIndex }
}

module.exports = {
  parseArgs,
  slugify,
  isLoopbackUrl,
  validateDescriptor
}
