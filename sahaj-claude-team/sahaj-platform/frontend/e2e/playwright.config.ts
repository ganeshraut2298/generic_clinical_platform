import { defineConfig } from '@playwright/test';

/**
 * Sahaj e2e. Serves the built Angular app (run `npm --prefix frontend run build` first, or use
 * `npm test` in this folder, which builds first). The real backend is NOT started: tests that
 * need the API mock it in the browser. Set E2E_CHANNEL=msedge (or chrome) to use an installed
 * browser instead of the Playwright download.
 */
const port = Number(process.env.E2E_PORT ?? 4300);
const channel = process.env.E2E_CHANNEL || undefined;

export default defineConfig({
  testDir: '.',
  testMatch: '**/*.spec.ts',
  fullyParallel: true,
  forbidOnly: true,
  retries: 0,
  reporter: [['list'], ['html', { outputFolder: 'report', open: 'never' }]],
  outputDir: 'results',
  timeout: 30_000,
  expect: { timeout: 7_000 },
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    channel,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata',
  },
  projects: [
    { name: 'phone-360', use: { viewport: { width: 360, height: 740 }, hasTouch: true } },
    { name: 'desktop-1440', use: { viewport: { width: 1440, height: 900 } } },
  ],
  webServer: {
    command: 'node serve.mjs',
    url: `http://127.0.0.1:${port}/`,
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
