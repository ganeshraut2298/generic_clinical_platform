import { of } from 'rxjs';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { PatientConsentState, PrivacySettings } from '../../privacy/privacy.model';
import { Patient, PatientSummary } from '../patient.model';
import { PatientRecordPage } from './patient-record.page';

const BASE = '/api/v1/patient/patients';

const PATIENT: Patient = {
  id: 'p1',
  uhid: 'PT-00001',
  name: 'Rohit Patil',
  age: 30,
  ageDetail: { value: 30, unit: 'YEARS', fromDateOfBirth: false },
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
  ageDetail: { value: 28, unit: 'YEARS', fromDateOfBirth: false },
  sex: 'F',
  mobile: '9876543210',
};

describe('PatientRecordPage', () => {
  let fixture: ComponentFixture<PatientRecordPage>;
  let http: HttpTestingController;

  const ON: PrivacySettings = { consentRequired: true, promoConsent: true, recordCopy: true };
  const NONE: PatientConsentState = { records: null, promotional: null };

  async function open(
    role: Role = 'FRONT_DESK',
    id = 'p1',
    privacy: PrivacySettings = ON,
    consents: PatientConsentState = NONE,
    patient: Patient = PATIENT,
  ) {
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
    http.expectOne(`${BASE}/${id}`).flush(patient);
    http.expectOne(`${BASE}/${id}/family`).flush([SISTER]);
    http.expectOne('/api/v1/platform/privacy').flush(privacy);
    http.expectOne(`${BASE}/${id}/consents`).flush(consents);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const buttonByText = (text: string) =>
    Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.includes(text)) as
      HTMLButtonElement | undefined;

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
      afterClosed: () => of(null),
    } as never);
    buttonByText('Add family member')!.click();
    expect(openSpy).toHaveBeenCalled();
    const data = openSpy.mock.calls[0][1] as { data: { familyMember: boolean; mobile: string } };
    expect(data.data).toEqual({ familyMember: true, mobile: '9876543210' });
  });

  it('opens the Edit details sheet for the owner and shows the saved answer', async () => {
    await open('OWNER');
    const dialog = TestBed.inject(MatDialog);
    const updated: Patient = {
      ...PATIENT,
      age: 1,
      ageDetail: { value: 17, unit: 'MONTHS', fromDateOfBirth: true },
      dateOfBirth: '2025-06-10',
    };
    const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(updated) } as never);
    buttonByText('Edit details')!.click();
    fixture.detectChanges();
    expect(openSpy).toHaveBeenCalled();
    const config = openSpy.mock.calls[0][1] as { data: { patient: Patient } };
    expect(config.data.patient.id).toBe('p1');
    expect(el().querySelector('[data-testid="age-line"]')!.textContent).toContain('17 months · Male');
    expect(el().querySelector('[data-testid="dob"]')!.textContent).toContain('10 Jun 2025');
  });

  it('keeps the record unchanged when the sheet is cancelled', async () => {
    await open('FRONT_DESK');
    const dialog = TestBed.inject(MatDialog);
    vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(null) } as never);
    buttonByText('Edit details')!.click();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="age-line"]')!.textContent).toContain('30 yrs');
  });

  describe('age and date of birth (SAH-73-3)', () => {
    const DATED: Patient = {
      ...PATIENT,
      age: 1,
      ageDetail: { value: 18, unit: 'MONTHS', fromDateOfBirth: true },
      dateOfBirth: '2025-06-13',
      sex: 'F',
    };

    it('shows "34 yrs · Female" for an adult and no date when the record has none', async () => {
      await open('FRONT_DESK', 'p1', ON, NONE, {
        ...PATIENT,
        age: 34,
        ageDetail: { value: 34, unit: 'YEARS', fromDateOfBirth: false },
        dateOfBirth: null,
        sex: 'F',
      });
      expect(el().querySelector('[data-testid="age-line"]')!.textContent).toContain('34 yrs · Female');
      expect(el().querySelector('[data-testid="dob"]')).toBeNull();
    });

    it('shows "18 months · Female" and the date as "13 Jun 2025"', async () => {
      await open('DOCTOR', 'p1', ON, NONE, DATED);
      expect(el().querySelector('[data-testid="age-line"]')!.textContent).toContain('18 months · Female');
      expect(el().querySelector('[data-testid="dob"]')!.textContent).toContain('13 Jun 2025');
    });

    it('shows no date when the answer carries none (billing and pharmacist)', async () => {
      const withoutDate: Patient = { ...DATED };
      delete withoutDate.dateOfBirth;
      await open('BILLING', 'p1', ON, NONE, withoutDate);
      expect(el().querySelector('[data-testid="age-line"]')!.textContent).toContain('18 months');
      expect(el().textContent).not.toContain('Date of birth');
      expect(el().textContent).not.toContain('13 Jun 2025');
    });

    it('writes family rows with the age text of the server', async () => {
      await open('FRONT_DESK');
      expect(el().querySelector('.famrow small')!.textContent).toContain('28 F');
    });
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
    http.expectOne('/api/v1/platform/privacy').flush(ON);
    http.expectOne(`${BASE}/missing/consents`).flush(NONE);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('Patient not found');
  });

  describe('consent (SAH-17)', () => {
    const AGREED: PatientConsentState = {
      records: {
        kind: 'RECORDS',
        given: true,
        recordedAt: '2026-10-24T05:35:00Z',
        recordedByName: 'Pooja',
      },
      promotional: null,
    };

    it('shows "Consent not recorded" for both kinds', async () => {
      await open();
      const text = el().textContent!;
      expect(text).toContain('Records · Consent not recorded');
      expect(text).toContain('Promotional messages · Consent not recorded');
    });

    it('shows who agreed and when', async () => {
      await open('FRONT_DESK', 'p1', ON, AGREED);
      expect(el().textContent).toContain('Records: agreed 24 Oct 2026 by Pooja');
    });

    it('hides kinds that are switched off', async () => {
      await open('FRONT_DESK', 'p1', { ...ON, promoConsent: false });
      expect(el().textContent).not.toContain('Promotional messages');
    });

    it('shows nothing when consent at first visit is off', async () => {
      await open('FRONT_DESK', 'p1', { ...ON, consentRequired: false });
      expect(el().textContent).not.toContain('Consent not recorded');
    });

    it('records a consent and shows the new state', async () => {
      await open('NURSE');
      buttonByText('Record consent')!.click();
      const req = http.expectOne(`${BASE}/p1/consents`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ kind: 'RECORDS', given: true });
      req.flush(AGREED);
      fixture.detectChanges();
      expect(el().textContent).toContain('Records: agreed 24 Oct 2026 by Pooja');
      expect(buttonByText('Withdraw')).toBeTruthy();
    });

    it('withdraws a consent as a new row', async () => {
      await open('OWNER', 'p1', ON, AGREED);
      buttonByText('Withdraw')!.click();
      const req = http.expectOne(`${BASE}/p1/consents`);
      expect(req.request.body).toEqual({ kind: 'RECORDS', given: false });
      req.flush({
        ...AGREED,
        records: { ...AGREED.records!, given: false },
      });
    });

    it('offers no buttons to roles that cannot edit patients', async () => {
      await open('DOCTOR', 'p1', ON, AGREED);
      expect(buttonByText('Withdraw')).toBeUndefined();
      expect(buttonByText('Record consent')).toBeUndefined();
      expect(el().textContent).toContain('Records: agreed');
    });
  });
});
