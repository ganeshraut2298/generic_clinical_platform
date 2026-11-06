import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { AdminAuthService } from '../auth/admin-auth.service';
import { AdminSignInPage } from './admin-sign-in.page';

const BASE = '/api/v1/admin/auth';
const PRE_AUTH = 'A'.repeat(43);
const CODES = Array.from({ length: 10 }, (_, i) => `ABCD-EFGH-IJKL-MN${String.fromCharCode(65 + i)}${i % 6 + 2}`);

const ADMIN = { id: 'id-1', name: 'Neha Rao', role: 'PLATFORM_ADMIN', roleLabel: 'Platform admin' };
const ENROLMENT = {
  secretKey: 'JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP',
  otpauthUri:
    'otpauth://totp/Sahaj%20admin:neha%40sahaj.in?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Sahaj%20admin&algorithm=SHA1&digits=6&period=30',
  accountName: 'neha@sahaj.in',
  issuer: 'Sahaj admin',
  digits: 6,
  periodSeconds: 30,
  algorithm: 'SHA1',
};

function problem(status: number, slug: string, extra: Record<string, unknown> = {}) {
  return {
    body: { type: `https://sahaj.in/problems/${slug}`, title: 'Server words', status, ...extra },
    init: { status, statusText: 'Error', headers: { 'Content-Type': 'application/problem+json' } },
  };
}

