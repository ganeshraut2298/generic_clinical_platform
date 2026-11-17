import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { problem } from '../emr/emr.fixtures';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { TOKEN_A, TOKEN_B, count, listOf, noticeOf, summaryOf } from '../orders/order-notices.fixtures';
import { OrderNoticesStore } from '../orders/order-notices.store';
import { NoticeCount } from '../orders/order.model';
import { OrdersTabComponent } from './orders-tab.component';

const LIST = '/api/v1/ipd/admissions/a1/order-notices';
const SUMMARY = '/api/v1/ipd/order-notices/summary';
const ACK = '/api/v1/ipd/order-notices/n1/acknowledge';

describe('OrdersTabComponent (SAH-76-3)', () => {
  let fixture: ComponentFixture<OrdersTabComponent>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;
  let report: ReturnType<typeof vi.fn>;
  let counted: ReturnType<typeof vi.fn<(c: NoticeCount) => void>>;

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** Opens the tab and answers its list (the counts poll starts with it: answered with the given summary). */
  async function open(
    list: ReturnType<typeof listOf> | { status: number; body: object } = listOf(),
    summary = summaryOf(),
  ): Promise<void> {
    snack = vi.fn();
    report = vi.fn();
    counted = vi.fn<(c: NoticeCount) => void>();
    await TestBed.configureTestingModule({
      imports: [OrdersTabComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snack } },
        { provide: ScopeRefusals, useValue: { report, refusals: new Subject(), watch: vi.fn() } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(OrdersTabComponent);
    fixture.componentRef.setInput('admissionId', 'a1');
    fixture.componentInstance.counted.subscribe(counted);
    fixture.detectChanges();
    http.expectOne(SUMMARY).flush(summary);
    const req = http.expectOne(LIST);
    if ('status' in list && 'body' in list) {
      req.flush(list.body, { status: list.status, statusText: 'x' });
    } else {
      req.flush(list);
    }
    await settle();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const rows = () => Array.from(el().querySelectorAll<HTMLElement>('[data-notice]'));

  afterEach(() => http.verify());

  describe('the list', () => {
    it('shows the heading, "n to acknowledge", and a row with the kind, the order and who ordered when', async () => {
      await open();
      expect(squash(q('h3'))).toBe('Nursing orders');
      expect(squash(q('[data-testid=orders-open]'))).toBe('1 to acknowledge');
      expect(squash(q('[data-testid=notice-kind]'))).toBe('New');
      expect(squash(q('[data-testid=notice-title]'))).toBe('Tab. Paracetamol 650 mg');
      expect(squash(q('[data-testid=notice-line]'))).toBe(
        '1 tablet · Oral · Once daily · from 6 Nov, 10:00 AM · Until stopped',
      );
      expect(squash(q('[data-testid=notice-by]'))).toBe('New by Dr. Meera Kulkarni at 11:05 AM');
      expect(counted).toHaveBeenCalledWith(count(1));
    });

    it('says "No new orders." when there are none', async () => {
      await open(listOf([]));
      expect(squash(q('[data-testid=orders-empty]'))).toBe('No new orders.');
      expect(q('[data-testid=orders-open]')).toBeNull();
    });

    it('shows the changed lines and the doctor reason for a Changed notice, and the kind words', async () => {
      const changed = noticeOf({
        kind: 'CHANGED',
        changes: [{ field: 'dose', label: 'Dose', before: '1 tablet', after: '2 tablet' }],
        reason: 'Not enough effect',
      });
      const held = noticeOf({ id: 'n2', kind: 'HELD', reason: 'Nil by mouth' });
      await open(listOf([changed, held, noticeOf({ id: 'n3', kind: 'RESUMED' }), noticeOf({ id: 'n4', kind: 'STOPPED' })]));
      expect(rows().map((r) => squash(r.querySelector('[data-testid=notice-kind]')))).toEqual([
        'Changed',
        'On hold',
        'Resumed',
        'Stopped',
      ]);
      expect(squash(q('[data-testid=notice-change]'))).toBe('Changed: Dose: 1 tablet → 2 tablet');
      expect(rows().map((r) => squash(r.querySelector('[data-testid=notice-reason]')))).toEqual([
        'Reason: Not enough effect',
        'Reason: Nil by mouth',
        '',
        '',
      ]);
    });

    it('marks STAT and Urgent orders and the badge is red only when a STAT notice is open', async () => {
      await open(listOf([noticeOf({ priority: 'STAT' }), noticeOf({ id: 'n2', priority: 'URGENT' })]));
      expect(squash(q('[data-testid=notice-stat-tag]'))).toBe('STAT');
      expect(squash(q('[data-testid=notice-urgent-tag]'))).toBe('Urgent');
      expect(q('[data-testid=orders-open]')!.classList).toContain('crit');
    });

    it('shows the badge in amber when no STAT notice is open', async () => {
      await open();
      expect(q('[data-testid=orders-open]')!.classList).toContain('warn');
    });

    it('shows who acknowledged and when, with no Acknowledge button, for an acknowledged notice', async () => {
      await open(
        listOf([noticeOf({ acknowledgedAt: '2026-11-06T05:40:00Z', acknowledgedByName: 'Sr. Anjali Pawar', waitingMinutes: null })]),
      );
      expect(squash(q('[data-testid=notice-ack]'))).toBe('Acknowledged by Sr. Anjali Pawar at 11:10 AM');
      expect(q('[data-act=ordack]')).toBeNull();
      expect(q('[data-testid=orders-open]')).toBeNull();
    });
  });

  describe('STAT and not acknowledged for too long', () => {
    it('shows the STAT window with the minutes left, and red "overdue" after the due time', async () => {
      const stat = { dueBy: '2026-11-06T05:56:00Z', windowMinutes: 15, minutesLeft: 9, minutesOverdue: null };
      await open(listOf([noticeOf({ priority: 'STAT', stat })]));
      const line = q('[data-testid=notice-stat]')!;
      expect(squash(line)).toBe('STAT · give within 15 min · 9 min left');
      expect(line.classList).not.toContain('late');
    });

    it('shows red "STAT · overdue by 6 min" and keeps it', async () => {
      const stat = { dueBy: '2026-11-06T05:41:00Z', windowMinutes: 15, minutesLeft: null, minutesOverdue: 6 };
      await open(listOf([noticeOf({ priority: 'STAT', stat })]));
      const line = q('[data-testid=notice-stat]')!;
      expect(squash(line)).toBe('STAT · overdue by 6 min');
      expect(line.classList).toContain('late');
    });

    it('shows "Not acknowledged for 35 min" in red when the server says it is overdue, and no message of an alert', async () => {
      await open(listOf([noticeOf({ overdue: true, waitingMinutes: 35 })]));
      expect(squash(q('[data-testid=notice-overdue]'))).toBe('Not acknowledged for 35 min');
      expect(el().textContent).not.toMatch(/alert|notified/i);
    });

    it('shows no overdue line for an acknowledged notice', async () => {
      await open(listOf([noticeOf({ overdue: true, waitingMinutes: 35, acknowledgedAt: '2026-11-06T05:40:00Z', acknowledgedByName: 'X' })]));
      expect(q('[data-testid=notice-overdue]')).toBeNull();
    });
  });

  describe('Acknowledge', () => {
    it('posts with no body, shows the list now with "Acknowledged by", and the badge drops', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=ordack]')!.click();
      await settle();
      const req = http.expectOne(ACK);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      expect(q<HTMLButtonElement>('[data-act=ordack]')!.disabled).toBe(true);
      req.flush(listOf([noticeOf({ acknowledgedAt: '2026-11-06T05:50:00Z', acknowledgedByName: 'Sr. Anjali Pawar' })]));
      await settle();
      expect(squash(q('[data-testid=notice-ack]'))).toBe('Acknowledged by Sr. Anjali Pawar at 11:20 AM');
      expect(q('[data-testid=orders-open]')).toBeNull();
      expect(counted).toHaveBeenLastCalledWith(count(0));
    });

    it('has no Acknowledge button for a doctor (the server sends canAcknowledge false)', async () => {
      await open(listOf([noticeOf({ canAcknowledge: false })], { canAcknowledge: false }));
      expect(q('[data-act=ordack]')).toBeNull();
      expect(rows()).toHaveLength(1);
    });

    it('shows the server text and reads the list again when another nurse was first (409 order-state)', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=ordack]')!.click();
      await settle();
      http.expectOne(ACK).flush(problem('order-state', 409, 'Sr. Anjali Pawar already acknowledged this.'), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      expect(snack.mock.calls[0][0]).toBe('Sr. Anjali Pawar already acknowledged this.');
      http
        .expectOne(LIST)
        .flush(listOf([noticeOf({ acknowledgedAt: '2026-11-06T05:46:00Z', acknowledgedByName: 'Sr. Anjali Pawar' })]));
      await settle();
      expect(q('[data-act=ordack]')).toBeNull();
      expect(squash(q('[data-testid=notice-ack]'))).toContain('Acknowledged by Sr. Anjali Pawar');
    });

    it('shows a generic text on a lost connection and keeps the button', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=ordack]')!.click();
      await settle();
      http.expectOne(ACK).error(new ProgressEvent('error'));
      await settle();
      expect(snack.mock.calls[0][0]).toBe('We could not acknowledge that order. Try again.');
      expect(q<HTMLButtonElement>('[data-act=ordack]')!.disabled).toBe(false);
    });

    it('turns every Acknowledge off while one request runs', async () => {
      await open(listOf([noticeOf(), noticeOf({ id: 'n2' })]));
      q<HTMLButtonElement>('[data-act=ordack]')!.click();
      await settle();
      const buttons = Array.from(el().querySelectorAll<HTMLButtonElement>('[data-act=ordack]'));
      expect(buttons.every((b) => b.disabled)).toBe(true);
      http.expectOne(ACK).flush(listOf());
    });

    it('tells the page about 403 outside-scope and shows no toast', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=ordack]')!.click();
      await settle();
      http.expectOne(ACK).flush(problem('outside-scope', 403, 'This patient is not in your wards.'), {
        status: 403,
        statusText: 'Forbidden',
      });
      await settle();
      expect(report).toHaveBeenCalledWith('a1', true);
      expect(snack).not.toHaveBeenCalled();
    });
  });

  describe('loading, failure and refresh', () => {
    it('shows "We could not load the orders. Try again." with a button, and loads again', async () => {
      await open({ status: 500, body: problem('server-error', 500, 'Try later.') });
      expect(squash(q('[data-testid=orders-error]'))).toContain('Try later.');
      q<HTMLButtonElement>('[data-act=orders-retry]')!.click();
      await settle();
      http.expectOne(LIST).flush(listOf());
      await settle();
      expect(q('[data-testid=orders-error]')).toBeNull();
      expect(rows()).toHaveLength(1);
    });

    it('reads the list again when the counts poll sees another change token', async () => {
      await open();
      const store = TestBed.inject(OrderNoticesStore);
      store.summary.set(summaryOf({ changeToken: TOKEN_B }));
      await settle();
      http.expectOne(LIST).flush(listOf([noticeOf(), noticeOf({ id: 'n2', priority: 'URGENT' })]));
      await settle();
      expect(rows()).toHaveLength(2);
    });

    it('does not read the list again while the change token is the same', async () => {
      await open(listOf(), summaryOf({ changeToken: TOKEN_A }));
      const store = TestBed.inject(OrderNoticesStore);
      store.summary.set(summaryOf({ changeToken: TOKEN_A, asOf: '2026-11-06T05:48:00Z' }));
      await settle();
      http.expectNone(LIST);
    });

    it('keeps the list and says "Could not refresh. Showing data from 11:17 AM." when a refresh fails', async () => {
      await open();
      const store = TestBed.inject(OrderNoticesStore);
      store.summary.set(summaryOf({ changeToken: TOKEN_B }));
      await settle();
      http.expectOne(LIST).error(new ProgressEvent('error'));
      await settle();
      expect(rows()).toHaveLength(1);
      expect(squash(q('[data-testid=orders-stale]'))).toBe('Could not refresh. Showing data from 11:17 AM.');
    });

    it('says the same when the counts poll fails', async () => {
      await open();
      TestBed.inject(OrderNoticesStore).refreshFailed.set(true);
      await settle();
      expect(squash(q('[data-testid=orders-stale]'))).toContain('Could not refresh.');
    });
  });
});
