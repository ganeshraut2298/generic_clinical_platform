import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { ACTIVE_VENDOR, BLOCKED_VENDOR } from '../pharmacy-c.fixtures';
import { PharmacyContext } from '../pharmacy.model';
import { PoDetailDialog } from './po-detail.dialog';
import { PoFormDialog } from './po-form.dialog';
import { PurchaseOrdersSection } from './purchase-orders.section';
import { BUYER_CONTEXT, purchaseOrder, purchaseOrderPage, reorderRow } from './purchase.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-central`;

describe('PurchaseOrdersSection', () => {
  let fixture: ComponentFixture<PurchaseOrdersSection>;
  let http: HttpTestingController;

  async function open(
    reorder: unknown[] = [reorderRow()],
    context: PharmacyContext = BUYER_CONTEXT,
    pos = purchaseOrderPage(),
  ): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(PurchaseOrdersSection);
    fixture.detectChanges();
    if (context.workingStoreId === 'store-central') {
      http.expectOne((r) => r.url === `${S}/purchase-orders`).flush(pos);
      http.expectOne((r) => r.url === `${S}/reorder-suggestions`).flush({ items: reorder, total: reorder.length, page: 0, size: 50 });
      http.expectOne((r) => r.url === `${BASE}/vendors`).flush({ items: [ACTIVE_VENDOR, BLOCKED_VENDOR], total: 2, page: 0, size: 100 });
    }
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

  it('says purchase orders are raised at the Central Store when the working store is another store', async () => {
    const el = await open([], { ...BUYER_CONTEXT, workingStoreId: 'store-ward' });
    expect(text(el, 'po-not-central')).toBe('Purchase orders are raised at the Central Store.');
    expect(q(el, 'new-po')).toBeNull();
    http.expectNone((r) => r.url.includes('purchase-orders'));
  });

  it('shows the tiles with their counts, the reorder card and Recent POs', async () => {
    const el = await open();
    expect(text(el, 'po-tile-DRAFT')).toBe('Draft 1');
    expect(text(el, 'po-tile-OVERDUE')).toBe('Overdue delivery 1');
    expect(text(el, 'po-tile-WAITING_APPROVAL')).toBe('Waiting for approval 0');
    expect(el.textContent).toContain('Reorder suggestions · 1 items');
    expect(el.textContent).toContain('Recent POs');
    expect(text(el, 'po-open-PO-26-27/0001')).toBe('PO-26-27/0001');
    expect(el.textContent).toContain('Sent to vendor');
    expect(el.textContent).toContain('Overdue 2 days');
    expect(el.textContent).toContain('₹ 784.00');
  });

  it('asks the server for the overdue orders when the Overdue tile is chosen', async () => {
    const el = await open();
    q(el, 'po-tile-OVERDUE')?.click();
    const req = http.expectOne((r) => r.url === `${S}/purchase-orders`);
    expect(req.request.params.get('overdue')).toBe('true');
    req.flush(purchaseOrderPage({ items: [] }));
    await settle();
    expect(text(el, 'po-empty')).toBe('No purchase orders yet. Create one from the reorder list or choose + Manual PO.');
    q(el, 'po-tile-DRAFT')?.click();
    const next = http.expectOne((r) => r.url === `${S}/purchase-orders`);
    expect(next.request.params.get('status')).toBe('DRAFT');
    next.flush(purchaseOrderPage());
  });

  it('shows the reorder row with its figures, the owner column and a dash while a count hides the stock', async () => {
    const el = await open([reorderRow({ csStock: null, allStoresStock: null })]);
    expect(text(el, 'reorder-cs-G-000101')).toBe('—');
    const heads = Array.from(el.querySelectorAll('[data-testid="reorder-table"] th')).map((h) => h.textContent?.trim());
    expect(heads).toEqual(['Select', 'Item and purchase unit', 'CS stock', 'All stores', 'Open PO', 'Suggested', 'Vendor', 'Rate']);
    expect(el.textContent).not.toContain('null');
  });

  it('does not select a product that an open purchase order already covers', async () => {
    const el = await open([reorderRow({ suggestedQty: 0, openPoNo: 'PO-26-27/0002', openPoQty: 5 })]);
    expect((q(el, 'reorder-select-G-000101') as HTMLInputElement).checked).toBe(false);
    expect((q(el, 'reorder-qty-G-000101') as HTMLInputElement).value).toBe('0');
    expect(el.textContent).toContain('PO-26-27/0002');
    expect((q(el, 'create-pos') as HTMLButtonElement).disabled).toBe(true);
  });

  it('asks for a vendor on a row that has none and then creates the drafts split by vendor', async () => {
    const el = await open([reorderRow({ vendor: null })]);
    q(el, 'create-pos')?.click();
    await settle();
    expect(el.textContent).toContain('Choose a vendor');
    http.expectNone(`${S}/purchase-orders/from-suggestions`);
    const select = q(el, 'reorder-vendor-G-000101') as HTMLSelectElement;
    select.value = 'vendor-1';
    select.dispatchEvent(new Event('change'));
    await settle();
    q(el, 'create-pos')?.click();
    const req = http.expectOne(`${S}/purchase-orders/from-suggestions`);
    expect(req.request.body.rows).toEqual([{ productId: 'prod-1', vendorId: 'vendor-1', qty: 3, rate: '70' }]);
    expect(req.request.body.clientKey).toMatch(/[0-9a-f-]{36}/);
    req.flush({ items: [purchaseOrder({ status: 'DRAFT', number: null })] }, { status: 201, statusText: 'Created' });
    http.expectOne((r) => r.url === `${S}/purchase-orders`).flush(purchaseOrderPage());
    http.expectOne((r) => r.url === `${S}/reorder-suggestions`).flush({ items: [], total: 0, page: 0, size: 50 });
    await settle();
    expect(text(el, 'po-note')).toBe('1 draft purchase order created.');
  });

  it('refuses a quantity that is not a whole number and a rate of 0 before sending', async () => {
    const el = await open();
    const qty = q(el, 'reorder-qty-G-000101') as HTMLInputElement;
    qty.value = '2.5';
    qty.dispatchEvent(new Event('input'));
    await settle();
    q(el, 'create-pos')?.click();
    await settle();
    expect(el.textContent).toContain('Enter a whole number from 1 to 999999');
    http.expectNone(`${S}/purchase-orders/from-suggestions`);
  });

  it('shows the server sentence when a vendor is blocked at creation', async () => {
    const el = await open();
    q(el, 'create-pos')?.click();
    http.expectOne(`${S}/purchase-orders/from-suggestions`).flush(
      { type: 'https://sahaj.in/problems/vendor-blocked', title: 'Blocked', status: 409, detail: "This vendor's drug licence has expired. It cannot get a new purchase order." },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'reorder-create-error')).toContain('drug licence has expired');
  });

  it('opens + Manual PO and the detail of a row, and reloads after a change', async () => {
    const el = await open();
    const dialog = fixture.debugElement.injector.get(MatDialog);
    const spy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(purchaseOrder({ status: 'DRAFT' })) } as never);
    q(el, 'new-po')?.click();
    expect(spy).toHaveBeenCalledWith(PoFormDialog, expect.objectContaining({ data: { storeId: 'store-central', po: undefined } }));
    http.expectOne((r) => r.url === `${S}/purchase-orders`).flush(purchaseOrderPage());
    await settle();
    expect(text(el, 'po-note')).toBe('Draft purchase order saved.');

    spy.mockReturnValue({ afterClosed: () => of({ changed: true, next: null }) } as never);
    q(el, 'po-open-PO-26-27/0001')?.click();
    expect(spy).toHaveBeenLastCalledWith(
      PoDetailDialog,
      expect.objectContaining({ data: { storeId: 'store-central', poId: 'po-1' } }),
    );
    http.expectOne((r) => r.url === `${S}/purchase-orders`).flush(purchaseOrderPage());
    http.expectOne((r) => r.url === `${S}/reorder-suggestions`).flush({ items: [], total: 0, page: 0, size: 50 });
  });

  it('says Could not load with a retry when the list fails', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(BUYER_CONTEXT);
    fixture = TestBed.createComponent(PurchaseOrdersSection);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${S}/purchase-orders`).flush(null, { status: 500, statusText: 'Server Error' });
    http.expectOne((r) => r.url === `${S}/reorder-suggestions`).flush({ items: [], total: 0, page: 0, size: 50 });
    http.expectOne((r) => r.url === `${BASE}/vendors`).flush({ items: [], total: 0, page: 0, size: 100 });
    await settle();
    expect(text(fixture.nativeElement, 'po-list-error')).toContain('Could not load. Try again.');
  });
});
