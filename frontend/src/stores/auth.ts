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
  async function login(email: string, password: string) {
    const r = await api.login(email, password)
    apply(r.token, r.user)
  }
  async function register(email: string, password: string, name: string, lang: 'de' | 'en') {
    const r = await api.register(email, password, name, lang)
    apply(r.token, r.user)
  }
  async function fetchMe() {
    user.value = await api.me()
  }
  async function update(data: Parameters<typeof api.updateMe>[0]) {
    user.value = await api.updateMe(data)
  }
  function logout() {
    setToken(null)
    token.value = null
    user.value = null
  }
  return { token, user, loggedIn, login, register, fetchMe, update, logout }
})
