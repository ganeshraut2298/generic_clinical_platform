import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject, of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { SAFETY_REFRESH_MS, SafetyComponent } from './safety.component';
import { marOf, orderOf, slotOf } from '../mar/mar.fixtures';
import {
  ACTIONS,
  AS_OF,
  ROUND_DUE,
  ROUND_LATE,
  ROW,
  SLOT_8AM,
  asNurse,
  boardOf,
  eventOf,
  pageOf,
  problem,
  roundOf,
} from './safety.fixtures';
import { EscalationActionResult, EscalationRow, SafetyBoard, SafetyCounts } from './safety.model';

const IPD = '/api/v1/ipd';
const BOARD = `${IPD}/safety`;
const EVENTS = `${IPD}/clinical-events`;

const ACK: EscalationRow = {
  ...ROW,
  id: 'e2',
  patientName: 'Ravi Shah',
  bedCode: '202-B',
  ewsScore: 6,
  ewsRisk: 'MEDIUM',
  targetMinutes: 60,
  status: 'ACK',
  pastTarget: false,
  acknowledgedByName: 'Dr. Meera Kulkarni',
  canAcknowledge: false,
  version: 2,
};

const DONE: EscalationRow = {
  ...ROW,
  id: 'e3',
  patientName: 'Sunita More',
  bedCode: '303-C',
  ewsScore: 5,
  ewsRisk: 'MEDIUM',
  targetMinutes: 60,
  status: 'DONE',
  pastTarget: false,
  acknowledgedByName: 'Dr. Meera Kulkarni',
  canAcknowledge: false,
  canRespond: false,
  version: 3,
  response: {
    action: 'BEDSIDE_REVIEW',
    actionLabel: 'Reviewed at the bedside',
    note: 'Chest X-ray repeat ordered',
    closedByName: 'Dr. Meera Kulkarni',
    closedAt: '2026-11-05T05:20:00Z',
  },
};

const result = (escalation: EscalationRow, replayed = false): EscalationActionResult => ({
  asOf: AS_OF,
  replayed,
  escalation,
});