describe('AdminSignInPage', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;
  let snack: ReturnType<typeof vi.spyOn>;

  async function open(url = '/admin/sign-in') {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            { path: 'admin/sign-in', component: AdminSignInPage },
            { path: 'admin/clinics', children: [] },
            { path: '', children: [] },
          ],
          withComponentInputBinding(),
        ),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    snack = vi.spyOn(TestBed.inject(MatSnackBar), 'open');
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
  }

  const root = () => harness.routeNativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => root().querySelector<T>(sel);
  const text = () => root().textContent ?? '';

  async function settle() {
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  async function type(selector: string, value: string) {
    const input = q<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  async function submit() {
    q<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await settle();
  }

  async function fillCredentials(code = '') {
    await type('#admin-email', 'Neha@Sahaj.in ');
    await type('#admin-password', 'correct horse battery');
    if (code) {
      await type('#admin-code', code);
    }
  }

  afterEach(() => {
    http.verify();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('shows the side panel, the card and the hint, with nothing pre-filled (criteria 2, 3)', async () => {
    await open();
    expect(text()).toContain('Sahaj team only');
    expect(q('h1')!.textContent).toBe('Admin console');
    expect(text()).toContain(
      'For the Sahaj team to look after clinics on the platform, approve sensitive actions, and configure plans, features and templates.',
    );
    expect(text()).toContain("Every action is logged with the admin's name and a reason");
    expect(text()).toContain('Patient records stay private: admins see counts, not patients');
    expect(text()).toContain("Support sessions need the clinic owner's OTP consent");
    expect(text()).toContain('Back to website');
    expect(q('h2')!.textContent).toBe('Sign in');

    const labels = Array.from(root().querySelectorAll('mat-label')).map((l) => l.textContent);
    expect(labels).toEqual(['Work email', 'Password', 'Code from authenticator app']);
    expect(q<HTMLInputElement>('#admin-email')!.value).toBe('');
    expect(q<HTMLInputElement>('#admin-password')!.value).toBe('');
    expect(q<HTMLInputElement>('#admin-code')!.value).toBe('');
    expect(q<HTMLInputElement>('#admin-email')!.autocomplete).toBe('username');
    expect(q<HTMLInputElement>('#admin-password')!.type).toBe('password');
    expect(q<HTMLInputElement>('#admin-password')!.autocomplete).toBe('current-password');
    expect(q<HTMLInputElement>('#admin-code')!.autocomplete).toBe('one-time-code');
    expect(q<HTMLInputElement>('#admin-code')!.inputMode).toBe('numeric');
    expect(q('button[type=submit]')!.textContent).toContain('Sign in to admin console');
    expect(text()).toContain(
      'Two-step sign-in is always on for admins, and sign-in works only from approved networks.',
    );
  });

  it('shows "Your session ended. Sign in again." after a session ends (criterion 14)', async () => {
    await open('/admin/sign-in?reason=ended');
    expect(text()).toContain('Your session ended. Sign in again.');
  });

  it('refuses a bad email without calling the server (criterion 4)', async () => {
    await open();
    await type('#admin-email', 'not-an-email');
    await type('#admin-password', 'x');
    await submit();
    expect(text()).toContain('Enter a valid work email');
  });

  it('shows the toast and sends nothing for a code that is not 6 digits (criterion 4)', async () => {
    await open();
    await fillCredentials('12345');
    await submit();
    expect(snack).toHaveBeenCalledWith('Enter the 6-digit code from your authenticator app', undefined, expect.anything());
  });

  it('signs in with one press: login, then verify, then opens /admin/clinics (criterion 5)', async () => {
    await open();
    await fillCredentials('482 913');
    await submit();

    const login = http.expectOne(`${BASE}/login`);
    expect(login.request.body).toEqual({ email: 'Neha@Sahaj.in', password: 'correct horse battery' });
    login.flush({ next: 'TOTP', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });

    const verify = http.expectOne(`${BASE}/totp/verify`);
    expect(verify.request.body).toEqual({ preAuthToken: PRE_AUTH, code: '482913' });
    verify.flush({ accessToken: 'jwt', expiresInSeconds: 600, admin: ADMIN });
    await settle();

    expect(snack).toHaveBeenCalledWith('Signed in as Neha Rao · Platform admin', undefined, expect.anything());
    expect(navigate).toHaveBeenCalledWith(['/admin/clinics']);
    expect(TestBed.inject(AdminAuthService).accessToken()).toBe('jwt');
  });

  it('keeps the pre-auth token when the code was empty, so the next press sends only the code', async () => {
    await open();
    await fillCredentials();
    await submit();
    http.expectOne(`${BASE}/login`).flush({ next: 'TOTP', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });
    await settle();
    expect(snack).toHaveBeenCalledWith('Enter the 6-digit code from your authenticator app', undefined, expect.anything());
    expect(document.activeElement?.id).toBe('admin-code');

    await type('#admin-code', '111222');
    await submit();
    http.expectNone(`${BASE}/login`);
    const verify = http.expectOne(`${BASE}/totp/verify`);
    expect(verify.request.body).toEqual({ preAuthToken: PRE_AUTH, code: '111222' });
    verify.flush({ accessToken: 'jwt', expiresInSeconds: 600, admin: ADMIN });
  });

  it('forgets the pre-auth token when the email or password changes', async () => {
    await open();
    await fillCredentials();
    await submit();
    http.expectOne(`${BASE}/login`).flush({ next: 'TOTP', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });
    await settle();

    await type('#admin-password', 'a different password');
    await type('#admin-code', '111222');
    await submit();
    http.expectOne(`${BASE}/login`).flush({ next: 'TOTP', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });
    http.expectOne(`${BASE}/totp/verify`).flush(problem(401, 'totp-invalid').body, problem(401, 'totp-invalid').init);
    await settle();
  });

  it('shows the single combined error for wrong credentials, from login (criterion 6)', async () => {
    await open();
    await fillCredentials('123456');
    await submit();
    const p = problem(401, 'admin-credentials-invalid');
    http.expectOne(`${BASE}/login`).flush(p.body, p.init);
    await settle();
    expect(q('[role=alert]')!.textContent).toContain('The email, password or code is not right.');
    expect(text()).not.toContain('Server words');
    http.expectNone(`${BASE}/totp/verify`);
  });

  it('shows the same error for a wrong code and lets the admin try again on the same token', async () => {
    await open();
    await fillCredentials('123456');
    await submit();
    http.expectOne(`${BASE}/login`).flush({ next: 'TOTP', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });
    const p = problem(401, 'totp-invalid');
    http.expectOne(`${BASE}/totp/verify`).flush(p.body, p.init);
    await settle();
    expect(q('[role=alert]')!.textContent).toContain('The email, password or code is not right.');

    await type('#admin-code', '654321');
    await submit();
    http.expectNone(`${BASE}/login`);
    http.expectOne(`${BASE}/totp/verify`).flush({ accessToken: 'jwt', expiresInSeconds: 600, admin: ADMIN });
  });

  it('shows the lockout, rate limit and network messages (criteria 7, 11)', async () => {
    const cases: [number, string, Record<string, unknown>, string][] = [
      [423, 'admin-sign-in-locked', { retryAfterSeconds: 900 }, 'Too many attempts. Try again in 15 min.'],
      [429, 'rate-limit', { retryAfterSeconds: 60 }, 'Too many attempts. Try again in a few minutes.'],
      [403, 'admin-network-denied', {}, 'Sign-in works only from approved networks.'],
    ];
    for (const [status, slug, extra, expected] of cases) {
      TestBed.resetTestingModule();
      await open();
      await fillCredentials();
      await submit();
      const p = problem(status, slug, extra);
      http.expectOne(`${BASE}/login`).flush(p.body, p.init);
      await settle();
      expect(q('[role=alert]')!.textContent, slug).toContain(expected);
      http.verify();
    }
  });

  it('resets after "Your sign-in took too long" (criterion 8)', async () => {
    await open();
    await fillCredentials('123456');
    await submit();
    http.expectOne(`${BASE}/login`).flush({ next: 'TOTP', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });
    const p = problem(401, 'preauth-expired');
    http.expectOne(`${BASE}/totp/verify`).flush(p.body, p.init);
    await settle();
    expect(q('[role=alert]')!.textContent).toContain('Your sign-in took too long. Start again.');

    await type('#admin-code', '222333');
    await submit();
    http.expectOne(`${BASE}/login`).flush({ next: 'TOTP', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });
    http.expectOne(`${BASE}/totp/verify`).flush(problem(401, 'totp-invalid').body, problem(401, 'totp-invalid').init);
  });

  it('first sign-in: enrolment card, then recovery codes, then /admin/clinics (criterion 9)', async () => {
    await open();
    await fillCredentials();
    await submit();
    http.expectOne(`${BASE}/login`).flush({ next: 'ENROL', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });
    const enrol = http.expectOne(`${BASE}/totp/enrol`);
    expect(enrol.request.body).toEqual({ preAuthToken: PRE_AUTH });
    enrol.flush(ENROLMENT);
    await settle();

    expect(q('h2')!.textContent).toBe('Set up your authenticator app');
    expect(text()).toContain('Add Sahaj admin to an authenticator app on your phone, then enter the 6-digit code it shows.');
    expect(text()).toContain('Key for manual entry');
    expect(text()).toContain('JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP');
    expect(text()).toContain('Copy key');
    expect(text()).toContain('Or open on your phone');
    const link = q<HTMLAnchorElement>('a.adm-uri')!;
    expect(link.getAttribute('href')).toBe(ENROLMENT.otpauthUri);
    expect(text()).toContain('Confirm and sign in');
    expect(text()).toContain('Start again');
    expect(q<HTMLInputElement>('#admin-enrol-code')!.autocomplete).toBe('one-time-code');
    expect(document.activeElement?.id).toBe('admin-step-heading');

    await type('#admin-enrol-code', '12');
    await submit();
    expect(text()).toContain('Enter the 6-digit code from your authenticator app');
    http.expectNone(`${BASE}/totp/verify`);

    await type('#admin-enrol-code', '123456');
    await submit();
    const verify = http.expectOne(`${BASE}/totp/verify`);
    expect(verify.request.body).toEqual({ preAuthToken: PRE_AUTH, code: '123456' });
    verify.flush({ accessToken: 'jwt', expiresInSeconds: 600, admin: ADMIN, recoveryCodes: CODES });
    await settle();

    expect(navigate).not.toHaveBeenCalled();
    expect(q('h2')!.textContent).toBe('Save your recovery codes');
    expect(text()).toContain('Each code signs you in once if you lose your phone. They are shown only now.');
    const shown = Array.from(root().querySelectorAll('ol li')).map((li) => li.textContent);
    expect(shown).toEqual(CODES);
    expect(text()).not.toContain('JBSW');
    expect(text()).toContain('Copy codes');

    const saved = Array.from(root().querySelectorAll('button')).find((b) =>
      b.textContent?.includes('I have saved them'),
    )!;
    saved.click();
    await settle();
    expect(navigate).toHaveBeenCalledWith(['/admin/clinics']);
    expect(text()).not.toContain(CODES[0]);
  });

  it('Start again on the enrolment card returns to an empty sign-in card', async () => {
    await open();
    await fillCredentials();
    await submit();
    http.expectOne(`${BASE}/login`).flush({ next: 'ENROL', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });
    http.expectOne(`${BASE}/totp/enrol`).flush(ENROLMENT);
    await settle();

    const again = Array.from(root().querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Start again'),
    )!;
    again.click();
    await settle();
    expect(q('h2')!.textContent).toBe('Sign in');
    expect(q<HTMLInputElement>('#admin-email')!.value).toBe('');
    expect(q<HTMLInputElement>('#admin-password')!.value).toBe('');
    expect(text()).not.toContain('JBSW');
  });

  it('goes back to the sign-in card when the enrolment pre-auth token has expired', async () => {
    await open();
    await fillCredentials();
    await submit();
    http.expectOne(`${BASE}/login`).flush({ next: 'ENROL', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });
    http.expectOne(`${BASE}/totp/enrol`).flush(ENROLMENT);
    await settle();

    await type('#admin-enrol-code', '123456');
    await submit();
    const p = problem(401, 'preauth-expired');
    http.expectOne(`${BASE}/totp/verify`).flush(p.body, p.init);
    await settle();
    expect(q('h2')!.textContent).toBe('Sign in');
    expect(q('[role=alert]')!.textContent).toContain('Your sign-in took too long. Start again.');
    expect(text()).not.toContain('JBSW');
  });

  it('signs in with a recovery code instead of the code (criterion 10)', async () => {
    await open();
    const toggle = Array.from(root().querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Use a recovery code'),
    )!;
    toggle.click();
    await settle();
    expect(q('#admin-code')).toBeNull();
    expect(root().querySelectorAll('mat-label')[2].textContent).toBe('Recovery code');
    expect(q<HTMLInputElement>('#admin-recovery')!.placeholder).toBe('XXXX-XXXX-XXXX-XXXX');

    await type('#admin-email', 'neha@sahaj.in');
    await type('#admin-password', 'correct horse battery');
    await type('#admin-recovery', 'abcd-efgh-ijkl-mnop');
    await submit();
    http.expectOne(`${BASE}/login`).flush({ next: 'TOTP', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });
    const verify = http.expectOne(`${BASE}/totp/verify`);
    expect(verify.request.body).toEqual({ preAuthToken: PRE_AUTH, recoveryCode: 'abcd-efgh-ijkl-mnop' });
    verify.flush({ accessToken: 'jwt', expiresInSeconds: 600, admin: ADMIN });
    await settle();
    expect(navigate).toHaveBeenCalledWith(['/admin/clinics']);
  });

  it('refuses a malformed recovery code without calling the server', async () => {
    await open();
    Array.from(root().querySelectorAll('button'))
      .find((b) => b.textContent?.includes('Use a recovery code'))!
      .click();
    await settle();
    await type('#admin-email', 'neha@sahaj.in');
    await type('#admin-password', 'correct horse battery');
    await type('#admin-recovery', 'nope');
    await submit();
    expect(q('[role=alert]')!.textContent).toContain('Enter the recovery code as XXXX-XXXX-XXXX-XXXX');
  });

  it('never leaves the password or the pre-auth token in storage, the URL or the console', async () => {
    const log = vi.spyOn(console, 'log');
    const warn = vi.spyOn(console, 'warn');
    const error = vi.spyOn(console, 'error');
    await open();
    await fillCredentials('123456');
    await submit();
    http.expectOne(`${BASE}/login`).flush({ next: 'TOTP', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });
    http.expectOne(`${BASE}/totp/verify`).flush({ accessToken: 'jwt', expiresInSeconds: 600, admin: ADMIN });
    await settle();
    const everything = [...log.mock.calls, ...warn.mock.calls, ...error.mock.calls].flat().join(' ');
    expect(everything).not.toContain('correct horse battery');
    expect(everything).not.toContain(PRE_AUTH);
    expect(location.href).not.toContain(PRE_AUTH);
    log.mockRestore();
    warn.mockRestore();
    error.mockRestore();
  });
});
