import { ref } from 'vue'
import { request, resetCsrf } from './api'

export const user = ref(null)
export const authReady = ref(false)
export const authError = ref('')
export async function loadUser() {
  authError.value = ''
  try { user.value = (await (await request('/auth/me')).json()).user }
  catch (error) { authError.value = error.message }
  finally { authReady.value = true }
}
export async function signIn(mode, data) {
  const response = await request(`/auth/${mode}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) })
  user.value = (await response.json()).user
  resetCsrf(); authError.value = ''
}
export async function signOut() {
  await request('/auth/logout', { method: 'POST' })
  user.value = null; resetCsrf()
}
