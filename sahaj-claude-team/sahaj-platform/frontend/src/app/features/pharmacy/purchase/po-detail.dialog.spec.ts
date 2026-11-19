import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PurchaseOrder } from '../pharmacy-buying.model';
import { PO_DETAIL_CONFIG, PoDetailDialog } from './po-detail.dialog';
import { purchaseOrder } from './purchase.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-central`;
const REASONS = {
  lists: [
    {
      listKey: 'approval-reject',
      reasons: [{ reasonKey: 'approval-reject.wrong-item', label: 'Wrong item', needsNote: false, fixed: false }],
    },
  ],
};

describe('PoDetailDialog', () => {
  let fixture: ComponentFixture<PoDetailDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(po: PurchaseOrder): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { storeId: 'store-central', poId: 'po-1' } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PoDetailDialog);
    fixture.detectChanges();
    http.expectOne(`${S}/purchase-orders/po-1`).flush(po);
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

  it('shows the number, the vendor, the status chip, the lines, the total and the rule note', async () => {
    const el = await open(purchaseOrder());
    expect(PO_DETAIL_CONFIG.ariaLabelledBy).toBe('po-detail-title');
    expect(text(el, 'po-title')).toContain('PO-26-27/0001 · Test Distributors Pvt. Ltd. (fake)');
    expect(text(el, 'po-status')).toBe('Approved');
    expect(text(el, 'po-total')).toBe('Total incl. GST ₹ 784.00');
    expect(text(el, 'po-rule-note')).toBe('POs above ₹ 50,000 need owner approval');
    expect(text(el, 'po-delivery')).toContain('Delivery expected 25 Nov 2026 · credit 30 days');
    expect(el.textContent?.toLowerCase()).not.toContain('whatsapp');
  });

  it('shows "Overdue <n> days" on a sent order whose goods are late', async () => {
    const el = await open(purchaseOrder({ status: 'SENT', overdueDays: 3, sentOn: '2026-11-10' }));
    expect(text(el, 'po-overdue')).toBe('Overdue 3 days');
    expect(text(el, 'po-sent-on')).toBe('Sent to vendor on 10 Nov 2026');
  });

  it('offers edit, discard and submit on a draft and nothing else', async () => {
    const el = await open(purchaseOrder({ status: 'DRAFT', number: null }));
    expect(q(el, 'po-edit')).toBeTruthy();
    expect(q(el, 'po-discard')).toBeTruthy();
    expect(q(el, 'po-submit')).toBeTruthy();
    expect(q(el, 'po-print')).toBeNull();
    expect(q(el, 'po-cancel')).toBeNull();
  });

  it('submits a draft with its version and one key, and shows the waiting status from the server', async () => {
    const el = await open(purchaseOrder({ status: 'DRAFT', number: null }));
    q(el, 'po-submit')?.click();
    const req = http.expectOne(`${S}/purchase-orders/po-1/submit`);
    expect(req.request.body.version).toBe(3);
    expect(req.request.body.clientKey).toMatch(/[0-9a-f-]{36}/);
    req.flush(
      purchaseOrder({
        status: 'WAITING_APPROVAL',
        approval: {
          requestId: 'req-1',
          status: 'PENDING',
          currentLevel: 1,
          ruleText: 'PO above ₹ 50,000',
          waitingFor: 'OWNER',
          escalated: false,
          needsAnotherApprover: false,
          canDecide: false,
        },
      }),
    );
    await settle();
    expect(text(el, 'po-status')).toBe('Waiting for approval');
    expect(text(el, 'po-approval')).toContain('Waiting for owner approval');
    q(el, 'po-open-approvals')?.click();
    expect(close).toHaveBeenCalledWith({ changed: true, next: 'approvals' });
  });

  it('prints an approved order and marks it as sent with a date', async () => {
    const el = await open(purchaseOrder());
    expect(q(el, 'po-print')).toBeTruthy();
    q(el, 'po-mark-sent')?.click();
    await settle();
    expect(q(el, 'po-sent-date')).toBeTruthy();
    q(el, 'po-panel-confirm')?.click();
    const req = http.expectOne(`${S}/purchase-orders/po-1/mark-sent`);
    expect(req.request.body.version).toBe(3);
    expect(req.request.body.sentOn).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    req.flush(purchaseOrder({ status: 'SENT', sentOn: '2026-11-19' }));
    await settle();
    expect(text(el, 'po-status')).toBe('Sent to vendor');
  });

  it('cancels a submitted order only with a reason chip', async () => {
    const el = await open(purchaseOrder({ status: 'SENT' }));
    q(el, 'po-cancel')?.click();
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush(REASONS);
    await settle();
    expect((q(el, 'po-panel-confirm') as HTMLButtonElement).disabled).toBe(true);
    q(el, 'po-reason-approval-reject.wrong-item')?.click();
    await settle();
    q(el, 'po-panel-confirm')?.click();
    const req = http.expectOne(`${S}/purchase-orders/po-1/cancel`);
    expect(req.request.body).toEqual({ version: 3, reasonKey: 'approval-reject.wrong-item' });
    req.flush(purchaseOrder({ status: 'CANCELLED' }));
    await settle();
    expect(text(el, 'po-status')).toBe('Cancelled');
  });

  it('hides Cancel once something was received and offers Close short on a partly received order', async () => {
    const lines = purchaseOrder().lines.map((l) => ({ ...l, receivedQty: 4 }));
    const el = await open(purchaseOrder({ status: 'PARTLY_RECEIVED', lines }));
    expect(q(el, 'po-cancel')).toBeNull();
    expect(q(el, 'po-close-short')).toBeTruthy();
  });

  it('shows the reject reason and copies a rejected order into a new draft', async () => {
    const el = await open(
      purchaseOrder({
        status: 'REJECTED',
        approval: {
          requestId: 'req-1',
          status: 'REJECTED',
          currentLevel: null,
          ruleText: 'PO above ₹ 50,000',
          waitingFor: null,
          escalated: false,
          needsAnotherApprover: false,
          canDecide: false,
          rejectReason: { reasonKey: 'approval-reject.price', label: 'Price too high' },
        },
      }),
    );
    expect(text(el, 'po-approval')).toContain('Rejected: Price too high');
    q(el, 'po-copy')?.click();
    const req = http.expectOne(`${S}/purchase-orders/po-1/copy`);
    expect(req.request.body.clientKey).toMatch(/[0-9a-f-]{36}/);
    req.flush(purchaseOrder({ id: 'po-9', status: 'DRAFT', number: null }), { status: 201, statusText: 'Created' });
    expect(close).toHaveBeenCalledWith({ changed: true, next: { open: 'po-9' } });
  });

  it('says a blocked vendor cannot get a new order but leaves the order readable', async () => {
    const el = await open(purchaseOrder({ vendor: { ...purchaseOrder().vendor, blocked: true } }));
    expect(text(el, 'po-vendor-blocked')).toContain('Blocked · licence expired');
    expect(q(el, 'po-lines')).toBeTruthy();
  });

  it('shows the server sentence when an action is refused, and keeps the order', async () => {
    const el = await open(purchaseOrder({ status: 'DRAFT', number: null }));
    q(el, 'po-submit')?.click();
    http.expectOne(`${S}/purchase-orders/po-1/submit`).flush(
      { type: 'https://sahaj.in/problems/changed', title: 'Changed', status: 409, detail: 'Someone changed this purchase order. Reload and try again.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'po-action-error')).toContain('Someone changed this purchase order');
    expect(q(el, 'po-lines')).toBeTruthy();
  });
});
