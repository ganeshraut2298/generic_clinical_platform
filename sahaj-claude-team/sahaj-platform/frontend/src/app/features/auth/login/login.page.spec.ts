import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ReducedMotion } from '../../../core/motion/reduced-motion';
import { KineticBackgroundComponent } from '../../../shared/ui/kinetic-background/kinetic-background.component';
import { provideReducedMotion } from '../../../testing/motion-testing';
import { ALREADY_HAS_CLINIC_MESSAGE, LOCK_MESSAGE, NOT_REGISTERED_MESSAGE } from './login-copy';
import { LoginPage } from './login.page';

const AUTH_BASE = '/api/v1/platform/auth';

describe('LoginPage', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let router: Router;
  let navigate: ReturnType<typeof vi.spyOn>;

  async function open(url = '/login', reduced?: boolean) {
    TestBed.configureTestingModule({
      providers: [
        ...(reduced === undefined ? [] : [provideReducedMotion(reduced)]),
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

  beforeEach(() => {
    // jsdom has no canvas: the background then removes itself, which is the "no 2D canvas" path.
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
  });

  afterEach(() => {
    // Restore the globals first: a stub left behind by a failed test breaks every later test in the same worker.
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    http.verify();
  });

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

  describe('error timing (SAH-74-5, mockup sendotp and verify)', () => {
    const redText = 'Enter a 10-digit mobile number';

    it('shows no error on an empty field that was never touched', async () => {
      await open('/login');
      expect(root().textContent).not.toContain(redText);
      expect(root().querySelector('.mat-form-field-invalid')).toBeNull();
    });

    it('shows no error when the user moves into and out of the empty field', async () => {
      await open('/login');
      mobileInput().dispatchEvent(new Event('focus'));
      mobileInput().dispatchEvent(new Event('blur'));
      detectChanges();
      await whenStable();
      expect(root().textContent).not.toContain(redText);
      expect(root().querySelector('.mat-form-field-invalid')).toBeNull();
    });

    it('shows no error while typing a short number, before Send OTP is pressed', async () => {
      await open('/login');
      await type(mobileInput(), '98');
      mobileInput().dispatchEvent(new Event('blur'));
      detectChanges();
      await whenStable();
      expect(root().textContent).not.toContain(redText);
    });

    it('shows the error after Send OTP is pressed on an empty field, and stays on the mobile step', async () => {
      await open('/login');
      await submitForm();
      expect(root().textContent).toContain(redText);
      expect(mobileInput()).not.toBeNull();
      http.expectNone(`${AUTH_BASE}/otp/request`);
    });

    it('clears the error when the step starts again (other tab)', async () => {
      await open('/login');
      await submitForm();
      expect(root().textContent).toContain(redText);
      root().querySelectorAll<HTMLElement>('mat-button-toggle button')[1].click();
      detectChanges();
      await whenStable();
      expect(root().textContent).not.toContain(redText);
    });

    it('shows no code error on a blurred empty code field, only after Verify', async () => {
      await open('/login');
      await sendValidOtp();
      codeInput().dispatchEvent(new Event('focus'));
      codeInput().dispatchEvent(new Event('blur'));
      detectChanges();
      await whenStable();
      expect(root().querySelector('mat-error')).toBeNull();
      expect(root().querySelector('.mat-form-field-invalid')).toBeNull();
      await submitForm();
      expect(root().querySelector('mat-error')?.textContent).toContain('Enter the 6-digit code');
    });
  });

  describe('background and attributes (SAH-74-3)', () => {
    it('mounts the background once, before and outside the content wrapper', async () => {
      await open('/login');
      const layers = root().querySelectorAll('app-kinetic-background');
      expect(layers.length).toBe(1);
      const wrap = root().querySelector('.pubwrap')!;
      expect(wrap.contains(layers[0])).toBe(false);
      expect(layers[0].compareDocumentPosition(wrap) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('uses the shared component with its default inputs', async () => {
      await open('/login');
      const debug = harness.fixture.debugElement.query(
        (d) => d.componentInstance instanceof KineticBackgroundComponent,
      );
      const layer = debug.componentInstance as KineticBackgroundComponent;
      expect(layer.interactionRadius()).toBe(90);
      expect(layer.gridSpacing()).toBe(28);
      expect(layer.friction()).toBe(0.75);
      expect(layer.enabled()).toBe(true);
    });

    it('marks the header data-dot-exclude and the header, left panel and card data-surface', async () => {
      await open('/login');
      const excluded = root().querySelectorAll('[data-dot-exclude]');
      expect(excluded.length).toBe(1);
      expect(excluded[0].tagName).toBe('HEADER');
      for (const selector of ['header', '.auth-side', '.auth-card']) {
        expect(root().querySelector(selector)!.hasAttribute('data-surface')).toBe(true);
      }
    });

    it('stays usable when the 2D canvas cannot be created', async () => {
      await open('/login');
      await sendValidOtp();
      expect(root().textContent).toContain('98220 00001');
    });

    it('puts the cursor in the mobile field when the page opens, as the mockup does', async () => {
      await open('/login');
      await new Promise((resolve) => setTimeout(resolve, 60));
      expect(mobileInput().ownerDocument.activeElement).toBe(mobileInput());
    });
  });

  describe('entrance (SAH-74-1, reduced motion SAH-74-4)', () => {
    const wrapper = () => root().querySelector('.pubwrap')!;

    it('plays the entrance when reduced motion is not asked for', async () => {
      await open('/login', false);
      expect(wrapper().classList.contains('entrance')).toBe(true);
    });

    it('has no entrance when reduced motion is asked for, and the text is in place', async () => {
      await open('/login', true);
      expect(wrapper().classList.contains('entrance')).toBe(false);
      expect(root().textContent).toContain('Welcome back');
      expect(mobileInput()).not.toBeNull();
    });

    it('follows the setting without a reload, and does not replay when it goes back', async () => {
      await open('/login', false);
      const reduced = TestBed.inject(ReducedMotion).reduced as WritableSignal<boolean>;
      reduced.set(true);
      detectChanges();
      await whenStable();
      expect(wrapper().classList.contains('entrance')).toBe(false);
      reduced.set(false);
      detectChanges();
      await whenStable();
      expect(wrapper().classList.contains('entrance')).toBe(false);
    });
  });

  describe('tab highlight (SAH-74-2)', () => {
    // jsdom has no layout, so the two tabs get fixed boxes: "Log in" 0 to 60, "New clinic" 62 to 152.
    let widths: number[];
    let lefts: number[];
    const indexOf = (el: HTMLElement) =>
      Array.from(el.parentElement!.querySelectorAll('mat-button-toggle')).indexOf(el);

    beforeEach(() => {
      widths = [60, 90];
      lefts = [0, 62];
      vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockImplementation(function (this: HTMLElement) {
        return this.tagName === 'MAT-BUTTON-TOGGLE' ? widths[indexOf(this)] : 0;
      });
      vi.spyOn(HTMLElement.prototype, 'offsetLeft', 'get').mockImplementation(function (this: HTMLElement) {
        return this.tagName === 'MAT-BUTTON-TOGGLE' ? lefts[indexOf(this)] : 0;
      });
    });

    const thumb = () => root().querySelector<HTMLElement>('.seg-thumb');
    const group = () => root().querySelector<HTMLElement>('.seg')!;
    const tab = (index: number) => root().querySelectorAll<HTMLElement>('mat-button-toggle button')[index];

    async function choose(index: number) {
      tab(index).click();
      detectChanges();
      await whenStable();
    }

    it('starts behind the selected tab, hidden from assistive technology, without sliding', async () => {
      await open('/login', false);
      expect(thumb()).not.toBeNull();
      expect(thumb()!.getAttribute('aria-hidden')).toBe('true');
      expect(thumb()!.style.width).toBe('60px');
      expect(thumb()!.style.transform).toBe('translateX(0px)');
      expect(group().classList.contains('thumb-slide')).toBe(false);
      expect(group().classList.contains('thumb-ready')).toBe(true);
    });

    it('starts behind New clinic for ?mode=signup', async () => {
      await open('/login?mode=signup', false);
      expect(thumb()!.style.transform).toBe('translateX(62px)');
      expect(thumb()!.style.width).toBe('90px');
    });

    it('slides to the chosen tab while the heading changes at once', async () => {
      await open('/login', false);
      tab(1).click();
      detectChanges();
      expect(root().querySelector('h2')!.textContent).toContain('Create your clinic account');
      await whenStable();
      expect(thumb()!.style.transform).toBe('translateX(62px)');
      expect(thumb()!.style.width).toBe('90px');
      expect(group().classList.contains('thumb-slide')).toBe(true);
    });

    it('ends on the last tab when changed twice quickly', async () => {
      await open('/login', false);
      tab(1).click();
      tab(0).click();
      detectChanges();
      await whenStable();
      expect(thumb()!.style.transform).toBe('translateX(0px)');
      expect(thumb()!.style.width).toBe('60px');
      expect(root().querySelector('h2')!.textContent).toContain('Log in to your clinic');
    });

    it('changes tab with no slide when reduced motion is asked for', async () => {
      await open('/login', true);
      await choose(1);
      expect(thumb()!.style.transform).toBe('translateX(62px)');
      expect(group().classList.contains('thumb-slide')).toBe(false);
    });

    it('stops sliding when reduced motion is switched on while the page is open', async () => {
      await open('/login', false);
      await choose(1);
      expect(group().classList.contains('thumb-slide')).toBe(true);
      (TestBed.inject(ReducedMotion).reduced as WritableSignal<boolean>).set(true);
      detectChanges();
      await whenStable();
      expect(group().classList.contains('thumb-slide')).toBe(false);
    });

    it('keeps one tab selected and the group label', async () => {
      await open('/login', false);
      await choose(1);
      // A single-choice Material toggle group is a radio group: the chosen tab has aria-checked, not aria-pressed.
      const tabs = Array.from(root().querySelectorAll('mat-button-toggle button'));
      expect(tabs.every((button) => button.getAttribute('role') === 'radio')).toBe(true);
      const selected = tabs.filter((button) => button.getAttribute('aria-checked') === 'true');
      expect(selected.length).toBe(1);
      expect(selected[0].textContent!.trim()).toBe('New clinic');
      expect(group().getAttribute('aria-label')).toBe('Account');
    });

    it('lines up again, without a slide, when the group is resized', async () => {
      // The Material form field also watches its label with a ResizeObserver, so the stub needs the whole API.
      const callbacks: ResizeObserverCallback[] = [];
      vi.stubGlobal(
        'ResizeObserver',
        class {
          constructor(callback: ResizeObserverCallback) {
            callbacks.push(callback);
          }
          observe = vi.fn();
          unobserve = vi.fn();
          disconnect = vi.fn();
        },
      );
      await open('/login', false);
      await choose(1);
      widths = [70, 100];
      lefts = [0, 72];
      callbacks.forEach((callback) => callback([], {} as ResizeObserver));
      detectChanges();
      await whenStable();
      expect(thumb()!.style.transform).toBe('translateX(72px)');
      expect(thumb()!.style.width).toBe('100px');
      expect(group().classList.contains('thumb-slide')).toBe(false);
    });

    it('keeps the toggle own highlight when the tabs cannot be measured', async () => {
      widths = [0, 0];
      await open('/login', false);
      expect(thumb()).toBeNull();
      expect(group().classList.contains('thumb-ready')).toBe(false);
    });
  });
});
