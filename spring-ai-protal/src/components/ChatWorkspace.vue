<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowUpRightIcon, ChatBubbleLeftRightIcon, LifebuoyIcon, PaperAirplaneIcon, PaperClipIcon, StopIcon, XMarkIcon, ExclamationCircleIcon } from '@heroicons/vue/24/outline'
import ConversationSidebar from './ConversationSidebar.vue'
import ChatMessage from './ChatMessage.vue'
import { chatAPI } from '../services/api'

const props = defineProps({ mode: { type: String, default: 'chat' } })
const isService = computed(() => props.mode === 'service')
const title = computed(() => isService.value ? '智能客服' : 'AI 对话')
const prompts = computed(() => isService.value ? ['有哪些适合零基础的课程？', '帮我比较 Java 和前端课程', '可以在哪些校区预约试听？', '预约试听需要提供哪些信息？'] : ['帮我制定一份 Java 学习计划', '解释一下 Redis 缓存穿透', '帮我梳理今天的学习笔记', '如何把一个想法变成项目？'])
const chatHistory = ref([]), currentId = ref(crypto.randomUUID()), messages = ref([]), input = ref(''), files = ref([])
const loading = ref(true), loadingMessages = ref(false), streaming = ref(false), error = ref(''), scrollEl = ref(null), fileInput = ref(null)
let controller, loadVersion = 0
const scroll = async () => { await nextTick(); if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight }
function newChat() { if (streaming.value || loadingMessages.value) return; loadVersion++; currentId.value = crypto.randomUUID(); messages.value = []; files.value = []; input.value = ''; error.value = '' }
async function loadChat(id) {
  if (streaming.value) return
  const version = ++loadVersion
  currentId.value = id; loadingMessages.value = true; error.value = ''; files.value = []; messages.value = []
  try { const data = await chatAPI.getChatMessages(id, props.mode); if (version === loadVersion) messages.value = data.filter(m => ['user', 'assistant'].includes(m.role)) }
  catch (e) { if (version === loadVersion) error.value = e.message }
  finally { if (version === loadVersion) { loadingMessages.value = false; scroll() } }
}
async function loadHistory() {
  loading.value = true
  try { chatHistory.value = await chatAPI.getChatHistory(props.mode) }
  catch (e) { error.value = e.message }
  finally { loading.value = false }
}
function selectFiles(event) {
  const selected = Array.from(event.target.files || [])
  event.target.value = ''
  if (selected.length > 5 || selected.reduce((n, f) => n + f.size, 0) > 20 * 1024 * 1024) { error.value = '最多上传 5 个附件，总大小不超过 20 MB'; return }
  if (selected.some(f => !/^(image|audio|video)\//.test(f.type) || !f.size)) { error.value = '请选择非空的图片、音频或视频'; return }
  files.value = selected; error.value = ''
}
async function send(text) {
  if (streaming.value || loadingMessages.value) return
  const question = typeof text === 'string' ? text : input.value.trim()
  if (!question && !files.value.length) return
  error.value = ''; streaming.value = true; controller = new AbortController()
  const attached = [...files.value]
  const userText = question || '请描述附件内容。'
  messages.value.push({ role: 'user', content: userText, attachments: attached.map(f => f.name), timestamp: new Date() })
  const index = messages.value.push({ role: 'assistant', content: '', timestamp: new Date() }) - 1
  input.value = ''; files.value = []; scroll()
  if (!chatHistory.value.some(c => c.id === currentId.value)) chatHistory.value.unshift({ id: currentId.value, title: userText.slice(0, 24) })
  try {
    const body = new FormData(); body.set('prompt', userText); attached.forEach(f => body.append('files', f))
    const reader = isService.value ? await chatAPI.sendServiceMessage(userText, currentId.value, controller.signal) : await chatAPI.sendMessage(body, currentId.value, controller.signal)
    const decoder = new TextDecoder()
    try {
      while (true) { const { value, done } = await reader.read(); if (done) break; messages.value[index].content += decoder.decode(value, { stream: true }); scroll() }
      messages.value[index].content += decoder.decode()
    } finally { reader.releaseLock() }
    if (!messages.value[index].content) error.value = '没有收到回复，请稍后重试。'
  } catch (e) { if (e.name === 'AbortError') { if (!messages.value[index].content) messages.value[index].content = '已停止生成。' } else error.value = e.message }
  finally { streaming.value = false; controller = null; scroll() }
}
function onEnter(event) { if (event.isComposing || event.shiftKey) return; event.preventDefault(); send() }
onMounted(loadHistory)
onBeforeUnmount(() => { loadVersion++; controller?.abort() })
</script>
<template>
  <div class="chat-workspace">
    <ConversationSidebar :title="title" :items="chatHistory" :current-id="currentId" :busy="streaming || loadingMessages" :loading="loading" @new="newChat" @select="loadChat" />
    <section class="conversation-panel">
      <header class="panel-header"><div class="panel-heading"><span class="app-icon sage"><LifebuoyIcon v-if="isService" /><ChatBubbleLeftRightIcon v-else /></span><div><h2>{{ title }}</h2><p>{{ isService ? '课程咨询 · 校区查询 · 试听预约' : '每一个好想法，都值得聊一聊' }}</p></div></div><span class="panel-status">{{ streaming ? '正在生成回复…' : 'sunset AI' }}</span></header>
      <div class="messages-scroll" ref="scrollEl" aria-live="polite" aria-relevant="additions">
        <div v-if="loadingMessages" class="chat-empty"><span class="spinner"></span><p>正在加载对话…</p></div>
        <div v-else-if="!messages.length" class="chat-empty"><span class="empty-emblem"><LifebuoyIcon v-if="isService" /><ChatBubbleLeftRightIcon v-else /></span><h1>{{ isService ? '下一步学习，一起规划。' : '今天，想探索些什么？' }}</h1><p>{{ isService ? '从课程选择到试听预约，把你的问题交给我。可以先试试下面这些问题。' : '提一个问题，分享一张图片，或者说说你的新想法。我会陪你一起理清思路。' }}</p><div class="suggestions"><button v-for="prompt in prompts" :key="prompt" class="suggestion" @click="send(prompt)">{{ prompt }}<ArrowUpRightIcon /></button></div></div>
        <ChatMessage v-for="(message, index) in messages" :key="index" :message="message" :is-stream="streaming && index === messages.length - 1" />
      </div>
      <div class="composer-region"><div v-if="error" class="error-notice" role="alert"><ExclamationCircleIcon /><span>{{ error }}</span><button @click="error = ''">关闭</button></div><div class="composer"><div v-if="files.length" class="attachment-list"><span v-for="(file, i) in files" :key="i" class="attachment-chip"><PaperClipIcon /><span>{{ file.name }}</span><button :aria-label="'移除 ' + file.name" @click="files.splice(i, 1)"><XMarkIcon /></button></span></div><label class="visually-hidden" for="chat-input">输入你的问题</label><textarea id="chat-input" v-model="input" rows="2" maxlength="20000" :disabled="loadingMessages" :placeholder="isService ? '想了解什么课程？在这里告诉我…' : '有什么想法，尽管聊聊…'" @keydown.enter="onEnter"></textarea><div class="composer-actions"><div class="composer-tools"><template v-if="!isService"><input ref="fileInput" type="file" accept="image/*,audio/*,video/*" multiple class="visually-hidden" @change="selectFiles"><button class="icon-button" aria-label="添加附件" title="图片、音频或视频，总计不超过 20 MB" :disabled="streaming" @click="fileInput.click()"><PaperClipIcon /></button><span>支持图片与多媒体</span></template><span v-else>课程与预约信息以实际查询为准</span></div><button v-if="streaming" class="send-button" aria-label="停止生成" @click="controller?.abort()"><StopIcon /></button><button v-else class="send-button" aria-label="发送消息" :disabled="loadingMessages || (!input.trim() && !files.length)" @click="send()"><PaperAirplaneIcon /></button></div></div><p class="composer-hint">Enter 发送 · Shift + Enter 换行　｜　AI 生成内容仅供参考，请核实重要信息</p></div>
    </section>
  </div>
</template>
