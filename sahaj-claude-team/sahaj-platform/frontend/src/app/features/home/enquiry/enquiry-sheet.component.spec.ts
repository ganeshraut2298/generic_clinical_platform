import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { EnquirySheetComponent } from './enquiry-sheet.component';
import { ENQUIRY_THANK_YOU, EnquirySource } from './sales-enquiry.model';

const URL = '/api/v1/platform/public/sales-enquiries';

describe('EnquirySheetComponent', () => {
  let fixture: ComponentFixture<EnquirySheetComponent>;
  let http: HttpTestingController;
  let dialogRef: { close: ReturnType<typeof vi.fn> };
  let snackBar: MatSnackBar;
  let snackBarOpen: ReturnType<typeof vi.spyOn>;

  async function setup(source: EnquirySource = 'BOOK_DEMO') {
    dialogRef = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [EnquirySheetComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: { source } },
        { provide: MatDialogRef, useValue: dialogRef },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    snackBar = TestBed.inject(MatSnackBar);
    snackBarOpen = vi.spyOn(snackBar, 'open');
    fixture = TestBed.createComponent(EnquirySheetComponent);
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

  async function fillValid() {
    await type('name', 'Dr. Asha Rao');
    await type('clinicCity', 'Rao Clinic, Pune');
    await type('mobile', '98220 12345');
  }

  afterEach(() => http.verify());

  describe('what the sheet asks for', () => {
    it('shows name, clinic name and city, mobile (+91) and best time to call', async () => {
      await setup();
      const text = el().textContent!;
      expect(text).toContain('Your name');
      expect(text).toContain('Clinic name and city');
      expect(text).toContain('Mobile number');
      expect(text).toContain('+91');
      expect(text).toContain('Best time to call');
      for (const label of ['Morning', 'Afternoon', 'After OPD hours']) {
        expect(text).toContain(label);
      }
    });

    it('is titled Book a demo for Book a Demo and Talk to sales for Contact sales', async () => {
      await setup('BOOK_DEMO');
      expect(el().querySelector('h2')!.textContent).toContain('Book a demo');
      TestBed.resetTestingModule();
      await setup('CONTACT_SALES');
      expect(el().querySelector('h2')!.textContent).toContain('Talk to sales');
    });

    it('starts on After OPD hours, like the mockup', async () => {
      await setup();
      const selected = el().querySelector('mat-button-toggle.mat-button-toggle-checked');
      expect(selected!.textContent).toContain('After OPD hours');
    });
  });

  describe('validation', () => {
    it('shows field errors and sends nothing when the form is empty', async () => {
      await setup();
      await submit();
      const errors = Array.from(el().querySelectorAll('mat-error')).map((e) => e.textContent!.trim());
      expect(errors).toContain('Enter your name');
      expect(errors).toContain('Enter a 10-digit mobile number');
      http.expectNone(URL);
    });

    it('rejects a mobile number with fewer than 10 digits', async () => {
      await setup();
      await type('name', 'Dr. Asha Rao');
      await type('mobile', '98220');
      await submit();
      expect(el().textContent).toContain('Enter a 10-digit mobile number');
      http.expectNone(URL);
    });

    it('rejects a 10-digit number that does not start with 6, 7, 8 or 9', async () => {
      await setup();
      await type('name', 'Dr. Asha Rao');
      await type('mobile', '1234567890');
      await submit();
      expect(el().textContent).toContain('Mobile numbers start with 6, 7, 8 or 9');
      http.expectNone(URL);
    });

    it('rejects a name that is only spaces', async () => {
      await setup();
      await type('name', '   ');
      await type('mobile', '9822012345');
      await submit();
      expect(el().textContent).toContain('Enter your name');
      http.expectNone(URL);
    });
  });

  describe('sending', () => {
    it('posts the contract fields with digits only for the mobile number', async () => {
      await setup('CONTACT_SALES');
      await fillValid();
      await submit();
      const req = http.expectOne(URL);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({
        name: 'Dr. Asha Rao',
        clinicCity: 'Rao Clinic, Pune',
        mobile: '9822012345',
        bestTime: 'AFTER_OPD',
        source: 'CONTACT_SALES',
      });
      req.flush({ id: '7f1c2d4e-0a1b-4c5d-8e9f-001122334455' }, { status: 201, statusText: 'Created' });
    });

    it('leaves clinicCity out when it is empty and sends the chosen best time', async () => {
      await setup('BOOK_DEMO');
      await type('name', 'Dr. Asha Rao');
      await type('mobile', '9822012345');
      el().querySelectorAll<HTMLButtonElement>('mat-button-toggle button')[0].click();
      await fixture.whenStable();
      await submit();
      const req = http.expectOne(URL);
      expect(req.request.body).toEqual({
        name: 'Dr. Asha Rao',
        mobile: '9822012345',
        bestTime: 'MORNING',
        source: 'BOOK_DEMO',
      });
      req.flush({ id: 'abc' }, { status: 201, statusText: 'Created' });
    });

    it('shows the thank-you message and closes only after a real 201', async () => {
      await setup();
      await fillValid();
      await submit();
      expect(snackBarOpen).not.toHaveBeenCalled();
      expect(dialogRef.close).not.toHaveBeenCalled();

      http
        .expectOne(URL)
        .flush({ id: '7f1c2d4e-0a1b-4c5d-8e9f-001122334455' }, { status: 201, statusText: 'Created' });
      expect(snackBarOpen).toHaveBeenCalledTimes(1);
      expect(snackBarOpen.mock.calls[0][0]).toBe(ENQUIRY_THANK_YOU);
      expect(ENQUIRY_THANK_YOU).toBe('Request received. We will call you within one working day.');
      expect(dialogRef.close).toHaveBeenCalledWith(true);
    });

    it('disables the button while sending, so it cannot be sent twice', async () => {
      await setup();
      await fillValid();
      await submit();
      fixture.detectChanges();
      const button = el().querySelector<HTMLButtonElement>('button[type="submit"]')!;
      expect(button.disabled).toBe(true);
      await submit();
      http.expectOne(URL).flush({ id: 'abc' }, { status: 201, statusText: 'Created' });
    });
  });

  describe('when the request fails', () => {
    async function failWith(body: string | object | null, status: number) {
      await fillValid();
      await submit();
      http.expectOne(URL).flush(body, { status, statusText: 'Error' });
      fixture.detectChanges();
      await fixture.whenStable();
    }

    function expectNothingSavedShown() {
      expect(snackBarOpen).not.toHaveBeenCalled();
      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(el().textContent).not.toContain('Request received');
    }

    function expectValuesKept() {
      expect(input('name').value).toBe('Dr. Asha Rao');
      expect(input('clinicCity').value).toBe('Rao Clinic, Pune');
      expect(input('mobile').value).toBe('98220 12345');
    }

    it('shows a clear error and keeps the values when the server fails (500)', async () => {
      await setup();
      await failWith({ title: 'Server error', status: 500 }, 500);
      const alert = el().querySelector('[role="alert"]')!;
      expect(alert.textContent).toContain('We could not send your request');
      expectNothingSavedShown();
      expectValuesKept();
    });

    it('says the server cannot be reached when the endpoint is down (no network)', async () => {
      await setup();
      await fillValid();
      await submit();
      http.expectOne(URL).error(new ProgressEvent('error'));
      fixture.detectChanges();
      await fixture.whenStable();
      expect(el().querySelector('[role="alert"]')!.textContent).toContain('could not reach Sahaj');
      expectNothingSavedShown();
      expectValuesKept();
    });

    it('treats a 404 like any other failure and shows no thank-you', async () => {
      await setup();
      await failWith('Not found', 404);
      expect(el().querySelector('[role="alert"]')!.textContent).toContain('We could not send');
      expectNothingSavedShown();
    });

    it('does not show a thank-you when the server answers 200 instead of 201', async () => {
      await setup();
      await fillValid();
      await submit();
      http.expectOne(URL).flush({ id: 'abc' }, { status: 200, statusText: 'OK' });
      fixture.detectChanges();
      await fixture.whenStable();
      expectNothingSavedShown();
      expect(el().querySelector('[role="alert"]')).not.toBeNull();
    });

    it('shows server field errors next to the fields (400)', async () => {
      await setup();
      await failWith(
        {
          type: 'https://sahaj.in/problems/validation',
          title: 'Check the form',
          status: 400,
          fieldErrors: [{ field: 'mobile', message: 'Enter a valid mobile number' }],
        },
        400,
      );
      expect(el().querySelector('mat-error')!.textContent).toContain('Enter a valid mobile number');
      expectNothingSavedShown();
      expectValuesKept();
    });

    it('explains the limit on 429', async () => {
      await setup();
      await failWith({ title: 'Too many requests', status: 429, retryAfterSeconds: 3600 }, 429);
      expect(el().querySelector('[role="alert"]')!.textContent).toContain('Too many requests');
      expectNothingSavedShown();
    });

    it('lets the visitor try again after a failure', async () => {
      await setup();
      await failWith({ title: 'Server error', status: 500 }, 500);
      await submit();
      http.expectOne(URL).flush({ id: 'abc' }, { status: 201, statusText: 'Created' });
      expect(snackBarOpen).toHaveBeenCalledTimes(1);
      expect(dialogRef.close).toHaveBeenCalledWith(true);
    });
  });

  it('closes without sending on Cancel', async () => {
    await setup();
    const cancel = Array.from(el().querySelectorAll('button')).find(
      (b) => b.textContent!.trim() === 'Cancel',
    )!;
    cancel.click();
    expect(dialogRef.close).toHaveBeenCalledWith(false);
    http.expectNone(URL);
  });
});
