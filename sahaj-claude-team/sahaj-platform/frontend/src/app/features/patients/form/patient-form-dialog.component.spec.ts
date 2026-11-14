import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ShellStore } from '../../../core/shell/shell.store';
import { addDays, istToday } from '../../../shared/format/ist-date';
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
    el().querySelector<HTMLInputElement>(`input[formControlName="${name}"], input[data-field="${name}"]`)!;

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

  it('shows the mockup hint under the title for a new patient (SAH-73-1)', async () => {
    await setup();
    expect(el().querySelector('[data-testid="pf-hint"]')!.textContent!.trim()).toBe(
      'Name, age and sex are enough. ABHA can be added later.',
    );
  });

  it('shows the same hint when adding a family member (SAH-73-1)', async () => {
    await setup({ familyMember: true, mobile: '9876543210' });
    expect(el().querySelector('[data-testid="pf-hint"]')!.textContent!.trim()).toBe(
      'Name, age and sex are enough. ABHA can be added later.',
    );
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

  describe('date of birth (SAH-73-1)', () => {
    const dob = () => el().querySelector<HTMLInputElement>('input[data-field="dateOfBirth"]')!;
    const ageBox = () => el().querySelector<HTMLInputElement>('input[data-field="age"]');
    const preview = () => el().querySelector<HTMLInputElement>('[data-testid="age-preview"]');
    const labels = () =>
      Array.from(el().querySelectorAll('mat-label, .field-label')).map((l) => l.textContent!.trim());

    async function typeDate(value: string) {
      dob().value = value;
      dob().dispatchEvent(new Event('input'));
      fixture.detectChanges();
      await fixture.whenStable();
    }

    async function fillBasics() {
      await type('name', 'Baby Patil');
      await type('mobile', '9876543210');
    }

    it('shows Full name, Age, Date of birth and Sex in that order with the hint', async () => {
      await setup();
      const order = labels().filter((l) => ['Full name', 'Age', 'Date of birth', 'Sex'].includes(l));
      expect(order).toEqual(['Full name', 'Age', 'Date of birth', 'Sex']);
      expect(el().textContent).toContain('Optional. If you know it, the age is worked out for you.');
      expect(dob().type).toBe('date');
    });

    it('shows the worked-out age read-only while a date is filled, and clears it with the date', async () => {
      await setup();
      await type('age', '34');
      await typeDate(addDays(istToday(), -30));
      expect(ageBox()).toBeNull();
      expect(preview()!.readOnly).toBe(true);
      expect(preview()!.value).toBe('4 weeks');
      await typeDate('');
      expect(preview()).toBeNull();
      expect(ageBox()!.value).toBe('');
      expect(ageBox()!.readOnly).toBe(false);
    });

    it('sends the date and no age when a date is filled', async () => {
      await setup();
      await fillBasics();
      const date = addDays(istToday(), -30);
      await typeDate(date);
      await submit();
      const req = http.expectOne(URL);
      expect(req.request.body.dateOfBirth).toBe(date);
      expect('age' in req.request.body).toBe(false);
      req.flush({ id: 'p1' });
    });

    it('sends the typed age and no date when the date is empty', async () => {
      await setup();
      await fillBasics();
      await type('age', '34');
      await submit();
      const req = http.expectOne(URL);
      expect(req.request.body.age).toBe(34);
      expect('dateOfBirth' in req.request.body).toBe(false);
      req.flush({ id: 'p1' });
    });

    it('accepts today (a baby born today) and shows 0 days', async () => {
      await setup();
      await fillBasics();
      await typeDate(istToday());
      expect(preview()!.value).toBe('0 days');
      await submit();
      http.expectOne(URL).flush({ id: 'p1' });
    });

    it('refuses neither a date nor an age with the existing age message', async () => {
      await setup();
      await fillBasics();
      await submit();
      expect(el().textContent).toContain('Enter the age');
      http.expectNone(URL);
    });

    it('refuses a future date under the field and keeps the other values', async () => {
      await setup();
      await fillBasics();
      await typeDate(addDays(istToday(), 1));
      await submit();
      expect(el().textContent).toContain('Date of birth cannot be in the future.');
      expect(input('name').value).toBe('Baby Patil');
      http.expectNone(URL);
    });

    it('refuses a date more than 120 years ago', async () => {
      await setup();
      await fillBasics();
      await typeDate('1800-01-01');
      await submit();
      expect(el().textContent).toContain('Date of birth must be within the last 120 years.');
      http.expectNone(URL);
    });

    it('shows a server error on the date field and keeps the typed values', async () => {
      await setup();
      await fillBasics();
      await typeDate(addDays(istToday(), -30));
      await submit();
      http.expectOne(URL).flush(
        {
          type: 'https://sahaj.in/problems/validation',
          title: 'Check the form',
          status: 400,
          fieldErrors: [{ field: 'dateOfBirth', message: 'Enter a valid date of birth.' }],
        },
        { status: 400, statusText: 'Bad Request' },
      );
      fixture.detectChanges();
      expect(el().textContent).toContain('Enter a valid date of birth.');
      expect(input('name').value).toBe('Baby Patil');
      expect(dialogRef.close).not.toHaveBeenCalled();
    });

    it('does not keep the date in the address or the page title', async () => {
      await setup();
      await typeDate(addDays(istToday(), -30));
      expect(document.title).not.toContain(dob().value);
      expect(location.href).not.toContain(dob().value);
    });
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
