/**
 * SAH-2 Home page: browser smoke tests at 360 px (phone) and 1440 px (desktop).
 * Both viewports run every test (see projects in playwright.config.ts).
 *
 * IMPORTANT: the real backend is not running here (it needs database credentials). Every test
 * that submits the enquiry sheet MOCKS the API in the browser with page.route(). Those tests
 * prove what the page does with a 201, 429 and 500 answer; they do not prove the real endpoint,
 * which is covered by the backend integration tests (SalesEnquiryIT).
 */
import { expect, test, type Locator, type Page } from '@playwright/test';

const ENQUIRY_URL = '**/api/v1/platform/public/sales-enquiries';
const THANK_YOU = 'Request received. We will call you within one working day.';

/** Sections in mockup order: hero, Clinic setup, Workflow builder, Roles, Patient journey,
 * Specialties, Compare, Pricing, Trust and control, Questions, final call to action, footer. */
const SECTION_ORDER = [
  { name: 'hero', selector: '.lp-hero' },
  { name: 'Clinic setup', selector: '#setup' },
  { name: 'Workflow builder', selector: '#workflow' },
  { name: 'Roles and responsibilities', selector: '#roles' },
  { name: 'Patient journey', selector: '#journey' },
  { name: 'Specialties', selector: '#specialties' },
  { name: 'Compare', selector: '#compare' },
  { name: 'Pricing', selector: '#pricing' },
  { name: 'Trust and control', selector: '#trust' },
  { name: 'Questions', selector: '#faq' },
  { name: 'final call to action', selector: '.final' },
  { name: 'footer', selector: 'footer' },
];

/** Collects page errors and console errors so a test can assert there are none. */
function watchErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(`console: ${m.text()}`);
  });
  return errors;
}

/** A page that is fully loaded: the lazy home route has rendered its heading. */
async function openHome(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
}

const header = (page: Page): Locator => page.locator('header.lnav');

async function openEnquirySheet(page: Page): Promise<Locator> {
  await openHome(page);
  const book = page.getByRole('button', { name: 'Book a Demo' });
  await book.scrollIntoViewIfNeeded();
  await book.click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('heading', { name: 'Book a demo' })).toBeVisible();
  return dialog;
}

async function fillValidEnquiry(dialog: Locator): Promise<void> {
  await dialog.getByLabel('Your name').fill('Test Doctor');
  await dialog.getByLabel('Clinic name and city').fill('Test Family Clinic, Pune');
  await dialog.getByLabel('Mobile number').fill('9876543210');
}

