import { createApp } from 'vue'
import { createPinia } from 'pinia'
import '@fontsource/roboto/400.css'
import '@fontsource/roboto/500.css'
import '@fontsource/roboto/700.css'
import '@mdi/font/css/materialdesignicons.css'
import vuetify from './plugins/vuetify'
import { i18n } from './i18n'
import router from './router'
import App from './App.vue'
import { setUnauthorizedHandler } from './api/client'
import { useAuthStore } from './stores/auth'
import { startDynamicFavicon } from './utils/favicon'
import { refreshPush, registerServiceWorker } from './utils/push'
import './styles.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(vuetify)
app.use(i18n)

setUnauthorizedHandler(() => {
  useAuthStore().logout()
  router.push({ name: 'login' })
})

app.mount('#app')
startDynamicFavicon()
// Service worker for Web Push reminders; an existing subscription is assigned to the signed-in account
registerServiceWorker().then(() => {
  if (useAuthStore().loggedIn) refreshPush().catch(() => {})
})
