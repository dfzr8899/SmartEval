let token = ''
let refreshing: Promise<string> | undefined
let unauthorizedHandler: (() => void) | undefined
const authChannel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('smarteval-auth') : undefined
authChannel?.addEventListener('message', event => {
  if (event.data?.type === 'token' && typeof event.data.access === 'string') {
    token = event.data.access
    if (!token) unauthorizedHandler?.()
  }
})

export function authorize(access: string, broadcast = true) {
  token = access
  if (broadcast) authChannel?.postMessage({ type: 'token', access })
}

export function onUnauthorized(handler: () => void) {
  unauthorizedHandler = handler
}

async function request(path: string, options: RequestInit = {}) {
  const headers = new Headers(options.headers)
  const publicRoute = path.startsWith('/public/')
  const original = publicRoute ? '' : token
  if (original) headers.set('Authorization', 'Bearer ' + original)
  const send = () => fetch('/api/v2' + path, { ...options, headers, credentials: publicRoute ? 'omit' : 'same-origin', cache: 'no-store' })
  let response = await send()
  if (response.status === 401 && original && !path.startsWith('/auth/')) {
    if (token === original) {
      refreshing ??= fetch('/api/v2/auth/refresh', { method:'POST', credentials:'same-origin', cache:'no-store' })
        .then(async result => result.ok ? (await result.json()).access_token as string : '')
        .finally(() => { refreshing = undefined })
      const replacement = await refreshing
      if (token === original && replacement) authorize(replacement)
    }
    if (token && token !== original) {
      headers.set('Authorization','Bearer '+token)
      response = await send()
    }
  }
  if (response.status === 401 && !publicRoute && !path.startsWith('/auth/')) {
    authorize('', false)
    unauthorizedHandler?.()
  }
  if (!response.ok) {
    const message = await response.json().catch(() => ({}))
    throw new Error(message.message ?? `请求失败（${response.status}）`)
  }
  return response
}

export async function api<T>(path: string, method = 'GET', data?: unknown, headers: Record<string, string> = {}, signal?: AbortSignal) {
  if (data !== undefined) headers = { ...headers, 'Content-Type': 'application/json' }
  const response = await request(path, { method, headers, signal, body: data === undefined ? undefined : JSON.stringify(data) })
  return { data: (response.status === 204 ? undefined : await response.json()) as T, tag: response.headers.get('ETag') ?? '' }
}

export async function upload(file: File) {
  const data = new FormData()
  data.append('file', file)
  return (await (await request('/assets', { method: 'POST', body: data })).json()) as { asset_id: string }
}

export async function form<T>(path: string, data: FormData, withBody = true) {
  const response = await request(path, { method: 'POST', body: data })
  return (withBody ? await response.json() : undefined) as T
}

export async function assetUrl(id: string, page?: number) {
  return URL.createObjectURL(await (await request('/assets/' + encodeURIComponent(id) + (page ? '/pages/' + page : ''))).blob())
}

function filenameFromHeader(header: string | null): string | undefined {
  if (!header) return undefined
  const utf8 = header.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)
  if (utf8?.[1]) {
    try { return decodeURIComponent(utf8[1].trim()) } catch { /* fallback below */ }
  }
  const plain = header.match(/filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;]+)/i)
  const raw = (plain?.[1] ?? plain?.[2])?.trim()
  return raw || undefined
}

export async function downloadAsset(id: string, fallbackName?: string) {
  const response = await request('/assets/' + encodeURIComponent(id))
  const name = filenameFromHeader(response.headers.get('Content-Disposition')) ?? fallbackName ?? ('asset-' + id)
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export async function exportGrades(id: string, run: number) {
  const response = await request('/grading-jobs/' + encodeURIComponent(id) + '/export?format=csv&run=' + run)
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = 'grading-' + id + '-run-' + run + '.csv'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export async function exportEvalDataset(id: string, run: number) {
  const response = await request('/grading-jobs/' + encodeURIComponent(id) + '/eval-dataset?run=' + run)
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = 'eval-dataset-' + id + '-run-' + run + '.json'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