// ---------------------------------------------------------------------------------------------
test.describe('Home page: load, order, layout', () => {
  test('page loads with the headline, no script errors and no horizontal page overflow', async ({ page }) => {
    const errors = watchErrors(page);
    await openHome(page);
    await expect(page).toHaveTitle('Sahaj · Clinic software for India');
    await expect(page.getByRole('heading', { level: 1 })).toContainText(
      'Clinic software that works the way your OPD already runs.',
    );

    await page.locator('footer').scrollIntoViewIfNeeded();
    const overflow = await page.evaluate(() => ({
      doc: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      body: document.body.scrollWidth - document.documentElement.clientWidth,
      width: document.documentElement.clientWidth,
    }));
    expect(overflow.doc, `document is wider than the ${overflow.width}px viewport`).toBeLessThanOrEqual(0);
    expect(overflow.body, `body is wider than the ${overflow.width}px viewport`).toBeLessThanOrEqual(0);
    expect(errors).toEqual([]);
  });

  test('no section is wider than the viewport', async ({ page }) => {
    await openHome(page);
    const wide = await page.evaluate(() => {
      const limit = document.documentElement.clientWidth;
      return [...document.querySelectorAll('main > *, footer, header')]
        .map((el) => ({
          tag: el.tagName + (el.id ? '#' + el.id : '.' + el.className),
          right: Math.round(el.getBoundingClientRect().right),
        }))
        .filter((e) => e.right > limit + 1);
    });
    expect(wide).toEqual([]);
  });

  test('sections appear in mockup order', async ({ page }) => {
    await openHome(page);
    const result = await page.evaluate((sections) => {
      const found = sections.map((s) => {
        const el = document.querySelector(s.selector);
        return { name: s.name, el, top: el ? el.getBoundingClientRect().top + window.scrollY : -1 };
      });
      const missing = found.filter((f) => !f.el).map((f) => f.name);
      const outOfOrder: string[] = [];
      for (let i = 1; i < found.length; i++) {
        const a = found[i - 1].el;
        const b = found[i].el;
        if (a && b && !(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)) {
          outOfOrder.push(`${found[i].name} is not after ${found[i - 1].name}`);
        }
        if (a && b && found[i].top < found[i - 1].top) {
          outOfOrder.push(`${found[i].name} is drawn above ${found[i - 1].name}`);
        }
      }
      return { missing, outOfOrder };
    }, SECTION_ORDER);
    expect(result.missing).toEqual([]);
    expect(result.outOfOrder).toEqual([]);
  });

  test('section headings match the mockup labels', async ({ page }) => {
    await openHome(page);
    const expected: [string, string][] = [
      ['#setup', 'Your clinic. Your workflow. Ready in 15 minutes.'],
      ['#compare', 'How does your current setup compare?'],
      ['#pricing', 'Start small. Grow without changing your system.'],
      ['.final', 'Spend less time managing your clinic. More time caring for patients.'],
    ];
    for (const [selector, text] of expected) {
      await expect(page.locator(selector).getByRole('heading', { level: 2 }).first()).toContainText(text);
    }
  });

  test('header shows brand, Admin portal, Log in and Start Free; section links show only above 980 px', async ({ page }, info) => {
    await openHome(page);
    const h = header(page);
    await expect(h.getByText('Sahaj', { exact: true })).toBeVisible();
    await expect(h.getByRole('link', { name: 'Admin portal' })).toBeVisible();
    await expect(h.getByRole('link', { name: 'Log in' })).toBeVisible();
    await expect(h.getByRole('link', { name: 'Start Free' })).toBeVisible();
    for (const label of ['How it works', 'Workflow', 'Specialties', 'Compare', 'Pricing']) {
      const link = h.getByRole('button', { name: label, exact: true });
      if (info.project.name === 'desktop-1440') await expect(link).toBeVisible();
      else await expect(link).toBeHidden();
    }
  });

  test('desktop: a section link scrolls to its section', async ({ page }, info) => {
    test.skip(info.project.name !== 'desktop-1440', 'section links are hidden at 980 px and below');
    await openHome(page);
    await header(page).getByRole('button', { name: 'Pricing', exact: true }).click();
    await expect(page.locator('#pricing')).toBeInViewport();
    await header(page).getByRole('button', { name: 'Compare', exact: true }).click();
    await expect(page.locator('#compare')).toBeInViewport();
  });

  test('header stays at the top while scrolling (sticky)', async ({ page }) => {
    await openHome(page);
    await page.locator('#pricing').scrollIntoViewIfNeeded();
    await expect(header(page)).toBeInViewport({ ratio: 1 });
    expect((await header(page).boundingBox())!.y).toBeLessThanOrEqual(10.5); // mockup: sticky at top 10px
  });

  test('footer says patient data is hosted in India and prices exclude GST; pricing note says 18% GST', async ({ page }) => {
    await openHome(page);
    await expect(page.locator('footer')).toContainText('Patient data hosted in India');
    await expect(page.locator('footer')).toContainText('Prices exclude GST');
    await expect(page.locator('#pricing')).toContainText('18% GST');
  });
});

