import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { BatchInventorySection } from './batch-inventory.section';
import {
  INVENTORY_OWNER_CONTEXT,
  READ_ONLY_CONTEXT,
  adjustment,
  batchRow,
  overview,
  productStock,
} from './inventory.fixtures';
import { VendorReturnDialog } from '../purchase/vendor-return.dialog';
import { StockAdjustmentDialog } from './stock-adjustment.dialog';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-central`;

describe('BatchInventorySection', () => {
  let fixture: ComponentFixture<BatchInventorySection>;
  let http: HttpTestingController;

  async function open(
    context: PharmacyContext = INVENTORY_OWNER_CONTEXT,
    storeId = 'store-central',
    blind = false,
  ): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(BatchInventorySection);
    fixture.detectChanges();
    http.expectOne(`${BASE}/stores/${storeId}/stock/overview`).flush(blind ? overview({ stockValuePaise: null }) : overview());
    http.expectOne((r) => r.url === `${BASE}/stores/${storeId}/stock/batches`).flush({
      items: [
        batchRow(blind ? { qty: null } : {}),
        batchRow({
          batch: { batchId: 'batch-2', batchNo: 'TESTB002', expiryDate: '2026-10-31' },
          state: 'QUARANTINE',
          fefoRank: null,
          statusChip: ['EXPIRED_QUARANTINED'],
          daysToExpiry: -19,
          purchaseRate: null,
          landed: null,
          marginPercent: null,
        }),
      ],
      total: 2,
      page: 0,
      size: 25,
    });
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => http.verify());

  it('shows the header with SKUs, batches and the stock value in rupees and paise', async () => {
    const el = await open();
    expect(el.querySelector('#bi-h')?.textContent).toBe('Batch inventory & expiry');
    expect(text(el, 'inventory-summary')).toBe('3 SKUs · 5 batches · stock value ₹ 1,25,000.50');
  });

  it('draws the batch table with expiry as month and year, FEFO order and a dash for a missing rate or rank', async () => {
    const el = await open();
    const heads = Array.from(el.querySelectorAll('th')).map((h) => h.textContent?.trim());
    expect(heads).toEqual(
      expect.arrayContaining(['Product', 'FEFO #', 'Batch', 'Expiry', 'Qty', 'MRP', 'Purchase rate', 'Landed', 'Margin', 'Supplier', 'Status']),
    );
    const live = Array.from(q(el, 'batch-TESTB001-AVAILABLE')!.querySelectorAll('td')).map((c) => c.textContent?.replace(/\s+/g, ' ').trim());
    expect(live).toEqual(expect.arrayContaining(['1', 'TESTB001', 'Mar 2028', '40 vial', '₹ 120.00', '₹ 70.50', '41.3%', 'Saleable']));
    const held = Array.from(q(el, 'batch-TESTB002-QUARANTINE')!.querySelectorAll('td')).map((c) => c.textContent?.replace(/\s+/g, ' ').trim());
    expect(held).toEqual(expect.arrayContaining(['—', 'Oct 2026', 'Expired · quarantined']));
    expect(q(el, 'adjust-TESTB002')).toBeNull();
  });

  it('lists the views of the story and not the Later pills', async () => {
    const el = await open();
    const pills = Array.from(el.querySelectorAll('[data-testid="pills"] button')).map((b) => b.textContent?.trim());
    expect(pills).toEqual([
      'All batches',
      'Near expiry (90 d) · 2',
      'Expired · quarantined · 1',
      'Low stock · 1',
      'Schedule H / H1 / X',
    ]);
  });

  it('asks the server for the near expiry view with its bucket, and shows the 30, 60 and 90 day buckets', async () => {
    const el = await open();
    q(el, 'pill-NEAR_EXPIRY')?.click();
    const first = http.expectOne((r) => r.url === `${S}/stock/batches`);
    expect(first.request.params.get('view')).toBe('NEAR_EXPIRY');
    expect(first.request.params.get('withinDays')).toBe('90');
    first.flush({ items: [], total: 0, page: 0, size: 25 });
    await settle();
    expect(q(el, 'buckets')).toBeTruthy();
    q(el, 'bucket-30')?.click();
    const second = http.expectOne((r) => r.url === `${S}/stock/batches`);
    expect(second.request.params.get('withinDays')).toBe('30');
    second.flush({ items: [], total: 0, page: 0, size: 25 });
    await settle();
    expect(text(el, 'list-empty')).toBe('No batches in this view.');
  });

  it('shows the low stock list from the server, without the actions of later stories', async () => {
    const el = await open();
    q(el, 'pill-LOW_STOCK')?.click();
    http.expectOne((r) => r.url === `${S}/stock/low-stock`).flush({
      items: [{ product: batchRow().product, available: 4, reorderLevel: 10, action: 'RAISE_INDENT' }],
      total: 1,
      page: 0,
      size: 25,
    });
    await settle();
    expect(text(el, 'low-G-000101')).toContain('4 vial');
    expect(text(el, 'low-G-000101')).toContain('10');
    expect(el.textContent).not.toContain('Raise indent');
  });

  it('shows a dash where a blind count hides the quantities, the stock value and the low stock figure', async () => {
    const el = await open(INVENTORY_OWNER_CONTEXT, 'store-central', true);
    expect(text(el, 'inventory-summary') ?? el.textContent).toContain('stock value —');
    const firstRow = el.querySelector('tbody tr');
    expect(firstRow?.textContent).toContain('—');
    expect(firstRow?.textContent).not.toContain('null');
    q(el, 'pill-LOW_STOCK')?.click();
    http.expectOne((r) => r.url === `${S}/stock/low-stock`).flush({
      items: [{ product: batchRow().product, available: null, reorderLevel: 10, action: 'RAISE_INDENT' }],
      total: 1,
      page: 0,
      size: 25,
    });
    await settle();
    expect(text(el, 'low-G-000101')).toContain('—');
    expect(text(el, 'low-G-000101')).not.toContain('null');
    expect(text(el, 'low-G-000101')).not.toContain('vial');
  });

  it('shows the empty state of a store with no stock', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(INVENTORY_OWNER_CONTEXT);
    fixture = TestBed.createComponent(BatchInventorySection);
    fixture.detectChanges();
    http.expectOne(`${S}/stock/overview`).flush(overview({ empty: true, skuCount: 0, batchCount: 0, stockValuePaise: 0 }));
    http.expectOne((r) => r.url === `${S}/stock/batches`).flush({ items: [], total: 0, page: 0, size: 25 });
    await settle();
    expect(text(fixture.nativeElement, 'inventory-empty')).toBe('No stock in this store yet. Post opening stock or receive stock.');
  });

  it('opens a product with its heading line, the rack and reorder level of the store, and the FEFO note', async () => {
    const el = await open();
    q(el, 'open-product-G-000101')?.click();
    http.expectOne(`${S}/stock/products/prod-1`).flush(productStock());
    await settle();
    expect(text(el, 'product-heading')).toContain('Sample Ceftriaxone 1 g injection · 1 g · Injection · Box of 10 → vial');
    expect(text(el, 'product-line')).toBe('Salt: Ceftriaxone · HSN 3004 · GST 12% · Rack R-12 · Reorder level 60 vial');
    expect(el.textContent).toContain('Stock lives at product + batch level. Every issue picks the top row first (FEFO).');
  });

  it('shows the Stock adjustment button and the Adjust action only when the server lists the capability for the store', async () => {
    const el = await open();
    expect(q(el, 'open-adjustment')).toBeTruthy();
    expect(q(el, 'adjust-TESTB001')).toBeTruthy();
  });

  it('hides the adjustment actions for a person who can only read stock, and shows no store select for one store', async () => {
    const el = await open(READ_ONLY_CONTEXT, 'store-ward');
    expect(q(el, 'open-adjustment')).toBeNull();
    expect(q(el, 'adjust-TESTB001')).toBeNull();
    expect(q(el, 'inventory-store')).toBeNull();
  });

  it('opens the adjustment sheet from a batch and reloads after an adjustment is posted', async () => {
    const el = await open();
    const spy = vi
      .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
      .mockReturnValue({ afterClosed: () => of(adjustment({ status: 'POSTED' })) } as never);
    q(el, 'adjust-TESTB001')?.click();
    expect(spy).toHaveBeenCalledWith(
      StockAdjustmentDialog,
      expect.objectContaining({ data: expect.objectContaining({ storeId: 'store-central', preset: expect.anything() }) }),
    );
    http.expectOne(`${S}/stock/overview`).flush(overview());
    http.expectOne((r) => r.url === `${S}/stock/batches`).flush({ items: [], total: 0, page: 0, size: 25 });
    await settle();
    expect(text(el, 'adjusted-note')).toBe('ADJ-0001 posted. The batch now shows the new quantity.');
  });

  it('shows Return only on a row the server marks canVendorReturn and opens the vendor return with that batch and vendor', async () => {
    const el = await open();
    expect(q(el, 'return-TESTB001-AVAILABLE')).toBeNull();
    expect(q(el, 'return-TESTB002-QUARANTINE')).toBeNull();
  });

  it('opens the vendor return from Return with the batch and its vendor preselected', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(READ_ONLY_CONTEXT);
    fixture = TestBed.createComponent(BatchInventorySection);
    fixture.detectChanges();
    http.expectOne(`${BASE}/stores/store-ward/stock/overview`).flush(overview());
    http.expectOne((r) => r.url === `${BASE}/stores/store-ward/stock/batches`).flush({
      items: [
        batchRow({
          batch: { batchId: 'batch-2', batchNo: 'TESTB002', expiryDate: '2026-10-31' },
          state: 'QUARANTINE',
          canVendorReturn: true,
          supplier: { vendorId: 'vendor-1', name: 'Test Vendor' },
        }),
      ],
      total: 1,
      page: 0,
      size: 25,
    });
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    expect(q(el, 'adjust-TESTB002')).toBeNull();
    const spy = vi
      .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
      .mockReturnValue({ afterClosed: () => of(null) } as never);
    q(el, 'return-TESTB002-QUARANTINE')?.click();
    expect(spy).toHaveBeenCalledWith(
      VendorReturnDialog,
      expect.objectContaining({
        data: { storeId: 'store-ward', vendorId: 'vendor-1', batchId: 'batch-2' },
      }),
    );
  });

  it('says it is waiting for approval when the rule sent the adjustment to the inbox', async () => {
    const el = await open();
    vi.spyOn(fixture.debugElement.injector.get(MatDialog), 'open').mockReturnValue({
      afterClosed: () => of(adjustment({ status: 'WAITING_APPROVAL' })),
    } as never);
    q(el, 'open-adjustment')?.click();
    http.expectOne(`${S}/stock/overview`).flush(overview());
    http.expectOne((r) => r.url === `${S}/stock/batches`).flush({ items: [], total: 0, page: 0, size: 25 });
    await settle();
    expect(text(el, 'adjusted-note')).toBe('ADJ-0001 is waiting for approval. It shows in the Approvals inbox.');
  });

  it('shows "Could not load. Try again." when the overview fails', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(INVENTORY_OWNER_CONTEXT);
    fixture = TestBed.createComponent(BatchInventorySection);
    fixture.detectChanges();
    http.expectOne(`${S}/stock/overview`).flush(null, { status: 500, statusText: 'Server Error' });
    http.expectOne((r) => r.url === `${S}/stock/batches`).flush({ items: [], total: 0, page: 0, size: 25 });
    await settle();
    expect(text(fixture.nativeElement, 'inventory-error')).toContain('Could not load. Try again.');
  });
});
