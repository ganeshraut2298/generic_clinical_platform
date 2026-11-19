import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { InventoryPage } from '../inventory/inventory.page';
import { INVENTORY_OWNER_CONTEXT } from '../inventory/inventory.fixtures';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { BUYER_CONTEXT } from './purchase.fixtures';

const BASE = '/api/v1/pharmacy';

describe('Inventory sections of work package E', () => {
  let http: HttpTestingController;

  let fixture: ComponentFixture<InventoryPage>;

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

  const tabs = (el: HTMLElement) => Array.from(el.querySelectorAll('[role="tab"]')).map((t) => t.textContent?.trim());

  it('lists Purchase orders, Receive stock (GRN) and Vendor return & debit note only when the server lists PURCHASE', async () => {
    const el = await open(BUYER_CONTEXT);
    expect(tabs(el)).toEqual([
      'Batch inventory & expiry',
      'Store stock',
      'Stock adjustments',
      'Purchase orders',
      'Receive stock (GRN)',
      'Vendor return & debit note',
      'Approvals inbox',
      'Stock ledger',
    ]);
    http.match(() => true);
  });

  it('hides them, not greys them out, when the capability is not listed', async () => {
    const el = await open(INVENTORY_OWNER_CONTEXT);
    expect(el.querySelector('[data-testid="inv-section-purchase-orders"]')).toBeNull();
    expect(el.querySelector('[data-testid="inv-section-receiving"]')).toBeNull();
    expect(el.querySelector('[data-testid="inv-section-vendor-returns"]')).toBeNull();
    http.match(() => true);
  });

  it('opens the Purchase orders section on its tab', async () => {
    const el = await open(BUYER_CONTEXT);
    (el.querySelector('[data-testid="inv-section-purchase-orders"]') as HTMLElement).click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="inv-section-purchase-orders"]')?.getAttribute('aria-selected')).toBe('true');
    http.match(() => true);
  });
});
