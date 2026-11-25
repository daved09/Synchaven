import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, getToken, setToken } from '@/api/client'
import type { User } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken())
  const user = ref<User | null>(null)
  const loggedIn = computed(() => !!token.value)

  function apply(t: string, u: User) {
    setToken(t)
    token.value = t
    user.value = u
  }
  async function login(email: string, password: string, code?: string) {
    const r = await api.login(email, password, code)
    apply(r.token, r.user)
  }
  async function register(email: string, password: string, name: string, lang: 'de' | 'en', setupToken?: string) {
    const r = await api.register(email, password, name, lang, setupToken)
    apply(r.token, r.user)
  }
  async function fetchMe() {
    user.value = await api.me()
  }
  async function update(data: Parameters<typeof api.updateMe>[0]) {
    const { token: newToken, expires_at: _, ...u } = await api.updateMe(data)
    if (newToken) {
      setToken(newToken)
      token.value = newToken
    }
    user.value = u
  }
  function logout() {
    setToken(null)
    token.value = null
    user.value = null
  }
  return { token, user, loggedIn, login, register, fetchMe, update, logout }
})
