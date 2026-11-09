import { defineConfig } from '@playwright/test';

/**
 * Sahaj e2e. Serves the built Angular app (run `npm --prefix frontend run build` first, or use
 * `npm test` in this folder, which builds first). The real backend is NOT started: tests that
 * need the API mock it in the browser. Set E2E_CHANNEL=msedge (or chrome) to use an installed
 * browser instead of the Playwright download.
 */
const port = Number(process.env.E2E_PORT ?? 4300);
const channel = process.env.E2E_CHANNEL || undefined;
const LAYOUT = /layout-matrix\.spec\.ts$/;

export default defineConfig({
  testDir: '.',
  testMatch: '**/*.spec.ts',
  fullyParallel: true,
  forbidOnly: true,
  retries: 0,
  reporter: [['list'], ['html', { outputFolder: 'report', open: 'never' }]],
  outputDir: 'results',
  timeout: 30_000,
  // 15 s, not 7: a cold start of the app (lazy home route) under 10 workers took longer than 7 s with the SAH-39 layer on AND off (SAH-39 test plan, load note). Only a passing wait gets longer; no assertion is relaxed.
  expect: { timeout: 15_000 },
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    channel,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata',
  },
  projects: [
    // the two sizes every older spec runs at; layout-matrix.spec.ts has its own seven projects below (DEC-025 G-7)
    { name: 'phone-360', testIgnore: LAYOUT, use: { viewport: { width: 360, height: 740 }, hasTouch: true } },
    { name: 'desktop-1440', testIgnore: LAYOUT, use: { viewport: { width: 1440, height: 900 } } },
    { name: 'layout-1920', testMatch: LAYOUT, use: { viewport: { width: 1920, height: 1080 } } },
    { name: 'layout-1366', testMatch: LAYOUT, use: { viewport: { width: 1366, height: 768 } } },
    { name: 'layout-1024', testMatch: LAYOUT, use: { viewport: { width: 1024, height: 768 }, hasTouch: true } },
    { name: 'layout-768', testMatch: LAYOUT, use: { viewport: { width: 768, height: 1024 }, hasTouch: true } },
    { name: 'layout-375', testMatch: LAYOUT, use: { viewport: { width: 375, height: 667 }, hasTouch: true } },
    // 125 % zoom = the CSS viewport shrinks by 1.25 and one CSS pixel is 1.25 device pixels (method: see layout-matrix.spec.ts)
    { name: 'zoom125-1920', testMatch: LAYOUT, use: { viewport: { width: 1536, height: 864 }, deviceScaleFactor: 1.25 } },
    { name: 'zoom125-1366', testMatch: LAYOUT, use: { viewport: { width: 1093, height: 614 }, deviceScaleFactor: 1.25 } },
  ],
  webServer: {
    command: 'node serve.mjs',
    url: `http://127.0.0.1:${port}/`,
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
