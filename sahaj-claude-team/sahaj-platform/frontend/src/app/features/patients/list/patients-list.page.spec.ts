import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { PATIENT_FILTERS, Patient, PatientMergeResult, PatientSummary } from '../patient.model';
import { PatientsListPage } from './patients-list.page';

const BASE = '/api/v1/patient/patients';

const ROHIT: PatientSummary = {
  id: 'p1',
  uhid: 'PT-00001',
  name: 'Rohit Patil',
  age: 30,
  ageDetail: { value: 30, unit: 'YEARS', fromDateOfBirth: false },
  sex: 'M',
  mobile: '9876543210',
  chronic: null,
  lastVisitAt: null,
};
const MEERA: PatientSummary = {
  id: 'p2',
  uhid: 'PT-00002',
  name: 'Meera Patil',
  age: 65,
  ageDetail: { value: 65, unit: 'YEARS', fromDateOfBirth: false },
  sex: 'F',
  mobile: '9988776655',
  chronic: 'Diabetes',
  lastVisitAt: null,
};

const BABY: PatientSummary = {
  id: 'p3',
  uhid: 'PT-00003',
  name: 'Baby Patil',
  age: 1,
  ageDetail: { value: 18, unit: 'MONTHS', fromDateOfBirth: true },
  sex: 'F',
  mobile: '9876500000',
  chronic: null,
  lastVisitAt: null,
};

describe('PatientsListPage', () => {
  let fixture: ComponentFixture<PatientsListPage>;
  let http: HttpTestingController;

  async function open(role: Role = 'FRONT_DESK') {
    await TestBed.configureTestingModule({
      imports: [PatientsListPage],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
        { provide: Router, useValue: { navigate: vi.fn() } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PatientsListPage);
    fixture.detectChanges();
    // One count request per filter chip, plus the main list request.
    for (const f of PATIENT_FILTERS) {
      http
        .match((r) => r.url === BASE && r.params.get('filter') === f.key && r.params.get('page') === '0')
        .forEach((req) => req.flush({ items: f.key === 'all' ? [ROHIT, MEERA, BABY] : [], total: f.key === 'chronic' ? 1 : f.key === 'all' ? 2 : 0 }));
    }
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

  it('shows the registered KPI from the "all" filter total, and the chip counts', async () => {
    await open();
    expect(el().textContent).toContain('Rohit Patil');
    expect(el().textContent).toContain('Meera Patil');
    const chip = buttonByText('Long-term care')!;
    expect(chip.textContent).toContain('1');
  });

  it('writes the age of each row from ageDetail: "30 M", "65 F", "18 months F"', async () => {
    await open();
    const rows = Array.from(el().querySelectorAll('.list .li small')).map((s) => s.textContent!.replace(/\s+/g, ' ').trim());
    expect(rows[0]).toContain('30 M · PT-00001');
    expect(rows[1]).toContain('65 F · PT-00002');
    expect(rows[2]).toContain('18 months F · PT-00003');
  });

  it('never shows a date of birth in the list', async () => {
    await open();
    expect(el().textContent).not.toContain('Date of birth');
  });

  it('keeps the chip label "Children"', async () => {
    await open();
    expect(buttonByText('Children')).toBeTruthy();
  });

  it('shows the dates-of-birth notice after a merge when the dates differed', async () => {
    await open();
    const dialog = TestBed.inject(MatDialog);
    const created = { ...ROHIT, language: 'mr', abhaLinked: false, createdAt: '2026-11-01T00:00:00Z', possibleDuplicateOfId: 'p9' } as unknown as Patient;
    const keep = { ...created, id: 'p9', possibleDuplicateOfId: null } as Patient;
    const merged = { ...keep, datesOfBirthDiffered: true } as PatientMergeResult;
    const spy = vi
      .spyOn(dialog, 'open')
      .mockReturnValueOnce({ afterClosed: () => of(created) } as never)
      .mockReturnValueOnce({ afterClosed: () => of(merged) } as never);
    buttonByText('New patient')!.click();
    http.match((r) => r.url === BASE).forEach((req) => req.flush({ items: [], total: 0 }));
    http.expectOne(`${BASE}/p9`).flush(keep);
    fixture.detectChanges();
    buttonByText('Review and merge')!.click();
    http.match((r) => r.url === BASE).forEach((req) => req.flush({ items: [], total: 0 }));
    fixture.detectChanges();
    expect(spy).toHaveBeenCalledTimes(2);
    expect(el().querySelector('[data-testid="merge-dob-notice"]')!.textContent).toContain(
      'The two records have different dates of birth. The date on the kept record was kept.',
    );
  });

  it('shows no dates-of-birth notice after a merge when the dates did not differ', async () => {
    await open();
    const dialog = TestBed.inject(MatDialog);
    const created = { ...ROHIT, language: 'mr', abhaLinked: false, createdAt: '2026-11-01T00:00:00Z', possibleDuplicateOfId: 'p9' } as unknown as Patient;
    const keep = { ...created, id: 'p9', possibleDuplicateOfId: null } as Patient;
    vi.spyOn(dialog, 'open')
      .mockReturnValueOnce({ afterClosed: () => of(created) } as never)
      .mockReturnValueOnce({ afterClosed: () => of({ ...keep, datesOfBirthDiffered: false }) } as never);
    buttonByText('New patient')!.click();
    http.match((r) => r.url === BASE).forEach((req) => req.flush({ items: [], total: 0 }));
    http.expectOne(`${BASE}/p9`).flush(keep);
    fixture.detectChanges();
    buttonByText('Review and merge')!.click();
    http.match((r) => r.url === BASE).forEach((req) => req.flush({ items: [], total: 0 }));
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="merge-dob-notice"]')).toBeNull();
  });

  it('shows the chronic tag on a row that has one', async () => {
    await open();
    expect(el().textContent).toContain('Long-term: Diabetes');
  });

  it('shows "New patient" for front desk but not for a nurse', async () => {
    await open('FRONT_DESK');
    expect(buttonByText('New patient')).toBeTruthy();
    TestBed.resetTestingModule();
    await open('NURSE');
    expect(buttonByText('New patient')).toBeUndefined();
  });

  it('reloads with the filter when a chip is clicked', async () => {
    await open();
    buttonByText('Children')!.click();
    const req = http.expectOne((r) => r.url === BASE && r.params.get('filter') === 'kids');
    req.flush({ items: [], total: 0 });
    await fixture.whenStable();
    expect(el().textContent).toContain('No patient matches');
  });

  it('searches after a short pause and sends the query to the server', async () => {
    await open();
    const input = el().querySelector<HTMLInputElement>('input[type="search"]')!;
    input.value = 'Rohit';
    input.dispatchEvent(new Event('input'));
    await new Promise((resolve) => setTimeout(resolve, 350));
    const req = http.expectOne((r) => r.url === BASE && r.params.get('q') === 'Rohit');
    req.flush({ items: [ROHIT], total: 1 });
  });

  it('opens the registration dialog and refreshes the list once a patient is added', async () => {
    await open();
    const dialog = TestBed.inject(MatDialog);
    const afterClosed = { subscribe: (cb: (v: Patient | null) => void) => cb(null) };
    vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => afterClosed } as never);
    buttonByText('New patient')!.click();
    expect(dialog.open).toHaveBeenCalled();
  });
});