// ---------------------------------------------------------------------------------------------
test.describe('Home page: navigation', () => {
  test('header Start Free opens /login?mode=signup (New clinic)', async ({ page }) => {
    await openHome(page);
    await header(page).getByRole('link', { name: 'Start Free' }).click();
    await expect(page).toHaveURL(/\/login\?mode=signup$/);
    await expect(page.getByText('Mode: New clinic')).toBeVisible();
  });

  test('hero Start Free opens /login?mode=signup', async ({ page }) => {
    await openHome(page);
    await page.locator('.lp-hero').getByRole('link', { name: 'Start Free' }).click();
    await expect(page).toHaveURL(/\/login\?mode=signup$/);
  });

  test('final call to action Start Free opens /login?mode=signup', async ({ page }) => {
    await openHome(page);
    const link = page.locator('.final').getByRole('link', { name: 'Start Free' });
    await link.scrollIntoViewIfNeeded();
    await link.click();
    await expect(page).toHaveURL(/\/login\?mode=signup$/);
  });

  test('Log in opens /login?mode=login (Log in mode)', async ({ page }) => {
    await openHome(page);
    await header(page).getByRole('link', { name: 'Log in' }).click();
    await expect(page).toHaveURL(/\/login\?mode=login$/);
    await expect(page.getByText('Mode: Log in')).toBeVisible();
  });

  test('Admin portal shows Coming soon', async ({ page }) => {
    await openHome(page);
    await header(page).getByRole('link', { name: 'Admin portal' }).click();
    await expect(page).toHaveURL(/\/admin$/);
    await expect(page.getByText('Coming soon', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Admin portal' })).toBeVisible();
  });
});

// ---------------------------------------------------------------------------------------------
test.describe('Billing period toggle', () => {
  test('Professional shows ₹1,999 per year, then ₹299 per month; other plans are as the story says', async ({ page }) => {
    await openHome(page);
    const pricing = page.locator('#pricing');
    await pricing.scrollIntoViewIfNeeded();
    const pro = pricing.getByRole('article', { name: 'Professional plan' });
    const toggle = pricing.locator('mat-button-toggle-group[aria-label="Billing period"]');

    await expect(toggle).toContainText('Yearly · save 44%');
    await expect(pro).toContainText('₹1,999');
    await expect(pro).toContainText('per year');

    await toggle.getByText('Monthly', { exact: true }).click();
    await expect(pro).toContainText('₹299');
    await expect(pro).toContainText('per month');
    await expect(pro).not.toContainText('₹1,999');

    await toggle.getByText('Yearly · save 44%').click();
    await expect(pro).toContainText('₹1,999');
    await expect(pro).toContainText('per year');

    const free = pricing.getByRole('article', { name: 'Free plan' });
    await expect(free).toContainText('₹0');
    await expect(free).toContainText('for 12 months');
    const multi = pricing.getByRole('article', { name: 'Multi-Doctor plan' });
    await expect(multi).toContainText('Per doctor');
    await expect(multi).toContainText('quote on request');
    await expect(pricing.getByRole('article', { name: 'Custom plan' })).toContainText("Let's talk");
  });
});

// ---------------------------------------------------------------------------------------------
test.describe('Enquiry sheet (API MOCKED in the browser; the real backend is not running)', () => {
  test('the sheet asks for name, clinic name and city, mobile (+91) and best time to call', async ({ page }) => {
    const dialog = await openEnquirySheet(page);
    await expect(dialog.getByLabel('Your name')).toBeVisible();
    await expect(dialog.getByLabel('Clinic name and city')).toBeVisible();
    await expect(dialog.getByLabel('Mobile number')).toBeVisible();
    await expect(dialog).toContainText('+91');
    await expect(dialog).toContainText('Best time to call');
    for (const label of ['Morning', 'Afternoon', 'After OPD hours']) {
      await expect(dialog.getByText(label, { exact: true })).toBeVisible();
    }
    // At 360 px the sheet must fit the screen.
    const box = (await dialog.boundingBox())!;
    const viewport = page.viewportSize()!;
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(viewport.width + 1);
  });

  test('Contact sales on a plan card opens the sheet titled Talk to sales', async ({ page }) => {
    await openHome(page);
    const card = page.locator('#pricing').getByRole('article', { name: 'Custom plan' });
    await card.scrollIntoViewIfNeeded();
    await card.getByRole('button', { name: 'Contact sales' }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: 'Talk to sales' })).toBeVisible();
  });

  test('invalid input shows field errors and sends nothing (API mocked)', async ({ page }) => {
    let calls = 0;
    await page.route(ENQUIRY_URL, (route) => {
      calls++;
      return route.fulfill({ status: 201, json: { id: 'x' } });
    });
    const dialog = await openEnquirySheet(page);
    const send = dialog.getByRole('button', { name: 'Request a call' });

    await send.click();
    await expect(dialog.getByText('Enter your name')).toBeVisible();
    await expect(dialog.getByText('Enter a 10-digit mobile number')).toBeVisible();

    await dialog.getByLabel('Your name').fill('Test Doctor');
    await dialog.getByLabel('Mobile number').fill('12345');
    await send.click();
    await expect(dialog.getByText('Enter your name')).toBeHidden();
    await expect(dialog.getByText('Enter a 10-digit mobile number')).toBeVisible();

    await dialog.getByLabel('Mobile number').fill('5123456789');
    await send.click();
    await expect(dialog.getByText('Mobile numbers start with 6, 7, 8 or 9')).toBeVisible();

    await dialog.getByLabel('Your name').fill('   ');
    await send.click();
    await expect(dialog.getByText('Enter your name')).toBeVisible();

    expect(calls, 'an invalid form must not call the API').toBe(0);
    await expect(page.getByText(THANK_YOU)).toBeHidden();
  });

  test('API MOCKED 201: request carries the typed values, then the thank-you shows and the sheet closes', async ({ page }) => {
    let body: Record<string, unknown> | undefined;
    await page.route(ENQUIRY_URL, (route) => {
      body = route.request().postDataJSON();
      return route.fulfill({ status: 201, json: { id: '7f0c2a4e-1b7e-4f43-9a54-0a5c6f1d2e3b' } });
    });
    const dialog = await openEnquirySheet(page);
    await fillValidEnquiry(dialog);
    await dialog.getByText('Morning', { exact: true }).click();
    await dialog.getByRole('button', { name: 'Request a call' }).click();

    await expect(page.getByText(THANK_YOU)).toBeVisible();
    await expect(page.getByRole('dialog')).toBeHidden();
    expect(body).toMatchObject({
      name: 'Test Doctor',
      clinicCity: 'Test Family Clinic, Pune',
      mobile: '9876543210',
      bestTime: 'MORNING',
      source: 'BOOK_DEMO',
    });
    expect(Object.keys(body!)).not.toContain('tenantId');
  });

  for (const failure of [
    { status: 429, text: 'Too many requests were sent from this connection' },
    { status: 500, text: 'We could not send your request. Your details are still here.' },
  ]) {
    test(`API MOCKED ${failure.status}: an error shows, the values stay, and there is no thank-you`, async ({ page }) => {
      let calls = 0;
      await page.route(ENQUIRY_URL, (route) => {
        calls++;
        return route.fulfill({
          status: failure.status,
          contentType: 'application/problem+json',
          headers: failure.status === 429 ? { 'Retry-After': '3600' } : {},
          body: JSON.stringify({ type: 'about:blank', title: 'Problem', status: failure.status }),
        });
      });
      const dialog = await openEnquirySheet(page);
      await fillValidEnquiry(dialog);
      await dialog.getByRole('button', { name: 'Request a call' }).click();

      await expect(dialog.getByRole('alert')).toContainText(failure.text);
      await expect(page.getByText(THANK_YOU)).toBeHidden();
      await expect(page.getByRole('dialog')).toBeVisible();
      await expect(dialog.getByLabel('Your name')).toHaveValue('Test Doctor');
      await expect(dialog.getByLabel('Clinic name and city')).toHaveValue('Test Family Clinic, Pune');
      await expect(dialog.getByLabel('Mobile number')).toHaveValue('9876543210');
      // The button is usable again, so the visitor can retry.
      await expect(dialog.getByRole('button', { name: 'Request a call' })).toBeEnabled();
      expect(calls).toBe(1);
    });
  }

  test('API MOCKED network failure: an error shows, the values stay, and there is no thank-you', async ({ page }) => {
    await page.route(ENQUIRY_URL, (route) => route.abort('connectionrefused'));
    const dialog = await openEnquirySheet(page);
    await fillValidEnquiry(dialog);
    await dialog.getByRole('button', { name: 'Request a call' }).click();
    await expect(dialog.getByRole('alert')).toContainText('We could not reach Sahaj');
    await expect(page.getByText(THANK_YOU)).toBeHidden();
    await expect(dialog.getByLabel('Your name')).toHaveValue('Test Doctor');
  });

  test('API MOCKED 200 (not 201): no false thank-you', async ({ page }) => {
    await page.route(ENQUIRY_URL, (route) => route.fulfill({ status: 200, json: { id: 'abc' } }));
    const dialog = await openEnquirySheet(page);
    await fillValidEnquiry(dialog);
    await dialog.getByRole('button', { name: 'Request a call' }).click();
    await expect(dialog.getByRole('alert')).toBeVisible();
    await expect(page.getByText(THANK_YOU)).toBeHidden();
  });
});

