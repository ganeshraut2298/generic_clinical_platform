import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { approvalRequest } from '../inventory/inventory.fixtures';
import { Indent } from '../pharmacy-indent.model';
import { INDENT_DETAIL_CONFIG, IndentDetailDialog } from './indent-detail.dialog';
import { indent, indentLine } from './indent.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

const REASONS = {
  lists: [
    {
      listKey: 'approval-reject',
      reasons: [
        { reasonKey: 'approval-reject.too-much', label: 'Too much', needsNote: false, fixed: true },
        { reasonKey: 'approval-reject.other', label: 'Other', needsNote: true, fixed: true },
      ],
    },
  ],
};

const APPROVER_VIEW: Partial<Indent> = {
  approval: {
    requestId: 'req-1',
    status: 'PENDING',
    currentLevel: 1,
    ruleText: 'HOD approves every indent',
    waitingFor: 'HOD',
    escalated: false,
    needsAnotherApprover: false,
    canDecide: true,
  },
};

describe('IndentDetailDialog', () => {
  let fixture: ComponentFixture<IndentDetailDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(over: Partial<Indent> = {}, decider = false): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { storeId: 'store-ward', indentId: 'ind-1' } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(IndentDetailDialog);
    fixture.detectChanges();
    http.expectOne(`${S}/indents/ind-1`).flush(indent(decider ? { ...APPROVER_VIEW, ...over } : over));
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush(REASONS);
    if (decider) {
      http.expectOne(`${BASE}/approvals/req-1`).flush(approvalRequest({ id: 'req-1', currentLevel: 1, version: 4 }));
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
  const type = async (el: HTMLElement, id: string, value: string) => {
    const input = q(el, id) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  };

  afterEach(() => http.verify());

  it('shows the header, the acting-as line, the status chips and the lines of a waiting indent', async () => {
    const el = await open();
    expect(INDENT_DETAIL_CONFIG.ariaLabelledBy).toBe('indent-detail-title');
    expect(text(el, 'indent-header')).toBe(
      'IND-0001 · Test Ward 3B store → Test Central Store · Routine · raised 19 Nov 2026, 10:00 AM',
    );
    expect(text(el, 'indent-chips')).toBe('Waiting for HOD approval');
    expect(text(el, 'indent-detail-acting-as')).toContain('Acting as Test Ward Nurse');
    expect(q(el, 'indent-row-1')).toBeTruthy();
    expect(q(el, 'indent-approve')).toBeNull();
  });

  it('shows the Stock impact card of the selected line for the two stores of the indent only', async () => {
    const el = await open({
      lines: [
        indentLine({
          stockImpact: { csSaleable: 110, inTransit: 18, requestingAvailable: 8, pendingReturn: 2, backOrder: 4, shortInvestigating: 1 },
        }),
      ],
    });
    expect(text(el, 'indent-impact-title')).toBe('Stock impact · Sample Ceftriaxone 1 g injection');
    expect(text(el, 'impact-cs')).toBe('110');
    expect(text(el, 'impact-transit')).toBe('18');
    expect(text(el, 'impact-available')).toBe('8');
    expect(text(el, 'impact-pending')).toBe('2');
    expect(text(el, 'impact-backorder')).toBe('4');
    expect(text(el, 'impact-short')).toBe('1');
  });

  it('shows "Counting in progress" for a hidden quantity and never "null"', async () => {
    const el = await open({
      lines: [
        indentLine({
          requestingStoreAvailable: null,
          stockImpact: { csSaleable: null, inTransit: 0, requestingAvailable: null, pendingReturn: null, backOrder: 0, shortInvestigating: 0 },
        }),
      ],
    });
    expect(text(el, 'impact-cs')).toBe('Counting in progress');
    expect(text(el, 'impact-available')).toBe('Counting in progress');
    expect(el.textContent).not.toContain('null');
  });

  it('says what the mockup says before the first step of the history', async () => {
    const el = await open({ status: 'DRAFT', number: null });
    expect(text(el, 'history-empty')).toBe('Nothing yet. History starts when the indent is submitted.');
  });

  it('shows the approver card with the approved quantity starting at the requested one', async () => {
    const el = await open({}, true);
    expect(el.querySelector('#indent-detail-title')?.textContent).toContain('Approve indent');
    expect((q(el, 'indent-approved-0') as HTMLInputElement).value).toBe('20');
    expect(text(el, 'indent-change-0')).toBe('—');
    expect(text(el, 'indent-decision-0')).toBe('Approve');
    await type(el, 'indent-approved-0', '24');
    expect(text(el, 'indent-change-0')).toBe('+4 by HOD');
    await type(el, 'indent-approved-0', '15');
    expect(text(el, 'indent-change-0')).toBe('−5 by HOD');
    await type(el, 'indent-approved-0', '0');
    expect(text(el, 'indent-decision-0')).toBe('Reject');
  });

  it('approves with the approved quantities, and asks for a remark only when a quantity is reduced', async () => {
    const el = await open({}, true);
    await type(el, 'indent-approved-0', '15');
    q(el, 'indent-approve')?.click();
    await settle();
    expect(text(el, 'indent-error')).toBe('Choose a reason for the remark.');
    http.expectNone(`${BASE}/approvals/req-1/decision`);
    q(el, 'remark-approval-reject.too-much')?.click();
    await settle();
    q(el, 'indent-approve')?.click();
    const req = http.expectOne((r) => r.url.startsWith(`${BASE}/approvals/req-1`) && r.method === 'POST');
    expect(req.request.body).toMatchObject({
      level: 1,
      requestVersion: 4,
      decision: 'APPROVED',
      reasonKey: 'approval-reject.too-much',
      indentLines: [
        { indentLineId: 'line-1', approvedQty: 15 },
        { indentLineId: 'line-2', approvedQty: 10 },
      ],
    });
    req.flush(approvalRequest({ id: 'req-1', status: 'APPROVED' }));
    await settle();
    expect(close).toHaveBeenCalledWith(true);
  });

  it('treats all lines at 0 as a rejection and does not send an approval', async () => {
    const el = await open({}, true);
    await type(el, 'indent-approved-0', '0');
    await type(el, 'indent-approved-1', '0');
    q(el, 'indent-approve')?.click();
    await settle();
    expect(text(el, 'indent-error')).toContain('Use “Reject indent”');
  });

  it('rejects the indent with a remark, and "Other" needs a note', async () => {
    const el = await open({}, true);
    q(el, 'indent-reject')?.click();
    await settle();
    expect(text(el, 'indent-error')).toBe('Choose a reason for the remark.');
    q(el, 'remark-approval-reject.other')?.click();
    await settle();
    q(el, 'indent-reject')?.click();
    await settle();
    expect(text(el, 'indent-error')).toBe('Write a note of at least 4 characters.');
    await type(el, 'indent-remark-note', 'Not needed this week');
    q(el, 'indent-reject')?.click();
    const req = http.expectOne((r) => r.url.startsWith(`${BASE}/approvals/req-1`) && r.method === 'POST');
    expect(req.request.body).toMatchObject({ decision: 'REJECTED', reasonKey: 'approval-reject.other', note: 'Not needed this week' });
    expect(req.request.body.indentLines).toBeUndefined();
    req.flush(approvalRequest({ id: 'req-1', status: 'REJECTED' }));
  });

  it('shows the server sentence when two people decide at once and keeps the card open', async () => {
    const el = await open({}, true);
    q(el, 'indent-reject')?.click();
    await settle();
    q(el, 'remark-approval-reject.too-much')?.click();
    await settle();
    q(el, 'indent-reject')?.click();
    http
      .expectOne((r) => r.url.startsWith(`${BASE}/approvals/req-1`) && r.method === 'POST')
      .flush(
        { type: 'https://sahaj.in/problems/already-decided', title: 'Already decided', status: 409, detail: 'Someone else has already decided this.' },
        { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
      );
    await settle();
    expect(text(el, 'indent-error')).toContain('Someone else has already decided this.');
    expect(close).not.toHaveBeenCalled();
  });

  it('lets the raiser cancel a submitted indent with a reason before dispatch', async () => {
    const el = await open();
    q(el, 'indent-cancel')?.click();
    await settle();
    q(el, 'cancel-reason-approval-reject.too-much')?.click();
    await settle();
    q(el, 'indent-cancel-confirm')?.click();
    const req = http.expectOne(`${S}/indents/ind-1/cancel`);
    expect(req.request.body).toEqual({ version: 1, reasonKey: 'approval-reject.too-much', note: null });
    req.flush(indent({ status: 'CANCELLED' }));
    await settle();
    expect(close).toHaveBeenCalledWith(true);
  });

  it('offers no cancel once something was dispatched', async () => {
    const el = await open({
      status: 'PARTLY_DISPATCHED',
      dispatches: [{ dispatchId: 'd1', reference: 'IND-0001 / 1', status: 'IN_TRANSIT', dispatchedAt: '2026-11-19T06:00:00Z' }],
    });
    expect(q(el, 'indent-cancel')).toBeNull();
    expect(text(el, 'indent-dispatches')).toContain('IND-0001 / 1 · In transit');
  });
});
