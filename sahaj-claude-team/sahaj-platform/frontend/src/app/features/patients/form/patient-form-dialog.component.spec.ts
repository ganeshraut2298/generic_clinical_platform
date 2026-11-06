import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ShellStore } from '../../../core/shell/shell.store';
import { PrivacySettings } from '../../privacy/privacy.model';
import { PatientFormDialogComponent, PatientFormDialogData } from './patient-form-dialog.component';

const URL = '/api/v1/patient/patients';

describe('PatientFormDialogComponent', () => {
  let fixture: ComponentFixture<PatientFormDialogComponent>;
  let http: HttpTestingController;
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  const OFF: PrivacySettings = { consentRequired: false, promoConsent: false, recordCopy: true };

  async function setup(data: PatientFormDialogData = {}, privacy: PrivacySettings = OFF) {
    dialogRef = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [PatientFormDialogComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: ShellStore, useValue: { me: () => ({ clinic: { name: 'Sai Clinic' } }) } },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PatientFormDialogComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/platform/privacy').flush(privacy);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const input = (name: string) =>
    el().querySelector<HTMLInputElement>(`input[formControlName="${name}"]`)!;

  async function type(name: string, value: string) {
    const field = input(name);
    field.value = value;
    field.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function submit() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  it('titles itself "New patient" by default', async () => {
    await setup();
    expect(el().querySelector('h2')!.textContent).toContain('New patient');
  });

  it('titles itself "Add family member" and pre-fills the mobile when adding family', async () => {
    await setup({ familyMember: true, mobile: '9876543210' });
    expect(el().querySelector('h2')!.textContent).toContain('Add family member');
    expect(input('mobile').value).toBe('9876543210');
  });

  it('pre-fills name, age, sex and mobile from an emergency case (SAH-28) and sends them as edited', async () => {
    await setup({ name: 'Unknown male, about 40', age: 40, sex: 'M' });
    expect(input('name').value).toBe('Unknown male, about 40');
    expect(input('age').value).toBe('40');
    expect(el().querySelector('mat-button-toggle[value="M"]')!.classList).toContain('mat-button-toggle-checked');
    expect(input('mobile').value).toBe('');
    await type('name', 'Ramesh Pawar');
    await type('mobile', '9876543210');
    await submit();
    const req = http.expectOne(URL);
    expect(req.request.body).toMatchObject({ name: 'Ramesh Pawar', age: 40, sex: 'M', mobile: '9876543210' });
    req.flush({ id: 'p1' }, { status: 201, statusText: 'Created' });
  });

  it('leaves the age empty when the emergency case had none', async () => {
    await setup({ name: 'Unknown person, about ?', age: null, sex: 'O', mobile: '9876543210' });
    expect(input('age').value).toBe('');
    expect(input('mobile').value).toBe('9876543210');
  });

  it('shows field errors and sends nothing when required fields are missing', async () => {
    await setup();
    await submit();
    const errors = Array.from(el().querySelectorAll('mat-error')).map((e) => e.textContent!.trim());
    expect(errors).toContain('Enter the name');
    expect(errors).toContain('Enter the age');
    expect(errors).toContain('Enter a 10-digit mobile number');
    http.expectNone(URL);
  });

  it('rejects a mobile number that does not match the contract pattern', async () => {
    await setup();
    await type('name', 'Rohit Patil');
    await type('age', '30');
    await type('mobile', '1234567890');
    await submit();
    expect(el().textContent).toContain('Enter a 10-digit mobile number');
    http.expectNone(URL);
  });

  it('registers the patient with the optional fields and closes with the result', async () => {
    await setup();
    await type('name', 'Rohit Patil');
    await type('age', '30');
    await type('mobile', '9876543210');
    await submit();
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      name: 'Rohit Patil',
      age: 30,
      sex: 'F',
      mobile: '9876543210',
      area: null,
      language: 'mr',
      allergy: null,
      chronic: null,
      abhaLinked: false,
    });
    const patient = { id: 'p1', uhid: 'PT-00001', name: 'Rohit Patil', age: 30, sex: 'F', mobile: '9876543210' };
    req.flush(patient);
    expect(dialogRef.close).toHaveBeenCalledWith(patient);
  });

  it('shows a server field error and keeps the values on 400', async () => {
    await setup();
    await type('name', 'Rohit Patil');
    await type('age', '30');
    await type('mobile', '9876543210');
    await submit();
    http.expectOne(URL).flush(
      {
        type: 'https://sahaj.in/problems/validation',
        title: 'Check the form',
        status: 400,
        fieldErrors: [{ field: 'mobile', message: 'A patient already exists with this exact number' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();
    expect(el().querySelector('mat-error')!.textContent).toContain(
      'A patient already exists with this exact number',
    );
    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(input('name').value).toBe('Rohit Patil');
  });

  it('closes without sending on Cancel', async () => {
    await setup();
    const cancel = Array.from(el().querySelectorAll('button')).find(
      (b) => b.textContent!.trim() === 'Cancel',
    )!;
    cancel.click();
    expect(dialogRef.close).toHaveBeenCalledWith(null);
    http.expectNone(URL);
  });

  describe('consent (SAH-17)', () => {
    const ON: PrivacySettings = { consentRequired: true, promoConsent: true, recordCopy: true };
    const boxes = () => Array.from(el().querySelectorAll('mat-checkbox')).map((b) => b.textContent!.trim());

    async function fillAndSubmit() {
      await type('name', 'Rohit Patil');
      await type('age', '30');
      await type('mobile', '9876543210');
      await submit();
    }

    it('shows no consent boxes when consent is off', async () => {
      await setup();
      expect(boxes()).toEqual([]);
    });

    it('shows both boxes and the clinic line when both switches are on', async () => {
      await setup({}, ON);
      expect(boxes()).toEqual([
        'The patient agreed to the consent line',
        'The patient agreed to promotional messages',
      ]);
      expect(el().textContent).toContain('I agree that Sai Clinic may keep my health records');
    });

    it('shows only the consent box when promotional is off', async () => {
      await setup({}, { ...ON, promoConsent: false });
      expect(boxes()).toEqual(['The patient agreed to the consent line']);
    });

    it('sends the answers with the new patient, ticked or not', async () => {
      await setup({}, ON);
      (el().querySelector('mat-checkbox input') as HTMLInputElement).click();
      await fillAndSubmit();
      const req = http.expectOne(URL);
      expect(req.request.body.consents).toEqual([
        { kind: 'RECORDS', given: true },
        { kind: 'PROMOTIONAL', given: false },
      ]);
      req.flush({ id: 'p1' });
    });

    it('registers without ticking anything', async () => {
      await setup({}, ON);
      await fillAndSubmit();
      const req = http.expectOne(URL);
      expect(req.request.body.consents).toEqual([
        { kind: 'RECORDS', given: false },
        { kind: 'PROMOTIONAL', given: false },
      ]);
      req.flush({ id: 'p1' });
    });

    it('sends no consents when consent is off', async () => {
      await setup();
      await fillAndSubmit();
      const req = http.expectOne(URL);
      expect(req.request.body.consents).toBeUndefined();
      req.flush({ id: 'p1' });
    });
  });
});
