<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { XMarkIcon, ArrowRightIcon } from '@heroicons/vue/24/outline'
import { signIn } from '../services/auth'
const emit = defineEmits(['close'])
const dialog = ref(null), mode = ref('login'), username = ref(''), password = ref(''), displayName = ref(''), error = ref(''), busy = ref(false)
onMounted(async () => { await nextTick(); dialog.value.showModal() })
async function submit() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await signIn(mode.value, { username: username.value, password: password.value, displayName: displayName.value }); emit('close') }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}
function switchMode() { mode.value = mode.value === 'login' ? 'register' : 'login'; error.value = ''; password.value = '' }
</script>
<template>
  <dialog ref="dialog" class="auth-dialog" aria-labelledby="auth-title" @cancel="emit('close')" @click="event => { if (event.target === dialog) emit('close') }"><div class="auth-dialog-content"><button class="icon-button auth-close" aria-label="关闭登录" @click="emit('close')"><XMarkIcon /></button><span class="empty-emblem">s</span><p class="eyebrow">YOUR OWN LITTLE WORKSPACE</p><h2 id="auth-title">{{ mode === 'login' ? '欢迎回到 sunset。' : '给想法，一个自己的空间。' }}</h2><p class="auth-subtitle">{{ mode === 'login' ? '登录后，继续你的对话与阅读。' : '创建账号，保存你的对话与文档。' }}</p><form @submit.prevent="submit"><label>用户名<input v-model="username" name="username" autocomplete="username" required minlength="3" maxlength="32" pattern="[A-Za-z0-9_]+" placeholder="3-32 位字母、数字或下划线" autofocus></label><label v-if="mode === 'register'">昵称<input v-model="displayName" name="displayName" autocomplete="nickname" maxlength="30" placeholder="希望我们怎么称呼你？（选填）"></label><label>密码<input v-model="password" name="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" type="password" required minlength="8" maxlength="64" placeholder="至少 8 个字符"></label><div v-if="error" class="error-notice" role="alert">{{ error }}</div><button class="button primary auth-submit" :disabled="busy" type="submit"><span v-if="busy" class="spinner"></span>{{ busy ? '请稍候…' : mode === 'login' ? '登录工作台' : '创建账号并进入' }}<ArrowRightIcon v-if="!busy" /></button></form><p class="auth-switch">{{ mode === 'login' ? '还没有账号？' : '已经有账号？' }}<button :disabled="busy" @click="switchMode">{{ mode === 'login' ? '创建一个' : '去登录' }}</button></p></div></dialog>
</template>
<style>
.auth-dialog{border:1px solid var(--line);border-radius:20px;padding:0;background:var(--surface);color:var(--ink);width:min(440px,calc(100vw - 32px));box-shadow:0 20px 100px #1e342740}.auth-dialog::backdrop{background:#15271c60;backdrop-filter:blur(4px)}.auth-dialog-content{padding:35px 36px;position:relative}.auth-close{position:absolute;right:12px;top:12px}.auth-dialog .empty-emblem{width:48px;height:48px;border-radius:14px;font:italic 38px Georgia;margin-bottom:24px}.auth-dialog .eyebrow{font-size:8px;letter-spacing:1.5px}.auth-dialog h2{font-size:23px;font-weight:550;line-height:1.6}.auth-subtitle{font-size:11px;color:var(--muted);margin:10px 0 25px}.auth-dialog label{display:block;font-size:11px;margin-bottom:17px;font-weight:500}.auth-dialog input{display:block;width:100%;margin-top:9px;padding:12px;border:1px solid var(--line);border-radius:8px;background:var(--canvas);color:var(--ink);font-size:11px}.auth-dialog input::placeholder{color:var(--muted)}.auth-submit{width:100%;margin-top:5px;padding:13px}.auth-switch{font-size:11px;color:var(--muted);text-align:center;margin-top:22px}.auth-switch button{background:none;color:var(--accent);font-size:11px;padding:3px 6px}.auth-gate{min-height:calc(100dvh - 72px);display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:30px}.auth-gate h1{font-size:27px;font-weight:550}.auth-gate p{font-size:12px;color:var(--muted);line-height:2;margin:15px 0 25px;max-width:420px}.account-button{background:var(--accent-soft);border-radius:8px;padding:8px 12px;font-size:11px;color:var(--accent)}.account-logout{font-size:10px;background:transparent;color:var(--muted)}
</style>
