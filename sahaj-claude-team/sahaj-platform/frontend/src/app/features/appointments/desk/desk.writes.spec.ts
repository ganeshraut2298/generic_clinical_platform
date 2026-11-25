import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../../core/http/problem-detail';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { DeskApi } from './desk.api';
import {
  confirmBookingLabel,
  deskProblem,
  followUpDate,
  followUpHint,
  rescheduleHint,
  slotSummary,
  tokenWaitText,
  visitFeePaise,
} from './desk.model';
import { makeCard, makeDoctor } from './desk.testing';

const OPD = '/api/v1/opd';

function problem(slug: string, status: number, extra: Record<string, unknown> = {}): ApiError {
  return new ApiError({ type: `https://sahaj.in/problems/${slug}`, title: slug, status, ...extra });
}

describe('desk write helpers', () => {
  it('words the confirm button with the fee or Free', () => {
    expect(confirmBookingLabel(40000)).toBe('Confirm booking · ₹400');
    expect(confirmBookingLabel(0)).toBe('Confirm booking · Free');
    expect(confirmBookingLabel(12500000)).toBe('Confirm booking · ₹1,25,000');
  });

  it('takes the fee of each visit type from the doctor in the snapshot', () => {
    const doctor = makeDoctor();
    expect(visitFeePaise(doctor, 'NEW')).toBe(40000);
    expect(visitFeePaise(doctor, 'FOLLOW_UP')).toBe(20000);
  });

  it('writes the slot summary and the wait line', () => {
    expect(slotSummary('2026-11-25', '10:30', 15)).toBe('Wed, 25 Nov · 10:30 AM · 15 min');
    expect(tokenWaitText({ patientsAhead: 0, estimatedWaitMinutes: 0 })).toBe('Next in line');
    expect(tokenWaitText({ patientsAhead: 3, estimatedWaitMinutes: 45 })).toBe('3 ahead · about 45 min');
  });

  it('works out the follow-up date in whole days and calendar months', () => {
    expect(followUpDate('2026-11-25', 'D7')).toBe('2026-12-02');
    expect(followUpDate('2026-11-25', 'D15')).toBe('2026-12-10');
    expect(followUpDate('2026-11-25', 'M1')).toBe('2026-12-25');
    expect(followUpDate('2026-12-20', 'M1')).toBe('2027-01-20');
    expect(followUpDate('2026-01-31', 'M1')).toBe('2026-02-28');
    expect(followUpDate('2028-01-31', 'M1')).toBe('2028-02-29');
  });

  it('words the rebook, move and follow-up hints', () => {
    const noShow = makeCard({ time: '11:15', tags: ['NO_SHOW'], appointmentStatus: 'NOSHOW' });
    expect(rescheduleHint(noShow)).toBe('Rebooking the 11:15 AM appointment with Dr. Meera K.');
    expect(rescheduleHint(makeCard({ time: '11:15' }))).toBe('Moving the 11:15 AM appointment with Dr. Meera K.');
    expect(
      followUpHint({
        followUpId: 'f1',
        patientId: 'p1',
        patientName: 'A',
        doctorMembershipId: 'd1',
        doctorName: 'Dr. Meera Kulkarni',
        dueDate: '2026-11-25',
        status: 'DUE',
        daysLate: 0,
        reason: 'Thyroid report review',
      }),
    ).toBe('Follow-up with Dr. Meera K. · Thyroid report review');
  });
});

