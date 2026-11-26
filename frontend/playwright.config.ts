import { defineConfig, devices } from '@playwright/test'
import { BASE_URL } from './e2e/support/constants.mjs'

const ci = !!process.env.CI

export default defineConfig({
  testDir: './e2e/tests',
  outputDir: './e2e/.results',
  fullyParallel: true,
  forbidOnly: ci,
  retries: ci ? 2 : 0,
  workers: ci ? 2 : undefined,
  reporter: ci ? [['github'], ['html', { open: 'never', outputFolder: 'e2e/.report' }]] : [['list'], ['html', { open: 'never', outputFolder: 'e2e/.report' }]],
  use: {
    baseURL: BASE_URL,
    locale: 'en-US',
    timezoneId: 'Europe/Berlin',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    // Creates the administrator through the initial setup page – must run first on the empty database
    { name: 'setup', testMatch: /setup\.setup\.ts/, use: { ...devices['Desktop Chrome'] } },
    {
      name: 'chromium',
      dependencies: ['setup'],
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
  ],
  webServer: {
    command: 'node e2e/support/server.mjs',
    url: `${BASE_URL}/api/info`,
    // The first `cargo run` compiles the backend
    timeout: 10 * 60_000,
    reuseExistingServer: false,
    stdout: 'pipe',
    stderr: 'pipe',
  },
})
