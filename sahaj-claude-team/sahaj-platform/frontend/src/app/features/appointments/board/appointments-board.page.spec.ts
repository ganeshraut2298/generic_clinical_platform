import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { istToday } from '../../../shared/format/ist-date';
import { TeamMember } from '../../config/team/team.model';
import { Appointment, BookingRules, DoctorSlotSettings } from '../appointments.model';
import { AppointmentsBoardPage } from './appointments-board.page';

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
  workingDays: [0, 1, 2, 3, 4, 5, 6],
  walkInEveryNth: 4,
  maxOnlinePerDay: 20,
  sessions: [{ startTime: '09:00', endTime: '10:00' }],
};

const RULES: BookingRules = {
  closeBeforeHours: 1,
  remindBeforeHours: 2,
  noShowAfterMinutes: 15,
  followPriority: true,
  selfBook: false,
  overbook: false,
};

const TODAY = istToday();

const BOOKED: Appointment = {
  id: 'a1',
  patientId: 'p1',
  patientName: 'Rekha Shinde',
  age: 34,
  sex: 'F',
  doctorMembershipId: 'm-doc',
  doctorName: 'Dr. Asha Kulkarni',
  apptDate: TODAY,
  apptTime: '09:00',
  status: 'BOOKED',
  visitType: 'NEW',
  source: 'PHONE',
};

const ARRIVED: Appointment = {
  ...BOOKED,
  id: 'a2',
  patientName: 'Mahesh Gaikwad',
  apptTime: '09:15',
  status: 'ARRIVED',
  tokenNo: 9,
};

describe('AppointmentsBoardPage', () => {
  let fixture: ComponentFixture<AppointmentsBoardPage>;
  let http: HttpTestingController;

  async function open(role: Role, appointments: Appointment[] = [BOOKED, ARRIVED]) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AppointmentsBoardPage);
    fixture.detectChanges();
    http.expectOne(`${PLATFORM_BASE}/team`).flush([DOCTOR]);
    http.expectOne(`${OPD_BASE}/doctor-slots`).flush([SETTINGS]);
    http.expectOne(`${OPD_BASE}/booking-rules`).flush(RULES);
    http.expectOne((r) => r.url === `${OPD_BASE}/appointments`).flush(appointments);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('computes the stat tiles from the slot template and the day\'s appointments', async () => {
    const view = await open('OWNER');
    // 09:00-10:00 at 15-minute slots gives 4 slots: 09:00, 09:15, 09:30, 09:45 (every 4th walk-in).
    // 09:00 is BOOKED, 09:15 is ARRIVED, 09:30 is free, 09:45 is kept for walk-ins.
    const tiles = Array.from(view.querySelectorAll('.aps')).map((t) => t.textContent!.trim());
    expect(tiles.some((t) => t.includes('1') && t.includes('Booked'))).toBe(true);
    expect(tiles.some((t) => t.includes('1') && t.includes('Arrived'))).toBe(true);
    expect(tiles.some((t) => t.includes('0') && t.includes('No-show'))).toBe(true);
    expect(tiles.some((t) => t.includes('1') && t.includes('Free slots'))).toBe(true);
    expect(tiles.some((t) => t.includes('1') && t.includes('Kept for walk-ins'))).toBe(true);
  });

  it('shows the booking-rule chips with a link into Clinic setup', async () => {
    const view = await open('OWNER');
    expect(view.textContent).toContain('Booking closes 1 h before');
    expect(view.textContent).toContain('No-show after 15 min');
    expect(view.textContent).toContain('Follow-ups get priority');
    expect(view.querySelector('a[href="/app/config/slots"]')).toBeTruthy();
  });

  it('lets front desk and owner book a free slot, but not a nurse', async () => {
    const owner = await open('OWNER');
    expect(Array.from(owner.querySelectorAll('button')).some((b) => b.textContent!.includes('Book this slot'))).toBe(true);
    TestBed.resetTestingModule();

    const nurse = await open('NURSE');
    expect(Array.from(nurse.querySelectorAll('button')).some((b) => b.textContent!.includes('Book this slot'))).toBe(false);
    expect(nurse.textContent).toContain('Free');
  });

  it('opens the book dialog for the free slot that was clicked', async () => {
    const view = await open('FRONT_DESK');
    const dialog = TestBed.inject(MatDialog);
    const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => ({ subscribe: () => undefined }) } as never);
    Array.from(view.querySelectorAll('button')).find((b) => b.textContent!.includes('Book this slot'))!.click();
    expect(openSpy).toHaveBeenCalled();
    const data = openSpy.mock.calls[0][1]?.data;
    expect(data).toEqual({ doctorMembershipId: 'm-doc', doctorName: 'Dr. Asha Kulkarni', apptDate: TODAY, apptTime: '09:30' });
  });

  it('opens the appointment detail dialog when a booked slot is clicked', async () => {
    const view = await open('OWNER');
    const dialog = TestBed.inject(MatDialog);
    const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => ({ subscribe: () => undefined }) } as never);
    (Array.from(view.querySelectorAll('.apcard')).find((b) => b.textContent!.includes('Rekha Shinde')) as HTMLButtonElement).click();
    expect(openSpy).toHaveBeenCalled();
  });

  it('reloads with tomorrow\'s date when the Tomorrow toggle is clicked', async () => {
    const view = await open('OWNER');
    Array.from(view.querySelectorAll('button')).find((b) => b.textContent!.trim() === 'Tomorrow')!.click();
    const req = http.expectOne((r) => r.url === `${OPD_BASE}/appointments`);
    expect(req.request.params.get('date')).not.toBe(TODAY);
    req.flush([]);
  });
});
