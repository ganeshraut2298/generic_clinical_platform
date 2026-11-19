import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ApprovalInboxPage } from '../pharmacy-inventory.model';
import { ApprovalRejectDialog } from './approval-reject.dialog';
import { ApprovalReviewDialog } from './approval-review.dialog';
import { ApprovalsInboxSection } from './approvals-inbox.section';
import { approvalRequest, inboxPage, inboxRow } from './inventory.fixtures';

const URL = '/api/v1/pharmacy/approvals/inbox';

describe('ApprovalsInboxSection', () => {
  let fixture: ComponentFixture<ApprovalsInboxSection>;
  let http: HttpTestingController;

  async function open(page: ApprovalInboxPage): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ApprovalsInboxSection);
    fixture.detectChanges();
    http.expectOne((r) => r.url === URL).flush(page);
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
  const dialog = () => fixture.debugElement.injector.get(MatDialog);

  afterEach(() => http.verify());

  it('shows the header, who is signed in, the card line and the filter chips with the server counts', async () => {
    const el = await open(
      inboxPage([inboxRow()], { counts: { all: 9, indents: 2, purchase: 2, stock: 3 }, signedInAs: 'HOD · Ward 3B' }),
    );
    expect(el.querySelector('#ai-h')?.textContent).toBe('Approvals inbox');
    expect(text(el, 'signed-in-as')).toBe('Signed in as HOD · Ward 3B');
    expect(el.textContent).toContain('Waiting for your decision');
    expect(el.textContent).toContain('Sorted by urgency. Every decision is saved with your name, time and reason.');
    expect(text(el, 'chip-ALL')).toBe('All 9');
    expect(text(el, 'chip-INDENTS')).toBe('Indents 2');
    expect(text(el, 'chip-PURCHASE')).toBe('Purchase 2');
    expect(text(el, 'chip-STOCK')).toBe('Stock 3');
    expect(q(el, 'chip-SALES')).toBeNull();
  });

  it('draws the columns Type, What, Raised by, Waiting and Rule, and the buttons Reject and Review', async () => {
    const el = await open(inboxPage([inboxRow({ waitingMinutes: 125 })]));
    const heads = Array.from(el.querySelectorAll('th')).map((h) => h.textContent?.trim());
    expect(heads.slice(0, 5)).toEqual(['Type', 'What', 'Raised by', 'Waiting', 'Rule']);
    expect(text(el, 'row-type')).toBe('Stock adjustment');
    expect(text(el, 'row-what')).toContain('ADJ-0001');
    expect(text(el, 'row-waiting')).toBe('2 h');
    expect(q(el, 'review-req-1')?.textContent?.trim()).toBe('Review');
    expect(q(el, 'reject-req-1')?.textContent?.trim()).toBe('Reject');
    expect(q(el, 'recount-req-1')).toBeNull();
  });

  it('shows Recount and Review for an audit variance, and no Reject', async () => {
    const el = await open(
      inboxPage([inboxRow({ requestId: 'req-9', chip: 'AUDIT_VARIANCE', actions: ['RECOUNT', 'REVIEW'] })]),
    );
    expect(q(el, 'recount-req-9')?.textContent?.trim()).toBe('Recount');
    expect(q(el, 'review-req-9')).toBeTruthy();
    expect(q(el, 'reject-req-9')).toBeNull();
  });

  it('marks an escalated item and one that needs another approver, read from the server', async () => {
    const el = await open(inboxPage([inboxRow({ escalated: true, needsAnotherApprover: true, urgent: true, chip: 'INDENT' })]));
    expect(text(el, 'row-escalated')).toBe('Escalated');
    expect(text(el, 'row-needs-other')).toBe('Needs another approver');
    expect(text(el, 'row-type')).toBe('Indent · urgent');
  });

  it('keeps the order the server sent', async () => {
    const el = await open(
      inboxPage([inboxRow({ requestId: 'a', what: 'first' }), inboxRow({ requestId: 'b', what: 'second' })]),
    );
    const whats = Array.from(el.querySelectorAll('[data-testid="row-what"]')).map((c) => c.textContent?.trim());
    expect(whats).toEqual(['first', 'second']);
  });

  it('says nothing is waiting and draws no table', async () => {
    const el = await open(inboxPage([]));
    expect(text(el, 'inbox-empty')).toBe('Nothing is waiting for your decision.');
    expect(q(el, 'inbox-table')).toBeNull();
  });

  it('asks the server for a type when a chip is chosen and starts again from page 0', async () => {
    const el = await open(inboxPage([inboxRow()]));
    q(el, 'chip-STOCK')?.click();
    const req = http.expectOne((r) => r.url === URL);
    expect(req.request.params.get('type')).toBe('STOCK');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(inboxPage([]));
    await settle();
    expect(q(el, 'chip-STOCK')?.getAttribute('aria-pressed')).toBe('true');
  });

  it('shows "Could not load. Try again." with a retry and nothing stale', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ApprovalsInboxSection);
    fixture.detectChanges();
    http.expectOne((r) => r.url === URL).flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    expect(text(el, 'inbox-error')).toContain('Could not load. Try again.');
    expect(q(el, 'inbox-table')).toBeNull();
    q(el, 'inbox-retry')?.click();
    http.expectOne((r) => r.url === URL).flush(inboxPage([]));
    await settle();
    expect(q(el, 'inbox-error')).toBeNull();
  });

  it('opens Review on the row and reads the list again after a decision', async () => {
    const row = inboxRow();
    const el = await open(inboxPage([row]));
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(true) } as never);
    q(el, 'review-req-1')?.click();
    expect(spy).toHaveBeenCalledWith(ApprovalReviewDialog, expect.objectContaining({ data: { row } }));
    http.expectOne((r) => r.url === URL).flush(inboxPage([]));
    await settle();
    expect(text(el, 'inbox-note')).toBe('Your decision was saved.');
    expect(q(el, 'inbox-empty')).toBeTruthy();
  });

  it('opens the reject sheet and reads the list again after a rejection', async () => {
    const el = await open(inboxPage([inboxRow()]));
    const spy = vi
      .spyOn(dialog(), 'open')
      .mockReturnValue({ afterClosed: () => of(approvalRequest({ status: 'REJECTED' })) } as never);
    q(el, 'reject-req-1')?.click();
    expect(spy).toHaveBeenCalledWith(ApprovalRejectDialog, expect.objectContaining({ ariaLabelledBy: 'approval-reject-title' }));
    http.expectOne((r) => r.url === URL).flush(inboxPage([]));
    await settle();
    expect(text(el, 'inbox-note')).toContain('Rejected.');
  });

  it('does not read the list again when the sheet closes without a decision', async () => {
    const el = await open(inboxPage([inboxRow()]));
    vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(null) } as never);
    q(el, 'reject-req-1')?.click();
    await settle();
    expect(q(el, 'inbox-note')).toBeNull();
  });
});
