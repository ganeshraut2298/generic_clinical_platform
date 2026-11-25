import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { addDays } from '../../../shared/format/ist-date';
import { DEFAULT_BOOKING_RULES } from '../appointments.model';
import { DESK_POLL_MS, DeskConsolePage } from './desk-console.page';
import { DeskBoardCard, DeskSnapshot } from './desk.model';
import { TEST_TODAY, makeCard, makeDaySlots, makeDoctor, makeTokenPanel, problemBody } from './desk.testing';

const OPD = '/api/v1/opd';
const DESK = `${OPD}/desk`;

function snapshot(cards: DeskBoardCard[], overrides: Partial<DeskSnapshot> = {}): DeskSnapshot {
  return {
    generatedAt: '2026-11-25T05:30:00Z',
    today: TEST_TODAY,
    bookingRules: { ...DEFAULT_BOOKING_RULES },
    followUpFee: 'half',
    counters: { booked: 1, waiting: 1, inConsult: 0, done: 0, freeToday: 3 },
    doctors: {
      total: 2,
      items: [makeDoctor({ doctorMembershipId: 'd1' }), makeDoctor({ doctorMembershipId: 'd2', name: 'Dr. Asha Rao', department: 'Pediatrics' })],
    },
    board: { date: TEST_TODAY, truncated: false, cards },
    followUpsToBook: {
      total: 1,
      items: [
        {
          followUpId: 'f1',
          patientId: 'p9',
          patientName: 'Sunita Pawar',
          doctorMembershipId: 'd2',
          doctorName: 'Dr. Asha Rao',
          dueDate: TEST_TODAY,
          status: 'DUE',
          daysLate: 0,
          reason: 'Thyroid report review',
        },
      ],
    },
    selected: null,
    ...overrides,
  };
}

const BOOKED = makeCard({ key: 'c1', appointmentId: 'a1', appointmentVersion: 4, patientId: 'p1', patientName: 'Rekha Shinde' });
const WAITING = makeCard({
  key: 'c2',
  appointmentId: 'a2',
  visitId: 'v2',
  patientId: 'p2',
  patientName: 'Mahesh Gaikwad',
  column: 'WAITING',
  appointmentStatus: 'ARRIVED',
  stage: 'WAITING',
  nextStage: 'WITH_DOCTOR',
  tokenNo: 8,
  primaryAction: 'START_CONSULTATION',
});
const IN_ROOM = makeCard({
  key: 'c3',
  appointmentId: null,
  kind: 'WALK_IN',
  visitId: 'v3',
  patientId: 'p3',
  patientName: 'Walk Inn',
  doctorMembershipId: 'd2',
  doctorName: 'Dr. Asha Rao',
  column: 'IN_CONSULTATION',
  appointmentStatus: null,
  stage: 'WITH_DOCTOR',
  nextStage: 'BILLING',
  tokenNo: 9,
  primaryAction: 'COMPLETE_VISIT',
});
const NOSHOW = makeCard({
  key: 'c4',
  appointmentId: 'a4',
  appointmentVersion: 7,
  patientId: 'p4',
  patientName: 'Deepa Nair',
  time: '11:15',
  appointmentStatus: 'NOSHOW',
  tags: ['NO_SHOW'],
  primaryAction: 'REBOOK',
});
const DONE = makeCard({
  key: 'c5',
  appointmentId: 'a5',
  visitId: 'v5',
  patientId: 'p5',
  patientName: 'Kiran Joshi',
  column: 'DONE',
  stage: 'BILLING',
  tokenNo: 3,
  primaryAction: 'SET_FOLLOW_UP',
  followUp: { followUpId: null, state: 'NONE', dueDate: null, appointmentId: null, apptDate: null, apptTime: null },
});
const CARDS = [BOOKED, WAITING, IN_ROOM, NOSHOW, DONE];

