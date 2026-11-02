import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { TeamMember } from '../team/team.model';
import { BookingRules, DoctorSlotSettings } from '../../appointments/appointments.model';
import { SlotsPage } from './slots.page';

const PLATFORM_BASE = '/api/v1/platform';
const OPD_BASE = '/api/v1/opd';

const DOCTOR: TeamMember = {
  id: 'm-doc',
  name: 'Dr. Asha Kulkarni',
  mobileMasked: '••••••1001',
  role: 'DOCTOR',
  status: 'ACTIVE',
};

const SETTINGS: DoctorSlotSettings = {
  doctorMembershipId: 'm-doc',
  slotMinutes: 15,
  workingDays: [1, 2, 3, 4, 5],
  walkInEveryNth: 4,
  maxOnlinePerDay: 20,
  sessions: [{ startTime: '09:00', endTime: '13:00' }],
};

const RULES: BookingRules = {
  closeBeforeHours: 1,
  remindBeforeHours: 2,
  noShowAfterMinutes: 15,
  followPriority: false,
  selfBook: false,
  overbook: false,
};

describe('SlotsPage', () => {
  let fixture: ComponentFixture<SlotsPage>;
  let http: HttpTestingController;

  async function open(role: Role) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SlotsPage);
    fixture.detectChanges();
    http.expectOne(`${PLATFORM_BASE}/team`).flush([DOCTOR]);
    http.expectOne(`${OPD_BASE}/doctor-slots`).flush([SETTINGS]);
    http.expectOne(`${OPD_BASE}/booking-rules`).flush(RULES);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const el = () => fixture.nativeElement as HTMLElement;

  afterEach(() => http.verify());

  it('shows the doctor, slot length and booking rules', async () => {
    const view = await open('OWNER');
    expect(view.textContent).toContain('Dr. Asha Kulkarni');
    expect(view.textContent).toContain('Booking rules for the clinic');
    expect(view.textContent).toContain('bookable');
  });

  it('lets front desk change slot length but not the booking rules', async () => {
    await open('FRONT_DESK');
    const slotButtons = Array.from(el().querySelectorAll('.field .seg button'));
    const fifteen = slotButtons.find((b) => b.textContent!.trim() === '15 min') as HTMLButtonElement;
    expect(fifteen.disabled).toBe(false);

    const stepper = Array.from(el().querySelectorAll('.stepper button'))[0] as HTMLButtonElement;
    expect(stepper.disabled).toBe(true);
  });

  it('saves a new slot length immediately', async () => {
    await open('OWNER');
    const slotButtons = Array.from(el().querySelectorAll('.field .seg button'));
    (slotButtons.find((b) => b.textContent!.trim() === '20 min') as HTMLButtonElement).click();
    const req = http.expectOne(`${OPD_BASE}/doctor-slots/m-doc`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.slotMinutes).toBe(20);
    req.flush({ ...SETTINGS, slotMinutes: 20 });
  });

  it('rolls back a slot change that the server rejects', async () => {
    await open('OWNER');
    const slotButtons = Array.from(el().querySelectorAll('.field .seg button'));
    (slotButtons.find((b) => b.textContent!.trim() === '30 min') as HTMLButtonElement).click();
    http
      .expectOne(`${OPD_BASE}/doctor-slots/m-doc`)
      .flush({ type: 'about:blank', title: 'Bad request', status: 400, detail: 'Invalid slot length' }, { status: 400, statusText: 'Bad request' });
    fixture.detectChanges();
    const refreshed = Array.from(el().querySelectorAll('.field .seg button'));
    expect((refreshed.find((b) => b.textContent!.trim() === '15 min') as HTMLButtonElement).getAttribute('aria-pressed')).toBe('true');
  });

  it('lets only the owner change the overbook toggle', async () => {
    await open('FRONT_DESK');
    const overbookToggle = Array.from(el().querySelectorAll('.sw')).find((b) => b.getAttribute('aria-label') === 'Allow two patients in one slot') as HTMLButtonElement;
    expect(overbookToggle.disabled).toBe(true);
  });

  it('saves a booking-rule toggle for the owner', async () => {
    await open('OWNER');
    const followToggle = Array.from(el().querySelectorAll('.sw')).find((b) => b.getAttribute('aria-label') === 'Follow-up patients get priority') as HTMLButtonElement;
    followToggle.click();
    const req = http.expectOne(`${OPD_BASE}/booking-rules`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.followPriority).toBe(true);
    req.flush({ ...RULES, followPriority: true });
  });
});