describe('deskProblem', () => {
  it.each([
    ['doctor-not-working', 400, 'This doctor does not see patients on that day.'],
    ['slot-not-on-grid', 400, "That time is not one of this doctor's slots. Pick a time from the list."],
    ['slot-walk-in-only', 400, 'That slot is kept for walk-ins. Pick another time.'],
    ['slot-in-past', 400, 'That time has passed.'],
    ['doctor-unavailable', 409, 'This doctor is not taking bookings on that date.'],
    ['appointment-duplicate', 409, 'This patient is already booked in this slot.'],
    ['appointment-slot-taken', 409, 'That slot was just booked. Pick another time.'],
    ['appointment-changed', 409, 'This appointment was changed by someone else. The board now shows the latest.'],
    ['appointment-not-reschedulable', 409, 'Only a booked or no-show appointment can be moved.'],
    ['appointment-not-booked', 409, 'This appointment is no longer booked. The board now shows the latest.'],
    ['appointment-already-checked-in', 409, 'This patient is already checked in.'],
    ['appointment-not-bookable', 409, 'This appointment cannot be checked in now. The board now shows the latest.'],
    ['check-in-not-today', 400, 'Check-in is only possible on the day of the visit.'],
    ['visit-stage-changed', 409, 'This card was moved by someone else. The board now shows the latest.'],
    ['visit-not-done', 409, 'Set the follow-up after the visit is completed.'],
    ['follow-up-already-booked', 409, 'This follow-up is already booked.'],
    ['follow-up-closed', 409, 'This follow-up is closed.'],
    ['database-busy', 503, 'Sahaj is busy. Nothing was saved. Wait a moment and try again.'],
  ])('maps %s to plain English', (slug, status, message) => {
    expect(deskProblem(problem(slug, status)).message).toBe(message);
  });

  it('names the cut-off from the server value', () => {
    expect(deskProblem(problem('booking-closed', 400, { closeBeforeHours: 2 })).message).toBe('Booking closes 2 hours before the slot.');
    expect(deskProblem(problem('booking-closed', 400, { closeBeforeHours: 1 })).message).toBe('Booking closes 1 hour before the slot.');
  });

  it('flags the finder for a taken slot and the board for a changed record', () => {
    expect(deskProblem(problem('appointment-slot-taken', 409)).slotTaken).toBe(true);
    expect(deskProblem(problem('visit-stage-changed', 409)).refreshBoard).toBe(true);
    expect(deskProblem(problem('slot-not-on-grid', 400))).toMatchObject({ slotTaken: false, refreshBoard: false });
  });

  it('uses the connection message when the server cannot be reached', () => {
    const offline = new ApiError({ type: 'about:blank', title: 'Cannot reach the server', status: 0 });
    expect(deskProblem(offline).message).toBe('Could not save. Check the connection and try again.');
    expect(deskProblem(offline, 'Could not book. Check the connection and try again.').message).toBe(
      'Could not book. Check the connection and try again.',
    );
    expect(deskProblem(new Error('x')).message).toBe('Could not save. Check the connection and try again.');
  });

  it('keeps the server message for 403 and falls back by status for unknown types', () => {
    expect(deskProblem(problem('forbidden', 403, { detail: 'You cannot move this card.' })).message).toBe('You cannot move this card.');
    expect(deskProblem(problem('forbidden', 403)).message).toBe('You do not have permission to do this.');
    expect(deskProblem(problem('not-found', 404))).toMatchObject({ refreshBoard: true });
    expect(deskProblem(problem('validation', 400, { fieldErrors: [{ field: 'x', message: 'Bad value' }] })).message).toBe('Bad value');
    expect(deskProblem(problem('whatever', 500)).message).toBe('Something went wrong. Try again.');
  });
});

describe('DeskApi writes (docs/api/opd.yaml 0.9.0)', () => {
  let api: DeskApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()] });
    api = TestBed.inject(DeskApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('books with the contract body and no clinic id', () => {
    const body = { patientId: 'p', doctorMembershipId: 'd', apptDate: '2026-11-25', apptTime: '10:30', visitType: 'NEW' as const, source: 'FRONT_DESK' as const };
    api.book(body).subscribe();
    const req = http.expectOne(`${OPD}/appointments`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
  });

  it('reschedules with the version', () => {
    api.reschedule('a1', { version: 3, doctorMembershipId: 'd', apptDate: '2026-11-25', apptTime: '12:15' }).subscribe();
    const req = http.expectOne(`${OPD}/appointments/a1/reschedule`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.version).toBe(3);
  });

  it('checks in, marks no-show and reads the token panel', () => {
    api.checkIn('a1').subscribe();
    expect(http.expectOne(`${OPD}/appointments/a1/token`).request.method).toBe('POST');
    api.markNoShow('a1').subscribe();
    const noShow = http.expectOne(`${OPD}/appointments/a1`);
    expect(noShow.request.method).toBe('PATCH');
    expect(noShow.request.body).toEqual({ status: 'NOSHOW' });
    api.getTokenPanel('v1').subscribe();
    expect(http.expectOne(`${OPD}/desk/visits/v1/token-panel`).request.method).toBe('GET');
  });

  it('moves the stage with expectedStage', () => {
    api.moveStage('v1', 'WITH_DOCTOR', 'WAITING').subscribe();
    const req = http.expectOne(`${OPD}/visits/v1/stage`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ stage: 'WITH_DOCTOR', expectedStage: 'WAITING' });
  });

  it('calls the three follow-up writes', () => {
    api.bookVisitFollowUp('v1', { intervalKey: 'D7', apptDate: '2026-12-02', apptTime: '10:30', visitType: 'FOLLOW_UP' }).subscribe();
    expect(http.expectOne(`${OPD}/visits/v1/follow-up/appointment`).request.body.intervalKey).toBe('D7');
    api.markFollowUpNotNeeded('v1').subscribe();
    expect(http.expectOne(`${OPD}/visits/v1/follow-up/not-needed`).request.method).toBe('POST');
    api.bookFollowUp('f1', { doctorMembershipId: 'd', apptDate: '2026-11-25', apptTime: '10:30' }).subscribe();
    expect(http.expectOne(`${OPD}/follow-ups/f1/appointment`).request.method).toBe('POST');
  });

  it('keeps the new problem fields from the server answer', () => {
    let error: unknown;
    api.checkIn('a1').subscribe({ error: (e: unknown) => (error = e) });
    http
      .expectOne(`${OPD}/appointments/a1/token`)
      .flush(
        { type: 'https://sahaj.in/problems/appointment-already-checked-in', title: 'x', status: 409, visitId: 'v9', tokenNo: 8 },
        { status: 409, statusText: 'Conflict' },
      );
    expect((error as ApiError).problem.visitId).toBe('v9');
    expect((error as ApiError).problem.tokenNo).toBe(8);
  });
});
