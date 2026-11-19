import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { GrnDialog } from './grn.dialog';
import { ReceivingSection } from './receiving.section';
import { BUYER_CONTEXT, purchaseOrderPage } from './purchase.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-central`;

const GRNS = {
  items: [
    { id: 'grn-1', number: null, vendorName: 'Test Distributors Pvt. Ltd. (fake)', invoiceNo: 'TEST-INV-1', invoiceTotalPaise: 83216, status: 'DRAFT', createdAt: '2026-11-19T04:30:00Z' },
    { id: 'grn-2', number: 'GRN-26-27/0001', vendorName: 'Test Distributors Pvt. Ltd. (fake)', invoiceNo: 'TEST-INV-0', invoiceTotalPaise: 100000, status: 'POSTED', createdAt: '2026-11-18T04:30:00Z' },
  ],
  total: 2,
  page: 0,
  size: 20,
};

describe('ReceivingSection', () => {
  let fixture: ComponentFixture<ReceivingSection>;
  let http: HttpTestingController;

  async function open(workingStoreId = 'store-central'): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush({ ...BUYER_CONTEXT, workingStoreId });
    fixture = TestBed.createComponent(ReceivingSection);
    fixture.detectChanges();
    if (workingStoreId === 'store-central') {
      http.expectOne((r) => r.url === `${S}/goods-receipts`).flush(GRNS);
      const pos = http.expectOne((r) => r.url === `${S}/purchase-orders`);
      expect(pos.request.params.get('status')).toBe('SENT,PARTLY_RECEIVED');
      pos.flush(purchaseOrderPage({ items: [{ id: 'po-1', number: 'PO-26-27/0001', vendorName: 'Test Distributors Pvt. Ltd. (fake)', totalPaise: 78400, status: 'SENT', overdueDays: null }], total: 1 }));
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

  it('shows nothing to receive at a store that is not the Central Store', async () => {
    const el = await open('store-ward');
    expect(q(el, 'grn-not-central')).toBeTruthy();
    expect(q(el, 'direct-receipt')).toBeNull();
  });

  it('shows "Purchase orders (<n> open)", the receipts newest first and their status', async () => {
    const el = await open();
    expect(text(el, 'toggle-open-pos')).toBe('Purchase orders (1 open)');
    expect(el.textContent).toContain('GRN-26-27/0001');
    expect(el.textContent).toContain('Posted');
    expect(el.textContent).toContain('₹ 832.16');
    expect(q(el, 'open-pos')).toBeNull();
  });

  it('lists the open purchase orders on request and starts a receipt against one', async () => {
    const el = await open();
    q(el, 'toggle-open-pos')?.click();
    await settle();
    expect(text(el, 'open-pos')).toContain('PO-26-27/0001');
    const spy = vi.spyOn(fixture.debugElement.injector.get(MatDialog), 'open').mockReturnValue({ afterClosed: () => of({ changed: true, next: null }) } as never);
    q(el, 'receive-PO-26-27/0001')?.click();
    expect(spy).toHaveBeenCalledWith(
      GrnDialog,
      expect.objectContaining({ data: { storeId: 'store-central', po: { id: 'po-1', number: 'PO-26-27/0001', vendorName: 'Test Distributors Pvt. Ltd. (fake)' } } }),
    );
    http.expectOne((r) => r.url === `${S}/goods-receipts`).flush(GRNS);
    http.expectOne((r) => r.url === `${S}/purchase-orders`).flush(purchaseOrderPage());
  });

  it('starts a direct receipt and continues a draft', async () => {
    const el = await open();
    const spy = vi.spyOn(fixture.debugElement.injector.get(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);
    q(el, 'direct-receipt')?.click();
    expect(spy).toHaveBeenLastCalledWith(GrnDialog, expect.objectContaining({ data: { storeId: 'store-central' } }));
    q(el, 'grn-open-grn-1')?.click();
    expect(spy).toHaveBeenLastCalledWith(GrnDialog, expect.objectContaining({ data: { storeId: 'store-central', grnId: 'grn-1' } }));
  });

  it('says there is nothing yet when no receipt exists', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(BUYER_CONTEXT);
    fixture = TestBed.createComponent(ReceivingSection);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${S}/goods-receipts`).flush({ items: [], total: 0, page: 0, size: 20 });
    http.expectOne((r) => r.url === `${S}/purchase-orders`).flush(purchaseOrderPage({ items: [], total: 0 }));
    await settle();
    expect(text(fixture.nativeElement, 'grn-empty')).toBe('No receipts yet. Choose Purchase orders or Direct receipt.');
  });
});
