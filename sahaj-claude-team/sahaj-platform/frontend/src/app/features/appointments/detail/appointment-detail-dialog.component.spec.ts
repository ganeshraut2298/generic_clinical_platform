import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Appointment } from '../appointments.model';
import { AppointmentDetailDialogComponent, AppointmentDetailDialogData } from './appointment-detail-dialog.component';

const OPD_BASE = '/api/v1/opd';

const BOOKED: Appointment = {
  id: 'a1',
  patientId: 'p1',
  patientName: 'Rekha Shinde',
  age: 34,
  sex: 'F',
  doctorMembershipId: 'm-doc',
  doctorName: 'Dr. Asha Kulkarni',
  apptDate: '2026-10-24',
  apptTime: '09:00',
  status: 'BOOKED',
  visitType: 'NEW',
  source: 'PHONE',
};

function dataFor(appointment: Appointment, role: 'OWNER' | 'FRONT_DESK' | 'NURSE' = 'FRONT_DESK'): AppointmentDetailDialogData {
  return { appointment, whenLabel: 'Today · 9:00 AM', isToday: true, role };
}

describe('AppointmentDetailDialogComponent', () => {
  let fixture: ComponentFixture<AppointmentDetailDialogComponent>;
  let http: HttpTestingController;
  let dialogRef: { close: ReturnType<typeof vi.fn> };
  let router: { navigate: ReturnType<typeof vi.fn> };

  function setup(data: AppointmentDetailDialogData) {
    dialogRef = { close: vi.fn() };
    router = { navigate: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: Router, useValue: router },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AppointmentDetailDialogComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const button = (text: string) => Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.trim().includes(text)) as HTMLButtonElement;

  afterEach(() => http.verify());

  it('shows when, doctor, visit type, source and status', () => {
    const view = setup(dataFor(BOOKED));
    expect(view.textContent).toContain('Today · 9:00 AM');
    expect(view.textContent).toContain('Dr. Asha Kulkarni');
    expect(view.textContent).toContain('New');
    expect(view.textContent).toContain('Phone');
    expect(view.textContent).toContain('Booked');
  });

  it('shows Remind disabled with a note instead of omitting it, because WhatsApp is not in this phase', () => {
    setup(dataFor(BOOKED));
    const remind = button('Remind');
    expect(remind.disabled).toBe(true);
    expect(remind.title).toContain('WhatsApp');
  });

  it('shows Cancel, Mark no-show and Create token for a booked, today appointment for OWNER/FRONT_DESK', () => {
    setup(dataFor(BOOKED, 'OWNER'));
    expect(button('Cancel')).toBeTruthy();
    expect(button('Mark no-show')).toBeTruthy();
    expect(button('Create token')).toBeTruthy();
  });

  it('hides every write action for a role that cannot manage appointments', () => {
    setup(dataFor(BOOKED, 'NURSE'));
    expect(button('Cancel')).toBeFalsy();
    expect(button('Mark no-show')).toBeFalsy();
    expect(button('Create token')).toBeFalsy();
    expect(button('Close')).toBeTruthy();
  });

  it('does not offer Create token for tomorrow, since the patient cannot arrive yet', () => {
    setup({ appointment: BOOKED, whenLabel: 'Tomorrow · 9:00 AM', isToday: false, role: 'OWNER' });
    expect(button('Create token')).toBeFalsy();
    expect(button('Send reminder')).toBeTruthy();
  });

  it('asks to confirm before cancelling, and keeps the appointment if the user backs out', () => {
    setup(dataFor(BOOKED, 'OWNER'));
    button('Cancel').click();
    fixture.detectChanges();
    expect(el().textContent).toContain('Cancel this appointment?');
    button('Keep appointment').click();
    fixture.detectChanges();
    expect(el().textContent).not.toContain('Cancel this appointment?');
    http.expectNone(`${OPD_BASE}/appointments/a1`);
  });

  it('cancels the appointment once confirmed', () => {
    setup(dataFor(BOOKED, 'OWNER'));
    button('Cancel').click();
    fixture.detectChanges();
    button('Cancel appointment').click();
    const req = http.expectOne(`${OPD_BASE}/appointments/a1`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ status: 'CANCELLED' });
    req.flush({ ...BOOKED, status: 'CANCELLED' });
    fixture.detectChanges();
    expect(el().textContent).toContain('Cancelled');
  });

  it('marks no-show', () => {
    setup(dataFor(BOOKED, 'FRONT_DESK'));
    button('Mark no-show').click();
    const req = http.expectOne(`${OPD_BASE}/appointments/a1`);
    expect(req.request.body).toEqual({ status: 'NOSHOW' });
    req.flush({ ...BOOKED, status: 'NOSHOW' });
    fixture.detectChanges();
    expect(el().textContent).toContain('Did not come');
  });

  it('creates a token and shows the arrived status with the token number', () => {
    setup(dataFor(BOOKED, 'FRONT_DESK'));
    button('Create token').click();
    const req = http.expectOne(`${OPD_BASE}/appointments/a1/token`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...BOOKED, status: 'ARRIVED', visitId: 'v1', tokenNo: 9 });
    fixture.detectChanges();
    expect(el().textContent).toContain('Arrived');
    expect(el().textContent).toContain('T-9');
    expect(button('Open queue')).toBeTruthy();
  });

  it('navigates to the queue from an arrived appointment', () => {
    setup(dataFor({ ...BOOKED, status: 'ARRIVED', tokenNo: 9 }, 'OWNER'));
    button('Open queue').click();
    expect(router.navigate).toHaveBeenCalledWith(['/app/queue']);
    expect(dialogRef.close).toHaveBeenCalledWith('queue');
  });
});
