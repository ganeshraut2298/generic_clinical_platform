import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { Patient, PatientSummary } from '../patient.model';
import { PatientRecordPage } from './patient-record.page';

const BASE = '/api/v1/patient/patients';

const PATIENT: Patient = {
  id: 'p1',
  uhid: 'PT-00001',
  name: 'Rohit Patil',
  age: 30,
  sex: 'M',
  mobile: '9876543210',
  area: 'Kothrud',
  language: 'mr',
  allergy: 'Penicillin',
  chronic: 'Diabetes',
  abhaLinked: true,
  createdAt: '2026-01-01T00:00:00Z',
};
const SISTER: PatientSummary = {
  id: 'p2',
  uhid: 'PT-00002',
  name: 'Sunita Patil',
  age: 28,
  sex: 'F',
  mobile: '9876543210',
};

describe('PatientRecordPage', () => {
  let fixture: ComponentFixture<PatientRecordPage>;
  let http: HttpTestingController;

  async function open(role: Role = 'FRONT_DESK', id = 'p1') {
    await TestBed.configureTestingModule({
      imports: [PatientRecordPage],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
        { provide: Router, useValue: { navigate: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id }) } },
        },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PatientRecordPage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/${id}`).flush(PATIENT);
    http.expectOne(`${BASE}/${id}/family`).flush([SISTER]);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const buttonByText = (text: string) =>
    Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.includes(text)) as
      | HTMLButtonElement
      | undefined;

  afterEach(() => http.verify());

  it('shows the header: name, age/sex, UHID, mobile, area, language and ABHA badge', async () => {
    await open();
    const text = el().textContent!;
    expect(text).toContain('Rohit Patil');
    expect(text).toContain('30 yrs');
    expect(text).toContain('PT-00001');
    expect(text).toContain('9876543210');
    expect(text).toContain('Kothrud');
    expect(text).toContain('ABHA linked');
    expect(text).toContain('Allergy: Penicillin');
    expect(text).toContain('Long-term: Diabetes');
  });

  it('shows the summary panel with "No visits yet" placeholders', async () => {
    await open();
    expect(el().textContent).toContain('No visits yet');
  });

  it('lists the family and offers "Add family member" for front desk', async () => {
    await open('FRONT_DESK');
    expect(el().textContent).toContain('Sunita Patil');
    expect(buttonByText('Add family member')).toBeTruthy();
  });

  it('hides "Add family member" and "Edit details" for a nurse', async () => {
    await open('NURSE');
    expect(buttonByText('Add family member')).toBeUndefined();
    expect(buttonByText('Edit details')).toBeUndefined();
  });

  it('opens the registration form pre-filled with the same mobile when adding a family member', async () => {
    await open('FRONT_DESK');
    const dialog = TestBed.inject(MatDialog);
    const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({
      afterClosed: () => ({ subscribe: (cb: (v: Patient | null) => void) => cb(null) }),
    } as never);
    buttonByText('Add family member')!.click();
    expect(openSpy).toHaveBeenCalled();
    const data = openSpy.mock.calls[0][1] as { data: { familyMember: boolean; mobile: string } };
    expect(data.data).toEqual({ familyMember: true, mobile: '9876543210' });
  });

  it('shows a coming-soon message for Edit details', async () => {
    await open('OWNER');
    const snackBar = TestBed.inject(MatSnackBar);
    const openSpy = vi.spyOn(snackBar, 'open');
    buttonByText('Edit details')!.click();
    expect(openSpy).toHaveBeenCalled();
  });

  it('shows "Patient not found" on a 404', async () => {
    await TestBed.configureTestingModule({
      imports: [PatientRecordPage],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role: 'FRONT_DESK' }) } },
        { provide: Router, useValue: { navigate: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'missing' }) } },
        },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PatientRecordPage);
    fixture.detectChanges();
    http
      .expectOne(`${BASE}/missing`)
      .flush({ title: 'Not found', status: 404 }, { status: 404, statusText: 'Not Found' });
    http.expectOne(`${BASE}/missing/family`).flush([]);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('Patient not found');
  });
});
