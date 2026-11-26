export const E2E_PORT = Number(process.env.E2E_PORT ?? 18080)
export const BASE_URL = `http://127.0.0.1:${E2E_PORT}`
export const SETUP_TOKEN = 'e2e-setup-token-0123456789abcdef'
export const ADMIN = { email: 'admin@e2e.test', password: 'admin-password-123', name: 'E2E Admin' }
export const ADMIN_STATE = 'e2e/.auth/admin.json'
