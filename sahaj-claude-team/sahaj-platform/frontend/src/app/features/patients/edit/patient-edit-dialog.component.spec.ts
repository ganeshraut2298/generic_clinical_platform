import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { addDays, istToday } from '../../../shared/format/ist-date';
import { Patient } from '../patient.model';
import { PatientEditDialogComponent } from './patient-edit-dialog.component';

const URL = '/api/v1/patient/patients/p1';

const UNDATED: Patient = {
  id: 'p1',
  uhid: 'PT-00001',
  name: 'Aarav Patil',
  age: 5,
  ageDetail: { value: 5, unit: 'YEARS', fromDateOfBirth: false },
  dateOfBirth: null,
  sex: 'M',
  mobile: '9876543210',
  language: 'mr',
  abhaLinked: false,
  createdAt: '2026-01-01T00:00:00Z',
};

describe('PatientEditDialogComponent', () => {
  let fixture: ComponentFixture<PatientEditDialogComponent>;
  let http: HttpTestingController;
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  async function setup(patient: Patient = UNDATED) {
    dialogRef = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [PatientEditDialogComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: { patient } },
        { provide: MatDialogRef, useValue: dialogRef },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PatientEditDialogComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const dob = () => el().querySelector<HTMLInputElement>('input[data-field="dateOfBirth"]')!;
  const ageBox = () => el().querySelector<HTMLInputElement>('input[data-field="age"]');
  const preview = () => el().querySelector<HTMLInputElement>('[data-testid="age-preview"]');

  async function typeDate(value: string) {
    dob().value = value;
    dob().dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function save() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  it('shows only Date of birth and Age, with Save and Cancel', async () => {
    await setup();
    expect(el().querySelector('h2')!.textContent).toContain('Edit details');
    expect(el().querySelectorAll('input').length).toBe(2);
    const buttons = Array.from(el().querySelectorAll('button')).map((b) => b.textContent!.trim());
    expect(buttons).toContain('Save');
    expect(buttons).toContain('Cancel');
  });

  it('adds a date to a patient with no date and sends only the date', async () => {
    await setup();
    expect(ageBox()!.value).toBe('5');
    const date = addDays(istToday(), -30);
    await typeDate(date);
    expect(preview()!.value).toBe('4 weeks');
    await save();
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ dateOfBirth: date });
    const updated = { ...UNDATED, dateOfBirth: date };
    req.flush(updated);
    expect(dialogRef.close).toHaveBeenCalledWith(updated);
  });

  it('shows the date of a dated patient with Age read-only, and sends a corrected date', async () => {
    const date = addDays(istToday(), -30);
    await setup({ ...UNDATED, dateOfBirth: date, ageDetail: { value: 4, unit: 'WEEKS', fromDateOfBirth: true } });
    expect(dob().value).toBe(date);
    expect(preview()!.value).toBe('4 weeks');
    const corrected = addDays(istToday(), -60);
    await typeDate(corrected);
    await save();
    const req = http.expectOne(URL);
    expect(req.request.body).toEqual({ dateOfBirth: corrected });
    req.flush({ ...UNDATED, dateOfBirth: corrected });
  });

  it('clears the date with an explicit null and pre-fills Age with the years worked out', async () => {
    const date = '2020-03-04';
    await setup({ ...UNDATED, dateOfBirth: date, ageDetail: { value: 6, unit: 'YEARS', fromDateOfBirth: true } });
    await typeDate('');
    expect(preview()).toBeNull();
    const years = Number(ageBox()!.value);
    expect(years).toBeGreaterThanOrEqual(6);
    await save();
    const req = http.expectOne(URL);
    expect(req.request.body).toEqual({ dateOfBirth: null, age: years });
    expect(JSON.stringify(req.request.body)).toContain('"dateOfBirth":null');
    req.flush({ ...UNDATED, dateOfBirth: null, age: years });
  });

  it('sends the typed age when the date stays empty and only the age changed', async () => {
    await setup();
    ageBox()!.value = '6';
    ageBox()!.dispatchEvent(new Event('input'));
    await save();
    const req = http.expectOne(URL);
    expect(req.request.body).toEqual({ age: 6 });
    req.flush({ ...UNDATED, age: 6 });
  });

  it('closes without a request when nothing changed', async () => {
    await setup();
    await save();
    http.expectNone(URL);
    expect(dialogRef.close).toHaveBeenCalledWith(null);
  });

  it('refuses a future date and sends nothing', async () => {
    await setup();
    await typeDate(addDays(istToday(), 1));
    await save();
    expect(el().textContent).toContain('Date of birth cannot be in the future.');
    http.expectNone(URL);
  });

  it('stays open with the typed values and a Retry button when the save fails', async () => {
    await setup();
    const date = addDays(istToday(), -30);
    await typeDate(date);
    await save();
    http.expectOne(URL).flush({ title: 'Server error', status: 500 }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(dob().value).toBe(date);
    expect(el().querySelector('[role="alert"]')).toBeTruthy();
    expect(
      Array.from(el().querySelectorAll('button')).some((b) => b.textContent!.trim() === 'Retry'),
    ).toBe(true);
  });

  it('shows a server field error on the date', async () => {
    await setup();
    await typeDate(addDays(istToday(), -30));
    await save();
    http.expectOne(URL).flush(
      {
        title: 'Check the form',
        status: 400,
        fieldErrors: [{ field: 'dateOfBirth', message: 'Enter a valid date of birth.' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();
    expect(el().textContent).toContain('Enter a valid date of birth.');
  });

  it('closes on Cancel without a request', async () => {
    await setup();
    Array.from(el().querySelectorAll('button'))
      .find((b) => b.textContent!.trim() === 'Cancel')!
      .click();
    expect(dialogRef.close).toHaveBeenCalledWith(null);
    http.expectNone(URL);
  });
});
