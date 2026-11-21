import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Dispatch } from '../pharmacy-indent.model';
import { dispatch, dispatchLine, SCHEDULE_X_PRODUCT } from './indent.fixtures';
import { RECEIPT_CONFIG, ReceiptData, ReceiptDialog } from './receipt.dialog';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

const REJECT_REASONS = {
  lists: [
    {
      listKey: 'receipt-reject',
      reasons: [
        { reasonKey: 'receipt-reject.damaged-in-transit', label: 'Damaged in transit', needsNote: false, fixed: true },
        { reasonKey: 'receipt-reject.short', label: 'Short / not received', needsNote: false, fixed: true },
        { reasonKey: 'receipt-reject.near-expiry', label: 'Near expiry / expired', needsNote: false, fixed: true },
      ],
    },
  ],
};

describe('ReceiptDialog', () => {
  let fixture: ComponentFixture<ReceiptDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(over: Partial<Dispatch> = {}, data: Partial<ReceiptData> = {}): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { storeId: 'store-ward', dispatchId: 'disp-1', isPharmacist: false, ...data } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReceiptDialog);
    fixture.detectChanges();
    http.expectOne(`${S}/dispatches/disp-1`).flush(dispatch(over));
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush(REJECT_REASONS);
    if (data.isPharmacist) {
      http.expectOne(`${S}/second-signers`).flush({ items: [{ membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' }] });
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
  const click = async (el: HTMLElement, id: string) => {
    q(el, id)?.click();
    await settle();
  };

  afterEach(() => http.verify());

  it('starts Accepted equal to Dispatched and shows the Check line for every line', async () => {
    const el = await open();
    expect(RECEIPT_CONFIG.ariaLabelledBy).toBe('receipt-title');
    expect(text(el, 'receipt-acting-as')).toContain('Acting as Test Ward Nurse');
    expect(text(el, 'receipt-accepted-0')).toBe('18');
    expect(text(el, 'receipt-rejected-0')).toBe('0');
    expect(text(el, 'receipt-check-0')).toBe('Check: Accepted 18 + Rejected 0 = Dispatched 18');
    expect(text(el, 'receipt-check-1')).toBe('Check: Accepted 10 + Rejected 0 = Dispatched 10');
    expect(text(el, 'receipt-status')).toBe('In transit');
  });

  it('works Rejected as Dispatched minus Accepted and cannot go below 0 or above Dispatched', async () => {
    const el = await open();
    await click(el, 'receipt-minus-0');
    await click(el, 'receipt-minus-0');
    expect(text(el, 'receipt-accepted-0')).toBe('16');
    expect(text(el, 'receipt-rejected-0')).toBe('2');
    expect(text(el, 'receipt-check-0')).toBe('Check: Accepted 16 + Rejected 2 = Dispatched 18');
    await click(el, 'receipt-plus-0');
    await click(el, 'receipt-plus-0');
    expect((q(el, 'receipt-plus-0') as HTMLButtonElement).disabled).toBe(true);
    expect(text(el, 'receipt-accepted-0')).toBe('18');
  });

  it('refuses a rejected quantity with no reason and shows the notes for rejected and short units', async () => {
    const el = await open();
    await click(el, 'receipt-minus-0');
    await click(el, 'receipt-minus-0');
    await click(el, 'receipt-confirm');
    expect(el.textContent).toContain('Choose a reason for the rejected quantity');
    http.expectNone(`${S}/dispatches/disp-1/receipt`);
    await click(el, 'receipt-reason-0-receipt-reject.damaged-in-transit');
    expect(text(el, 'receipt-notes')).toBe(
      'The 2 rejected units do not enter Test Ward 3B store stock. They wait for Central Store to accept them back.',
    );
    await click(el, 'receipt-reason-0-receipt-reject.short');
    expect(text(el, 'receipt-notes')).toBe(
      'These 2 units are not added to Test Ward 3B store stock and cannot be returned. Central Store will investigate.',
    );
  });

  it('confirms the receipt with accepted, rejected and the reason once, then shows the result', async () => {
    const el = await open();
    await click(el, 'receipt-minus-0');
    await click(el, 'receipt-minus-0');
    await click(el, 'receipt-reason-0-receipt-reject.damaged-in-transit');
    q(el, 'receipt-confirm')?.click();
    q(el, 'receipt-confirm')?.click();
    const req = http.expectOne(`${S}/dispatches/disp-1/receipt`);
    expect(req.request.body.dispatchVersion).toBe(1);
    expect(req.request.body.secondSigner).toBeNull();
    expect(req.request.body.lines).toEqual([
      { dispatchLineId: 'dline-1', accepted: 16, rejected: 2, rejectReasonKey: 'receipt-reject.damaged-in-transit', note: null, skipped: false },
      { dispatchLineId: 'dline-2', accepted: 10, rejected: 0, rejectReasonKey: null, note: null, skipped: false },
    ]);
    req.flush(
      dispatch({
        status: 'RECEIVED',
        lines: [
          dispatchLine({ accepted: 16, rejected: 2, received: true, rejectReason: { reasonKey: 'receipt-reject.damaged-in-transit', label: 'Damaged in transit' } }),
          dispatchLine({ id: 'dline-2', dispatched: 10, accepted: 10, rejected: 0, received: true }),
        ],
      }),
    );
    await settle();
    expect(text(el, 'receipt-done')).toContain('Receipt confirmed');
    expect(text(el, 'receipt-status')).toBe('Received');
    expect(q(el, 'receipt-confirm')).toBeNull();
  });

  it('shows "replaced by Central Store" with its reason and "n back-ordered"', async () => {
    const el = await open({
      lines: [
        dispatchLine({ replacedByCs: true, changeReason: { reasonKey: 'cs-change.short-stock', label: 'Short stock' }, backOrdered: 4 }),
      ],
    });
    expect(text(el, 'receipt-replaced-0')).toBe('replaced by Central Store · Short stock');
    expect(text(el, 'receipt-backordered-0')).toBe('4 back-ordered');
  });

  it('forces an expired batch to be rejected as near expiry or expired', async () => {
    const el = await open({
      lines: [dispatchLine({ batch: { batchId: 'b9', batchNo: 'TESTB009', expiryDate: '2020-01-31' } })],
    });
    expect(text(el, 'receipt-accepted-0')).toBe('0');
    expect((q(el, 'receipt-plus-0') as HTMLButtonElement).disabled).toBe(true);
    expect(q(el, 'receipt-reason-0-receipt-reject.near-expiry')?.getAttribute('aria-pressed')).toBe('true');
  });

  it('leaves a Schedule X line for a pharmacist and sends it as skipped, with the other lines received', async () => {
    const el = await open({
      status: 'IN_TRANSIT',
      lines: [dispatchLine({ product: SCHEDULE_X_PRODUCT, scheduleX: true, dispatched: 5 }), dispatchLine({ id: 'dline-2', dispatched: 10 })],
    });
    expect(text(el, 'receipt-skipped-0')).toBe('Waits for a pharmacist. It stays in transit.');
    expect(text(el, 'receipt-x-note')).toBe(
      'Schedule X / NDPS lines can only be received by a mapped pharmacist, with a second signature.',
    );
    expect(q(el, 'f-pin')).toBeNull();
    await click(el, 'receipt-confirm');
    const req = http.expectOne(`${S}/dispatches/disp-1/receipt`);
    expect(req.request.body.lines[0]).toEqual({ dispatchLineId: 'dline-1', accepted: 0, rejected: 0, rejectReasonKey: null, note: null, skipped: true });
    expect(req.request.body.lines[1].skipped).toBe(false);
    req.flush(
      dispatch({
        status: 'PARTLY_RECEIVED',
        lines: [
          dispatchLine({ product: SCHEDULE_X_PRODUCT, scheduleX: true, dispatched: 5, received: false }),
          dispatchLine({ id: 'dline-2', dispatched: 10, received: true, accepted: 10, rejected: 0 }),
        ],
      }),
    );
    await settle();
    expect(text(el, 'receipt-status')).toBe('Partly received');
    expect(text(el, 'receipt-skipped-0')).toBe('Waits for a pharmacist. It stays in transit.');
    expect(text(el, 'receipt-partly')).toContain('Schedule X lines are still waiting for a pharmacist');
    expect(el.textContent).not.toContain('Receipt confirmed. The accepted units');
  });

  it('asks a pharmacist for the second signer and PIN on a Schedule X line, and clears the PIN after the send', async () => {
    const el = await open(
      { lines: [dispatchLine({ product: SCHEDULE_X_PRODUCT, scheduleX: true, dispatched: 5 })] },
      { isPharmacist: true },
    );
    const pin = q(el, 'f-pin') as HTMLInputElement;
    expect(pin.type).toBe('password');
    await click(el, 'receipt-confirm');
    expect(el.textContent).toContain('Choose the second signer');
    http.expectNone(`${S}/dispatches/disp-1/receipt`);
    (fixture.componentInstance as unknown as { signerId: { set(v: string): void }; pin: { set(v: string): void } }).signerId.set('m-2');
    (fixture.componentInstance as unknown as { pin: { set(v: string): void } }).pin.set('4321');
    await click(el, 'receipt-confirm');
    const req = http.expectOne(`${S}/dispatches/disp-1/receipt`);
    expect(req.request.body.secondSigner).toEqual({ membershipId: 'm-2', pin: '4321' });
    req.flush(
      { type: 'https://sahaj.in/problems/changed', title: 'Changed', status: 409, detail: 'This dispatch changed. Open it again.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect((q(el, 'f-pin') as HTMLInputElement).value).toBe('');
    expect(text(el, 'receipt-error')).toContain('This dispatch changed');
    expect(text(el, 'receipt-accepted-0')).toBe('5');
  });
});
