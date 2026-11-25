import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ShellStore } from '../../../core/shell/shell.store';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { DeskBookPanelComponent } from './desk-book-panel.component';
import { DeskBookedSummary, DeskPanelMode, DeskProblem, NO_PICK_CONTEXT } from './desk.model';
import { TEST_TODAY, makeCard, makeDoctor, problemBody } from './desk.testing';

type BookMode = Extract<DeskPanelMode, { kind: 'book' }>;

const OPD = '/api/v1/opd';
const PATIENTS = '/api/v1/patient/patients';
const PRIVACY = '/api/v1/platform/privacy';

const PLAIN: BookMode = {
  kind: 'book',
  doctorMembershipId: 'd1',
  date: TEST_TODAY,
  time: '10:30',
  insideCutOff: false,
  ...NO_PICK_CONTEXT,
};

const FOUND = { id: 'p1', uhid: 'U1', name: 'Rekha Shinde', age: 34, ageDetail: {}, sex: 'F', mobile: '9876543210' };

describe('DeskBookPanelComponent', () => {
  let fixture: ComponentFixture<DeskBookPanelComponent>;
  let http: HttpTestingController;
  let booked: DeskBookedSummary[];
  let failed: DeskProblem[];
  let view: HTMLElement;

  function open(mode: BookMode = PLAIN, doctor = makeDoctor()) {
    TestBed.configureTestingModule({
      providers: [
        { provide: ShellStore, useValue: { me: () => ({ clinic: { name: 'Sai Clinic' } }) } },
        provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DeskBookPanelComponent);
    booked = [];
    failed = [];
    fixture.componentInstance.booked.subscribe((s) => booked.push(s));
    fixture.componentInstance.failed.subscribe((p) => failed.push(p));
    fixture.componentRef.setInput('mode', mode);
    fixture.componentRef.setInput('doctor', doctor);
    fixture.detectChanges();
    view = fixture.nativeElement as HTMLElement;
  }

  const q = (testId: string) => view.querySelector(`[data-testid="${testId}"]`) as HTMLElement | null;
  const button = (text: string) => Array.from(view.querySelectorAll('button')).find((b) => b.textContent!.includes(text)) as HTMLButtonElement;

  function type(text: string) {
    const input = view.querySelector('#desk-patient-q') as HTMLInputElement;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function search(text: string, items: unknown[]) {
    type(text);
    vi.advanceTimersByTime(300);
    http.expectOne((r) => r.url === PATIENTS && r.params.get('q') === text).flush({ items, total: items.length });
    fixture.detectChanges();
  }

  async function pickPatient() {
    await search('rekha', [FOUND]);
    (view.querySelector('.result') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  const OFF = { consentRequired: false, promoConsent: false, recordCopy: true };

  function flushPrivacy(settings: object = OFF) {
    http.expectOne(PRIVACY).flush(settings);
    fixture.detectChanges();
  }

  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('shows the doctor, department, day, time and slot length', () => {
    open();
    expect(view.querySelector('h2')!.textContent).toBe('Confirm booking');
    expect(q('book-doctor')!.textContent).toContain('Dr. Meera K.');
    expect(q('book-doctor')!.textContent).toContain('General medicine');
    expect(q('book-when')!.textContent).toBe('Wed, 25 Nov · 10:30 AM · 15 min');
  });

  it('shows the cut-off note to staff only when the slot is inside the cut-off', () => {
    open({ ...PLAIN, insideCutOff: true });
    expect(q('cutoff-note')!.textContent).toBe('Inside the booking cut-off');
    TestBed.resetTestingModule();
    open();
    expect(q('cutoff-note')).toBeNull();
  });

  it('labels the patient field and searches by name or mobile after a short pause', async () => {
    open();
    expect(view.querySelector('label[for="desk-patient-q"]')!.textContent).toBe('Patient');
    type('rek');
    http.expectNone(PATIENTS);
    await search('rekha', [FOUND]);
    expect(view.querySelector('.result')!.textContent).toContain('Rekha Shinde');
    expect(view.querySelector('.result')!.textContent).toContain('9876543210');
  });

  it('does not search for fewer than two characters', () => {
    open();
    type('r');
    vi.advanceTimersByTime(500);
    http.expectNone(PATIENTS);
  });

  it('shows the chosen patient with name and mobile and lets staff change them', async () => {
    open();
    await pickPatient();
    expect(q('book-patient')!.textContent).toBe('Rekha Shinde');
    expect(q('book-mobile')!.textContent).toBe('9876543210');
    button('Change patient').click();
    fixture.detectChanges();
    expect(view.querySelector('#desk-patient-q')).not.toBeNull();
  });

  it('shows a plain message when the search fails', async () => {
    open();
    type('rekha');
    vi.advanceTimersByTime(300);
    http.expectOne((r) => r.url === PATIENTS).flush({}, { status: 500, statusText: 'Server error' });
    fixture.detectChanges();
    expect(view.textContent).toContain('Could not search. Try again.');
  });

  it('offers New patient when nothing matches and checks the minimal form before saving', async () => {
    open();
    await search('zzz', []);
    expect(q('no-match')!.textContent).toBe('No patient found.');
    q('new-patient')!.click();
    fixture.detectChanges();
    flushPrivacy();
    expect(view.querySelector('#newp-title')!.textContent).toBe('New patient');
    const name = view.querySelector('[formControlName="name"]') as HTMLInputElement;
    expect(name.value).toBe('zzz');
    name.value = '';
    name.dispatchEvent(new Event('input'));
    q('save-patient')!.click();
    fixture.detectChanges();
    expect(view.textContent).toContain('Enter the name');
    expect(view.textContent).toContain('Enter a 10-digit mobile number');
    http.expectNone(PATIENTS);
  });

  it('refuses a mobile number that is not valid', async () => {
    open();
    await search('zzz', []);
    q('new-patient')!.click();
    fixture.detectChanges();
    flushPrivacy();
    const name = view.querySelector('[formControlName="name"]') as HTMLInputElement;
    const mobile = view.querySelector('[formControlName="mobile"]') as HTMLInputElement;
    const age = view.querySelector('[data-field="age"]') as HTMLInputElement;
    name.value = 'Anil Kale';
    name.dispatchEvent(new Event('input'));
    mobile.value = '12345';
    mobile.dispatchEvent(new Event('input'));
    age.value = '40';
    age.dispatchEvent(new Event('input'));
    q('save-patient')!.click();
    fixture.detectChanges();
    expect(view.textContent).toContain('Enter a 10-digit mobile number');
    http.expectNone(PATIENTS);
  });

  it('creates the patient with name, sex, mobile and age, then selects them', async () => {
    open();
    await search('Anil', []);
    q('new-patient')!.click();
    fixture.detectChanges();
    flushPrivacy();
    const mobile = view.querySelector('[formControlName="mobile"]') as HTMLInputElement;
    const age = view.querySelector('[data-field="age"]') as HTMLInputElement;
    mobile.value = '9876501234';
    mobile.dispatchEvent(new Event('input'));
    age.value = '40';
    age.dispatchEvent(new Event('input'));
    q('save-patient')!.click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === PATIENTS);
    expect(req.request.body).toEqual({ name: 'Anil', age: 40, sex: 'F', mobile: '9876501234' });
    req.flush({ ...FOUND, id: 'p7', name: 'Anil', mobile: '9876501234' });
    fixture.detectChanges();
    expect(q('book-patient')!.textContent).toBe('Anil');
    expect(q('book-mobile')!.textContent).toBe('9876501234');
  });

  it('shows each visit type with its fee, presses the chosen one and changes the confirm label', () => {
    open();
    expect(q('type-new')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('New consultation · ₹400');
    expect(q('type-follow')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('Follow-up · ₹200');
    expect(q('type-new')!.getAttribute('aria-pressed')).toBe('true');
    expect(q('confirm-booking')!.textContent!.trim()).toBe('Confirm booking · ₹400');
    q('type-follow')!.click();
    fixture.detectChanges();
    expect(q('type-follow')!.getAttribute('aria-pressed')).toBe('true');
    expect(q('confirm-booking')!.textContent!.trim()).toBe('Confirm booking · ₹200');
  });

  it('writes a zero follow-up fee as Free everywhere and still allows the booking', async () => {
    open(PLAIN, makeDoctor({ followUpFeePaise: 0 }));
    q('type-follow')!.click();
    fixture.detectChanges();
    expect(q('type-follow')!.textContent).toContain('Free');
    expect(q('confirm-booking')!.textContent!.trim()).toBe('Confirm booking · Free');
    await pickPatient();
    expect((q('confirm-booking') as HTMLButtonElement).disabled).toBe(false);
  });

  it('keeps the confirm button off until a patient is chosen', async () => {
    open();
    expect((q('confirm-booking') as HTMLButtonElement).disabled).toBe(true);
    await pickPatient();
    expect((q('confirm-booking') as HTMLButtonElement).disabled).toBe(false);
  });

  it('books one appointment from the front desk and reports it', async () => {
    open();
    await pickPatient();
    q('type-follow')!.click();
    q('confirm-booking')!.click();
    fixture.detectChanges();
    expect((q('confirm-booking') as HTMLButtonElement).disabled).toBe(true);
    const req = http.expectOne(`${OPD}/appointments`);
    expect(req.request.body).toEqual({
      patientId: 'p1',
      doctorMembershipId: 'd1',
      apptDate: TEST_TODAY,
      apptTime: '10:30',
      visitType: 'FOLLOW_UP',
      source: 'FRONT_DESK',
    });
    req.flush({ id: 'a9' }, { status: 201, statusText: 'Created' });
    expect(booked).toEqual([{ patientName: 'Rekha Shinde', doctorName: 'Dr. Meera Kulkarni', date: TEST_TODAY, time: '10:30' }]);
  });

  it('sends only one request while the first is waiting', async () => {
    open();
    await pickPatient();
    q('confirm-booking')!.click();
    q('confirm-booking')!.click();
    http.expectOne(`${OPD}/appointments`).flush({ id: 'a9' });
  });

  it('shows the connection message and allows a retry', async () => {
    open();
    await pickPatient();
    q('confirm-booking')!.click();
    http.expectOne(`${OPD}/appointments`).error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(q('book-error')!.textContent).toBe('Could not book. Check the connection and try again.');
    expect((q('confirm-booking') as HTMLButtonElement).disabled).toBe(false);
    q('confirm-booking')!.click();
    http.expectOne(`${OPD}/appointments`).flush({ id: 'a9' });
    expect(booked).toHaveLength(1);
  });

  it('treats a duplicate answer on retry as the saved booking, never a second one', async () => {
    open();
    await pickPatient();
    q('confirm-booking')!.click();
    http
      .expectOne(`${OPD}/appointments`)
      .flush(problemBody('appointment-duplicate', 409, { appointmentId: 'a1' }), { status: 409, statusText: 'Conflict' });
    expect(booked).toHaveLength(1);
    expect(failed).toHaveLength(0);
  });

  it('says the slot was just booked and asks the page to refresh the finder', async () => {
    open();
    await pickPatient();
    q('confirm-booking')!.click();
    http.expectOne(`${OPD}/appointments`).flush(problemBody('appointment-slot-taken', 409), { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(q('book-error')!.textContent).toBe('That slot was just booked. Pick another time.');
    expect(failed[0].slotTaken).toBe(true);
    expect(booked).toHaveLength(0);
  });

  it.each([
    ['doctor-not-working', 400, {}, 'This doctor does not see patients on that day.'],
    ['slot-not-on-grid', 400, {}, "That time is not one of this doctor's slots. Pick a time from the list."],
    ['slot-walk-in-only', 400, {}, 'That slot is kept for walk-ins. Pick another time.'],
    ['slot-in-past', 400, {}, 'That time has passed.'],
    ['booking-closed', 400, { closeBeforeHours: 2 }, 'Booking closes 2 hours before the slot.'],
    ['doctor-unavailable', 409, {}, 'This doctor is not taking bookings on that date.'],
    ['forbidden', 403, {}, 'You do not have permission to do this.'],
  ])('shows %s in plain English', async (slug, status, extra, message) => {
    open();
    await pickPatient();
    q('confirm-booking')!.click();
    http.expectOne(`${OPD}/appointments`).flush(problemBody(slug, status, extra), { status, statusText: 'Problem' });
    fixture.detectChanges();
    expect(q('book-error')!.textContent).toBe(message);
  });

  describe('moving an appointment (Rebook, Move to new slot)', () => {
    const card = makeCard({ appointmentId: 'a5', appointmentVersion: 3, patientId: 'p5', patientName: 'Deepa Nair', time: '10:45', visitType: 'FOLLOW_UP' });
    const moving: BookMode = { ...PLAIN, reschedule: card };

    it('shows the patient as a fixed name with the kept visit type and its fee', () => {
      open(moving);
      expect(q('book-patient')!.textContent).toBe('Deepa Nair');
      expect(view.querySelector('#desk-patient-q')).toBeNull();
      expect(q('book-type')!.textContent).toBe('Follow-up · ₹200');
      expect(q('type-new')).toBeNull();
      expect(q('confirm-booking')!.textContent!.trim()).toBe('Confirm booking · ₹200');
    });

    it('sends the version the board showed and no new appointment', () => {
      open(moving);
      q('confirm-booking')!.click();
      const req = http.expectOne(`${OPD}/appointments/a5/reschedule`);
      expect(req.request.body).toEqual({ version: 3, doctorMembershipId: 'd1', apptDate: TEST_TODAY, apptTime: '10:30' });
      req.flush({ id: 'a5' });
      expect(booked[0].patientName).toBe('Deepa Nair');
    });

    it('shows a version clash in plain English and asks for a board refresh', () => {
      open(moving);
      q('confirm-booking')!.click();
      http.expectOne(`${OPD}/appointments/a5/reschedule`).flush(problemBody('appointment-changed', 409), { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(q('book-error')!.textContent).toBe('This appointment was changed by someone else. The board now shows the latest.');
      expect(failed[0].refreshBoard).toBe(true);
    });

    it('shows that an arrived or cancelled appointment cannot be moved', () => {
      open(moving);
      q('confirm-booking')!.click();
      http.expectOne(`${OPD}/appointments/a5/reschedule`).flush(problemBody('appointment-not-reschedulable', 409), { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(q('book-error')!.textContent).toBe('Only a booked or no-show appointment can be moved.');
    });
  });

  describe('booking a follow-up from the idle list', () => {
    const item = {
      followUpId: 'f1',
      patientId: 'p9',
      patientName: 'Sunita Pawar',
      doctorMembershipId: 'd1',
      doctorName: 'Dr. Meera Kulkarni',
      dueDate: TEST_TODAY,
      status: 'DUE' as const,
      daysLate: 0,
      reason: 'Thyroid report review',
    };
    const linked: BookMode = { ...PLAIN, followUp: item };

    it('preselects Follow-up with its fee and links the booking to the follow-up record', () => {
      open(linked);
      expect(q('book-patient')!.textContent).toBe('Sunita Pawar');
      expect(q('type-follow')!.getAttribute('aria-pressed')).toBe('true');
      expect(q('confirm-booking')!.textContent!.trim()).toBe('Confirm booking · ₹200');
      q('confirm-booking')!.click();
      const req = http.expectOne(`${OPD}/follow-ups/f1/appointment`);
      expect(req.request.body).toEqual({ doctorMembershipId: 'd1', apptDate: TEST_TODAY, apptTime: '10:30', visitType: 'FOLLOW_UP' });
      req.flush({ followUpId: 'f1', state: 'BOOKED', dueDate: TEST_TODAY, appointment: null });
      expect(booked).toHaveLength(1);
    });

    it('lets the desk change to New consultation, which changes the fee but still links the follow-up', () => {
      open(linked);
      q('type-new')!.click();
      fixture.detectChanges();
      expect(q('confirm-booking')!.textContent!.trim()).toBe('Confirm booking · ₹400');
      q('confirm-booking')!.click();
      const req = http.expectOne(`${OPD}/follow-ups/f1/appointment`);
      expect(req.request.body.visitType).toBe('NEW');
      req.flush({ followUpId: 'f1', state: 'BOOKED', dueDate: TEST_TODAY, appointment: null });
    });

    it('says the follow-up was already booked and asks for a refresh', () => {
      open(linked);
      q('confirm-booking')!.click();
      http.expectOne(`${OPD}/follow-ups/f1/appointment`).flush(problemBody('follow-up-already-booked', 409), { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(q('book-error')!.textContent).toBe('This follow-up is already booked.');
      expect(failed[0].refreshBoard).toBe(true);
    });
  });

  describe('consent on the new patient form (DEC-098 F1)', () => {
    const ON = { consentRequired: true, promoConsent: true, recordCopy: true };
    const boxes = () => Array.from(view.querySelectorAll('mat-checkbox')).map((b) => b.textContent!.trim());

    async function startNew(privacy: object) {
      open();
      await search('Anil', []);
      q('new-patient')!.click();
      fixture.detectChanges();
      flushPrivacy(privacy);
      const mobile = view.querySelector('[formControlName="mobile"]') as HTMLInputElement;
      const age = view.querySelector('[data-field="age"]') as HTMLInputElement;
      mobile.value = '9876501234';
      mobile.dispatchEvent(new Event('input'));
      age.value = '40';
      age.dispatchEvent(new Event('input'));
    }

    it('shows the clinic consent line and both boxes when consent and promotional messages are on', async () => {
      await startNew(ON);
      expect(boxes()).toEqual(['The patient agreed to the consent line', 'The patient agreed to promotional messages']);
      expect(view.textContent).toContain('I agree that Sai Clinic may keep my health records');
    });

    it('shows only the consent box when promotional messages are off', async () => {
      await startNew({ ...ON, promoConsent: false });
      expect(boxes()).toEqual(['The patient agreed to the consent line']);
    });

    it('sends the consents with the new patient, as the patient form does', async () => {
      await startNew(ON);
      const [records, promo] = Array.from(view.querySelectorAll('mat-checkbox input')) as HTMLInputElement[];
      records.click();
      fixture.detectChanges();
      q('save-patient')!.click();
      const req = http.expectOne((r) => r.method === 'POST' && r.url === PATIENTS);
      expect(promo).toBeDefined();
      expect(req.request.body.consents).toEqual([
        { kind: 'RECORDS', given: true },
        { kind: 'PROMOTIONAL', given: false },
      ]);
      req.flush({ ...FOUND, id: 'p7', name: 'Anil' });
    });

    it('sends the unticked answer when the box is left alone', async () => {
      await startNew({ ...ON, promoConsent: false });
      q('save-patient')!.click();
      const req = http.expectOne((r) => r.method === 'POST' && r.url === PATIENTS);
      expect(req.request.body.consents).toEqual([{ kind: 'RECORDS', given: false }]);
      req.flush({ ...FOUND, id: 'p7', name: 'Anil' });
    });

    it('shows nothing and sends no consents when consent is off', async () => {
      await startNew({ consentRequired: false, promoConsent: false, recordCopy: true });
      expect(boxes()).toEqual([]);
      expect(view.textContent).not.toContain('I agree that');
      q('save-patient')!.click();
      const req = http.expectOne((r) => r.method === 'POST' && r.url === PATIENTS);
      expect(req.request.body.consents).toBeUndefined();
      req.flush({ ...FOUND, id: 'p7', name: 'Anil' });
    });

    it('does not save until the consent setting is known, and offers a retry when it cannot be read', async () => {
      open();
      await search('Anil', []);
      q('new-patient')!.click();
      fixture.detectChanges();
      expect((q('save-patient') as HTMLButtonElement).disabled).toBe(true);
      http.expectOne(PRIVACY).flush({}, { status: 500, statusText: 'Server error' });
      fixture.detectChanges();
      expect(q('consent-error')).not.toBeNull();
      expect((q('save-patient') as HTMLButtonElement).disabled).toBe(true);
      (q('consent-error')!.querySelector('button') as HTMLButtonElement).click();
      http.expectOne(PRIVACY).flush({ consentRequired: false, promoConsent: false, recordCopy: true });
      fixture.detectChanges();
      expect(q('consent-error')).toBeNull();
      expect((q('save-patient') as HTMLButtonElement).disabled).toBe(false);
    });
  });
});
