<script setup>
import { computed, ref, onBeforeUnmount } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github-dark.css'
import { DocumentDuplicateIcon, CheckIcon, PaperClipIcon } from '@heroicons/vue/24/outline'
const props = defineProps({ message: { type: Object, required: true }, isStream: Boolean })
const copied = ref(false), copyError = ref('')
let timer
const isUser = computed(() => props.message.role === 'user')
const time = computed(() => props.message.timestamp ? new Date(props.message.timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '')
const html = computed(() => {
  const text = (props.message.content || '').replace(/<think>([\s\S]*?)(<\/think>|$)/g, (_, think) => `\n\n> 思考过程\n> ${think.replace(/\n/g, '\n> ')}\n\n`)
  const container = document.createElement('div')
  container.innerHTML = DOMPurify.sanitize(marked.parse(text, { breaks: true, gfm: true }))
  container.querySelectorAll('pre code').forEach(code => {
    const lang = [...code.classList].find(c => c.startsWith('language-'))?.slice(9)
    if (lang && hljs.getLanguage(lang)) { code.innerHTML = hljs.highlight(code.textContent, { language: lang }).value; code.classList.add('hljs') }
  })
  container.querySelectorAll('a').forEach(a => { a.target = '_blank'; a.rel = 'noopener noreferrer' })
  return container.innerHTML
})
async function copy() {
  try { await navigator.clipboard.writeText(props.message.content || ''); copied.value = true; copyError.value = ''; clearTimeout(timer); timer = setTimeout(() => copied.value = false, 1800) }
  catch { copyError.value = '复制失败，请手动选择文字' }
}
onBeforeUnmount(() => clearTimeout(timer))
</script>
<template>
  <article class="chat-message" :class="{ 'from-user': isUser }">
    <div class="message-avatar">{{ isUser ? '你' : 's' }}</div>
    <div class="message-body"><div class="message-meta"><strong>{{ isUser ? '你' : 'sunset' }}</strong><time>{{ time }}</time></div>
      <div v-if="isUser" class="message-plain">{{ message.content }}</div>
      <div v-else-if="message.content" class="markdown-content" v-html="html"></div>
      <span v-else-if="isStream" class="typing-dots" aria-label="正在思考"><i></i><i></i><i></i></span>
      <div v-if="message.attachments?.length" class="message-attachments"><span v-for="name in message.attachments" :key="name"><PaperClipIcon />{{ name }}</span></div>
      <div v-if="message.content" class="message-actions"><button :aria-label="copied ? '已复制' : '复制消息'" @click="copy"><CheckIcon v-if="copied" /><DocumentDuplicateIcon v-else /><span>{{ copied ? '已复制' : '复制' }}</span></button><span v-if="copyError">{{ copyError }}</span></div>
    </div>
  </article>
</template>
<style>
.chat-message{display:flex;gap:12px;margin:0 0 26px;max-width:100%;font-size:12px}.message-avatar{width:30px;height:30px;border-radius:9px;display:grid;place-items:center;background:var(--accent-soft);color:var(--accent);flex-shrink:0;font-family:Georgia,serif;font-style:italic;font-size:23px}.chat-message.from-user{flex-direction:row-reverse}.from-user .message-body{flex:0 1 auto;max-width:min(80%,calc(100% - 42px));display:flex;flex-direction:column;align-items:flex-end}.from-user .message-meta,.from-user .message-actions,.from-user .message-attachments{justify-content:flex-end;max-width:100%}.from-user .message-plain{max-width:100%;text-align:left;background:var(--accent-soft);border-radius:12px 0 12px 12px}.from-user .message-avatar{font-family:inherit;font-style:normal;background:var(--soft);font-size:11px;color:var(--muted)}.message-body{min-width:0;flex:1}.message-meta{display:flex;align-items:center;gap:12px;font-size:10px;margin:2px 0 10px}.message-meta strong{font-weight:600}.message-meta time{color:var(--muted);font-size:9px}.message-plain{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9;background:var(--soft);border-radius:0 10px 10px;padding:12px 15px}.markdown-content{font-size:12px;line-height:1.95;overflow-wrap:anywhere}.markdown-content p{margin:0 0 12px}.markdown-content ul,.markdown-content ol{padding-left:22px;margin:10px 0}.markdown-content li{margin:5px 0}.markdown-content h1,.markdown-content h2,.markdown-content h3{font-size:15px;line-height:1.6;margin:18px 0 10px}.markdown-content a{color:var(--accent);text-decoration:underline}.markdown-content pre{background:#202b25;color:#e8eee9;border-radius:9px;padding:15px;overflow:auto;line-height:1.7;max-width:100%;font-size:11px;margin:12px 0}.markdown-content pre code{background:transparent;padding:0;font-size:inherit}.markdown-content code{font-size:.9em;background:var(--soft);border-radius:4px;padding:2px 5px}.markdown-content blockquote{border-left:3px solid #b7cdb0;color:var(--muted);padding:4px 14px;margin:12px 0;background:var(--soft)}.markdown-content blockquote p:last-child{margin-bottom:0}.markdown-content table{display:block;max-width:100%;overflow:auto;border-collapse:collapse;font-size:11px;margin:12px 0}.markdown-content th,.markdown-content td{border:1px solid var(--line);padding:8px 12px}.markdown-content img{max-width:100%;border-radius:8px}.message-actions{display:flex;align-items:center;gap:8px;margin-top:8px;color:var(--muted);font-size:9px}.message-actions button{display:flex;align-items:center;gap:4px;font-size:9px;color:var(--muted);background:transparent;padding:3px 0}.message-actions svg{width:12px;height:12px}.message-attachments{display:flex;flex-wrap:wrap;gap:5px;margin-top:8px}.message-attachments span{display:flex;align-items:center;gap:4px;font-size:9px;color:var(--muted);overflow-wrap:anywhere}.message-attachments svg{width:12px;height:12px}.typing-dots{display:flex;gap:5px;padding:10px 0}.typing-dots i{width:5px;height:5px;border-radius:50%;background:var(--accent);animation:dot-pulse 1.2s infinite}.typing-dots i:nth-child(2){animation-delay:.2s}.typing-dots i:nth-child(3){animation-delay:.4s}@keyframes dot-pulse{0%,70%,100%{opacity:.3;transform:translateY(0)}35%{opacity:1;transform:translateY(-3px)}}
</style>