// ---------------------------------------------------------------------------------------------
test.describe('Keyboard', () => {
  /** What is focused now: its name and whether a focus ring can be seen. */
  async function focusInfo(page: Page) {
    return page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body) return null;
      const css = getComputedStyle(el);
      const ring = (s: CSSStyleDeclaration) =>
        (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) ||
        (s.boxShadow !== 'none' && s.boxShadow !== '');
      const edge = (pseudo: '::before' | '::after') => {
        const s = getComputedStyle(el, pseudo);
        return (
          s.content !== 'none' &&
          s.borderTopStyle !== 'none' &&
          parseFloat(s.borderTopWidth) > 0 &&
          s.borderTopColor !== 'rgba(0, 0, 0, 0)'
        );
      };
      const label = el.getAttribute('aria-label') || (el.textContent ?? '').replace(/\s+/g, ' ').trim();
      return {
        label,
        tag: el.tagName.toLowerCase(),
        visibleRing: ring(css) || edge('::before') || edge('::after'),
        outline: `${css.outlineStyle} ${css.outlineWidth} ${css.outlineColor}`,
      };
    });
  }

  test('Tab reaches the skip link, Admin portal, Log in and Start Free, each with a visible focus ring', async ({ page }) => {
    await openHome(page);
    const seen: string[] = [];
    const noRing: string[] = [];
    for (let i = 0; i < 14; i++) {
      await page.keyboard.press('Tab');
      const info = await focusInfo(page);
      if (!info) continue;
      seen.push(info.label);
      if (!info.visibleRing) noRing.push(`${info.label} (${info.tag}, outline ${info.outline})`);
      if (info.label === 'Start Free') break;
    }
    expect(seen).toContain('Skip to main content');
    expect(seen).toContain('Admin portal');
    expect(seen).toContain('Log in');
    expect(seen).toContain('Start Free');
    expect(seen.indexOf('Admin portal')).toBeLessThan(seen.indexOf('Log in'));
    expect(seen.indexOf('Log in')).toBeLessThan(seen.indexOf('Start Free'));
    expect(noRing, `these focused controls showed no focus ring: ${noRing.join('; ')}`).toEqual([]);
  });

  test('desktop: Tab also reaches the five section links before Admin portal', async ({ page }, info) => {
    test.skip(info.project.name !== 'desktop-1440', 'section links are hidden at 980 px and below');
    await openHome(page);
    const seen: string[] = [];
    for (let i = 0; i < 14 && !seen.includes('Start Free'); i++) {
      await page.keyboard.press('Tab');
      seen.push((await focusInfo(page))?.label ?? '');
    }
    for (const l of ['How it works', 'Workflow', 'Specialties', 'Compare', 'Pricing']) {
      expect(seen).toContain(l);
    }
    expect(seen.indexOf('Pricing')).toBeLessThan(seen.indexOf('Admin portal'));
  });

  test('keyboard only: Enter on Start Free opens the sign-up screen', async ({ page }) => {
    await openHome(page);
    for (let i = 0; i < 14; i++) {
      await page.keyboard.press('Tab');
      if ((await focusInfo(page))?.label === 'Start Free') break;
    }
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/login\?mode=signup$/);
  });

  test('keyboard only: the enquiry sheet opens with focus on the name field and Escape closes it', async ({ page }) => {
    const dialog = await openEnquirySheet(page);
    await expect(dialog.getByLabel('Your name')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeHidden();
  });
});