describe('DeskConsolePage card actions (SAH-91 to SAH-93)', () => {
  let fixture: ComponentFixture<DeskConsolePage>;
  let http: HttpTestingController;
  let view: HTMLElement;
  let snap: DeskSnapshot;

  async function open(role: Role = 'FRONT_DESK', cards: DeskBoardCard[] = CARDS) {
    snap = snapshot(cards);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role, clinic: { name: 'Shree Sai Clinic' } }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DeskConsolePage);
    fixture.detectChanges();
    http.expectOne(`${DESK}/snapshot`).flush(snap);
    http.expectOne((r) => r.url === `${DESK}/doctors/d1/day-slots`).flush(makeDaySlots());
    fixture.detectChanges();
    view = fixture.nativeElement as HTMLElement;
  }

  const buttons = () => Array.from(view.querySelectorAll('button'));
  const q = (id: string) => view.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  const snapshotReq = () => http.expectOne((r) => r.url === `${DESK}/snapshot`);
  const slotsReq = (doctor: string) => http.expectOne((r) => r.url === `${DESK}/doctors/${doctor}/day-slots`);
  const cardOf = (name: string) => Array.from(view.querySelectorAll('.card')).find((c) => c.textContent!.includes(name)) as HTMLElement;
  const actOf = (name: string, label: string) =>
    Array.from(cardOf(name).querySelectorAll('button')).find((b) => b.textContent!.includes(label)) as HTMLButtonElement;

  function flushRefresh() {
    snapshotReq().flush(snap);
    fixture.detectChanges();
  }

  beforeEach(() => {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    http.verify();
  });

  describe('check in and token (SAH-92-1, SAH-92-2)', () => {
    it('checks in, shows Token issued with the numbers and reads the console again', async () => {
      await open();
      actOf('Rekha Shinde', 'Check in · token').click();
      fixture.detectChanges();
      const req = http.expectOne(`${OPD}/appointments/a1/token`);
      expect(req.request.method).toBe('POST');
      expect(actOf('Rekha Shinde', 'Check in · token').disabled).toBe(true);
      req.flush({ id: 'a1', status: 'ARRIVED', visitId: 'v1', tokenNo: 8, tokenPanel: makeTokenPanel() });
      fixture.detectChanges();
      expect(view.querySelector('app-desk-token-panel h2')!.textContent).toBe('Token issued');
      expect(q('token-number')!.textContent).toBe('T-8');
      expect(q('token-wait')!.textContent).toBe('3 ahead · about 45 min');
      flushRefresh();
    });

    it('disables every card button while a check-in is running so a retry cannot make a second visit', async () => {
      await open();
      actOf('Rekha Shinde', 'Check in · token').click();
      fixture.detectChanges();
      const all = Array.from(view.querySelectorAll('.board .act')) as HTMLButtonElement[];
      expect(all.every((b) => b.disabled)).toBe(true);
      http.expectOne(`${OPD}/appointments/a1/token`).flush({ id: 'a1', tokenPanel: makeTokenPanel() });
      flushRefresh();
      fixture.detectChanges();
      expect((Array.from(view.querySelectorAll('.board .act')) as HTMLButtonElement[]).some((b) => b.disabled)).toBe(false);
    });

    it('says the patient is already checked in, opens their token and refreshes', async () => {
      await open();
      actOf('Rekha Shinde', 'Check in · token').click();
      http
        .expectOne(`${OPD}/appointments/a1/token`)
        .flush(problemBody('appointment-already-checked-in', 409, { visitId: 'v1', tokenNo: 8 }), { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(q('action-error')!.textContent).toContain('This patient is already checked in.');
      http.expectOne(`${DESK}/visits/v1/token-panel`).flush(makeTokenPanel());
      flushRefresh();
      expect(q('token-number')!.textContent).toBe('T-8');
    });

    it('shows the check-in day rule in plain English', async () => {
      await open();
      actOf('Rekha Shinde', 'Check in · token').click();
      http.expectOne(`${OPD}/appointments/a1/token`).flush(problemBody('check-in-not-today', 400), { status: 400, statusText: 'Bad Request' });
      fixture.detectChanges();
      expect(q('action-error')!.textContent).toContain('Check-in is only possible on the day of the visit.');
    });

    it('shows a connection message and lets the user try again', async () => {
      await open();
      actOf('Rekha Shinde', 'Check in · token').click();
      http.expectOne(`${OPD}/appointments/a1/token`).error(new ProgressEvent('error'));
      fixture.detectChanges();
      expect(q('action-error')!.textContent).toContain('Check the connection and try again.');
      expect(actOf('Rekha Shinde', 'Check in · token').disabled).toBe(false);
    });

    it('opens the token panel from the token on a card with the current numbers, with Print slip', async () => {
      await open();
      (cardOf('Mahesh Gaikwad').querySelector('.tokbtn') as HTMLButtonElement).click();
      fixture.detectChanges();
      http.expectOne(`${DESK}/visits/v2/token-panel`).flush(makeTokenPanel({ visitId: 'v2', patientsAhead: 0, estimatedWaitMinutes: 0 }));
      fixture.detectChanges();
      expect(view.querySelector('app-desk-token-panel h2')!.textContent).toBe('Token');
      expect(q('token-wait')!.textContent).toBe('Next in line');
      expect(q('print-slip')).not.toBeNull();
    });

    it('lets a nurse open the token panel and print, but not check in', async () => {
      await open('NURSE');
      expect(view.querySelectorAll('.board .act')).toHaveLength(0);
      (cardOf('Mahesh Gaikwad').querySelector('.tokbtn') as HTMLButtonElement).click();
      fixture.detectChanges();
      http.expectOne(`${DESK}/visits/v2/token-panel`).flush(makeTokenPanel({ visitId: 'v2' }));
      fixture.detectChanges();
      expect(q('print-slip')).not.toBeNull();
    });

    it('closes the token panel back to Next actions', async () => {
      await open();
      (cardOf('Mahesh Gaikwad').querySelector('.tokbtn') as HTMLButtonElement).click();
      fixture.detectChanges();
      http.expectOne(`${DESK}/visits/v2/token-panel`).flush(makeTokenPanel({ visitId: 'v2' }));
      fixture.detectChanges();
      q('token-close')!.click();
      fixture.detectChanges();
      expect(view.querySelector('.panel h2')!.textContent).toBe('Next actions');
    });
  });

  describe('start and complete (SAH-92-3)', () => {
    it('starts the consultation with the next stage and the stage the card showed', async () => {
      await open();
      actOf('Mahesh Gaikwad', 'Start consultation').click();
      const req = http.expectOne(`${OPD}/visits/v2/stage`);
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toEqual({ stage: 'WITH_DOCTOR', expectedStage: 'WAITING' });
      req.flush({ id: 'v2', stage: 'WITH_DOCTOR' });
      flushRefresh();
    });

    it('shows the server message and refreshes when another user moved the card', async () => {
      await open();
      actOf('Mahesh Gaikwad', 'Start consultation').click();
      http.expectOne(`${OPD}/visits/v2/stage`).flush(problemBody('visit-stage-changed', 409), { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(q('action-error')!.textContent).toContain('This card was moved by someone else. The board now shows the latest.');
      flushRefresh();
    });

    it('shows the stage owner message when the server refuses the step with 403', async () => {
      await open();
      actOf('Mahesh Gaikwad', 'Start consultation').click();
      http
        .expectOne(`${OPD}/visits/v2/stage`)
        .flush({ ...problemBody('forbidden', 403), detail: 'You cannot move this card.' }, { status: 403, statusText: 'Forbidden' });
      fixture.detectChanges();
      expect(q('action-error')!.textContent).toContain('You cannot move this card.');
      expect(view.querySelector('.board')!.textContent).toContain('Mahesh Gaikwad');
    });

    it('completes the visit of a walk-in and opens the follow-up panel', async () => {
      await open();
      actOf('Walk Inn', 'Complete visit').click();
      const req = http.expectOne(`${OPD}/visits/v3/stage`);
      expect(req.request.body).toEqual({ stage: 'BILLING', expectedStage: 'WITH_DOCTOR' });
      req.flush({ id: 'v3', stage: 'BILLING' });
      fixture.detectChanges();
      flushRefresh();
      expect(view.querySelector('app-desk-follow-panel h2')!.textContent).toBe('Follow-up');
      expect(q('follow-patient')!.textContent).toBe('Walk Inn');
      slotsReq('d2').flush(makeDaySlots({ doctorMembershipId: 'd2', date: addDays(TEST_TODAY, 7) }));
    });

    it('opens the same panel from Set follow-up on a Done card', async () => {
      await open();
      actOf('Kiran Joshi', 'Set follow-up').click();
      fixture.detectChanges();
      expect(q('follow-patient')!.textContent).toBe('Kiran Joshi');
      http
        .expectOne((r) => r.url === `${DESK}/doctors/d1/day-slots` && r.params.get('date') === addDays(TEST_TODAY, 7))
        .flush(makeDaySlots({ date: addDays(TEST_TODAY, 7) }));
    });

    it('books the follow-up, goes back to Idle and reads the console again', async () => {
      await open();
      actOf('Kiran Joshi', 'Set follow-up').click();
      fixture.detectChanges();
      http.expectOne((r) => r.url === `${DESK}/doctors/d1/day-slots` && r.params.get('date') === '2026-12-02').flush(makeDaySlots({ date: '2026-12-02' }));
      fixture.detectChanges();
      q('book-follow-up')!.click();
      http
        .expectOne(`${OPD}/visits/v5/follow-up/appointment`)
        .flush({ followUpId: 'f5', state: 'BOOKED', dueDate: '2026-12-02', appointment: null }, { status: 201, statusText: 'Created' });
      fixture.detectChanges();
      expect(view.querySelector('.panel h2')!.textContent).toBe('Next actions');
      flushRefresh();
    });

    it('hides Start, Complete and follow-up buttons from other roles', async () => {
      await open('DOCTOR');
      expect(view.querySelectorAll('.board .act')).toHaveLength(0);
      expect(buttons().some((b) => b.textContent!.includes('Mark no-show'))).toBe(false);
    });
  });

  describe('no-show (SAH-92-4)', () => {
    it('marks a Booked card as no-show by hand and refreshes', async () => {
      await open();
      actOf('Rekha Shinde', 'Mark no-show').click();
      const req = http.expectOne(`${OPD}/appointments/a1`);
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toEqual({ status: 'NOSHOW' });
      req.flush({ id: 'a1', status: 'NOSHOW' });
      flushRefresh();
    });

    it('offers Mark no-show only on Booked cards not already shown as no-show', async () => {
      await open();
      expect(Array.from(cardOf('Deepa Nair').querySelectorAll('button')).some((b) => b.textContent!.includes('Mark no-show'))).toBe(false);
      expect(Array.from(cardOf('Mahesh Gaikwad').querySelectorAll('button')).some((b) => b.textContent!.includes('Mark no-show'))).toBe(false);
    });

    it('says the appointment is no longer booked when it was already checked in', async () => {
      await open();
      actOf('Rekha Shinde', 'Mark no-show').click();
      http.expectOne(`${OPD}/appointments/a1`).flush(problemBody('appointment-not-booked', 409), { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(q('action-error')!.textContent).toContain('This appointment is no longer booked.');
      flushRefresh();
    });
  });

  describe('rebook and move to a new slot (SAH-91-3)', () => {
    it('opens Pick a slot for the same doctor and day today with the patient, hint and instruction', async () => {
      await open();
      actOf('Deepa Nair', 'Rebook').click();
      fixture.detectChanges();
      const req = slotsReq('d1');
      expect(req.request.params.get('date')).toBe(TEST_TODAY);
      req.flush(makeDaySlots());
      fixture.detectChanges();
      expect(view.querySelector('.panel h2')!.textContent).toBe('Pick a slot');
      expect(q('pick-patient')!.textContent).toBe('Deepa Nair');
      expect(q('pick-hint')!.textContent).toBe('Rebooking the 11:15 AM appointment with Dr. Meera K.');
      expect(q('pick-text')!.textContent).toBe('Pick a day and a time in the slot finder. The doctor is already selected.');
    });

    it('moves to the first day with free slots when today has none', async () => {
      await open();
      actOf('Deepa Nair', 'Rebook').click();
      slotsReq('d1').flush(makeDaySlots({ freeCount: 0, groups: [], nextFreeDate: addDays(TEST_TODAY, 1) }));
      const next = slotsReq('d1');
      expect(next.request.params.get('date')).toBe(addDays(TEST_TODAY, 1));
      next.flush(makeDaySlots({ date: addDays(TEST_TODAY, 1) }));
      fixture.detectChanges();
      expect(view.querySelector('.finder')!.textContent).toContain('10:30 AM');
    });

    it('reschedules the same appointment with its version, then shows Booked and Book another', async () => {
      await open();
      actOf('Deepa Nair', 'Rebook').click();
      slotsReq('d1').flush(makeDaySlots());
      fixture.detectChanges();
      (Array.from(view.querySelectorAll('.slotbtn')).find((b) => b.textContent!.includes('10:45')) as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(q('book-patient')!.textContent).toBe('Deepa Nair');
      expect(view.querySelector('#desk-patient-q')).toBeNull();
      q('confirm-booking')!.click();
      const req = http.expectOne(`${OPD}/appointments/a4/reschedule`);
      expect(req.request.body).toEqual({ version: 7, doctorMembershipId: 'd1', apptDate: TEST_TODAY, apptTime: '10:45' });
      req.flush({ id: 'a4', status: 'BOOKED' });
      fixture.detectChanges();
      expect(view.querySelector('.panel h2')!.textContent).toBe('Booked');
      expect(q('booked-patient')!.textContent).toBe('Deepa Nair');
      expect(q('booked-note')!.textContent).toBe("Added to Booked in today's flow.");
      http.expectOne((r) => r.url === `${DESK}/snapshot`).flush(snap);
      slotsReq('d1').flush(makeDaySlots());
      fixture.detectChanges();
      q('book-another')!.click();
      fixture.detectChanges();
      expect(view.querySelector('.panel h2')!.textContent).toBe('Next actions');
    });

    it('keeps the move when another doctor is chosen in the rail', async () => {
      await open();
      actOf('Deepa Nair', 'Rebook').click();
      slotsReq('d1').flush(makeDaySlots());
      fixture.detectChanges();
      const second = Array.from(view.querySelectorAll('app-desk-doctor-rail button')).find((b) => b.textContent!.includes('Asha R.')) as HTMLButtonElement;
      second.click();
      slotsReq('d2').flush(makeDaySlots({ doctorMembershipId: 'd2' }));
      fixture.detectChanges();
      expect(view.querySelector('.panel h2')!.textContent).toBe('Pick a slot');
      expect(q('pick-patient')!.textContent).toBe('Deepa Nair');
    });
  });

  describe('booking from the finder (SAH-91-1, SAH-91-2)', () => {
    it('opens Confirm booking with the staff cut-off note when a slot is picked', async () => {
      await open();
      (Array.from(view.querySelectorAll('.slotbtn')).find((b) => b.textContent!.includes('10:30')) as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(view.querySelector('.panel h2')!.textContent).toBe('Confirm booking');
      expect(q('book-when')!.textContent).toBe('Wed, 25 Nov · 10:30 AM · 15 min');
      expect(q('cutoff-note')).toBeNull();
    });

    it('reloads the finder and the board when the new slot was just booked by someone else', async () => {
      await open('OWNER');
      (Array.from(view.querySelectorAll('button')).find((b) => b.textContent!.includes('Find slot')) as HTMLButtonElement).click();
      slotsReq('d2').flush(makeDaySlots({ doctorMembershipId: 'd2' }));
      fixture.detectChanges();
      expect(q('pick-hint')!.textContent).toBe('Follow-up with Dr. Asha R. · Thyroid report review');
      (Array.from(view.querySelectorAll('.slotbtn')).find((b) => b.textContent!.includes('10:30')) as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(q('book-patient')!.textContent).toBe('Sunita Pawar');
      expect(q('type-follow')!.getAttribute('aria-pressed')).toBe('true');
      q('confirm-booking')!.click();
      http
        .expectOne(`${OPD}/follow-ups/f1/appointment`)
        .flush(problemBody('appointment-slot-taken', 409), { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(q('book-error')!.textContent).toBe('That slot was just booked. Pick another time.');
      slotsReq('d2').flush(makeDaySlots({ doctorMembershipId: 'd2' }));
      flushRefresh();
      expect(view.querySelector('.panel h2')!.textContent).toBe('Confirm booking');
    });

    it('books a follow-up from Find slot and refreshes so the list count drops', async () => {
      await open('OWNER');
      (Array.from(view.querySelectorAll('button')).find((b) => b.textContent!.includes('Find slot')) as HTMLButtonElement).click();
      slotsReq('d2').flush(makeDaySlots({ doctorMembershipId: 'd2' }));
      fixture.detectChanges();
      (Array.from(view.querySelectorAll('.slotbtn')).find((b) => b.textContent!.includes('10:30')) as HTMLButtonElement).click();
      fixture.detectChanges();
      q('confirm-booking')!.click();
      http
        .expectOne(`${OPD}/follow-ups/f1/appointment`)
        .flush({ followUpId: 'f1', state: 'BOOKED', dueDate: TEST_TODAY, appointment: null }, { status: 201, statusText: 'Created' });
      fixture.detectChanges();
      expect(view.querySelector('.panel h2')!.textContent).toBe('Booked');
      snap = snapshot(CARDS, { followUpsToBook: { total: 0, items: [] } });
      flushRefresh();
      slotsReq('d2').flush(makeDaySlots({ doctorMembershipId: 'd2' }));
      fixture.detectChanges();
      q('book-another')!.click();
      fixture.detectChanges();
      expect(view.textContent).toContain('Follow-ups to book · 0');
    });

    it('shows no confirm button to a nurse', async () => {
      await open('NURSE');
      expect(buttons().some((b) => b.textContent!.includes('Confirm booking'))).toBe(false);
    });
  });

  describe('one write at a time and the move version', () => {
    async function startMove() {
      await open();
      actOf('Deepa Nair', 'Rebook').click();
      slotsReq('d1').flush(makeDaySlots());
      fixture.detectChanges();
      (Array.from(view.querySelectorAll('.slotbtn')).find((b) => b.textContent!.includes('10:45')) as HTMLButtonElement).click();
      fixture.detectChanges();
    }

    it('disables the card buttons while the book panel saves', async () => {
      await startMove();
      q('confirm-booking')!.click();
      fixture.detectChanges();
      const all = Array.from(view.querySelectorAll('.board .act')) as HTMLButtonElement[];
      expect(all.length).toBeGreaterThan(0);
      expect(all.every((b) => b.disabled)).toBe(true);
      http.expectOne(`${OPD}/appointments/a4/reschedule`).flush({ id: 'a4', status: 'BOOKED' });
      flushRefresh();
      slotsReq('d1').flush(makeDaySlots());
      fixture.detectChanges();
      expect((Array.from(view.querySelectorAll('.board .act')) as HTMLButtonElement[]).some((b) => b.disabled)).toBe(false);
    });

    it('sends the version from the newest snapshot when the appointment changed meanwhile', async () => {
      await startMove();
      snap = snapshot(CARDS.map((c) => (c.key === 'c4' ? { ...c, appointmentVersion: 9 } : c)));
      fixture.componentInstance.refresh();
      flushRefresh();
      q('confirm-booking')!.click();
      const req = http.expectOne(`${OPD}/appointments/a4/reschedule`);
      expect(req.request.body.version).toBe(9);
      req.flush({ id: 'a4', status: 'BOOKED' });
      flushRefresh();
      slotsReq('d1').flush(makeDaySlots());
    });

    it('treats a card with no version as an error and sends nothing', async () => {
      await startMove();
      snap = snapshot(CARDS.map((c) => (c.key === 'c4' ? { ...c, appointmentVersion: null } : c)));
      fixture.componentInstance.refresh();
      flushRefresh();
      expect(q('action-error')!.textContent).toContain('could not be checked');
      expect(q('confirm-booking')).toBeNull();
    });
  });

  describe('refresh after writes', () => {
    it('reads again straight after the running poll when a write finishes meanwhile', async () => {
      vi.useFakeTimers();
      await open();
      vi.advanceTimersByTime(DESK_POLL_MS);
      const poll: TestRequest = snapshotReq();
      actOf('Rekha Shinde', 'Mark no-show').click();
      http.expectOne(`${OPD}/appointments/a1`).flush({ id: 'a1', status: 'NOSHOW' });
      http.expectNone((r) => r.url === `${DESK}/snapshot`);
      poll.flush(snap);
      flushRefresh();
    });
  });
});
