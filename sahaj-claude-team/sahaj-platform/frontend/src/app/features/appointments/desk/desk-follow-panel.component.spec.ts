import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { DeskFollowPanelComponent } from './desk-follow-panel.component';
import { DeskProblem } from './desk.model';
import { TEST_TODAY, makeDaySlots, makeDoctor, problemBody } from './desk.testing';

const OPD = '/api/v1/opd';
const SLOTS_URL = `${OPD}/desk/doctors/d1/day-slots`;

describe('DeskFollowPanelComponent', () => {
  let fixture: ComponentFixture<DeskFollowPanelComponent>;
  let http: HttpTestingController;
  let view: HTMLElement;
  let saved: number;
  let failed: DeskProblem[];

  function open(doctor = makeDoctor()) {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DeskFollowPanelComponent);
    saved = 0;
    failed = [];
    fixture.componentInstance.saved.subscribe(() => saved++);
    fixture.componentInstance.failed.subscribe((p) => failed.push(p));
    fixture.componentRef.setInput('mode', {
      kind: 'follow',
      visitId: 'v1',
      patientName: 'Rekha Shinde',
      doctorMembershipId: 'd1',
      doctorName: 'Dr. Meera Kulkarni',
    });
    fixture.componentRef.setInput('doctor', doctor);
    fixture.componentRef.setInput('today', TEST_TODAY);
    fixture.detectChanges();
    view = fixture.nativeElement as HTMLElement;
  }

  const q = (id: string) => view.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  const date = (d: string) => (r: { url: string; params: { get(k: string): string | null } }) => r.url === SLOTS_URL && r.params.get('date') === d;

  function flushSlots(d: string, body = makeDaySlots({ date: d })) {
    fixture.detectChanges();
    http.expectOne(date(d)).flush(body);
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('shows the patient, doctor and department, the three intervals with +7 days pressed, and the date', () => {
    open();
    flushSlots('2026-12-02');
    expect(view.querySelector('h2')!.textContent).toBe('Follow-up');
    expect(q('follow-patient')!.textContent).toBe('Rekha Shinde');
    expect(q('follow-doctor')!.textContent).toContain('Dr. Meera K.');
    expect(q('follow-doctor')!.textContent).toContain('General medicine');
    const labels = Array.from(view.querySelectorAll('.seg button')).map((b) => b.textContent!.trim());
    expect(labels).toEqual(['+7 days', '+15 days', '+1 month']);
    expect(q('interval-D7')!.getAttribute('aria-pressed')).toBe('true');
    expect(q('follow-date')!.textContent).toBe('Date: Wed, 2 Dec 2026');
  });

  it('works out +15 days and +1 month from today', () => {
    open();
    flushSlots('2026-12-02');
    q('interval-D15')!.click();
    fixture.detectChanges();
    expect(q('follow-date')!.textContent).toBe('Date: Thu, 10 Dec 2026');
    flushSlots('2026-12-10');
    q('interval-M1')!.click();
    fixture.detectChanges();
    expect(q('follow-date')!.textContent).toBe('Date: Fri, 25 Dec 2026');
    flushSlots('2026-12-25');
  });

  it('lists the earliest slots, presses the first by default and shows the fee', () => {
    open();
    flushSlots('2026-12-02');
    const slots = Array.from(view.querySelectorAll('.slotbtn'));
    expect(slots.map((s) => s.textContent!.trim())).toEqual(['10:30 AM', '10:45 AM', '11:00 AM']);
    expect(slots[0].getAttribute('aria-pressed')).toBe('true');
    expect(q('book-follow-up')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('Book follow-up · ₹200');
  });

  it('says Free for a zero fee', () => {
    open(makeDoctor({ followUpFeePaise: 0 }));
    flushSlots('2026-12-02');
    expect(q('book-follow-up')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('Book follow-up · Free');
  });

  it('offers the next free day when the date has no slot', () => {
    open();
    flushSlots('2026-12-02', makeDaySlots({ date: '2026-12-02', freeCount: 0, groups: [], nextFreeDate: '2026-12-03' }));
    expect(q('follow-no-slots')!.textContent).toBe('No slots on Wed, 2 Dec. Next free day: Thu, 3 Dec.');
    flushSlots('2026-12-03');
    expect(q('follow-offer-day')!.textContent).toBe('Slots on Thu, 3 Dec');
    expect(view.querySelectorAll('.slotbtn')).toHaveLength(3);
  });

  it('does not offer a day when the doctor has no free day soon, and keeps Book follow-up off', () => {
    open();
    flushSlots('2026-12-02', makeDaySlots({ date: '2026-12-02', freeCount: 0, groups: [], nextFreeDate: null }));
    expect(q('follow-no-slots')!.textContent).toContain('No slots on Wed, 2 Dec.');
    expect((q('book-follow-up') as HTMLButtonElement).disabled).toBe(true);
    expect((q('follow-not-needed') as HTMLButtonElement).disabled).toBe(false);
  });

  it('resets the slot choice when the interval changes', () => {
    open();
    flushSlots('2026-12-02');
    (view.querySelectorAll('.slotbtn')[2] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(view.querySelectorAll('.slotbtn')[2].getAttribute('aria-pressed')).toBe('true');
    q('interval-D15')!.click();
    flushSlots('2026-12-10');
    expect(view.querySelectorAll('.slotbtn')[0].getAttribute('aria-pressed')).toBe('true');
    expect(view.querySelectorAll('.slotbtn')[2].getAttribute('aria-pressed')).toBe('false');
  });

  it('books the follow-up with the interval, date and the chosen time', () => {
    open();
    flushSlots('2026-12-02');
    (view.querySelectorAll('.slotbtn')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    q('book-follow-up')!.click();
    fixture.detectChanges();
    expect((q('book-follow-up') as HTMLButtonElement).disabled).toBe(true);
    const req = http.expectOne(`${OPD}/visits/v1/follow-up/appointment`);
    expect(req.request.body).toEqual({ intervalKey: 'D7', apptDate: '2026-12-02', apptTime: '10:45', visitType: 'FOLLOW_UP' });
    req.flush({ followUpId: 'f1', state: 'BOOKED', dueDate: '2026-12-02', appointment: null }, { status: 201, statusText: 'Created' });
    expect(saved).toBe(1);
  });

  it('books on the next free day when the date had no slot, keeping the interval for the due date', () => {
    open();
    flushSlots('2026-12-02', makeDaySlots({ date: '2026-12-02', freeCount: 0, groups: [], nextFreeDate: '2026-12-03' }));
    flushSlots('2026-12-03');
    q('book-follow-up')!.click();
    const req = http.expectOne(`${OPD}/visits/v1/follow-up/appointment`);
    expect(req.request.body.apptDate).toBe('2026-12-03');
    expect(req.request.body.intervalKey).toBe('D7');
    req.flush({ followUpId: 'f1', state: 'BOOKED', dueDate: '2026-12-02', appointment: null });
  });

  it('keeps the panel open, shows the message and reloads the slots when the slot was just booked', () => {
    open();
    flushSlots('2026-12-02');
    q('book-follow-up')!.click();
    http
      .expectOne(`${OPD}/visits/v1/follow-up/appointment`)
      .flush(problemBody('appointment-slot-taken', 409), { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(q('follow-error')!.textContent).toBe('That slot was just booked. Pick another time.');
    expect(saved).toBe(0);
    flushSlots('2026-12-02', makeDaySlots({ date: '2026-12-02', groups: [{ label: 'MORNING', slots: [{ time: '11:00', insideCutOff: false }] }] }));
    expect(view.querySelectorAll('.slotbtn')).toHaveLength(1);
    expect(failed[0].slotTaken).toBe(true);
  });

  it('stores No follow-up needed and reports it', () => {
    open();
    flushSlots('2026-12-02');
    q('follow-not-needed')!.click();
    fixture.detectChanges();
    expect((q('follow-not-needed') as HTMLButtonElement).disabled).toBe(true);
    const req = http.expectOne(`${OPD}/visits/v1/follow-up/not-needed`);
    expect(req.request.method).toBe('POST');
    req.flush({ followUpId: 'f1', state: 'NOT_NEEDED', dueDate: TEST_TODAY, appointment: null });
    expect(saved).toBe(1);
  });

  it('shows a plain message when the visit is not completed yet', () => {
    open();
    flushSlots('2026-12-02');
    q('follow-not-needed')!.click();
    http.expectOne(`${OPD}/visits/v1/follow-up/not-needed`).flush(problemBody('visit-not-done', 409), { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(q('follow-error')!.textContent).toBe('Set the follow-up after the visit is completed.');
  });

  it('shows a connection message that allows a retry without saving twice', () => {
    open();
    flushSlots('2026-12-02');
    q('book-follow-up')!.click();
    http.expectOne(`${OPD}/visits/v1/follow-up/appointment`).error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(q('follow-error')!.textContent).toBe('Could not save. Check the connection and try again.');
    expect((q('book-follow-up') as HTMLButtonElement).disabled).toBe(false);
    expect(saved).toBe(0);
  });

  it('shows Try again when the slots cannot be read', () => {
    open();
    http.expectOne(date('2026-12-02')).flush({}, { status: 500, statusText: 'Server error' });
    fixture.detectChanges();
    expect(view.textContent).toContain('We could not load the slots. Try again.');
  });
});
