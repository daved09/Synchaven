import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('./views/AuthView.vue'), meta: { public: true } },
    { path: '/register', name: 'register', component: () => import('./views/AuthView.vue'), meta: { public: true } },
    { path: '/', name: 'calendar', component: () => import('./views/CalendarView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.loggedIn) return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  if (to.meta.public && auth.loggedIn) return { name: 'calendar' }
})

export default router
