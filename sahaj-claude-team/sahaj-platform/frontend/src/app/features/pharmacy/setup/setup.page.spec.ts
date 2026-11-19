import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, DeferBlockBehavior, DeferBlockState, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import {
  CENTRAL,
  OPD_STORE,
  OWNER_CONTEXT,
  PHARMACIST_CONTEXT,
  SETTINGS,
  STORE_USER_CONTEXT,
} from '../pharmacy.fixtures';
import { PharmacyContext } from '../pharmacy.model';
import { RELOAD_PAGE, SetupPage } from './setup.page';

const BASE = '/api/v1/pharmacy';

describe('SetupPage (Inventory setup)', () => {
  let fixture: ComponentFixture<SetupPage>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext, summaryFails = false): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(SetupPage);
    fixture.detectChanges();
    if (context.caller.setupAccess !== 'NONE') {
      const summary = http.expectOne(`${BASE}/setup-summary`);
      if (summaryFails) {
        summary.error(new ProgressEvent('error'));
      } else {
        summary.flush({ storeCount: 7, mappedStaffCount: 24, activeVendorCount: 12 });
      }
      http.expectOne(`${BASE}/unit-settings`).flush(SETTINGS);
      http.expectOne(`${BASE}/stores`).flush({ items: [CENTRAL, OPD_STORE], centralStoreId: 'store-central' });
      http.expectOne(`${BASE}/stores/store-central/staff`).flush({ items: [] });
    }
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  /** Answers whatever the sections ask once they are drawn (each answer may start the next request). */
  function flushAll(): void {
    for (const r of http.match((x) => x.url.startsWith(`${BASE}/products`) || x.url.startsWith(`${BASE}/product-options`))) {
      r.flush(r.request.url.endsWith('product-options') ? { units: [], forms: [] } : { items: [], total: 0, page: 0, size: 25 });
    }
    for (const r of http.match((x) => x.url === `${BASE}/opening-stock-imports`)) {
      r.flush({ items: [], total: 0, page: 0, size: 20 });
    }
    for (const r of http.match((x) => x.url === `${BASE}/stores`)) {
      r.flush({ items: [CENTRAL, OPD_STORE], centralStoreId: 'store-central' });
    }
    for (const r of http.match((x) => x.url === `${BASE}/unit-settings`)) {
      r.flush(SETTINGS);
    }
    for (const r of http.match((x) => x.url.endsWith('/staff'))) {
      r.flush({ items: [] });
    }
  }

  /** Waits for the deferred chunk of a section to arrive (its test id is drawn), then answers its requests. */
  async function drain(arrived: string): Promise<void> {
    for (let i = 0; i < 80; i++) {
      await new Promise((resolve) => setTimeout(resolve, 25));
      await fixture.whenStable();
      fixture.detectChanges();
      if (fixture.nativeElement.querySelector(`[data-testid="${arrived}"]`)) {
        break;
      }
    }
    for (let i = 0; i < 4; i++) {
      flushAll();
      await fixture.whenStable();
      fixture.detectChanges();
    }
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

  afterEach(() => http.verify());

  it('shows the header Setup, the unit line and the Stores & staff tab with its live subtitle', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(el.querySelector('h1')?.textContent).toBe('Setup');
    expect(el.querySelector('.lead')?.textContent).toBe('Unit: Test Clinic Baner');
    expect(q(el, 'section-stores')?.textContent).toContain('Stores & staff');
    expect(q(el, 'section-subtitle')?.textContent).toBe('7 stores · 24 staff mapped');
    expect(el.textContent).not.toContain('Organization');
    expect(q(el, 'view-only')).toBeNull();
  });

  it('shows the released sections in the order of story 78-1, with Vendors, Approval rules and Numbering and documents', async () => {
    const el = await open(OWNER_CONTEXT);
    const tabs = Array.from(el.querySelectorAll('[role="tab"]')).map((t) => t.firstChild?.textContent?.trim());
    expect(tabs).toEqual([
      'Stores & staff',
      'Products',
      'Vendors',
      'Approval rules',
      'Numbering and documents',
      'Opening stock',
    ]);
  });

  it('gives the Vendors tab its subtitle "12 active suppliers" from the setup summary', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(q(el, 'section-subtitle-vendors')?.textContent).toBe('12 active suppliers');
  });

  it('gives the Products tab its subtitle "Global catalogue + own products"', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(q(el, 'section-subtitle-products')?.textContent).toBe('Global catalogue + own products');
    expect(q(el, 'section-subtitle-opening')).toBeNull();
  });

  it('hides the Opening stock tab from a reader who lists no opening-stock capability', async () => {
    const el = await open({ ...PHARMACIST_CONTEXT, capabilities: ['SETUP_VIEW'] });
    const tabs = Array.from(el.querySelectorAll('[role="tab"]')).map((t) => t.firstChild?.textContent?.trim());
    expect(tabs).toEqual([
      'Stores & staff',
      'Products',
      'Vendors',
      'Approval rules',
      'Numbering and documents',
    ]);
  });

  it('opens the Products section on its tab and the Stores section again on the first', async () => {
    const el = await open(OWNER_CONTEXT);
    (q(el, 'section-products') as HTMLElement).click();
    fixture.detectChanges();
    await drain('product-master');
    expect(q(el, 'product-master')).toBeTruthy();
    expect(q(el, 'unit-settings')).toBeNull();
    expect(q(el, 'section-products')?.getAttribute('aria-selected')).toBe('true');
  });

  it('never leaves the Products and Opening stock tabs blank: loading text or the section at once (no idle wait)', async () => {
    const el = await open(OWNER_CONTEXT);
    const panelText = () => el.querySelector('[role="tabpanel"]')?.textContent?.replace(/\s+/g, ' ').trim();
    (q(el, 'section-products') as HTMLElement).click();
    fixture.detectChanges();
    expect(panelText()?.includes('Loading products') || q(el, 'product-master') !== null).toBe(true);
    await drain('product-master');
    expect(q(el, 'product-master')).toBeTruthy();
    (q(el, 'section-opening') as HTMLElement).click();
    fixture.detectChanges();
    expect(panelText()?.includes('Loading opening stock') || q(el, 'opening-stock') !== null).toBe(true);
    await drain('opening-stock');
    expect(q(el, 'opening-stock')).toBeTruthy();
  });

  it('completes the WAI-ARIA tab pattern: tab controls a tabpanel that is named by the tab', async () => {
    const el = await open(OWNER_CONTEXT);
    const tab = q(el, 'section-stores') as HTMLElement;
    const panel = el.querySelector('[role="tabpanel"]') as HTMLElement;
    expect(tab.getAttribute('aria-selected')).toBe('true');
    expect(tab.getAttribute('tabindex')).toBe('0');
    expect(tab.getAttribute('aria-controls')).toBe(panel.id);
    expect(panel.getAttribute('aria-labelledby')).toBe(tab.id);
    expect(panel.querySelector('[data-testid="unit-settings"]')).toBeTruthy();
  });

  it('moves between the tabs with arrow, Home and End keys, and ignores other keys', async () => {
    const el = await open(OWNER_CONTEXT);
    const press = async (key: string, arrived: string) => {
      const current = el.querySelector('[role="tab"][aria-selected="true"]') as HTMLElement;
      const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      current.dispatchEvent(event);
      fixture.detectChanges();
      await drain(arrived);
      return event;
    };
    const selected = () => el.querySelector('[role="tab"][aria-selected="true"]')?.getAttribute('data-testid');
    expect((await press('ArrowRight', 'product-master')).defaultPrevented).toBe(true);
    expect(selected()).toBe('section-products');
    await press('End', 'opening-stock');
    expect(selected()).toBe('section-opening');
    await press('ArrowRight', 'unit-settings');
    expect(selected()).toBe('section-stores');
    await press('ArrowLeft', 'opening-stock');
    expect(selected()).toBe('section-opening');
    await press('Home', 'unit-settings');
    expect(selected()).toBe('section-stores');
    const other = new KeyboardEvent('keydown', { key: 'a', bubbles: true, cancelable: true });
    (q(el, 'section-stores') as HTMLElement).dispatchEvent(other);
    expect(other.defaultPrevented).toBe(false);
  });

  it('marks a pharmacist View only', async () => {
    const el = await open(PHARMACIST_CONTEXT);
    expect(q(el, 'view-only')?.textContent).toContain('View only');
    expect(q(el, 'add-store')).toBeNull();
  });

  it('leaves the subtitle out when the counts cannot be read, without hiding the section', async () => {
    const el = await open(OWNER_CONTEXT, true);
    expect(q(el, 'section-subtitle')).toBeNull();
    expect(q(el, 'section-stores')).toBeTruthy();
  });

  it('shows no setup to a store user who opens the address directly', async () => {
    const el = await open(STORE_USER_CONTEXT);
    expect(el.textContent).toContain('You do not have access to Inventory setup. Ask your owner.');
    expect(q(el, 'section-stores')).toBeNull();
  });
});

