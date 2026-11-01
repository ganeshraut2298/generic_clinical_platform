import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ALREADY_HAS_CLINIC_MESSAGE, LOCK_MESSAGE, NOT_REGISTERED_MESSAGE } from './login-copy';
import { LoginPage } from './login.page';

const AUTH_BASE = '/api/v1/platform/auth';

describe('LoginPage', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let router: Router;
  let navigate: ReturnType<typeof vi.spyOn>;

  async function open(url = '/login') {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            { path: 'login', component: LoginPage },
            { path: 'setup', children: [] },
            { path: 'app', children: [] },
          ],
          withComponentInputBinding(),
        ),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    return root();
  }

  const root = () => harness.routeNativeElement as HTMLElement;
  const detectChanges = () => harness.detectChanges();
  const whenStable = () => harness.fixture.whenStable();
  const mobileInput = () => root().querySelector<HTMLInputElement>('#login-mobile')!;
  const codeInput = () => root().querySelector<HTMLInputElement>('#login-code')!;

  async function type(input: HTMLInputElement, value: string) {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    detectChanges();
    await whenStable();
  }

  async function submitForm() {
    root().querySelector('form')!.dispatchEvent(new Event('submit'));
    detectChanges();
    await whenStable();
  }

  async function sendValidOtp() {
    await type(mobileInput(), '9822000001');
    await submitForm();
    http.expectOne(`${AUTH_BASE}/otp/request`).flush({ resendAfterSeconds: 30 });
    detectChanges();
    await whenStable();
  }

  afterEach(() => http.verify());

  it('shows Log in heading and Log in toggle pressed by default', async () => {
    await open('/login');
    expect(root().querySelector('h2')!.textContent).toContain('Log in to your clinic');
  });

  it('shows New clinic heading for ?mode=signup', async () => {
    await open('/login?mode=signup');
    expect(root().querySelector('h2')!.textContent).toContain('Create your clinic account');
    expect(root().textContent).toContain('Start your clinic on Sahaj');
  });

  it('shows the consent line for everyone', async () => {
    await open('/login');
    expect(root().textContent).toContain('Terms of Use and Privacy Policy');
    expect(root().textContent).toContain('Patient data is stored in India');
  });

  it('shows the three side ticks', async () => {
    await open('/login');
    const text = root().textContent!;
    expect(text).toContain('No password to remember');
    expect(text).toContain('own web address');
    expect(text).toContain('own tasks');
  });

  describe('mobile step', () => {
    it('rejects an invalid mobile number', async () => {
      await open('/login');
      await type(mobileInput(), '123');
      await submitForm();
      expect(root().textContent).toContain('Enter a 10-digit mobile number');
      http.expectNone(`${AUTH_BASE}/otp/request`);
    });

    it('sends the mode with the request', async () => {
      await open('/login?mode=signup');
      await type(mobileInput(), '9822000001');
      await submitForm();
      const req = http.expectOne(`${AUTH_BASE}/otp/request`);
      expect(req.request.body).toEqual({ mobile: '9822000001', mode: 'SIGNUP' });
      req.flush({ resendAfterSeconds: 30 });
    });

    it('locks with a clear message on a 423 response', async () => {
      await open('/login');
      await type(mobileInput(), '9822000001');
      await submitForm();
      http
        .expectOne(`${AUTH_BASE}/otp/request`)
        .flush({ title: 'Locked', status: 423 }, { status: 423, statusText: 'Locked' });
      detectChanges();
      await whenStable();
      expect(root().textContent).toContain(LOCK_MESSAGE);
    });
  });

  describe('otp step', () => {
    it('moves to the code step and shows the masked number', async () => {
      await open('/login');
      await sendValidOtp();
      expect(root().textContent).toContain('98220 00001');
    });

    it('rejects a short code', async () => {
      await open('/login');
      await sendValidOtp();
      await type(codeInput(), '123');
      await submitForm();
      expect(root().textContent).toContain('Enter the 6-digit code');
      http.expectNone(`${AUTH_BASE}/otp/verify`);
    });

    it('counts the resend timer down from 30 seconds', async () => {
      vi.useFakeTimers();
      await open('/login');
      await sendValidOtp();
      expect(root().textContent).toContain('Resend code in 0:30');
      vi.advanceTimersByTime(2000);
      detectChanges();
      expect(root().textContent).toContain('Resend code in 0:28');
      vi.useRealTimers();
    });

    it('routes to /setup when a sign-up succeeds', async () => {
      await open('/login?mode=signup');
      await sendValidOtp();
      await type(codeInput(), '123456');
      root().querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
      detectChanges();
      await whenStable();
      await submitForm();
      const req = http.expectOne(`${AUTH_BASE}/otp/verify`);
      expect(req.request.body).toEqual({
        mobile: '9822000001',
        code: '123456',
        mode: 'SIGNUP',
        consent: { termsVersion: 'v0.1', privacyVersion: 'v0.1' },
      });
      req.flush({ next: 'SETUP' });
      expect(navigate).toHaveBeenCalledWith(['/setup']);
    });

    it('blocks verify in sign-up mode until consent is checked', async () => {
      await open('/login?mode=signup');
      await sendValidOtp();
      await type(codeInput(), '123456');
      await submitForm();
      expect(root().textContent).toContain('Agree to the Terms of Use and Privacy Policy');
      http.expectNone(`${AUTH_BASE}/otp/verify`);
    });

    it('routes to /app on a successful log in', async () => {
      await open('/login');
      await sendValidOtp();
      await type(codeInput(), '123456');
      await submitForm();
      http.expectOne(`${AUTH_BASE}/otp/verify`).flush({ next: 'APP', accessToken: 'token-1' });
      expect(navigate).toHaveBeenCalledWith(['/app']);
    });

    it('tells the visitor they are logged in instead, when sign-up finds an existing clinic', async () => {
      await open('/login?mode=signup');
      await sendValidOtp();
      await type(codeInput(), '123456');
      root().querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
      detectChanges();
      await whenStable();
      const snackBar = TestBed.inject(MatSnackBar);
      const open$ = vi.spyOn(snackBar, 'open');
      await submitForm();
      http.expectOne(`${AUTH_BASE}/otp/verify`).flush({ next: 'APP', accessToken: 'token-1' });
      expect(open$.mock.calls[0][0]).toBe(ALREADY_HAS_CLINIC_MESSAGE);
      expect(navigate).toHaveBeenCalledWith(['/app']);
    });

    it('shows the lock message on 423 from verify', async () => {
      await open('/login');
      await sendValidOtp();
      await type(codeInput(), '123456');
      await submitForm();
      http
        .expectOne(`${AUTH_BASE}/otp/verify`)
        .flush({ title: 'Locked', status: 423 }, { status: 423, statusText: 'Locked' });
      detectChanges();
      await whenStable();
      expect(root().textContent).toContain(LOCK_MESSAGE);
    });

    it('shows the not-registered message for a log in with an unknown number', async () => {
      await open('/login');
      await sendValidOtp();
      await type(codeInput(), '123456');
      await submitForm();
      http.expectOne(`${AUTH_BASE}/otp/verify`).flush({ next: 'NOT_REGISTERED' });
      detectChanges();
      await whenStable();
      expect(root().textContent).toContain(NOT_REGISTERED_MESSAGE);
    });

    it('lets the visitor change the number and go back to the mobile step', async () => {
      await open('/login');
      await sendValidOtp();
      const changeNumber = Array.from(root().querySelectorAll('button')).find(
        (b) => b.textContent!.trim() === 'Change number',
      )!;
      changeNumber.click();
      detectChanges();
      await whenStable();
      expect(mobileInput()).not.toBeNull();
    });
  });

  describe('choose clinic step', () => {
    it('lists the clinics and opens the chosen one', async () => {
      await open('/login');
      await sendValidOtp();
      await type(codeInput(), '123456');
      await submitForm();
      http.expectOne(`${AUTH_BASE}/otp/verify`).flush({
        next: 'CHOOSE_CLINIC',
        selectionToken: 'sel-1',
        clinics: [
          { tenantId: 't1', name: 'Rao Clinic', area: 'Kothrud' },
          { tenantId: 't2', name: 'City Care' },
        ],
      });
      detectChanges();
      await whenStable();
      expect(root().textContent).toContain('Rao Clinic');
      expect(root().textContent).toContain('City Care');

      root().querySelector<HTMLButtonElement>('.clinic-row')!.click();
      const req = http.expectOne(`${AUTH_BASE}/select-clinic`);
      expect(req.request.body).toEqual({ selectionToken: 'sel-1', tenantId: 't1' });
      req.flush({ next: 'APP', accessToken: 'token-1' });
      expect(navigate).toHaveBeenCalledWith(['/app']);
    });
  });
});
