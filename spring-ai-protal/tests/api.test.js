import { afterEach, beforeEach, mock, test } from 'node:test'
import assert from 'node:assert/strict'
import { chatAPI, pdfFilename, request, resetCsrf } from '../src/services/api.js'

beforeEach(() => resetCsrf())
afterEach(() => mock.restoreAll())
function mockFetch(handler) {
  return mock.method(globalThis, 'fetch', async (url, options) => {
    if (url.endsWith('/auth/csrf')) return Response.json({ token: 'test-csrf', headerName: 'X-CSRF-TOKEN' })
    return handler(url, options)
  })
}

test('upload rejects an HTTP 200 business failure instead of showing success', async () => {
  mockFetch(async () => Response.json({ ok: 0, msg: 'PDF 无法解析' }))
  const file = new File(['%PDF-fake'], 'test.pdf', { type: 'application/pdf' })
  await assert.rejects(chatAPI.uploadPdf(file, 'pdf_1'), /PDF 无法解析/)
})

test('backend validation errors are displayed to the user', async () => {
  mockFetch(async () => Response.json({ ok: 0, msg: '文件过大' }, { status: 413 }))
  const file = new File(['%PDF-fake'], 'test.pdf', { type: 'application/pdf' })
  await assert.rejects(chatAPI.uploadPdf(file, 'pdf_1'), /文件过大/)
})

test('successful uploads preserve the server document identifier and deduplication flag', async () => {
  const result = { ok: 1, chatId: 'pdf_1', documentId: 'doc_1', reused: true, chunkCount: 3 }
  mockFetch(async () => Response.json(result))
  const file = new File(['%PDF-fake'], 'test.pdf', { type: 'application/pdf' })
  assert.deepEqual(await chatAPI.uploadPdf(file, 'pdf_1'), result)
})

test('history preserves server timestamps and sends the pagination cursor', async () => {
  let requested
  mockFetch(async url => {
    requested = new URL(url, 'http://localhost')
    return Response.json([{ id: 7, role: 'user', content: 'hello', timestamp: '2026-10-15T02:00:00Z' }])
  })
  const messages = await chatAPI.getChatMessages('pdf_1', 'pdf', { limit: 10, beforeId: 8 })
  assert.equal(requested.searchParams.get('beforeId'), '8')
  assert.equal(requested.searchParams.get('limit'), '10')
  assert.equal(messages[0].timestamp.toISOString(), '2026-10-15T02:00:00.000Z')
})

test('long questions are posted in the body instead of appended to the URL', async () => {
  let request
  mockFetch(async (url, options) => {
    request = { url, options }
    return new Response('answer')
  })
  const question = '问题'.repeat(5000)
  const reader = await chatAPI.sendPdfMessage(question, 'pdf_1')
  assert.equal(new URL(request.url, 'http://localhost').search, '')
  assert.equal(request.options.method, 'POST')
  assert.equal(request.options.body.get('prompt'), question)
  await reader.cancel()
})

test('an empty upload is rejected without making a request', async () => {
  const fetch = mockFetch(async () => { throw new Error('unexpected request') })
  await assert.rejects(chatAPI.uploadPdf(new File([], 'empty.pdf'), 'pdf_1'), /非空/)
  assert.equal(fetch.mock.calls.length, 0)
})

test('Chinese PDF filenames are recovered from the RFC 5987 download header', () => {
  assert.equal(pdfFilename("attachment; filename=\"fallback.pdf\"; filename*=UTF-8''%E8%AF%BE%E7%A8%8B.pdf"), '课程.pdf')
  assert.equal(pdfFilename('attachment; filename="notes.pdf"'), 'notes.pdf')
  assert.equal(pdfFilename(null), 'document.pdf')
})


test('mutations include a CSRF token and browser session cookies', async () => {
  let options
  mockFetch(async (url, init) => { options = init; return Response.json({}) })
  await request('/auth/login', { method: 'POST' })
  assert.equal(options.credentials, 'include')
  assert.equal(options.headers.get('X-CSRF-TOKEN'), 'test-csrf')
})

test('asynchronous uploads poll until the indexed document is ready', async () => {
  const states = []
  const result = { ok: 1, chatId: 'pdf_1', filename: 'test.pdf', documentId: 'doc_1' }
  mockFetch(async url => url.includes('/upload/') ? Response.json({ id: 'task1', status: 'QUEUED' }, { status: 202 }) : Response.json({ id: 'task1', status: 'SUCCEEDED', result }))
  const file = new File(['%PDF-fake'], 'test.pdf', { type: 'application/pdf' })
  assert.deepEqual(await chatAPI.uploadPdf(file, 'pdf_1', { onTask: task => states.push(task.status) }), result)
  assert.deepEqual(states, ['QUEUED', 'SUCCEEDED'])
})

test('failed jobs show their processing error and cancellation stops polling', async () => {
  await assert.rejects(chatAPI.waitForPdfTask({ id: 'task1', status: 'FAILED', error: '扫描件未提取到文字' }), /扫描件/)
  const controller = new AbortController()
  controller.abort()
  await assert.rejects(chatAPI.waitForPdfTask({ id: 'task1', status: 'RUNNING' }, { signal: controller.signal }), { name: 'AbortError' })
})