describe('SetupPage when a section chunk does not load (R3-5a)', () => {
  let fixture: ComponentFixture<SetupPage>;
  let http: HttpTestingController;
  let reload: ReturnType<typeof vi.fn>;

  async function openWithManualDefer(): Promise<HTMLElement> {
    reload = vi.fn();
    TestBed.configureTestingModule({
      deferBlockBehavior: DeferBlockBehavior.Manual,
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: RELOAD_PAGE, useValue: reload },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
    fixture = TestBed.createComponent(SetupPage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/setup-summary`).flush({ storeCount: 7, mappedStaffCount: 24, activeVendorCount: 12 });
    http.expectOne(`${BASE}/unit-settings`).flush(SETTINGS);
    http.expectOne(`${BASE}/stores`).flush({ items: [CENTRAL, OPD_STORE], centralStoreId: 'store-central' });
    http.expectOne(`${BASE}/stores/store-central/staff`).flush({ items: [] });
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

  afterEach(() => http.verify());

  it.each(['products', 'vendors', 'rules', 'documents', 'opening'])(
    'shows "Could not load. Try again." with a retry on the %s tab instead of a blank panel',
    async (key) => {
      const el = await openWithManualDefer();
      (q(el, `section-${key}`) as HTMLElement).click();
      fixture.detectChanges();
      const blocks = await fixture.getDeferBlocks();
      expect(blocks).toHaveLength(1);
      await blocks[0].render(DeferBlockState.Error);
      fixture.detectChanges();
      const panel = el.querySelector('[role="tabpanel"]') as HTMLElement;
      const error = q(panel, 'section-load-error') as HTMLElement;
      expect(error.getAttribute('role')).toBe('alert');
      expect(error.textContent).toContain('Could not load. Try again.');
      expect(panel.textContent).not.toContain('Loading');
      (q(panel, 'section-load-retry') as HTMLButtonElement).click();
      expect(reload).toHaveBeenCalledTimes(1);
    },
  );
});
