import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ApprovalRejectData, ApprovalRejectDialog } from './approval-reject.dialog';
import { ApprovalReviewData, ApprovalReviewDialog } from './approval-review.dialog';
import { purchaseOrder } from '../purchase/purchase.fixtures';
import { adjustment, approvalRequest, inboxRow } from './inventory.fixtures';

const BASE = '/api/v1/pharmacy';
const REASONS = {
  lists: [
    {
      listKey: 'approval-reject',
      reasons: [
        { reasonKey: 'approval-reject.quantity-too-high', label: 'Quantity too high', needsNote: false, fixed: false },
        { reasonKey: 'approval-reject.other', label: 'Other', needsNote: true, fixed: false },
      ],
    },
  ],
};

function setup(data: unknown) {
  const close = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([problemDetailInterceptor])),
      provideHttpClientTesting(),
      { provide: MAT_DIALOG_DATA, useValue: data },
      { provide: MatDialogRef, useValue: { close } },
    ],
  });
  return { close, http: TestBed.inject(HttpTestingController) };
}

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

describe('ApprovalRejectDialog', () => {
  let fixture: ComponentFixture<ApprovalRejectDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  const DATA: ApprovalRejectData = { requestId: 'req-1', what: 'ADJ-0001 · Sample Ceftriaxone' };

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function open(): Promise<HTMLElement> {
    ({ close, http } = setup(DATA));
    fixture = TestBed.createComponent(ApprovalRejectDialog);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush(REASONS);
    http.expectOne(`${BASE}/approvals/req-1`).flush(approvalRequest());
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('names the sheet, shows the reason chips from the list and asks for a reason', async () => {
    const el = await open();
    expect(el.querySelector('#approval-reject-title')?.textContent).toBe('Reject this request');
    expect(el.textContent).toContain('ADJ-0001');
    expect(text(el, 'reject-approval-reject.quantity-too-high')).toBe('Quantity too high');
    q(el, 'confirm-reject')?.click();
    await settle();
    expect(text(el, 'reason-error')).toBe('Choose a reason');
    http.expectNone(`${BASE}/approvals/req-1/decision`);
  });

  it('needs a note of at least 4 characters for Other', async () => {
    const el = await open();
    q(el, 'reject-approval-reject.other')?.click();
    await settle();
    (q(el, 'f-note') as HTMLTextAreaElement).value = 'abc';
    (q(el, 'f-note') as HTMLTextAreaElement).dispatchEvent(new Event('input'));
    q(el, 'confirm-reject')?.click();
    await settle();
    expect(el.textContent).toContain('Write at least 4 characters');
    http.expectNone(`${BASE}/approvals/req-1/decision`);
  });

  it('sends the level and the version it read, with the reason, and closes with the answer', async () => {
    const el = await open();
    q(el, 'reject-approval-reject.quantity-too-high')?.click();
    await settle();
    q(el, 'confirm-reject')?.click();
    const req = http.expectOne(`${BASE}/approvals/req-1/decision`);
    expect(req.request.body).toEqual(
      expect.objectContaining({ level: 1, requestVersion: 4, decision: 'REJECTED', reasonKey: 'approval-reject.quantity-too-high', note: null }),
    );
    req.flush(approvalRequest({ status: 'REJECTED' }));
    await settle();
    expect(close).toHaveBeenCalledOnce();
  });

  it('shows the server sentence when someone decided first, and stays open', async () => {
    const el = await open();
    q(el, 'reject-approval-reject.quantity-too-high')?.click();
    await settle();
    q(el, 'confirm-reject')?.click();
    http.expectOne(`${BASE}/approvals/req-1/decision`).flush(
      { type: 'https://sahaj.in/problems/already-decided', title: 'Already decided', status: 409, detail: 'This request was already decided.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'reject-error')).toBe('This request was already decided.');
    expect(close).not.toHaveBeenCalled();
  });
});

describe('ApprovalReviewDialog', () => {
  let fixture: ComponentFixture<ApprovalReviewDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function open(
    data: ApprovalReviewData,
    request = approvalRequest(),
    adj = adjustment(),
  ): Promise<HTMLElement> {
    ({ close, http } = setup(data));
    fixture = TestBed.createComponent(ApprovalReviewDialog);
    fixture.detectChanges();
    http.expectOne(`${BASE}/approvals/${data.row.requestId}`).flush(request);
    if (request.subjectType === 'STOCK_ADJUSTMENT') {
      http.expectOne(`${BASE}/stores/store-central/stock-adjustments/adj-1`).flush(adj);
    }
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('shows the request, the adjustment with book to physical, the levels and the history', async () => {
    const el = await open({ row: inboxRow() });
    expect(text(el, 'review-what')).toContain('ADJ-0001');
    expect(text(el, 'review-facts')).toContain('Test Pharmacist One · Test Central Store');
    expect(text(el, 'adjustment-facts')).toContain('40 → 38');
    expect(text(el, 'adjustment-facts')).toContain('−2 · ₹ 141.00');
    expect(text(el, 'adjustment-facts')).toContain('Mar 2028');
    expect(text(el, 'review-levels')).toContain('Level 1 · Owner · Adjustment above ₹ 100');
    expect(text(el, 'review-history')).toContain('Raised · Test Pharmacist One');
  });

  it('approves at the level and version it read, and closes as changed', async () => {
    const el = await open({ row: inboxRow() });
    q(el, 'review-approve')?.click();
    const req = http.expectOne(`${BASE}/approvals/req-1/decision`);
    expect(req.request.body).toEqual(expect.objectContaining({ level: 1, requestVersion: 4, decision: 'APPROVED' }));
    req.flush(approvalRequest({ status: 'APPROVED' }));
    await settle();
    expect(close).toHaveBeenCalledWith(true);
  });

  it('does not draw Approve when the server says the caller raised it, and shows why', async () => {
    const el = await open(
      { row: inboxRow() },
      approvalRequest({ canDecide: false, decideLockReason: 'SELF_APPROVAL' }),
    );
    expect(q(el, 'review-approve')).toBeNull();
    expect(q(el, 'review-reject')).toBeNull();
    expect(text(el, 'review-lock')).toBe('You cannot approve your own request.');
  });

  it('shows the capacity of each earlier decision, and "Escalated" and "Needs another approver" from the server', async () => {
    const el = await open(
      { row: inboxRow() },
      approvalRequest({
        escalated: true,
        needsAnotherApprover: true,
        decisions: [
          {
            level: 1,
            decision: 'APPROVED',
            decidedBy: { membershipId: 'm2', name: 'Test HOD' },
            actingAs: 'DELEGATE',
            decidedAt: '2026-11-19T06:00:00Z',
            reason: null,
          },
        ],
      }),
    );
    expect(text(el, 'review-decisions')).toContain('Approved by Test HOD (Delegate)');
    expect(q(el, 'review-escalated')).toBeTruthy();
    expect(q(el, 'review-needs-other')).toBeTruthy();
  });

  it('draws no Approve for a document type whose screen is not released, and still allows Reject', async () => {
    const el = await open(
      { row: inboxRow({ subjectType: 'INDENT', chip: 'INDENT', transactionType: 'INDENT' }) },
      approvalRequest({ subjectType: 'INDENT', transactionType: 'INDENT' }),
    );
    expect(q(el, 'review-approve')).toBeNull();
    expect(q(el, 'review-reject')).toBeTruthy();
    expect(q(el, 'review-elsewhere')).toBeTruthy();
  });

  it('opens a purchase order approval under the store of its row, shows the order and allows Approve', async () => {
    ({ close, http } = setup({
      row: inboxRow({
        subjectType: 'PURCHASE_ORDER',
        chip: 'PURCHASE_ORDER',
        transactionType: 'PURCHASE_ORDER',
        subjectId: 'po-1',
        subjectStoreId: 'store-central',
      }),
    }));
    fixture = TestBed.createComponent(ApprovalReviewDialog);
    fixture.detectChanges();
    http.expectOne(`${BASE}/approvals/req-1`).flush(
      approvalRequest({ subjectType: 'PURCHASE_ORDER', transactionType: 'PURCHASE_ORDER', subjectId: 'po-1', subjectStoreId: 'store-central' }),
    );
    http.expectOne(`${BASE}/stores/store-central/purchase-orders/po-1`).flush(purchaseOrder({ status: 'WAITING_APPROVAL' }));
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    expect(text(el, 'po-facts')).toContain('Total incl. GST₹ 784.00');
    expect(text(el, 'po-facts')).toContain('POs above ₹ 50,000 need owner approval');
    expect(q(el, 'review-approve')).toBeTruthy();
    expect(q(el, 'review-elsewhere')).toBeNull();
  });

  it('opens the reject sheet from the review and closes as changed when it rejects', async () => {
    const el = await open({ row: inboxRow() });
    const spy = vi
      .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
      .mockReturnValue({ afterClosed: () => of(approvalRequest({ status: 'REJECTED' })) } as never);
    q(el, 'review-reject')?.click();
    expect(spy).toHaveBeenCalledWith(ApprovalRejectDialog, expect.anything());
    expect(close).toHaveBeenCalledWith(true);
  });

  it('shows the server sentence after a failed approval and reads the request again', async () => {
    const el = await open({ row: inboxRow() });
    q(el, 'review-approve')?.click();
    http.expectOne(`${BASE}/approvals/req-1/decision`).flush(
      { type: 'https://sahaj.in/problems/insufficient-stock', title: 'Not enough stock', status: 409, detail: 'Not enough stock. Available: 1.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'decision-error')).toBe('Not enough stock. Available: 1.');
    http.expectOne(`${BASE}/approvals/req-1`).flush(approvalRequest());
    http.expectOne(`${BASE}/stores/store-central/stock-adjustments/adj-1`).flush(adjustment());
    await settle();
    expect(close).not.toHaveBeenCalled();
  });
});
