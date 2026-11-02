import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AppointmentsApi } from './appointments.api';
import { BookingRules, DoctorSlotSettings } from './appointments.model';

const BASE = '/api/v1/opd';

describe('AppointmentsApi', () => {
  let api: AppointmentsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(AppointmentsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads and saves a doctor slot template with PUT', () => {
    api.getDoctorSlots().subscribe();
    expect(http.expectOne(`${BASE}/doctor-slots`).request.method).toBe('GET');

    const settings: DoctorSlotSettings = {
      doctorMembershipId: 'm-doc',
      slotMinutes: 15,
      workingDays: [1, 2, 3, 4, 5],
      walkInEveryNth: 4,
      maxOnlinePerDay: 20,
      sessions: [{ startTime: '09:00', endTime: '13:00' }],
    };
    api.saveDoctorSlots('m-doc', settings).subscribe();
    const put = http.expectOne(`${BASE}/doctor-slots/m-doc`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual(settings);
    put.flush(settings);
  });

  it('reads and saves booking rules with PUT', () => {
    api.getBookingRules().subscribe();
    expect(http.expectOne(`${BASE}/booking-rules`).request.method).toBe('GET');

    const rules: BookingRules = {
      closeBeforeHours: 1,
      remindBeforeHours: 2,
      noShowAfterMinutes: 15,
      followPriority: true,
      selfBook: false,
      overbook: false,
    };
    api.saveBookingRules(rules).subscribe();
    const put = http.expectOne(`${BASE}/booking-rules`);
    expect(put.request.method).toBe('PUT');
    put.flush(rules);
  });

  it('lists appointments by date, with an optional doctor filter', () => {
    api.list('2026-10-24').subscribe();
    const req = http.expectOne((r) => r.url === `${BASE}/appointments`);
    expect(req.request.params.get('date')).toBe('2026-10-24');
    expect(req.request.params.has('doctorMembershipId')).toBe(false);
    req.flush([]);

    api.list('2026-10-24', 'm-doc').subscribe();
    const filtered = http.expectOne((r) => r.url === `${BASE}/appointments`);
    expect(filtered.request.params.get('doctorMembershipId')).toBe('m-doc');
    filtered.flush([]);
  });

  it('books an appointment', () => {
    api
      .book({
        patientId: 'p1',
        doctorMembershipId: 'm-doc',
        apptDate: '2026-10-24',
        apptTime: '09:00',
        visitType: 'NEW',
        source: 'FRONT_DESK',
      })
      .subscribe();
    const req = http.expectOne(`${BASE}/appointments`);
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('cancels, marks no-show and creates a token', () => {
    api.setStatus('a1', 'CANCELLED').subscribe();
    const cancel = http.expectOne(`${BASE}/appointments/a1`);
    expect(cancel.request.method).toBe('PATCH');
    expect(cancel.request.body).toEqual({ status: 'CANCELLED' });
    cancel.flush({});

    api.setStatus('a1', 'NOSHOW').subscribe();
    http.expectOne(`${BASE}/appointments/a1`).flush({});

    api.createToken('a1').subscribe();
    const token = http.expectOne(`${BASE}/appointments/a1/token`);
    expect(token.request.method).toBe('POST');
    token.flush({});
  });
});