describe('SafetyComponent', () => {
  let fixture: ComponentFixture<SafetyComponent>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;
  let dialog: { open: ReturnType<typeof vi.fn> };
  let counts: SafetyCounts[];

  async function open(
    board: SafetyBoard | 'fail' = boardOf(),
    events = pageOf([eventOf()]),
    dialogResult: unknown = undefined,
  ): Promise<void> {
    snack = vi.fn();
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(dialogResult) })) };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snack, dismiss: vi.fn() } },
        { provide: MatDialog, useValue: dialog },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SafetyComponent);
    counts = [];
    fixture.componentInstance.counts.subscribe((c) => counts.push(c));
    fixture.detectChanges();
    const req = http.expectOne(BOARD);
    if (board === 'fail') {
      req.flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'Server Error' });
    } else {
      req.flush(board);
    }
    eventsReq().flush(events);
    await settle();
  }

  const eventsReq = (): TestRequest => http.expectOne((r) => r.url === EVENTS);

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const rows = () => Array.from(el().querySelectorAll<HTMLElement>('.sfrow[data-esc]'));
  const row = (id: string) => el().querySelector<HTMLElement>(`[data-esc=${id}]`)!;
  const act = (id: string, name: string) => row(id).querySelector<HTMLButtonElement>(`[data-act=${name}]`);
  const labels = (id: string) => Array.from(row(id).querySelectorAll('.sfacts button')).map((b) => squash(b));
  const toasts = () => snack.mock.calls.map((c) => c[0] as string);

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  describe('Doctor view', () => {
    it('shows, top to bottom, the red notice, the tile, the escalation panel and the audit trail', async () => {
      await open(boardOf({ escalations: [ROW, ACK, DONE] }));
      const blocks = Array.from(el().querySelectorAll('.safety > *')).map((n) =>
        n.tagName === 'APP-MEDICINE-ROUND' ? 'round' : n.className.split(' ')[0],
      );
      expect(blocks).toEqual(['notice', 'kpis', 'panel', 'round', 'panel']);
      expect(squash(el().querySelector('[data-testid=safety-late-notice]'))).toBe(
        '1 escalation past the response target. Asha Patil (EWS 8) raised 17 min ago. Acknowledge, then record what you did.',
      );
      expect(el().querySelector('[data-kpi=escalations] .eyebrow')!.textContent).toBe('Escalations open');
      expect(el().querySelector('[data-kpi=escalations] .v')!.textContent).toBe('2');
      expect(el().querySelector('[data-kpi=escalations] .v')!.classList).toContain('crit');
      expect(squash(el().querySelector('[data-kpi=escalations] .s'))).toBe('1 past target');
      expect(el().querySelector('[data-kpi=your-patients]')).toBeNull();
      expect(el().querySelector('h2#sf-esc-h')!.textContent!.trim()).toBe('Early warning escalations');
      expect(squash(el().querySelector('#sf-esc-h + small'))).toBe(
        'High score: doctor within 15 min. Medium: within 1 hour. A nurse can call the senior doctor if the target passes.',
      );
      expect(el().querySelector('h2#sf-aud-h')!.textContent!.trim()).toBe('Clinical audit trail');
      expect(squash(el().querySelector('#sf-aud-h + small'))).toBe(
        'Every order, check, dose, escalation and record opened. Entries cannot be edited or deleted.',
      );
    });

    it('shows no notice and "All within target" when nothing is past target', async () => {
      await open(boardOf({ escalations: [{ ...ROW, pastTarget: false }] }));
      expect(el().querySelector('[data-testid=safety-late-notice]')).toBeNull();
      expect(squash(el().querySelector('[data-kpi=escalations] .s'))).toBe('All within target');
      expect(el().querySelector('[data-kpi=escalations] .v')!.classList).not.toContain('crit');
    });

    it('writes plural heads and joins the patients with semicolons', async () => {
      await open(
        boardOf({
          escalations: [ROW, { ...ROW, id: 'e9', patientName: 'Ravi Shah', ewsScore: 6, waitingMinutes: 65, ewsRisk: 'MEDIUM' }],
        }),
      );
      expect(squash(el().querySelector('[data-testid=safety-late-notice]'))).toBe(
        '2 escalations past the response target. Asha Patil (EWS 8) raised 17 min ago; Ravi Shah (EWS 6) raised 1 h 5 min ago. Acknowledge, then record what you did.',
      );
    });

    it('shows each row in the mockup order with the badge, line, pill and the buttons of the row flags', async () => {
      await open(boardOf({ escalations: [ROW, ACK, DONE] }));
      expect(rows().length).toBe(3);

      const open1 = row('e1');
      expect(squash(open1.querySelector('.sfew'))).toBe('8 High');
      expect(open1.querySelector('.sfew')!.classList).toContain('crit');
      expect(squash(open1.querySelector('.sfmn b'))).toBe('Asha Patil · bed 101-A');
      expect(squash(open1.querySelector('[data-testid=esc-raised]'))).toBe('Raised by Priya Nair 17 min ago · target 15 min');
      expect(squash(open1.querySelector('[data-testid=esc-pill]'))).toBe('Overdue · not seen');
      expect(open1.classList).toContain('late');
      expect(labels('e1')).toEqual(['Acknowledge', 'Record response']);
      expect(act('e1', 'sfack')!.classList).toContain('mat-mdc-unelevated-button');
      expect(act('e1', 'sfresp')!.classList).toContain('mat-mdc-outlined-button');

      const ack = row('e2');
      expect(squash(ack.querySelector('.sfew'))).toBe('6 Medium');
      expect(ack.querySelector('.sfew')!.classList).toContain('warn');
      expect(squash(ack.querySelector('[data-testid=esc-raised]'))).toBe('Raised by Priya Nair 17 min ago · target 1 hour');
      expect(squash(ack.querySelector('[data-testid=esc-detail]'))).toBe(
        'Acknowledged by Dr. Meera Kulkarni · response not recorded yet',
      );
      expect(squash(ack.querySelector('[data-testid=esc-pill]'))).toBe('Doctor reviewing');
      expect(ack.querySelector('[data-testid=esc-pill]')!.classList).toContain('p-warn');
      expect(labels('e2')).toEqual(['Record response']);
      expect(act('e2', 'sfresp')!.classList).toContain('mat-mdc-unelevated-button');

      const done = row('e3');
      expect(done.classList).toContain('done');
      expect(squash(done.querySelector('[data-testid=esc-detail]'))).toBe(
        'Reviewed at the bedside · Chest X-ray repeat ordered · Dr. Meera Kulkarni',
      );
      expect(squash(done.querySelector('[data-testid=esc-pill]'))).toBe('Closed');
      expect(done.querySelector('[data-testid=esc-pill]')!.classList).toContain('p-good');
      expect(labels('e3')).toEqual([]);
    });

    it('shows the senior calls in the line', async () => {
      await open(boardOf({ escalations: [{ ...ROW, seniorCalls: 2 }] }));
      expect(squash(row('e1').querySelector('[data-testid=esc-raised]'))).toContain('· escalated to senior 2×');
    });

    it('keeps the server order of the rows', async () => {
      await open(boardOf({ escalations: [DONE, ROW] }));
      expect(rows().map((r) => r.dataset['esc'])).toEqual(['e3', 'e1']);
    });

    it('shows the empty text when there are no escalations, and no tile red', async () => {
      await open(boardOf({ escalations: [] }), pageOf([]));
      expect(squash(el().querySelector('.sfp .empty'))).toBe(
        'No escalations. Scores of 5 and above raise one automatically when vitals are saved.',
      );
      expect(el().querySelector('[data-kpi=escalations] .v')!.textContent).toBe('0');
    });

    it('gives every button the patient and bed in its name, so rows can be told apart', async () => {
      await open(boardOf({ escalations: [ROW] }));
      expect(act('e1', 'sfack')!.getAttribute('aria-label')).toBe('Acknowledge Asha Patil, bed 101-A');
      expect(act('e1', 'sfresp')!.getAttribute('aria-label')).toBe('Record response for Asha Patil, bed 101-A');
    });

    it('emits the counts of the board answer', async () => {
      await open(boardOf({ escalations: [ROW, ACK] }));
      expect(counts.map((c) => [c.open, c.pastTarget])).toEqual([[2, 1]]);
    });
  });

  describe('Nurse view', () => {
    const nurse = (rows: EscalationRow[]) =>
      boardOf({ view: 'NURSE', yourPatients: 4, escalations: rows.map(asNurse) });

    it('shows the second tile, the nurse notice ending and the audit subtitle', async () => {
      await open(nurse([ROW]));
      expect(squash(el().querySelector('[data-testid=safety-late-notice]'))).toBe(
        '1 escalation past the response target. Asha Patil (EWS 8) raised 17 min ago. Call the senior doctor now.',
      );
      expect(el().querySelector('[data-kpi=your-patients] .eyebrow')!.textContent).toBe('Your patients');
      expect(el().querySelector('[data-kpi=your-patients] .v')!.textContent).toBe('4');
      expect(squash(el().querySelector('[data-kpi=your-patients] .s'))).toBe('In your wards');
      expect(squash(el().querySelector('#sf-aud-h + small'))).toBe(
        'Your actions and your patients. Entries cannot be edited or deleted.',
      );
    });

    it('shows only Call senior on an Open row: primary when past target, secondary otherwise', async () => {
      await open(nurse([ROW, { ...ROW, id: 'e4', pastTarget: false, patientName: 'Gita Rao' }, ACK, DONE]));
      expect(labels('e1')).toEqual(['Call senior']);
      expect(act('e1', 'sfup')!.classList).toContain('mat-mdc-unelevated-button');
      expect(labels('e4')).toEqual(['Call senior']);
      expect(act('e4', 'sfup')!.classList).toContain('mat-mdc-outlined-button');
      expect(labels('e2')).toEqual([]);
      expect(labels('e3')).toEqual([]);
      expect(act('e1', 'sfack')).toBeNull();
      expect(act('e1', 'sfresp')).toBeNull();
    });
  });

  describe('Acknowledge', () => {
    it('posts the version, toasts with the patient name and reloads the board and the trail', async () => {
      await open(boardOf({ escalations: [ROW] }));
      act('e1', 'sfack')!.click();
      const req = http.expectOne(`${IPD}/escalations/e1/acknowledge`);
      expect(req.request.body).toEqual({ version: 1 });
      req.flush(result({ ...ROW, status: 'ACK', version: 2, pastTarget: false, acknowledgedByName: 'Dr. Meera Kulkarni' }));
      await settle();
      expect(toasts()).toEqual(['Acknowledged. Record what you did for Asha Patil after you see the patient.']);
      http.expectOne(BOARD).flush(boardOf({ escalations: [{ ...ROW, status: 'ACK', version: 2, pastTarget: false, acknowledgedByName: 'Dr. Meera Kulkarni', canAcknowledge: false }] }));
      eventsReq().flush(pageOf([eventOf({ id: 'x2', text: 'Acknowledged escalation after 17 min' }), eventOf()]));
      await settle();
      expect(squash(row('e1').querySelector('[data-testid=esc-pill]'))).toBe('Doctor reviewing');
      expect(el().querySelectorAll('.sfai').length).toBe(2);
    });

    it('does not show the row as changed until the server has answered, and ignores a second tap', async () => {
      await open(boardOf({ escalations: [ROW] }));
      const button = act('e1', 'sfack')!;
      button.click();
      button.click();
      await settle();
      expect(squash(row('e1').querySelector('[data-testid=esc-pill]'))).toBe('Overdue · not seen');
      expect(act('e1', 'sfack')!.disabled).toBe(true);
      expect(act('e1', 'sfresp')!.disabled).toBe(true);
      const req = http.expectOne(`${IPD}/escalations/e1/acknowledge`);
      req.flush(result({ ...ROW, status: 'ACK' }));
      http.expectOne(BOARD).flush(boardOf());
      eventsReq().flush(pageOf());
      await settle();
      expect(act('e1', 'sfack')!.disabled).toBe(false);
    });

    it('takes a replayed 200 as a success with the same toast', async () => {
      await open(boardOf({ escalations: [ROW] }));
      act('e1', 'sfack')!.click();
      http.expectOne(`${IPD}/escalations/e1/acknowledge`).flush(result({ ...ROW, status: 'ACK', version: 2 }, true));
      http.expectOne(BOARD).flush(boardOf());
      eventsReq().flush(pageOf());
      await settle();
      expect(toasts()).toEqual(['Acknowledged. Record what you did for Asha Patil after you see the patient.']);
      expect(el().querySelector('[data-testid=safety-action-notice]')).toBeNull();
    });

    it('on 409 escalation-state shows the server message and reloads the board', async () => {
      await open(boardOf({ escalations: [ROW] }));
      act('e1', 'sfack')!.click();
      http
        .expectOne(`${IPD}/escalations/e1/acknowledge`)
        .flush(problem('escalation-state', 409, 'Dr. Anil Rao already acknowledged this escalation.'), {
          status: 409,
          statusText: 'Conflict',
        });
      await settle();
      expect(squash(el().querySelector('[data-testid=safety-action-notice]'))).toContain(
        'Dr. Anil Rao already acknowledged this escalation.',
      );
      expect(toasts()).toEqual([]);
      const reloaded = { ...ROW, status: 'ACK' as const, version: 2, pastTarget: false, acknowledgedByName: 'Dr. Anil Rao', canAcknowledge: false };
      http.expectOne(BOARD).flush(boardOf({ escalations: [reloaded] }));
      eventsReq().flush(pageOf());
      await settle();
      expect(squash(row('e1').querySelector('[data-testid=esc-pill]'))).toBe('Doctor reviewing');
      expect(squash(el().querySelector('[data-testid=safety-action-notice]'))).toContain('Dr. Anil Rao already');
    });

    it('on 409 escalation-changed shows the new score message and reloads', async () => {
      await open(boardOf({ escalations: [ROW] }));
      act('e1', 'sfack')!.click();
      http
        .expectOne(`${IPD}/escalations/e1/acknowledge`)
        .flush(problem('escalation-changed', 409, 'The score is now EWS 9 (high). Review it and try again.'), {
          status: 409,
          statusText: 'Conflict',
        });
      await settle();
      expect(squash(el().querySelector('[data-testid=safety-action-notice]'))).toContain(
        'The score is now EWS 9 (high). Review it and try again.',
      );
      http.expectOne(BOARD).flush(boardOf({ escalations: [{ ...ROW, ewsScore: 9, version: 2 }] }));
      eventsReq().flush(pageOf());
      await settle();
      expect(squash(row('e1').querySelector('.sfew'))).toBe('9 High');
      // The next tap carries the version of the reloaded row.
      act('e1', 'sfack')!.click();
      expect(http.expectOne(`${IPD}/escalations/e1/acknowledge`).request.body).toEqual({ version: 2 });
    });

    it('on 404 shows the server message and reloads; on 403 only shows the message', async () => {
      await open(boardOf({ escalations: [ROW] }));
      act('e1', 'sfack')!.click();
      http
        .expectOne(`${IPD}/escalations/e1/acknowledge`)
        .flush(problem('not-found', 404, 'This patient is not admitted any more.'), { status: 404, statusText: 'Not Found' });
      await settle();
      expect(squash(el().querySelector('[data-testid=safety-action-notice]'))).toContain(
        'This patient is not admitted any more.',
      );
      http.expectOne(BOARD).flush(boardOf({ escalations: [] }));
      eventsReq().flush(pageOf([]));
      await settle();
    });

    it('on 403 or a network failure shows a message and keeps the board', async () => {
      await open(boardOf({ escalations: [ROW] }));
      act('e1', 'sfack')!.click();
      http
        .expectOne(`${IPD}/escalations/e1/acknowledge`)
        .flush(problem('forbidden', 403, 'Only a doctor can do this.'), { status: 403, statusText: 'Forbidden' });
      await settle();
      expect(squash(el().querySelector('[data-testid=safety-action-notice]'))).toContain('Only a doctor can do this.');
      expect(rows().length).toBe(1);

      act('e1', 'sfack')!.click();
      http.expectOne(`${IPD}/escalations/e1/acknowledge`).error(new ProgressEvent('error'));
      await settle();
      expect(squash(el().querySelector('[data-testid=safety-action-notice]'))).toContain('We could not do that. Try again.');
      expect(act('e1', 'sfack')!.disabled).toBe(false);
    });

    it('Dismiss removes the notice', async () => {
      await open(boardOf({ escalations: [ROW] }));
      act('e1', 'sfack')!.click();
      http
        .expectOne(`${IPD}/escalations/e1/acknowledge`)
        .flush(problem('forbidden', 403, 'Only a doctor can do this.'), { status: 403, statusText: 'Forbidden' });
      await settle();
      Array.from(el().querySelectorAll<HTMLButtonElement>('[data-testid=safety-action-notice] button'))
        .find((b) => squash(b) === 'Dismiss')!
        .click();
      await settle();
      expect(el().querySelector('[data-testid=safety-action-notice]')).toBeNull();
    });
  });

  describe('Call senior', () => {
    const nurse = (rows: EscalationRow[]) => boardOf({ view: 'NURSE', yourPatients: 3, escalations: rows.map(asNurse) });

    it('sends the version and the senior calls the nurse saw, and toasts that no alert is sent', async () => {
      await open(nurse([{ ...ROW, seniorCalls: 1, version: 4 }]));
      act('e1', 'sfup')!.click();
      const req = http.expectOne(`${IPD}/escalations/e1/call-senior`);
      expect(req.request.body).toEqual({ version: 4, seniorCalls: 1 });
      req.flush(result({ ...ROW, seniorCalls: 2, version: 4 }));
      await settle();
      expect(toasts()).toEqual(['Call recorded for Asha Patil. No alert is sent: phone the senior doctor now.']);
      http.expectOne(BOARD).flush(nurse([{ ...ROW, seniorCalls: 2, version: 4 }]));
      eventsReq().flush(pageOf());
      await settle();
      expect(squash(row('e1').querySelector('[data-testid=esc-raised]'))).toContain('· escalated to senior 2×');
      // A second deliberate call on fresh data carries the new count.
      act('e1', 'sfup')!.click();
      expect(http.expectOne(`${IPD}/escalations/e1/call-senior`).request.body).toEqual({ version: 4, seniorCalls: 2 });
    });

    it('toasts the same on a replayed 200 (same user within 60 s, or another nurse already called)', async () => {
      await open(nurse([ROW]));
      act('e1', 'sfup')!.click();
      http.expectOne(`${IPD}/escalations/e1/call-senior`).flush(result(ROW, true));
      http.expectOne(BOARD).flush(nurse([ROW]));
      eventsReq().flush(pageOf());
      await settle();
      expect(toasts()).toEqual(['Call recorded for Asha Patil. No alert is sent: phone the senior doctor now.']);
      expect(el().querySelector('[data-testid=safety-action-notice]')).toBeNull();
    });

    it('is off while the request runs (one call per tap)', async () => {
      await open(nurse([ROW]));
      const button = act('e1', 'sfup')!;
      button.click();
      button.click();
      await settle();
      expect(act('e1', 'sfup')!.disabled).toBe(true);
      http.expectOne(`${IPD}/escalations/e1/call-senior`).flush(result(ROW, true));
      http.expectOne(BOARD).flush(nurse([ROW]));
      eventsReq().flush(pageOf());
      await settle();
      expect(toasts().length).toBe(1);
    });

    it('on 409 escalation-state shows the message and reloads', async () => {
      await open(nurse([ROW]));
      act('e1', 'sfup')!.click();
      http
        .expectOne(`${IPD}/escalations/e1/call-senior`)
        .flush(problem('escalation-state', 409, 'This escalation is already closed.'), { status: 409, statusText: 'Conflict' });
      await settle();
      expect(squash(el().querySelector('[data-testid=safety-action-notice]'))).toContain('This escalation is already closed.');
      http.expectOne(BOARD).flush(nurse([{ ...DONE, id: 'e1', response: DONE.response }]));
      eventsReq().flush(pageOf());
      await settle();
      expect(labels('e1')).toEqual([]);
    });
  });

  describe('Record response', () => {
    it('opens the sheet with the row, the actions and the board reload hook', async () => {
      await open(boardOf({ escalations: [ROW] }));
      act('e1', 'sfresp')!.click();
      expect(dialog.open).toHaveBeenCalledTimes(1);
      const config = dialog.open.mock.calls[0][1] as {
        data: { row: EscalationRow; actions: unknown; live: () => EscalationRow | null; onConflict: () => void };
      };
      expect(config.data.row).toEqual(ROW);
      expect(config.data.actions).toEqual(ACTIONS);
      expect(config.data.live()!.id).toBe('e1');
      config.data.onConflict();
      http.expectOne(BOARD).flush(boardOf({ escalations: [{ ...ROW, version: 5 }] }));
      eventsReq().flush(pageOf());
      await settle();
      expect(config.data.live()!.version).toBe(5);
    });

    it('toasts and reloads after the sheet closed the escalation', async () => {
      await open(boardOf({ escalations: [ROW] }), pageOf(), result({ ...DONE, id: 'e1', patientName: 'Asha Patil' }));
      act('e1', 'sfresp')!.click();
      expect(toasts()).toEqual(['Escalation for Asha Patil closed and recorded']);
      http.expectOne(BOARD).flush(boardOf({ escalations: [{ ...DONE, id: 'e1' }] }));
      eventsReq().flush(pageOf());
      await settle();
      expect(squash(row('e1').querySelector('[data-testid=esc-pill]'))).toBe('Closed');
    });

    it('moves focus to the panel heading after a successful close, one tick after the dialog gave focus back', async () => {
      await open(boardOf({ escalations: [ROW] }), pageOf(), result({ ...DONE, id: 'e1', patientName: 'Asha Patil' }));
      const heading = el().querySelector<HTMLElement>('#sf-esc-h')!;
      expect(heading.getAttribute('tabindex')).toBe('-1');
      expect(el().querySelector('#sf-aud-h')!.getAttribute('tabindex')).toBe('-1');
      act('e1', 'sfresp')!.click();
      http.expectOne(BOARD).flush(boardOf({ escalations: [{ ...DONE, id: 'e1' }] }));
      eventsReq().flush(pageOf());
      await settle();
      await new Promise<void>((resolve) => setTimeout(resolve, 5));
      expect(document.activeElement).toBe(el().querySelector('#sf-esc-h'));
    });

    it('does not move focus when the sheet was cancelled', async () => {
      await open(boardOf({ escalations: [ROW] }));
      act('e1', 'sfresp')!.click();
      await new Promise<void>((resolve) => setTimeout(resolve, 5));
      expect(document.activeElement).not.toBe(el().querySelector('#sf-esc-h'));
    });

    it('does nothing more when the sheet was cancelled', async () => {
      await open(boardOf({ escalations: [ROW] }));
      act('e1', 'sfresp')!.click();
      expect(toasts()).toEqual([]);
    });
  });

  describe('Clinical audit trail', () => {
    it('shows kind icon, bold text, user, patient and bed, and the time', async () => {
      await open(
        boardOf(),
        pageOf([
          eventOf(),
          eventOf({ id: 'x2', kind: 'info', text: 'Recorded vitals · EWS 8 (high)', actorName: 'Priya Nair', at: '2026-10-24T05:35:00Z' }),
          eventOf({ id: 'x3', kind: 'view', text: 'Opened the clinical record', admissionId: null, patientName: null, bedCode: null }),
        ]),
      );
      const items = Array.from(el().querySelectorAll<HTMLElement>('.sfai'));
      expect(items.length).toBe(3);
      expect(items[0].className).toContain('k-esc');
      expect(squash(items[0].querySelector('b'))).toBe('Acknowledged escalation after 20 min');
      expect(squash(items[0].querySelector('small'))).toBe('Dr. Meera Kulkarni · Asha Patil · bed 101-A');
      expect(squash(items[0].querySelector('.faint'))).toBe('11:05 AM');
      expect(squash(items[1].querySelector('.faint'))).toBe('24 Oct, 11:05 AM');
      expect(items[1].className).toContain('k-info');
      expect(squash(items[2].querySelector('small'))).toBe('Dr. Meera Kulkarni');
      expect(items[0].querySelector('app-icon')).not.toBeNull();
    });

    it('asks for the first 14 and shows "Nothing recorded yet." when empty', async () => {
      snack = vi.fn();
      await open(boardOf(), pageOf([]));
      expect(squash(el().querySelector('#sf-aud-h')!.closest('.sfp')!.querySelector('.empty'))).toBe('Nothing recorded yet.');
      expect(el().querySelector('[data-act=events-more]')).toBeNull();
    });

    it('requests limit 14 on the first page', async () => {
      snack = vi.fn();
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(),
          provideHttpClientTesting(),
          { provide: MatSnackBar, useValue: { open: vi.fn(), dismiss: vi.fn() } },
          { provide: MatDialog, useValue: { open: vi.fn() } },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(SafetyComponent);
      fixture.detectChanges();
      http.expectOne(BOARD).flush(boardOf());
      const req = eventsReq();
      expect(req.request.params.get('limit')).toBe('14');
      expect(req.request.params.has('cursor')).toBe(false);
      req.flush(pageOf());
    });

    it('"Show earlier entries" loads 50 more with the cursor, and goes when there are no more', async () => {
      await open(boardOf(), pageOf([eventOf({ id: 'x1' })], 'cur1'));
      const more = () => el().querySelector<HTMLButtonElement>('[data-act=events-more]');
      expect(squash(more())).toBe('Show earlier entries');
      more()!.click();
      await settle();
      expect(more()!.disabled).toBe(true);
      const req = eventsReq();
      expect(req.request.params.get('limit')).toBe('50');
      expect(req.request.params.get('cursor')).toBe('cur1');
      req.flush(pageOf([eventOf({ id: 'x0', text: 'Earlier entry' })], null));
      await settle();
      expect(el().querySelectorAll('.sfai').length).toBe(2);
      expect(squash(el().querySelectorAll('.sfai')[1].querySelector('b'))).toBe('Earlier entry');
      expect(more()).toBeNull();
    });

    it('keeps the earlier entries when the 60-second reload brings new ones', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open(boardOf(), pageOf([eventOf({ id: 'x2' }), eventOf({ id: 'x1' })], 'cur1'));
      el().querySelector<HTMLButtonElement>('[data-act=events-more]')!.click();
      eventsReq().flush(pageOf([eventOf({ id: 'x0' })], null));
      await settle();
      expect(el().querySelectorAll('.sfai').length).toBe(3);
      vi.advanceTimersByTime(SAFETY_REFRESH_MS);
      http.expectOne(BOARD).flush(boardOf());
      eventsReq().flush(pageOf([eventOf({ id: 'x3' }), eventOf({ id: 'x2' })], 'cur9'));
      await settle();
      expect(Array.from(el().querySelectorAll('.sfai')).length).toBe(4);
    });

    it('shows an error with the next try when the first page cannot load, and keeps the board', async () => {
      snack = vi.fn();
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
          { provide: MatSnackBar, useValue: { open: vi.fn(), dismiss: vi.fn() } },
          { provide: MatDialog, useValue: { open: vi.fn() } },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(SafetyComponent);
      fixture.detectChanges();
      http.expectOne(BOARD).flush(boardOf());
      eventsReq().flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'Server Error' });
      await settle();
      expect(squash(el().querySelector('[data-testid=events-error]'))).toContain('Try later.');
      expect(rows().length).toBe(1);
    });
  });

  describe('Medicine round (SAH-35)', () => {
    const kpiNames = () => Array.from(el().querySelectorAll('.kpis .eyebrow')).map((n) => squash(n));
    const panels = () => Array.from(el().querySelectorAll('.safety h2')).map((n) => squash(n));
    const gives = () => Array.from(el().querySelectorAll<HTMLButtonElement>('[data-testid=round-give]'));
    const roundRows = () => el().querySelectorAll('[data-testid=round-row]');
    const MAR_A1 = `${IPD}/admissions/a1/mar`;
    const marForLate = () =>
      marOf({
        admissionId: 'a1',
        orders: [orderOf({ orderId: 'o2', slots: [slotOf({ slotAt: SLOT_8AM, state: 'LATE', lateHours: 3 })] })],
      });
    const reloaded = async (board: SafetyBoard) => {
      http.expectOne(BOARD).flush(board);
      eventsReq().flush(pageOf());
      await settle();
    };
    const afterTick = () => new Promise<void>((r) => setTimeout(r, 0));

    it('the tile "Doses due or overdue" shows Due plus Late, "N overdue", red when someone is late', async () => {
      await open(boardOf({ escalations: [] }));
      const tile = el().querySelector('[data-kpi=doses]')!;
      expect(squash(tile.querySelector('.eyebrow'))).toBe('Doses due or overdue');
      expect(squash(tile.querySelector('.v'))).toBe('2');
      expect(tile.querySelector('.v')!.classList).toContain('crit');
      expect(squash(tile.querySelector('.s'))).toBe('1 overdue');
    });

    it('the tile says "0 overdue", is not red when nobody is late, and shows the full count, not the rows', async () => {
      const board = boardOf({ medicineRound: roundOf({ rows: [ROUND_DUE] }) });
      board.counts = { ...board.counts, dosesDue: 340, dosesLate: 0 };
      await open(board);
      const tile = el().querySelector('[data-kpi=doses]')!;
      expect(squash(tile.querySelector('.v'))).toBe('340');
      expect(tile.querySelector('.v')!.classList).not.toContain('crit');
      expect(squash(tile.querySelector('.s'))).toBe('0 overdue');
    });

    it('puts the tile after Escalations open and before Your patients (nurse), and the panel before the audit trail', async () => {
      await open(boardOf({ view: 'NURSE', yourPatients: 4, escalations: [asNurse(ROW)] }));
      expect(kpiNames()).toEqual(['Escalations open', 'Doses due or overdue', 'Your patients']);
      expect(panels()).toEqual(['Early warning escalations', 'Medicine round', 'Clinical audit trail']);
    });

    it('a doctor reads the same tile and rows but gets no Give and no line', async () => {
      await open(boardOf({ medicineRound: roundOf({ canGive: false, giveLockReason: 'ROLE' }) }));
      expect(roundRows().length).toBe(2);
      expect(gives()).toEqual([]);
      expect(el().querySelector('[data-testid=round-readonly]')).toBeNull();
    });

    it('needs no request of its own: the board answer carries the round', async () => {
      await open();
      http.expectNone((r) => r.url.includes('medicine-round'));
      expect(roundRows().length).toBe(2);
    });

    it('Give: one MAR read, then the Give sheet; after a dose the board reloads, the user stays, focus goes to the Give of the next row when the row is gone', async () => {
      await open();
      dialog.open.mockReturnValue({ afterClosed: () => of({ mar: marForLate() }) });
      document.body.appendChild(el());
      gives()[0].click();
      http.expectOne(MAR_A1).flush(marForLate());
      await settle();
      expect(dialog.open).toHaveBeenCalledTimes(1);
      await reloaded(boardOf({ medicineRound: roundOf({ rows: [ROUND_DUE] }) }));
      expect(roundRows().length).toBe(1);
      expect(el().querySelector('[data-kpi=doses] .v')!.textContent!.trim()).toBe('1');
      await afterTick();
      expect(document.activeElement).toBe(gives()[0]);
      expect(counts.at(-1)!.dosesLate).toBe(0);
      el().remove();
    });

    it('Give: the focus returns to the same Give button when the row is still on the board', async () => {
      await open();
      dialog.open.mockReturnValue({ afterClosed: () => of({ reload: true }) });
      document.body.appendChild(el());
      gives()[1].click();
      http.expectOne(`${IPD}/admissions/a2/mar`).flush(
        marOf({
          admissionId: 'a2',
          orders: [orderOf({ orderId: 'o1', slots: [slotOf({ slotAt: ROUND_DUE.slotAt, state: 'DUE' })] })],
        }),
      );
      await settle();
      await reloaded(boardOf());
      await afterTick();
      expect(document.activeElement).toBe(gives()[1]);
      el().remove();
    });

    it('Give: a slot recorded meanwhile opens no sheet, shows the text and reloads the board', async () => {
      await open();
      gives()[0].click();
      http.expectOne(MAR_A1).flush(
        marOf({
          admissionId: 'a1',
          orders: [orderOf({ orderId: 'o2', slots: [slotOf({ slotAt: SLOT_8AM, state: 'GIVEN', canGive: false })] })],
        }),
      );
      await settle();
      expect(dialog.open).not.toHaveBeenCalled();
      expect(toasts()).toEqual(['This dose is already recorded.']);
      await reloaded(boardOf({ medicineRound: roundOf({ rows: [ROUND_DUE] }) }));
      expect(roundRows().length).toBe(1);
    });

    it('Give: a failed reload after an answer keeps the old rows and shows the stale line', async () => {
      await open();
      dialog.open.mockReturnValue({ afterClosed: () => of({ reload: true }) });
      gives()[0].click();
      http.expectOne(MAR_A1).flush(marForLate());
      await settle();
      http.expectOne(BOARD).flush(problem('server-error', 500, 'x'), { status: 500, statusText: 'x' });
      eventsReq().flush(pageOf());
      await settle();
      expect(el().querySelector('[data-testid=safety-stale]')).not.toBeNull();
      expect(roundRows().length).toBe(2);
    });

    it('the 60-second reload brings what changed without anyone acting: a Due row turns Late', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open(boardOf({ medicineRound: roundOf({ rows: [ROUND_DUE] }) }));
      expect(squash(el().querySelector('[data-testid=round-pill]'))).toBe('Due now');
      vi.advanceTimersByTime(SAFETY_REFRESH_MS);
      await reloaded(
        boardOf({ medicineRound: roundOf({ rows: [{ ...ROUND_DUE, state: 'LATE', lateHours: 1 }, ROUND_LATE] }) }),
      );
      expect(Array.from(el().querySelectorAll('[data-testid=round-pill]')).map((n) => squash(n))).toEqual([
        '1 h late',
        '3 h late',
      ]);
    });

    it('emits the counts with the dose numbers so the tab number follows', async () => {
      await open();
      expect(counts.at(-1)).toMatchObject({ dosesDue: 1, dosesLate: 1 });
    });
  });

  describe('Loading, refresh and errors', () => {
    it('shows Loading until the first board arrives', () => {
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(),
          provideHttpClientTesting(),
          { provide: MatSnackBar, useValue: { open: vi.fn(), dismiss: vi.fn() } },
          { provide: MatDialog, useValue: { open: vi.fn() } },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(SafetyComponent);
      fixture.detectChanges();
      expect(squash(el())).toBe('Loading the Safety board');
      expect(el().querySelector('[aria-busy=true]')).not.toBeNull();
      http.expectOne(BOARD);
      eventsReq();
    });

    it('shows the load error with Try again', async () => {
      await open('fail');
      expect(squash(el().querySelector('.notice.err'))).toContain('Try later.');
      Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => squash(b) === 'Try again')!.click();
      http.expectOne(BOARD).flush(boardOf());
      eventsReq().flush(pageOf());
      await settle();
      expect(rows().length).toBe(1);
    });

    it('clears the load error when a later 60-second reload succeeds', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open('fail');
      expect(el().querySelector('.notice.err')).not.toBeNull();
      expect(rows().length).toBe(0);
      vi.advanceTimersByTime(SAFETY_REFRESH_MS);
      http.expectOne(BOARD).flush(boardOf({ escalations: [ROW] }));
      eventsReq().flush(pageOf());
      await settle();
      expect(el().querySelector('.notice.err')).toBeNull();
      expect(rows().length).toBe(1);
    });

    describe('hidden browser tab', () => {
      const hide = (hidden: boolean) => {
        Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
        document.dispatchEvent(new Event('visibilitychange'));
      };
      afterEach(() => {
        delete (document as unknown as Record<string, unknown>)['hidden'];
      });

      it('does not reload while hidden and reloads at once when visible again', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open(boardOf({ escalations: [ROW] }));
        hide(true);
        vi.advanceTimersByTime(SAFETY_REFRESH_MS * 3);
        http.expectNone(BOARD);
        http.expectNone((r) => r.url === EVENTS);
        hide(false);
        http.expectOne(BOARD).flush(boardOf({ escalations: [ROW] }));
        eventsReq().flush(pageOf());
        await settle();
        vi.advanceTimersByTime(SAFETY_REFRESH_MS);
        http.expectOne(BOARD).flush(boardOf({ escalations: [ROW] }));
        eventsReq().flush(pageOf());
        await settle();
      });
    });

    it('reloads every 60 seconds without the Loading text', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open(boardOf({ escalations: [ROW] }));
      vi.advanceTimersByTime(SAFETY_REFRESH_MS - 1000);
      http.expectNone(BOARD);
      vi.advanceTimersByTime(1000);
      await settle();
      expect(el().querySelector('[aria-busy=true]')).toBeNull();
      expect(rows().length).toBe(1);
      http.expectOne(BOARD).flush(boardOf({ escalations: [{ ...ROW, minutesSinceRaised: 18, waitingMinutes: 18 }] }));
      eventsReq().flush(pageOf());
      await settle();
      expect(squash(row('e1').querySelector('[data-testid=esc-raised]'))).toContain('18 min ago');
      expect(counts.length).toBe(2);
    });

    it('keeps the last data and warns with the time of the last answer when a reload fails, then recovers', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open(boardOf({ escalations: [ROW] }));
      expect(el().querySelector('[data-testid=safety-stale]')).toBeNull();
      vi.advanceTimersByTime(SAFETY_REFRESH_MS);
      http.expectOne(BOARD).flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'Server Error' });
      eventsReq().error(new ProgressEvent('error'));
      await settle();
      expect(squash(el().querySelector('[data-testid=safety-stale]'))).toBe('Could not refresh. Showing data from 11:17 AM.');
      expect(rows().length).toBe(1);
      expect(el().querySelector('[data-testid=events-error]')).toBeNull();

      vi.advanceTimersByTime(SAFETY_REFRESH_MS);
      http.expectOne(BOARD).flush(boardOf({ escalations: [ROW] }));
      eventsReq().flush(pageOf());
      await settle();
      expect(el().querySelector('[data-testid=safety-stale]')).toBeNull();
    });

    it('a 403 on the 60-second reload clears the board and shows the refusal of the first load (G-2)', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open(boardOf({ escalations: [ROW] }));
      expect(rows().length).toBe(1);
      vi.advanceTimersByTime(SAFETY_REFRESH_MS);
      http.expectOne(BOARD).flush(problem('forbidden', 403, 'You cannot see the Safety board.'), { status: 403, statusText: 'Forbidden' });
      expect(eventsReq().cancelled, 'the audit-trail reload is dropped too').toBe(true);
      await settle();
      expect(rows().length).toBe(0);
      expect(el().querySelector('[data-testid=safety-stale]')).toBeNull();
      expect(squash(el().querySelector('.notice.err'))).toContain('You cannot see the Safety board.');
      expect(el().querySelector('[data-testid=safety-board]')).toBeNull();
    });

    it('a 403 module-off on the reload clears the board and shows the server message (G-2)', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open(boardOf({ escalations: [ROW] }));
      vi.advanceTimersByTime(SAFETY_REFRESH_MS);
      http.expectOne(BOARD).flush(problem('module-off', 403, 'In-patient care is off.'), { status: 403, statusText: 'Forbidden' });
      eventsReq();
      await settle();
      expect(rows().length).toBe(0);
      expect(squash(el().querySelector('.notice.err'))).toContain('In-patient care is off.');
    });

    it('reads nothing on later ticks after a 403 until Try again, then loads as the first load (SAH-40, rule 6)', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open(boardOf({ escalations: [ROW] }));
      vi.advanceTimersByTime(SAFETY_REFRESH_MS);
      http.expectOne(BOARD).flush(problem('forbidden', 403, 'You cannot see the Safety board.'), { status: 403, statusText: 'Forbidden' });
      eventsReq();
      await settle();
      expect(rows().length).toBe(0);

      vi.advanceTimersByTime(SAFETY_REFRESH_MS * 3);
      http.expectNone(BOARD);
      http.expectNone((r) => r.url === EVENTS);
      await settle();
      expect(rows().length).toBe(0);
      expect(squash(el().querySelector('.notice.err'))).toContain('You cannot see the Safety board.');

      el().querySelector<HTMLButtonElement>('.notice.err button')!.click();
      await settle();
      expect(el().textContent).toContain('Loading the Safety board');
      http.expectOne(BOARD).flush(boardOf({ escalations: [ROW] }));
      eventsReq().flush(pageOf());
      await settle();
      expect(rows().length).toBe(1);

      vi.advanceTimersByTime(SAFETY_REFRESH_MS);
      http.expectOne(BOARD).flush(boardOf({ escalations: [ROW] }));
      eventsReq().flush(pageOf());
    });

    describe('after a refusal nothing reads or stays open (SAH-40, D-1)', () => {
      const FORBIDDEN = () => problem('forbidden', 403, 'You cannot see the Safety board.');
      const REFUSED = { status: 403, statusText: 'Forbidden' };
      let closed: Subject<EscalationActionResult | undefined>;
      let sheetClose: ReturnType<typeof vi.fn>;

      /** Opens the Response sheet of e1 with a sheet that stays open until `closed` emits. */
      async function withOpenSheet(): Promise<{ data: { onConflict: () => void } }> {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open(boardOf({ escalations: [ROW] }));
        closed = new Subject<EscalationActionResult | undefined>();
        sheetClose = vi.fn();
        dialog.open.mockReturnValue({ afterClosed: () => closed.asObservable(), close: sheetClose });
        act('e1', 'sfresp')!.click();
        return dialog.open.mock.calls[0][1] as { data: { onConflict: () => void } };
      }

      async function refuseByTimer(): Promise<void> {
        vi.advanceTimersByTime(SAFETY_REFRESH_MS);
        http.expectOne(BOARD).flush(FORBIDDEN(), REFUSED);
        eventsReq();
        await settle();
      }

      const roundAnswered = () =>
        (fixture.componentInstance as unknown as { roundAnswered(): void }).roundAnswered();

      it('closes the open Response sheet when a reload is refused', async () => {
        await withOpenSheet();
        expect(sheetClose).not.toHaveBeenCalled();
        await refuseByTimer();
        expect(sheetClose).toHaveBeenCalledTimes(1);
        expect(rows().length).toBe(0);
        expect(squash(el().querySelector('.notice.err'))).toContain('You cannot see the Safety board.');
      });

      it('closes the open Response sheet when the board is refused module-off too', async () => {
        await withOpenSheet();
        vi.advanceTimersByTime(SAFETY_REFRESH_MS);
        http.expectOne(BOARD).flush(problem('module-off', 403, 'In-patient care is off.'), REFUSED);
        eventsReq();
        await settle();
        expect(sheetClose).toHaveBeenCalledTimes(1);
      });

      it('closes no sheet on a 5xx (the old board stays)', async () => {
        await withOpenSheet();
        vi.advanceTimersByTime(SAFETY_REFRESH_MS);
        http.expectOne(BOARD).flush(problem('server-error', 500, 'x'), { status: 500, statusText: 'x' });
        eventsReq();
        await settle();
        expect(sheetClose).not.toHaveBeenCalled();
        expect(rows().length).toBe(1);
      });

      it('does not read the board when the sheet reports a conflict after the refusal', async () => {
        const config = await withOpenSheet();
        await refuseByTimer();
        config.data.onConflict();
        http.expectNone(BOARD);
        http.expectNone((r) => r.url === EVENTS);
      });

      it('does not read the board when a sheet saves after the refusal, and the notice stays', async () => {
        await withOpenSheet();
        await refuseByTimer();
        closed.next(result({ ...DONE, id: 'e1', patientName: 'Asha Patil' }));
        await settle();
        http.expectNone(BOARD);
        http.expectNone((r) => r.url === EVENTS);
        expect(rows().length).toBe(0);
        expect(squash(el().querySelector('.notice.err'))).toContain('You cannot see the Safety board.');
        vi.advanceTimersByTime(SAFETY_REFRESH_MS * 2);
        http.expectNone(BOARD);
      });

      it('does not read the board when an action answers 409 after the refusal', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open(boardOf({ escalations: [ROW] }));
        act('e1', 'sfack')!.click();
        const call = http.expectOne(`${IPD}/escalations/e1/acknowledge`);
        vi.advanceTimersByTime(SAFETY_REFRESH_MS);
        http.expectOne(BOARD).flush(FORBIDDEN(), REFUSED);
        eventsReq();
        call.flush(problem('escalation-state', 409, 'This escalation is already closed.'), {
          status: 409,
          statusText: 'Conflict',
        });
        await settle();
        http.expectNone(BOARD);
        expect(rows().length).toBe(0);
      });

      it('does not read the board when the Medicine round answers after the refusal', async () => {
        await withOpenSheet();
        await refuseByTimer();
        roundAnswered();
        await settle();
        http.expectNone(BOARD);
        http.expectNone((r) => r.url === EVENTS);
        expect(el().querySelector('[data-testid=safety-board]')).toBeNull();
        expect(squash(el().querySelector('.notice.err'))).toContain('You cannot see the Safety board.');
      });

      it('reads again only after Try again, and the timer then goes on reading', async () => {
        const config = await withOpenSheet();
        await refuseByTimer();
        config.data.onConflict();
        http.expectNone(BOARD);
        el().querySelector<HTMLButtonElement>('.notice.err button')!.click();
        http.expectOne(BOARD).flush(boardOf({ escalations: [ROW] }));
        eventsReq().flush(pageOf());
        await settle();
        expect(rows().length).toBe(1);
        config.data.onConflict();
        http.expectOne(BOARD).flush(boardOf({ escalations: [ROW] }));
        eventsReq().flush(pageOf());
        vi.advanceTimersByTime(SAFETY_REFRESH_MS);
        http.expectOne(BOARD).flush(boardOf({ escalations: [ROW] }));
        eventsReq().flush(pageOf());
      });

      it('drops the answer of "Show earlier entries" that arrives after the refusal', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open(boardOf(), pageOf([eventOf({ id: 'x1' })], 'cur1'));
        el().querySelector<HTMLButtonElement>('[data-act=events-more]')!.click();
        const more = eventsReq();
        await refuseByTimer();
        expect(more.cancelled).toBe(true);
        expect(el().querySelectorAll('.sfai').length).toBe(0);
      });

      it('refuses the same way on the first load: no board, a notice with Try again, no reads', async () => {
        snack = vi.fn();
        dialog = { open: vi.fn() };
        TestBed.configureTestingModule({
          providers: [
            provideHttpClient(withInterceptors([problemDetailInterceptor])),
            provideHttpClientTesting(),
            { provide: MatSnackBar, useValue: { open: snack, dismiss: vi.fn() } },
            { provide: MatDialog, useValue: dialog },
          ],
        });
        http = TestBed.inject(HttpTestingController);
        fixture = TestBed.createComponent(SafetyComponent);
        fixture.detectChanges();
        http.expectOne(BOARD).flush(FORBIDDEN(), REFUSED);
        expect(eventsReq().cancelled).toBe(true);
        await settle();
        expect(squash(el().querySelector('.notice.err'))).toContain('You cannot see the Safety board.');
        roundAnswered();
        http.expectNone(BOARD);
      });

      it('moves focus to the notice when it was on the board', async () => {
        await withOpenSheet();
        document.body.appendChild(el());
        const button = act('e1', 'sfresp')!;
        button.focus();
        expect(document.activeElement).toBe(button);
        await refuseByTimer();
        const notice = el().querySelector<HTMLElement>('.notice.err')!;
        expect(notice.getAttribute('tabindex')).toBe('-1');
        expect(document.activeElement).toBe(notice);
        el().remove();
      });

      it('closes the open Response sheet when the board is destroyed (review fix 3)', async () => {
        await withOpenSheet();
        expect(sheetClose).not.toHaveBeenCalled();
        fixture.destroy();
        expect(sheetClose).toHaveBeenCalledTimes(1);
      });

      it('closes nothing on destroy once the Response sheet is closed', async () => {
        await withOpenSheet();
        closed.next(undefined);
        fixture.destroy();
        expect(sheetClose).not.toHaveBeenCalled();
      });

      it('tells the page about every refusal, and dismisses the toast of an earlier success (review fix 5, 6)', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open(boardOf({ escalations: [ROW] }));
        let refusals = 0;
        fixture.componentInstance.refused.subscribe(() => refusals++);
        const dismiss = TestBed.inject(MatSnackBar).dismiss as ReturnType<typeof vi.fn>;
        await refuseByTimer();
        expect(refusals).toBe(1);
        expect(dismiss).toHaveBeenCalledTimes(1);
      });

      it('does not tell the page about a failed reload that is not a refusal', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open(boardOf({ escalations: [ROW] }));
        let refusals = 0;
        fixture.componentInstance.refused.subscribe(() => refusals++);
        vi.advanceTimersByTime(SAFETY_REFRESH_MS);
        http.expectOne(BOARD).flush(problem('server-error', 500, 'x'), { status: 500, statusText: 'x' });
        eventsReq();
        await settle();
        expect(refusals).toBe(0);
      });

      it('moves focus to the notice on a repeated refusal when Try again was replaced by the Loading text (review fix 3)', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open(boardOf({ escalations: [ROW] }));
        document.body.appendChild(el());
        await refuseByTimer();
        const retry = el().querySelector<HTMLButtonElement>('.notice.err button')!;
        retry.focus();
        retry.click();
        await settle();
        expect(document.activeElement).toBe(document.body);
        http.expectOne(BOARD).flush(FORBIDDEN(), REFUSED);
        eventsReq();
        await settle();
        expect(document.activeElement).toBe(el().querySelector('.notice.err'));
        el().remove();
      });

      it('leaves focus alone when it was outside the board', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open(boardOf({ escalations: [ROW] }));
        document.body.appendChild(el());
        const outside = document.createElement('button');
        document.body.appendChild(outside);
        outside.focus();
        await refuseByTimer();
        expect(document.activeElement).toBe(outside);
        outside.remove();
        el().remove();
      });
    });

    it('stops the timer when it is destroyed', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open();
      fixture.destroy();
      vi.advanceTimersByTime(SAFETY_REFRESH_MS * 3);
      http.expectNone(BOARD);
    });
  });

  it('uses no browser clock for the minutes (the text follows the server numbers)', async () => {
    await open(boardOf({ escalations: [{ ...ROW, minutesSinceRaised: 125, waitingMinutes: 125 }] }));
    expect(squash(row('e1').querySelector('[data-testid=esc-raised]'))).toBe('Raised by Priya Nair 2 h 5 min ago · target 15 min');
  });
});
