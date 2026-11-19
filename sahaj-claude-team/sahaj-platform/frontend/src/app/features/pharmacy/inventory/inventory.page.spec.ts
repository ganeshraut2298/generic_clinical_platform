import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { OWNER_CONTEXT } from '../pharmacy.fixtures';
import { INVENTORY_OWNER_CONTEXT, READ_ONLY_CONTEXT } from './inventory.fixtures';
import { InventoryPage } from './inventory.page';

const BASE = '/api/v1/pharmacy';

describe('InventoryPage', () => {
  let fixture: ComponentFixture<InventoryPage>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(InventoryPage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const tabs = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('[role="tab"]')).map((t) => t.textContent?.trim());

  it('lists every section for an owner with the capabilities, in the order of the mockup menu, and opens on the first', async () => {
    const el = await open(INVENTORY_OWNER_CONTEXT);
    expect(tabs(el)).toEqual([
      'Batch inventory & expiry',
      'Store stock',
      'Stock adjustments',
      'Approvals inbox',
      'Stock ledger',
    ]);
    expect(el.querySelector('[data-testid="inv-section-batches"]')?.getAttribute('aria-selected')).toBe('true');
    expect(el.querySelector('app-batch-inventory')).toBeTruthy();
    http.match(() => true);
  });

  it('hides, not greys out, what a person who can only read stock cannot use', async () => {
    const el = await open(READ_ONLY_CONTEXT);
    expect(tabs(el)).toEqual(['Batch inventory & expiry', 'Store stock']);
    expect(el.querySelector('[data-testid="inv-section-approvals"]')).toBeNull();
    expect(el.querySelector('[data-testid="inv-section-adjustments"]')).toBeNull();
    expect(el.querySelector('[data-testid="inv-section-ledger"]')).toBeNull();
    http.match(() => true);
  });

  it('opens on the Approvals inbox for an approver with no stock access', async () => {
    const el = await open({
      ...READ_ONLY_CONTEXT,
      capabilities: ['INVENTORY', 'APPROVALS_INBOX'],
      stores: [{ ...READ_ONLY_CONTEXT.stores[0], access: { ...READ_ONLY_CONTEXT.stores[0].access, readStock: false } }],
    });
    expect(tabs(el)).toEqual(['Approvals inbox']);
    expect(el.querySelector('app-approvals-inbox')).toBeTruthy();
    http.match(() => true);
  });

  it('says there is no access when the server does not list the Inventory capability', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(el.querySelector('[data-testid="inventory-no-access"]')?.textContent).toContain(
      'You do not have access to Inventory. Ask your owner.',
    );
    expect(tabs(el)).toEqual([]);
  });

  it('switches section with the arrow keys', async () => {
    const el = await open(INVENTORY_OWNER_CONTEXT);
    const first = el.querySelector<HTMLElement>('[data-testid="inv-section-batches"]')!;
    first.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="inv-section-store-stock"]')?.getAttribute('aria-selected')).toBe('true');
    expect(el.querySelector('app-store-stock')).toBeTruthy();
    http.match(() => true);
  });
});
