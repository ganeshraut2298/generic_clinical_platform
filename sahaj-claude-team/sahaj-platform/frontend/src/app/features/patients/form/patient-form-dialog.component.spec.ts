import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PatientFormDialogComponent, PatientFormDialogData } from './patient-form-dialog.component';

const URL = '/api/v1/patient/patients';

describe('PatientFormDialogComponent', () => {
  let fixture: ComponentFixture<PatientFormDialogComponent>;
  let http: HttpTestingController;
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  async function setup(data: PatientFormDialogData = {}) {
    dialogRef = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [PatientFormDialogComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRef },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PatientFormDialogComponent);
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
});
