export const API_BASE_URL = (import.meta.env?.VITE_API_BASE_URL || '/api').replace(/\/$/, '')

let csrfPromise
export function resetCsrf() { csrfPromise = undefined }
async function csrf() {
  if (!csrfPromise) csrfPromise = fetch(`${API_BASE_URL}/auth/csrf`, { credentials: 'include', signal: AbortSignal.timeout(15000) })
    .then(async response => { if (!response.ok) throw new Error('无法连接登录服务，请稍后重试'); return response.json() })
    .catch(error => { resetCsrf(); throw error })
  return csrfPromise
}

export function pdfFilename(header) {
  const encoded = header?.match(/filename\*=UTF-8''([^;]+)/i)
  if (encoded) {
    try { return decodeURIComponent(encoded[1]) } catch { /* Fall back to the ordinary filename. */ }
  }
  return header?.match(/filename="([^"]+)"/i)?.[1] || 'document.pdf'
}

export async function request(path, options = {}) {
  const headers = new Headers(options.headers)
  if (!['GET', 'HEAD', 'OPTIONS'].includes((options.method || 'GET').toUpperCase())) {
    const token = await csrf()
    headers.set(token.headerName, token.token)
  }
  const response = await fetch(`${API_BASE_URL}${path}`, {
    signal: AbortSignal.timeout(120000),
    credentials: 'include',
    ...options,
    headers,
  })
  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const data = await response.json()
      message = data.msg || data.detail || message
    } catch { /* A proxy may return a non-JSON error page. */ }
    if (response.status === 401 && path.startsWith('/ai/') && typeof window !== 'undefined') window.dispatchEvent(new Event('session-expired'))
    if (response.status === 403) resetCsrf()
    const error = new Error(message)
    error.status = response.status
    throw error
  }
  return response
}

async function stream(path, prompt, chatId, signal) {
  const response = await request(path, {
    method: 'POST',
    body: new URLSearchParams({ prompt, chatId }),
    ...(signal ? { signal } : {}),
  })
  if (!response.body) throw new Error('服务器未返回可读取的响应')
  return response.body.getReader()
}

export const chatAPI = {
  async sendMessage(data, chatId, signal) {
    const body = data instanceof FormData ? data : new URLSearchParams({ prompt: data })
    body.set('chatId', chatId)
    const response = await request('/ai/chat', { method: 'POST', body, ...(signal ? { signal } : {}) })
    if (!response.body) throw new Error('服务器未返回可读取的响应')
    return response.body.getReader()
  },

  async getChatHistory(type = 'chat') {
    const response = await request(`/ai/history/${encodeURIComponent(type)}`)
    const ids = await response.json()
    return ids.map(id => ({
      id,
      title: `${type === 'pdf' ? 'PDF对话' : type === 'service' ? '咨询' : '对话'} ${id.slice(-6)}`,
    }))
  },

  async getChatMessages(chatId, type = 'chat', { limit = 200, beforeId } = {}) {
    const query = new URLSearchParams({ limit: String(limit) })
    if (beforeId != null) query.set('beforeId', String(beforeId))
    const response = await request(`/ai/history/${encodeURIComponent(type)}/${encodeURIComponent(chatId)}?${query}`)
    const messages = await response.json()
    return messages.map(message => ({
      ...message,
      timestamp: message.timestamp ? new Date(message.timestamp) : null,
    }))
  },

  async uploadPdf(file, chatId, options = {}) {
    if (!file || file.size === 0) throw new Error('请选择非空的 PDF 文件')
    if (file.size > 20 * 1024 * 1024) throw new Error('PDF 文件不能超过 20 MB')
    const body = new FormData()
    body.append('file', file)
    const response = await request(`/ai/pdf/upload/${encodeURIComponent(chatId)}`, { method: 'POST', body, ...(options.signal ? { signal: options.signal } : {}) })
    const result = await response.json()
    if (!result.id) {
      if (result.ok !== 1) throw new Error(result.msg || '文件处理失败')
      return result
    }
    let task = result
    if (options.retry && task.status === 'FAILED') task = await (await request(`/ai/pdf/tasks/${encodeURIComponent(task.id)}/retry`, { method: 'POST', ...(options.signal ? { signal: options.signal } : {}) })).json()
    return this.waitForPdfTask(task, options)
  },

  async getPdfTaskForChat(chatId) {
    return (await request(`/ai/pdf/tasks?chatId=${encodeURIComponent(chatId)}`)).json()
  },

  async retryPdfTask(taskId, options = {}) {
    const task = await (await request(`/ai/pdf/tasks/${encodeURIComponent(taskId)}/retry`, { method: 'POST', ...(options.signal ? { signal: options.signal } : {}) })).json()
    return this.waitForPdfTask(task, options)
  },

  async waitForPdfTask(initial, { signal, onTask } = {}) {
    let task = initial
    while (true) {
      signal?.throwIfAborted()
      onTask?.(task)
      if (task.status === 'SUCCEEDED') {
        if (!task.result || task.result.ok !== 1) throw new Error('任务结果异常，请重新打开文档')
        return task.result
      }
      if (task.status === 'FAILED') throw new Error(task.error || '文档处理失败，请重试')
      if (!['QUEUED', 'RUNNING'].includes(task.status)) throw new Error('无法识别文档处理状态')
      await new Promise((resolve, reject) => {
        const abort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')) }
        const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve() }, 1000)
        signal?.addEventListener('abort', abort, { once: true })
        if (signal?.aborted) abort()
      })
      task = await (await request(`/ai/pdf/tasks/${encodeURIComponent(task.id)}`, { ...(signal ? { signal } : {}) })).json()
    }
  },

  async getPdfFile(chatId) {
    const response = await request(`/ai/pdf/file/${encodeURIComponent(chatId)}`)
    return new File([await response.blob()], pdfFilename(response.headers.get('content-disposition')),
      { type: 'application/pdf' })
  },

  sendGameMessage(prompt, chatId, signal) { return stream('/ai/game', prompt, chatId, signal) },
  sendServiceMessage(prompt, chatId, signal) { return stream('/ai/service', prompt, chatId, signal) },
  sendPdfMessage(prompt, chatId, signal) { return stream('/ai/pdf/chat', prompt, chatId, signal) },
}
