<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDark, useToggle } from '@vueuse/core'
import { Squares2X2Icon, ChatBubbleLeftRightIcon, DocumentTextIcon, LifebuoyIcon, HeartIcon, SunIcon, MoonIcon, Bars3Icon, ArrowUpRightIcon } from '@heroicons/vue/24/outline'
import './assets/workspace.css'
import AuthDialog from './components/AuthDialog.vue'
import { user, authReady, authError, loadUser, signOut } from './services/auth'

const route = useRoute()
const router = useRouter()
const authOpen = ref(false)
const logoutBusy = ref(false)
const logoutError = ref('')
const expired = () => { user.value = null; authOpen.value = true }
async function logout() {
  if (logoutBusy.value) return
  logoutBusy.value = true; logoutError.value = ''
  try { await signOut(); router.push('/') } catch (e) { logoutError.value = e.message }
  finally { logoutBusy.value = false }
}
onMounted(() => { loadUser(); window.addEventListener('session-expired', expired) })
onBeforeUnmount(() => window.removeEventListener('session-expired', expired))
const isDark = useDark({ storageKey: 'sunset-color-scheme', initialValue: 'light' })
const toggleDark = useToggle(isDark)
const menuOpen = ref(false)
const nav = [
  { path: '/', name: '工作台', icon: Squares2X2Icon, caption: 'Overview' },
  { path: '/ai-chat', name: 'AI 对话', icon: ChatBubbleLeftRightIcon, caption: 'Chat' },
  { path: '/chat-pdf', name: '文档问答', icon: DocumentTextIcon, caption: 'Documents' },
  { path: '/customer-service', name: '智能客服', icon: LifebuoyIcon, caption: 'Assistant' },
  { path: '/game', name: '哄哄模拟器', icon: HeartIcon, caption: 'Playground' },
]
const current = computed(() => nav.find(item => item.path === route.path) || nav[0])
</script>

<template>
  <div class="workspace-app" :class="{ dark: isDark }" @keydown.esc="menuOpen = false">
    <div v-if="menuOpen" class="nav-scrim" @click="menuOpen = false"></div>
    <aside id="workspace-navigation" class="app-rail" :class="{ open: menuOpen }">
      <RouterLink to="/" class="brand" @click="menuOpen = false"><span class="brand-symbol">s<span></span></span><span>sunset<span class="brand-sub">AI WORKSPACE</span></span></RouterLink>
      <div class="rail-label">你的创作空间</div>
      <nav class="rail-navigation" aria-label="主导航">
        <RouterLink v-for="item in nav" :key="item.path" :to="item.path" :class="{ selected: route.path === item.path }" @click="menuOpen = false">
          <component :is="item.icon" /><span>{{ item.name }}</span><span v-if="route.path === item.path" class="nav-dot"></span>
        </RouterLink>
      </nav>
      <div class="rail-note"><span class="small-spark">✳</span><strong>让想法，慢慢生长。</strong><p>从一句提问开始，探索更多可能。</p><RouterLink to="/ai-chat">开始一段对话 <ArrowUpRightIcon /></RouterLink></div>
      <div class="rail-footer"><span class="avatar-dot">{{ user ? user.displayName.slice(0, 1).toUpperCase() : 'S' }}</span><div><strong>{{ user?.displayName || '个人工作台' }}</strong><span>{{ user ? '@' + user.username : 'sunset · 每日灵感' }}</span></div></div>
    </aside>
    <div class="app-body">
      <header class="workspace-topbar">
        <div class="breadcrumb"><button class="icon-button mobile-menu" aria-label="打开导航" :aria-expanded="menuOpen" aria-controls="workspace-navigation" @click="menuOpen = true"><Bars3Icon /></button><span>工作空间</span><span class="slash">/</span><strong>{{ current.name }}</strong></div>
        <div class="topbar-actions"><span class="workspace-label"><span></span> {{ user ? user.displayName : '个人 AI 助手' }}</span><button v-if="user" class="account-logout" :disabled="logoutBusy" @click="logout">{{ logoutBusy ? '退出中…' : '退出登录' }}</button><button v-else class="account-button" @click="authOpen = true">登录 / 注册</button><button class="icon-button" :aria-label="isDark ? '切换浅色主题' : '切换深色主题'" @click="toggleDark()"><SunIcon v-if="isDark" /><MoonIcon v-else /></button></div>
      </header>
      <div v-if="logoutError" class="error-notice" role="alert">{{ logoutError }}<button @click="logoutError = ''">关闭</button></div>
      <main class="route-content"><RouterView v-if="route.path === '/' || user" :key="route.path + (user?.id || '')" /><div v-else class="auth-gate"><span class="empty-emblem">✳</span><h1>{{ authReady ? '让每一次对话，都有归属。' : '正在打开你的工作台…' }}</h1><p>{{ authError || '登录后使用 AI 对话、文档问答和智能客服。你的对话与文件将保存在自己的账号下。' }}</p><button v-if="authReady" class="button primary" @click="authOpen = true">登录，开启工作台 <ArrowUpRightIcon /></button><button v-if="authError" class="button" style="margin-top:12px" @click="loadUser">重新连接</button></div></main>
    </div>
    <AuthDialog v-if="authOpen" @close="authOpen = false" />
  </div>
</template>
