<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowUpRightIcon, ArrowUpTrayIcon, DocumentTextIcon, PaperAirplaneIcon, StopIcon, ExclamationCircleIcon, ArrowsRightLeftIcon, ArrowDownTrayIcon } from '@heroicons/vue/24/outline'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import ChatMessage from '../components/ChatMessage.vue'
import { chatAPI } from '../services/api'
const history = ref([]), currentId = ref(null), filename = ref(''), messages = ref([]), input = ref(''), error = ref('')
const loading = ref(true), busy = ref(false), streaming = ref(false), dragging = ref(false), preview = ref(false), pdfUrl = ref(''), scrollEl = ref(null), fileInput = ref(null), selectedFile = ref(null), task = ref(null)
const prompts = ['这份文档主要讲了什么？', '帮我提炼三个关键要点', '有哪些值得进一步关注的内容？']
const steps = ['等待处理', '解析与建立索引', '可以开始提问']
const taskStep = computed(() => task.value?.status === 'SUCCEEDED' ? 2 : task.value?.status === 'RUNNING' ? 1 : 0)
let controller, pollController, loadVersion = 0
const scroll = async () => { await nextTick(); if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight }
function setFile(file) { if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value); pdfUrl.value = file ? URL.createObjectURL(file) : '' }
function reset() { if (busy.value || streaming.value) return; loadVersion++; currentId.value = null; filename.value = ''; messages.value = []; input.value = ''; error.value = ''; task.value = null; selectedFile.value = null; preview.value = false; setFile(null) }
async function loadChat(id) {
  if (busy.value || streaming.value) return
  const version = ++loadVersion
  currentId.value = id; filename.value = ''; messages.value = []; error.value = ''; busy.value = true; task.value = null; selectedFile.value = null; setFile(null)
  try {
    const pending = await chatAPI.getPdfTaskForChat(id)
    if (pending && pending.status !== 'SUCCEEDED') {
      task.value = pending
      pollController = new AbortController()
      await chatAPI.waitForPdfTask(pending, { signal: pollController.signal, onTask: update => task.value = update })
      task.value = null
    }
    const [data, file] = await Promise.all([chatAPI.getChatMessages(id, 'pdf'), chatAPI.getPdfFile(id)])
    if (version !== loadVersion) return
    messages.value = data.filter(m => ['user', 'assistant'].includes(m.role)); filename.value = file.name; setFile(file)
    const entry = history.value.find(h => h.id === id); if (entry) entry.title = file.name
  } catch (e) { if (version === loadVersion) error.value = e.message }
  finally { if (version === loadVersion) { busy.value = false; pollController = null; scroll() } }
}
async function processFile(file) {
  if (busy.value || streaming.value || !file) return
  if (!/\.pdf$/i.test(file.name) || !file.size || file.size > 20 * 1024 * 1024) { error.value = '请选择非空的 PDF 文件，大小不超过 20 MB'; return }
  currentId.value = `pdf_${crypto.randomUUID()}`; selectedFile.value = file; messages.value = []; filename.value = ''; setFile(null)
  await submit()
}
async function submit(retry = false) {
  if ((!selectedFile.value && !task.value?.id) || busy.value) return
  const previousTask = task.value
  busy.value = true; error.value = ''; task.value = { status: 'QUEUED' }; pollController = new AbortController()
  try {
    const options = { signal: pollController.signal, retry, onTask: update => task.value = update }
    const result = previousTask?.id && retry ? await chatAPI.retryPdfTask(previousTask.id, options) : await chatAPI.uploadPdf(selectedFile.value, currentId.value, options)
    currentId.value = result.chatId || currentId.value; filename.value = result.filename || selectedFile.value?.name
    setFile(selectedFile.value || await chatAPI.getPdfFile(currentId.value))
    if (!history.value.some(h => h.id === currentId.value)) history.value.unshift({ id: currentId.value, title: filename.value })
    task.value = null
  } catch (e) { if (e.name !== 'AbortError') { error.value = e.message; task.value = { ...task.value, status: 'FAILED' } } }
  finally { busy.value = false; pollController = null }
}
function onFile(event) { const file = event.target.files?.[0]; event.target.value = ''; processFile(file) }
function drop(event) { dragging.value = false; processFile(event.dataTransfer.files?.[0]) }
async function send(text) {
  const question = typeof text === 'string' ? text : input.value.trim()
  if (!question || !filename.value || busy.value || streaming.value) return
  streaming.value = true; error.value = ''; controller = new AbortController()
  messages.value.push({ role: 'user', content: question, timestamp: new Date() })
  const index = messages.value.push({ role: 'assistant', content: '', timestamp: new Date() }) - 1
  input.value = ''; scroll()
  try {
    const reader = await chatAPI.sendPdfMessage(question, currentId.value, controller.signal), decoder = new TextDecoder()
    try { while (true) { const { value, done } = await reader.read(); if (done) break; messages.value[index].content += decoder.decode(value, { stream: true }); scroll() } messages.value[index].content += decoder.decode() }
    finally { reader.releaseLock() }
    if (!messages.value[index].content) error.value = '没有收到回复，请稍后重试。'
  } catch (e) { if (e.name === 'AbortError') { if (!messages.value[index].content) messages.value[index].content = '已停止生成。' } else error.value = e.message }
  finally { streaming.value = false; controller = null; scroll() }
}
function onEnter(event) { if (event.isComposing || event.shiftKey) return; event.preventDefault(); send() }
onMounted(async () => { try { history.value = await chatAPI.getChatHistory('pdf') } catch (e) { error.value = e.message } finally { loading.value = false } })
onBeforeUnmount(() => { loadVersion++; controller?.abort(); pollController?.abort(); setFile(null) })
</script>
<template>
  <div class="chat-workspace">
    <ConversationSidebar title="文档问答" :items="history" :current-id="currentId" :busy="busy || streaming" :loading="loading" @new="reset" @select="loadChat" />
    <section class="conversation-panel">
      <header class="panel-header"><div class="panel-heading"><span class="app-icon sage"><DocumentTextIcon /></span><div><h2 class="document-heading" :title="filename">{{ filename || '和文档聊一聊' }}</h2><p>{{ filename ? '文档已就绪 · 回答基于当前 PDF' : '上传、阅读、提问，让知识变得触手可及' }}</p></div></div><div class="pdf-header-tools"><button v-if="filename" class="icon-button preview-toggle" :aria-label="preview ? '切换到问答' : '查看原文'" @click="preview = !preview"><ArrowsRightLeftIcon /></button><a v-if="pdfUrl" class="icon-button" :href="pdfUrl" :download="filename" aria-label="下载 PDF"><ArrowDownTrayIcon /></a></div></header>
      <div v-if="!filename" class="upload-welcome"><div class="upload-content"><span class="empty-emblem"><DocumentTextIcon /></span><h1>读懂文档，从一个问题开始。</h1><p>上传你的 PDF，快速梳理重点、查找细节。<br>少一点翻找，多一点理解。</p><div v-if="error" class="error-notice" role="alert"><ExclamationCircleIcon /><span>{{ error }}</span><button @click="error = ''">关闭</button></div>
        <div v-if="task || busy" class="upload-state"><span v-if="busy" class="spinner"></span><h3>{{ task?.status === 'FAILED' ? '这份文档暂未处理完成' : task ? '正在为你准备文档' : '正在打开文档' }}</h3><p>{{ selectedFile?.name || '读取已保存的 PDF 与对话' }}</p><div v-if="task" class="processing-steps"><span v-for="(step, i) in steps" :key="step" :class="{ current: taskStep === i }">{{ i + 1 }}. {{ step }}</span></div><p v-if="busy && task">你可以切换到其他功能，任务会在后台继续处理。</p><template v-if="task?.status === 'FAILED'"><button class="button primary" @click="submit(true)">重新处理</button> <button class="button" @click="reset">换一份文档</button></template></div>
        <div v-else class="upload-zone" :class="{ dragging }" @dragover.prevent="dragging = true" @dragleave.prevent="dragging = false" @drop.prevent="drop"><ArrowUpTrayIcon /><strong>将 PDF 拖放到这里</strong><p>支持可提取文字的 PDF · 最大 20 MB · 最多 300 页</p><button class="button primary" @click="fileInput.click()">选择 PDF 文件 <ArrowUpRightIcon /></button><input ref="fileInput" type="file" accept="application/pdf,.pdf" class="visually-hidden" @change="onFile"></div><div class="upload-features"><span>✧ 提炼文档重点</span><span>✧ 查找关键细节</span><span>✧ 多轮连续提问</span></div></div></div>
      <div v-else class="pdf-layout" :class="{ 'show-preview': preview }"><div class="pdf-preview"><div class="pdf-preview-label"><span>文档原文</span><span>PDF PREVIEW</span></div><iframe :src="pdfUrl" :title="filename"></iframe></div><div class="pdf-conversation"><div ref="scrollEl" class="messages-scroll" aria-live="polite" aria-relevant="additions"><div v-if="!messages.length" class="chat-empty"><span class="empty-emblem"><DocumentTextIcon /></span><h1>文档已就绪。</h1><p>想了解这份文档的什么内容？<br>从概括重点开始，或直接问一个具体问题。</p><div class="suggestions"><button v-for="prompt in prompts" :key="prompt" class="suggestion" @click="send(prompt)">{{ prompt }}<ArrowUpRightIcon /></button></div></div><ChatMessage v-for="(message, i) in messages" :key="i" :message="message" :is-stream="streaming && i === messages.length - 1" /></div><div class="composer-region"><div v-if="error" class="error-notice" role="alert"><ExclamationCircleIcon /><span>{{ error }}</span><button @click="error = ''">关闭</button></div><div class="composer"><label class="visually-hidden" for="pdf-input">输入文档问题</label><textarea id="pdf-input" v-model="input" maxlength="20000" rows="2" placeholder="问问这份文档…" @keydown.enter="onEnter"></textarea><div class="composer-actions"><span class="composer-tools">基于当前文档回答</span><button v-if="streaming" class="send-button" aria-label="停止生成" @click="controller?.abort()"><StopIcon /></button><button v-else class="send-button" aria-label="发送消息" :disabled="!input.trim()" @click="send()"><PaperAirplaneIcon /></button></div></div><p class="composer-hint">AI 可能存在遗漏，请结合原文核实。</p></div></div></div>
    </section>
  </div>
</template>
<style scoped>.document-heading{max-width:38vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.pdf-header-tools{display:flex;gap:7px;align-items:center}@media(max-width:640px){.document-heading{max-width:49vw}}</style>
