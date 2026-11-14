import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { BookAppointmentDialogComponent, BookAppointmentDialogData } from './book-appointment-dialog.component';

const PATIENT_BASE = '/api/v1/patient';
const OPD_BASE = '/api/v1/opd';

const DIALOG_DATA: BookAppointmentDialogData = {
  doctorMembershipId: 'm-doc',
  doctorName: 'Dr. Asha Kulkarni',
  apptDate: '2026-10-24',
  apptTime: '09:00',
};

describe('BookAppointmentDialogComponent', () => {
  let fixture: ComponentFixture<BookAppointmentDialogComponent>;
  let http: HttpTestingController;
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  function setup() {
    dialogRef = { close: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: DIALOG_DATA },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(BookAppointmentDialogComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const el = () => fixture.nativeElement as HTMLElement;

  async function typeMobile(value: string) {
    const input = el().querySelector<HTMLInputElement>('input')!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 350));
  }

  afterEach(() => http.verify());

  it('shows the fixed doctor and slot time', () => {
    const view = setup();
    expect(view.textContent).toContain('Dr. Asha Kulkarni');
    expect(view.textContent).toContain('9:00 AM');
  });

  it('cannot book until a patient is picked', () => {
    setup();
    const bookButton = Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.includes('Book appointment'))!;
    expect(bookButton.disabled).toBe(true);
  });

  it('shows the mockup hint under the Add family member option (SAH-73-1)', async () => {
    setup();
    await typeMobile('9876543210');
    http
      .expectOne((r) => r.url === `${PATIENT_BASE}/patients`)
      .flush({ items: [{ id: 'p1', uhid: 'PT-1', name: 'Rohit Patil', age: 30, sex: 'M', mobile: '9876543210' }], total: 1 });
    fixture.detectChanges();
    await fixture.whenStable();
    const option = Array.from(el().querySelectorAll('.member')).find((b) => b.textContent!.includes('Add family member'))!;
    expect(option.querySelector('small')!.textContent!.trim()).toBe('Name, age and sex are enough. ABHA can be added later.');
  });

  it('books the fixed slot with the chosen visit type and source', async () => {
    setup();
    await typeMobile('9876543210');
    http
      .expectOne((r) => r.url === `${PATIENT_BASE}/patients`)
      .flush({ items: [{ id: 'p1', uhid: 'PT-1', name: 'Rohit Patil', age: 30, sex: 'M', mobile: '9876543210' }], total: 1 });
    fixture.detectChanges();
    await fixture.whenStable();

    (Array.from(el().querySelectorAll('.member')).find((b) => b.textContent!.includes('Rohit Patil')) as HTMLButtonElement).click();
    fixture.detectChanges();

    (Array.from(el().querySelectorAll('.seg-wrap button')).find((b) => b.textContent!.trim() === 'Follow-up') as HTMLButtonElement).click();
    (Array.from(el().querySelectorAll('.seg-wrap button')).find((b) => b.textContent!.trim() === 'Phone') as HTMLButtonElement).click();
    fixture.detectChanges();

    const bookButton = Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.includes('Book appointment')) as HTMLButtonElement;
    expect(bookButton.disabled).toBe(false);
    bookButton.click();

    const req = http.expectOne(`${OPD_BASE}/appointments`);
    expect(req.request.body).toEqual({
      patientId: 'p1',
      doctorMembershipId: 'm-doc',
      apptDate: '2026-10-24',
      apptTime: '09:00',
      visitType: 'FOLLOW_UP',
      source: 'PHONE',
      note: null,
    });
    req.flush({
      id: 'a1',
      patientId: 'p1',
      patientName: 'Rohit Patil',
      doctorMembershipId: 'm-doc',
      doctorName: 'Dr. Asha Kulkarni',
      apptDate: '2026-10-24',
      apptTime: '09:00',
      status: 'BOOKED',
      visitType: 'FOLLOW_UP',
      source: 'PHONE',
    });
    fixture.detectChanges();
    expect(el().textContent).toContain('Appointment booked');
    expect(el().textContent).toContain('Rohit Patil');
  });

  it('shows the server error when booking fails, for example a 409 on a double booking', async () => {
    setup();
    await typeMobile('9876543210');
    http
      .expectOne((r) => r.url === `${PATIENT_BASE}/patients`)
      .flush({ items: [{ id: 'p1', uhid: 'PT-1', name: 'Rohit Patil', age: 30, sex: 'M', mobile: '9876543210' }], total: 1 });
    fixture.detectChanges();
    await fixture.whenStable();
    (Array.from(el().querySelectorAll('.member')).find((b) => b.textContent!.includes('Rohit Patil')) as HTMLButtonElement).click();
    fixture.detectChanges();

    const bookButton = Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.includes('Book appointment')) as HTMLButtonElement;
    bookButton.click();
    http.expectOne(`${OPD_BASE}/appointments`).flush(
      { type: 'about:blank', title: 'Conflict', status: 409, detail: 'This slot is already booked.' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(el().textContent).toContain('This slot is already booked.');
  });

  it('closes with null on cancel', () => {
    setup();
    (Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.trim() === 'Cancel') as HTMLButtonElement).click();
    expect(dialogRef.close).toHaveBeenCalledWith(null);
  });
});
