import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { TeamMember } from '../../config/team/team.model';
import { NewTokenDialogComponent } from './new-token-dialog.component';

const PATIENT_BASE = '/api/v1/patient';
const PLATFORM_BASE = '/api/v1/platform';
const OPD_BASE = '/api/v1/opd';

const DOCTOR: TeamMember = {
  id: 'm-doc',
  name: 'Dr. Asha Kulkarni',
  mobileMasked: '••••••1001',
  role: 'DOCTOR',
  status: 'ACTIVE',
  doctor: { feeRupees: 400 },
};

describe('NewTokenDialogComponent', () => {
  let fixture: ComponentFixture<NewTokenDialogComponent>;
  let http: HttpTestingController;
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  async function setup() {
    dialogRef = { close: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: dialogRef },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NewTokenDialogComponent);
    fixture.detectChanges();
    http.expectOne(`${PLATFORM_BASE}/team`).flush([DOCTOR]);
    fixture.detectChanges();
    await fixture.whenStable();
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

  it('lists the active doctors', async () => {
    const view = await setup();
    expect(view.textContent).toContain('Dr. Asha Kulkarni');
    expect(view.textContent).toContain('₹400');
  });

  it('searches patients once 10 digits are entered and shows the results', async () => {
    await setup();
    await typeMobile('9876543210');
    const req = http.expectOne((r) => r.url === `${PATIENT_BASE}/patients` && r.params.get('q') === '9876543210');
    req.flush({ items: [{ id: 'p1', uhid: 'PT-1', name: 'Rohit Patil', age: 30, sex: 'M', mobile: '9876543210' }], total: 1 });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el().textContent).toContain('Rohit Patil');
  });

  it('does not search before 10 digits', async () => {
    await setup();
    await typeMobile('98765');
    http.expectNone((r) => r.url === `${PATIENT_BASE}/patients`);
  });

  it('cannot issue until a patient and a doctor are picked', async () => {
    await setup();
    const issueButton = Array.from(el().querySelectorAll('button')).find((b) =>
      b.textContent!.includes('Issue token'),
    )!;
    expect(issueButton.disabled).toBe(true);
  });

  it('issues a token once a patient and doctor are picked, and shows the computed fee', async () => {
    await setup();
    await typeMobile('9876543210');
    http
      .expectOne((r) => r.url === `${PATIENT_BASE}/patients`)
      .flush({ items: [{ id: 'p1', uhid: 'PT-1', name: 'Rohit Patil', age: 30, sex: 'M', mobile: '9876543210' }], total: 1 });
    fixture.detectChanges();
    await fixture.whenStable();

    (Array.from(el().querySelectorAll('.member')).find((b) =>
      b.textContent!.includes('Rohit Patil'),
    ) as HTMLButtonElement).click();
    fixture.detectChanges();

    (Array.from(el().querySelectorAll('.docbtn')).find((b) =>
      b.textContent!.includes('Dr. Asha Kulkarni'),
    ) as HTMLButtonElement).click();
    fixture.detectChanges();

    const issueButton = Array.from(el().querySelectorAll('button')).find((b) =>
      b.textContent!.includes('Issue token'),
    ) as HTMLButtonElement;
    expect(issueButton.disabled).toBe(false);
    issueButton.click();

    const req = http.expectOne(`${OPD_BASE}/visits`);
    expect(req.request.body).toEqual({ patientId: 'p1', doctorMembershipId: 'm-doc' });
    req.flush({
      id: 'v1',
      tokenNo: 7,
      patientId: 'p1',
      patientName: 'Rohit Patil',
      age: 30,
      sex: 'M',
      doctorMembershipId: 'm-doc',
      doctorName: 'Dr. Asha Kulkarni',
      visitType: 'NEW',
      stage: 'WAITING',
      feePaise: 40000,
      workflowVersionNo: 1,
      createdAt: new Date().toISOString(),
    });
    fixture.detectChanges();
    expect(el().textContent).toContain('T-7');
    expect(el().textContent).toContain('₹400');
  });

  it('closes with null on cancel', async () => {
    await setup();
    (Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.trim() === 'Cancel') as HTMLButtonElement).click();
    expect(dialogRef.close).toHaveBeenCalledWith(null);
  });

  it('opens the SAH-8 registration form to add a new patient', async () => {
    await setup();
    await typeMobile('9876543210');
    http
      .expectOne((r) => r.url === `${PATIENT_BASE}/patients`)
      .flush({ items: [], total: 0 });
    fixture.detectChanges();
    await fixture.whenStable();
    const dialog = TestBed.inject(MatDialog);
    const openSpy = vi
      .spyOn(dialog, 'open')
      .mockReturnValue({ afterClosed: () => ({ subscribe: () => undefined }) } as never);
    (Array.from(el().querySelectorAll('.member')).find((b) =>
      b.textContent!.includes('Add new patient'),
    ) as HTMLButtonElement).click();
    expect(openSpy).toHaveBeenCalled();
  });
});