// ---------------------------------------------------------------------------------------------
test.describe('Reduced motion (emulated: prefers-reduced-motion: reduce)', () => {
  test.use({ reducedMotion: 'reduce' });

  test('the browser reports reduced motion and no CSS animation is running', async ({ page }) => {
    await openHome(page);
    expect(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches)).toBe(true);
    for (const id of ['#setup', '#workflow', '#roles', '#journey', '#pricing']) {
      await page.locator(id).scrollIntoViewIfNeeded();
    }
    await page.waitForTimeout(1500);
    const running = await page.evaluate(() =>
      document
        .getAnimations()
        .filter((a) => a instanceof CSSAnimation && a.playState === 'running')
        .map((a) => (a as CSSAnimation).animationName),
    );
    expect(running).toEqual([]);
  });

  test('hero scene shows its final state straight away and never plays', async ({ page }) => {
    await openHome(page);
    const hero = page.locator('.lp-hero');
    await expect(hero.locator('.hstage')).not.toHaveClass(/playing/);
    await expect(hero.locator('.tickc.on')).toHaveCount(3);
    await expect(hero.locator('.mnode.on')).toHaveCount(5);
    await expect(hero.locator('.qmini')).toHaveClass(/on/);
    await expect(hero.getByText('Workflow ready')).toBeVisible();
    await page.waitForTimeout(2000); // a playing scene would have restarted by now
    await expect(hero.locator('.tickc.on')).toHaveCount(3);
    await expect(hero.locator('.hstage')).not.toHaveClass(/playing/);
  });

  test('Clinic setup shows all seven steps done', async ({ page }) => {
    await openHome(page);
    await page.locator('#setup').scrollIntoViewIfNeeded();
    await expect(page.locator('#setup .ssteps .sst.done')).toHaveCount(7);
  });

  test('Roles shows every task finished, with nobody still "In progress"', async ({ page }) => {
    await openHome(page);
    await page.locator('#roles').scrollIntoViewIfNeeded();
    await expect(page.locator('#roles .task .pill.p-good')).toHaveCount(6);
    await expect(page.locator('#roles').getByText('In progress')).toHaveCount(0);
    await expect(page.locator('#roles').getByText('Waiting for their step')).toHaveCount(0);
  });

  test('Workflow builder shows the Vitals step already added', async ({ page }) => {
    await openHome(page);
    await page.locator('#workflow').scrollIntoViewIfNeeded();
    await expect(page.locator('#workflow .nodes .node b', { hasText: 'Vitals' })).toHaveCount(1);
    await page.waitForTimeout(2000);
    await expect(page.locator('#workflow .nodes .node b', { hasText: 'Vitals' })).toHaveCount(1);
  });

  test('Pricing: the clinic size does not rotate by itself', async ({ page }) => {
    await openHome(page);
    await page.locator('#pricing').scrollIntoViewIfNeeded();
    const text = await page.locator('#pricing .grow-text').innerText();
    await page.waitForTimeout(5500); // longer than two 2.4 s rotations
    expect(await page.locator('#pricing .grow-text').innerText()).toBe(text);
  });
});

test.describe('Motion control: without reduced motion the hero scene does play', () => {
  test.use({ reducedMotion: 'no-preference' });

  test('hero scene plays (proves the reduced-motion tests are not passing by accident)', async ({ page }) => {
    await openHome(page);
    expect(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches)).toBe(false);
    await expect(page.locator('.lp-hero .hstage')).toHaveClass(/playing/);
  });
});
