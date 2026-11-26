// Starts the Synchaven backend for the E2E tests: a fresh, empty data directory on every run, the built web UI from
// `dist/` as static files, and a fixed setup token so the tests can create the administrator account.
//
// The backend binary is `E2E_BACKEND_BIN` if set (e.g. a prebuilt release binary in CI), otherwise `cargo run`.
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { E2E_PORT, SETUP_TOKEN } from './constants.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const frontend = resolve(here, '../..')
const backend = resolve(frontend, '../backend')
const dataDir = resolve(frontend, 'e2e/.data')
const staticDir = resolve(frontend, 'dist')

if (!existsSync(resolve(staticDir, 'index.html'))) {
  console.error('dist/index.html is missing – build the web UI first (npm run build, or use npm run test:e2e)')
  process.exit(1)
}

rmSync(dataDir, { recursive: true, force: true })
mkdirSync(dataDir, { recursive: true })

const env = {
  ...process.env,
  BIND_ADDR: `127.0.0.1:${E2E_PORT}`,
  DATA_DIR: dataDir,
  STATIC_DIR: staticDir,
  SETUP_TOKEN,
  ALLOW_REGISTRATION: 'true',
  // The subscription test serves its calendar file from 127.0.0.1
  SUBSCRIPTIONS_ALLOW_PRIVATE: 'true',
  JWT_SECRET: 'e2e-jwt-secret-0123456789abcdef0123456789abcdef',
  TOTP_ENCRYPTION_KEY: 'e2e-totp-key-0123456789abcdef0123456789abcdef',
  RUST_LOG: process.env.RUST_LOG ?? 'synchaven=error',
}

const [cmd, args] = process.env.E2E_BACKEND_BIN
  ? [resolve(process.env.E2E_BACKEND_BIN), []]
  : ['cargo', ['run', '--quiet', '--locked', '--manifest-path', resolve(backend, 'Cargo.toml')]]

const child = spawn(cmd, args, { env, stdio: 'inherit' })
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => child.kill(sig))
// If Playwright dies without stopping us (e.g. killed), don't leave the server running on the port
const parent = process.ppid
setInterval(() => {
  if (process.ppid !== parent) child.kill('SIGTERM')
}, 1000).unref()
child.on('exit', (code, signal) => process.exit(code ?? (signal ? 1 : 0)))
