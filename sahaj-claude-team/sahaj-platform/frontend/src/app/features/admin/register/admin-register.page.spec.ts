import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { isSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { AdminRegisterPage } from './admin-register.page';

const UNLOCK = '/api/v1/admin/bootstrap/unlock';
const REGISTER = '/api/v1/admin/bootstrap/register';

// Throwaway values only. The real PIN is in no file and no test.
const PIN = 'throwaway-pin-A1';
const OTHER_PIN = 'throwaway-pin-B2';
const TICKET = 'T'.repeat(43);
const NAME = 'First admin';
const EMAIL = 'first.admin@example.test';
const PASSWORD = 'throwaway-password-16';

function problem(status: number, slug: string, extra: Record<string, unknown> = {}) {
  return {
    body: { type: `https://sahaj.in/problems/${slug}`, title: 'Server words', status, ...extra },
    init: { status, statusText: 'Error', headers: { 'Content-Type': 'application/problem+json' } },
  };
}

describe('AdminRegisterPage', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;
  let navigateByUrl: ReturnType<typeof vi.spyOn>;

  async function open() {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'admin/register', component: AdminRegisterPage }]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const router = TestBed.inject(Router);
    navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/admin/register');
    // Spied only now: the harness itself navigates through navigateByUrl.
    navigateByUrl = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  }

  const root = () => harness.routeNativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => root().querySelector<T>(sel);
  const text = () => root().textContent ?? '';
  const labels = () => Array.from(root().querySelectorAll('mat-label')).map((l) => l.textContent);
  const alertText = () => q('[role="alert"]')?.textContent?.trim() ?? null;
  const focusedId = () => document.activeElement?.id;
  const submitButton = () => q<HTMLButtonElement>('button[type=submit]')!;

  async function settle() {
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  async function type(selector: string, value: string) {
    const input = q<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    await settle();
  }

  async function submit() {
    q<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await settle();
  }

  async function enterPin(value = PIN) {
    await type('#reg-pin', value);
    await submit();
  }

  async function answer(req: TestRequest, kind: ReturnType<typeof problem> | 'offline' | object, status = 200) {
    if (kind === 'offline') {
      req.error(new ProgressEvent('error'));
    } else if ('init' in kind && 'body' in kind) {
      req.flush(kind.body, kind.init);
    } else {
      req.flush(kind, { status, statusText: 'OK' });
    }
    await settle();
  }

  /** The register 201 has no body (ADR-018 Revision 5). */
  async function created(req: TestRequest) {
    req.flush(null, { status: 201, statusText: 'Created' });
    await settle();
  }

  async function unlockWith(value = PIN) {
    await enterPin(value);
    await answer(http.expectOne(UNLOCK), { ticket: TICKET, expiresInSeconds: 300 });
  }

  async function fillForm(over: Partial<Record<'name' | 'email' | 'password' | 'confirm', string>> = {}) {
    await type('#reg-name', over.name ?? NAME);
    await type('#reg-email', over.email ?? EMAIL);
    await type('#reg-password', over.password ?? PASSWORD);
    await type('#reg-confirm', over.confirm ?? over.password ?? PASSWORD);
  }

  /** Reads every place the component keeps state: its signals, its forms, its own text fields. */
  function heldTexts(target?: unknown): string[] {
    const instance = (target ?? harness.routeDebugElement!.componentInstance) as Record<string, unknown>;
    const out: string[] = [];
    for (const value of Object.values(instance)) {
      if (typeof value === 'string') {
        out.push(value);
      } else if (isSignal(value)) {
        out.push(JSON.stringify(value()));
      } else if (value && typeof value === 'object' && 'getRawValue' in value) {
        out.push(JSON.stringify((value as { getRawValue: () => unknown }).getRawValue()));
      }
    }
    return out;
  }

  const heldAnywhere = (needle: string, target?: unknown) =>
    heldTexts(target).some((held) => held.includes(needle)) ||
    JSON.stringify(history.state ?? null).includes(needle) ||
    location.href.includes(needle) ||
    Array.from({ length: localStorage.length }, (_, i) => localStorage.getItem(localStorage.key(i)!)).some((v) =>
      v?.includes(needle),
    ) ||
    Array.from({ length: sessionStorage.length }, (_, i) => sessionStorage.getItem(sessionStorage.key(i)!)).some((v) =>
      v?.includes(needle),
    );

  afterEach(() => {
    http.verify();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  describe('Unlock card', () => {
    it('shows only the PIN card: side panel, one password-type field, Unlock, the hint (SAH-42-2 criterion 1)', async () => {
      await open();
      expect(text()).toContain('Sahaj team only');
      expect(q('h1')!.textContent).toBe('Create the first admin');
      expect(text()).toContain('Local setup only. This page closes by itself once an admin exists.');
      expect(q('h2')!.textContent).toBe('Unlock registration');
      expect(labels()).toEqual(['Registration PIN']);
      const pin = q<HTMLInputElement>('#reg-pin')!;
      expect(pin.type).toBe('password');
      expect(pin.autocomplete).toBe('one-time-code');
      expect(root().querySelectorAll('[autocomplete="one-time-code"]').length).toBe(1);
      expect(pin.value).toBe('');
      expect(submitButton().textContent).toContain('Unlock');
      expect(text()).toContain('Works only on your own computer while the switch in .env is on.');
      expect(q('#reg-name')).toBeNull();
      expect(q('#reg-email')).toBeNull();
      expect(q('#reg-password')).toBeNull();
      expect(focusedId()).toBe('reg-pin');
    });

    it('says "Enter the PIN." and makes no call for an empty or blank PIN (criterion 2)', async () => {
      await open();
      await submit();
      expect(text()).toContain('Enter the PIN.');
      await enterPin('   ');
      expect(text()).toContain('Enter the PIN.');
      http.expectNone(UNLOCK);
    });

    it('sends the PIN exactly as typed, in the body only (criterion 9)', async () => {
      await open();
      await enterPin(` ${PIN} `);
      const req = http.expectOne(UNLOCK);
      expect(req.request.body).toEqual({ pin: ` ${PIN} ` });
      expect(req.request.urlWithParams).not.toContain('throwaway');
      expect(req.request.headers.keys()).toEqual([]);
      expect(req.request.withCredentials).toBe(true);
      await answer(req, { ticket: TICKET, expiresInSeconds: 300 });
    });

    it('clears the field while the call runs, so the PIN is gone before the answer (rule: PIN never kept)', async () => {
      await open();
      await enterPin();
      expect(q<HTMLInputElement>('#reg-pin')!.value).toBe('');
      expect(heldAnywhere(PIN)).toBe(false);
      await answer(http.expectOne(UNLOCK), { ticket: TICKET, expiresInSeconds: 300 });
    });

    it('shows "{n} tries left" for wrong PINs 1 to 4, "1 try left" for the last, clears and focuses the field (criteria 3, 4)', async () => {
      await open();
      const expected = [
        'That PIN is not right. 4 tries left.',
        'That PIN is not right. 3 tries left.',
        'That PIN is not right. 2 tries left.',
        'That PIN is not right. 1 try left.',
      ];
      for (const [index, message] of expected.entries()) {
        await enterPin(OTHER_PIN);
        await answer(http.expectOne(UNLOCK), problem(401, 'bootstrap-pin-invalid', { triesLeft: 4 - index }));
        expect(alertText(), message).toContain(message);
        expect(q<HTMLInputElement>('#reg-pin')!.value).toBe('');
        expect(focusedId()).toBe('reg-pin');
        expect(heldAnywhere(OTHER_PIN)).toBe(false);
      }
      expect(text()).not.toContain('Server words');
    });

    it('shows the locked state for the 5th wrong PIN: alert text, field and button disabled, nothing else (SAH-42-3)', async () => {
      await open();
      await enterPin(OTHER_PIN);
      await answer(http.expectOne(UNLOCK), problem(423, 'bootstrap-locked', { retryAfterSeconds: 3600 }));
      expect(alertText()).toContain('Registration is locked. Try again in 60 min.');
      expect(q<HTMLInputElement>('#reg-pin')!.disabled).toBe(true);
      expect(submitButton().disabled).toBe(true);
      expect(text()).not.toContain('Works only on your own computer');
      expect(q('#reg-name')).toBeNull();
      expect(heldAnywhere(OTHER_PIN)).toBe(false);
      // The PIN field is disabled, so focus moves to the card heading, not the page body.
      expect(focusedId()).toBe('reg-step-heading');
      expect(document.activeElement).toBe(q('h2'));
      await submit();
      http.expectNone(UNLOCK);
    });

    it('shows the remaining minutes of a lock in force (criterion 3 of SAH-42-3: 30 min)', async () => {
      await open();
      await enterPin();
      await answer(http.expectOne(UNLOCK), problem(423, 'bootstrap-locked', { retryAfterSeconds: 1800 }));
      expect(alertText()).toContain('Registration is locked. Try again in 30 min.');
    });

    it('moves to the Create card after a correct PIN and keeps the PIN nowhere (criterion 6)', async () => {
      await open();
      await unlockWith();
      expect(q('h2')!.textContent).toBe('Create the first admin');
      expect(q('#reg-pin')).toBeNull();
      expect(heldAnywhere(PIN)).toBe(false);
      expect(focusedId()).toBe('reg-name');
      expect(navigateByUrl).not.toHaveBeenCalled();
    });

    it('disables the button with a busy label and ignores a second press (criterion 7)', async () => {
      await open();
      await enterPin();
      expect(submitButton().disabled).toBe(true);
      expect(submitButton().textContent).toContain('Unlocking');
      await submit();
      const req = http.expectOne(UNLOCK);
      await answer(req, problem(401, 'bootstrap-pin-invalid', { triesLeft: 4 }));
      expect(submitButton().disabled).toBe(false);
    });

    it('shows the offline text, keeps focus on the field and counts nothing on the page (criterion 8)', async () => {
      await open();
      await enterPin();
      await answer(http.expectOne(UNLOCK), 'offline');
      expect(alertText()).toContain('We could not reach the server. Check that the app is running and try again.');
      expect(focusedId()).toBe('reg-pin');
      expect(text()).not.toContain('tries left');
      expect(heldAnywhere(PIN)).toBe(false);
    });

    it('goes to what the app shows for an unknown address (the home page) when the server answers the empty 404, replacing the history entry (SAH-42-1 criterion 11)', async () => {
      await open();
      await enterPin();
      http.expectOne(UNLOCK).flush(null, { status: 404, statusText: 'Not Found' });
      await settle();
      expect(navigateByUrl).toHaveBeenCalledWith('/page-not-found', { replaceUrl: true });
      expect(heldAnywhere(PIN)).toBe(false);
    });

    it('shows "Enter the PIN." for a validation answer and the safety-net text for a bad-request (OQ-42-15)', async () => {
      await open();
      await enterPin();
      await answer(
        http.expectOne(UNLOCK),
        problem(400, 'validation', { fieldErrors: [{ field: 'pin', message: 'words' }] }),
      );
      expect(text()).toContain('Enter the PIN.');

      await enterPin();
      await answer(http.expectOne(UNLOCK), problem(400, 'bad-request'));
      expect(alertText()).toContain('Something went wrong. Try again.');
      expect(text()).not.toContain('Server words');
    });

    it('shows the safety-net text for 429, 500 and 413 and never the server words', async () => {
      await open();
      for (const status of [429, 500, 413]) {
        await enterPin();
        await answer(http.expectOne(UNLOCK), problem(status, 'x'));
        expect(alertText(), String(status)).toContain('Something went wrong. Try again.');
      }
    });
  });

  describe('Create the first admin card', () => {
    it('shows the fields in order with the hint and the right autocomplete (SAH-42-5 criterion 1)', async () => {
      await open();
      await unlockWith();
      expect(labels()).toEqual(['Full name', 'Work email', 'Password', 'Confirm password']);
      expect(text()).toContain('12 to 64 characters. Do not use the first part of your email.');
      expect(q<HTMLInputElement>('#reg-email')!.autocomplete).toBe('username');
      expect(q<HTMLInputElement>('#reg-password')!.autocomplete).toBe('new-password');
      expect(q<HTMLInputElement>('#reg-confirm')!.autocomplete).toBe('new-password');
      expect(q<HTMLInputElement>('#reg-password')!.type).toBe('password');
      expect(q<HTMLInputElement>('#reg-confirm')!.type).toBe('password');
      expect(root().querySelectorAll('[autocomplete="one-time-code"]').length).toBe(0);
      expect(submitButton().textContent).toContain('Create admin');
    });

    it('says the two passwords do not match and makes no call (criterion 6)', async () => {
      await open();
      await unlockWith();
      await fillForm({ password: PASSWORD, confirm: `${PASSWORD}x` });
      await submit();
      expect(text()).toContain('The two passwords do not match.');
      expect(focusedId()).toBe('reg-confirm');
      http.expectNone(REGISTER);
    });

    it('shows every error the page can find itself, focuses the first, and makes no call (criteria 7, 19)', async () => {
      await open();
      await unlockWith();
      await submit();
      expect(text()).toContain('Enter the full name.');
      expect(text()).toContain('Enter a valid email.');
      expect(text()).toContain(
        'The password must be 12 to 64 characters and must not contain the first part of your email.',
      );
      expect(focusedId()).toBe('reg-name');
      http.expectNone(REGISTER);
    });

    it('checks the limits: name over 120, email without @ or over 254, password 11 or 65 characters', async () => {
      await open();
      await unlockWith();
      await fillForm({ name: 'n'.repeat(121), email: 'no-at-sign', password: 'x'.repeat(11) });
      await submit();
      expect(text()).toContain('Enter the full name.');
      expect(text()).toContain('Enter a valid email.');
      expect(text()).toContain('The password must be 12 to 64 characters');

      await fillForm({ name: 'n'.repeat(120), email: `${'e'.repeat(250)}@x.io`, password: 'x'.repeat(65) });
      await submit();
      expect(text()).toContain('Enter a valid email.');
      expect(text()).toContain('The password must be 12 to 64 characters');
      http.expectNone(REGISTER);
    });

    it('sends the trimmed name and email, the password, the ticket, and no confirm field (criterion 2)', async () => {
      await open();
      await unlockWith();
      await fillForm({ name: `  ${NAME} `, email: ` ${EMAIL} ` });
      await submit();
      const req = http.expectOne(REGISTER);
      expect(req.request.body).toEqual({ ticket: TICKET, fullName: NAME, email: EMAIL, password: PASSWORD });
      expect(req.request.urlWithParams).toBe(REGISTER);
      expect(req.request.headers.keys()).toEqual([]);
      expect(req.request.withCredentials).toBe(true);
      expect(submitButton().disabled).toBe(true);
      expect(submitButton().textContent).toContain('Creating admin');
      // A second press while the call runs does nothing (criterion 15).
      await submit();
      await created(req);
    });

    it('clears both password fields as the call is sent, so they are not kept (SAH-42-6)', async () => {
      await open();
      await unlockWith();
      await fillForm();
      await submit();
      const req = http.expectOne(REGISTER);
      expect(q<HTMLInputElement>('#reg-password')!.value).toBe('');
      expect(q<HTMLInputElement>('#reg-confirm')!.value).toBe('');
      expect(heldAnywhere(PASSWORD)).toBe(false);
      await created(req);
    });

    it('after success drops everything and goes to the normal sign-in, keeping nothing (criterion 12)', async () => {
      await open();
      await unlockWith();
      await fillForm();
      await submit();
      await created(http.expectOne(REGISTER));
      expect(navigate).toHaveBeenCalledWith(['/admin/sign-in']);
      expect(heldAnywhere(TICKET)).toBe(false);
      expect(heldAnywhere(PASSWORD)).toBe(false);
      expect(heldAnywhere(EMAIL)).toBe(false);
    });

    it('shows the field errors with the page texts, keeps the ticket, and the owner can send again (criteria 4, 19)', async () => {
      await open();
      await unlockWith();
      await fillForm();
      await submit();
      await answer(
        http.expectOne(REGISTER),
        problem(400, 'validation', {
          fieldErrors: [
            { field: 'fullName', message: 'server words' },
            { field: 'email', message: 'server words' },
          ],
        }),
      );
      expect(text()).toContain('Enter the full name.');
      expect(text()).toContain('Enter a valid email.');
      expect(text()).not.toContain('server words');
      expect(focusedId()).toBe('reg-name');
      expect(q<HTMLInputElement>('#reg-password')!.value).toBe('');

      // Correct and send again: the same ticket goes again, nothing was reset to the Unlock card.
      expect(q('#reg-pin')).toBeNull();
      await fillForm();
      await submit();
      const again = http.expectOne(REGISTER);
      expect(again.request.body.ticket).toBe(TICKET);
      await created(again);
    });

    it('shows the password text when the server rejects the password', async () => {
      await open();
      await unlockWith();
      await fillForm({ password: 'first.admin-0000' });
      await submit();
      await answer(
        http.expectOne(REGISTER),
        problem(400, 'validation', { fieldErrors: [{ field: 'password', message: 'server words' }] }),
      );
      expect(text()).toContain('The password must be 12 to 64 characters and must not contain the first part of your email.');
      expect(focusedId()).toBe('reg-password');
    });

    it('returns to the Unlock card with "Your unlock took too long. Enter the PIN again." on 401 (SAH-42-4)', async () => {
      await open();
      await unlockWith();
      await fillForm();
      await submit();
      await answer(http.expectOne(REGISTER), problem(401, 'bootstrap-ticket-invalid'));
      expect(q('h2')!.textContent).toBe('Unlock registration');
      expect(alertText()).toContain('Your unlock took too long. Enter the PIN again.');
      expect(q('#reg-name')).toBeNull();
      expect(focusedId()).toBe('reg-pin');
      expect(heldAnywhere(TICKET)).toBe(false);
      expect(heldAnywhere(EMAIL)).toBe(false);
      expect(heldAnywhere(PASSWORD)).toBe(false);
    });

    it('returns to the Unlock card in the locked state when the register call answers 423 (SAH-42-3 criterion 9)', async () => {
      await open();
      await unlockWith();
      await fillForm();
      await submit();
      await answer(http.expectOne(REGISTER), problem(423, 'bootstrap-locked', { retryAfterSeconds: 3000 }));
      expect(q('h2')!.textContent).toBe('Unlock registration');
      expect(alertText()).toContain('Registration is locked. Try again in 50 min.');
      expect(q<HTMLInputElement>('#reg-pin')!.disabled).toBe(true);
      expect(submitButton().disabled).toBe(true);
      expect(focusedId()).toBe('reg-step-heading');
      expect(heldAnywhere(TICKET)).toBe(false);
    });

    it('goes where the app sends an unknown address when the register call answers the empty 404 (admin exists)', async () => {
      await open();
      await unlockWith();
      await fillForm();
      await submit();
      http.expectOne(REGISTER).flush(null, { status: 404, statusText: 'Not Found' });
      await settle();
      expect(navigateByUrl).toHaveBeenCalledWith('/page-not-found', { replaceUrl: true });
      expect(heldAnywhere(TICKET)).toBe(false);
      expect(heldAnywhere(EMAIL)).toBe(false);
    });

    it('shows the safety-net line for a 400 bad-request, no field error, and keeps the ticket (OQ-42-15)', async () => {
      await open();
      await unlockWith();
      await fillForm();
      await submit();
      await answer(http.expectOne(REGISTER), problem(400, 'bad-request'));
      expect(alertText()).toContain('Something went wrong. Try again.');
      expect(q('mat-error')).toBeNull();
      expect(q('h2')!.textContent).toBe('Create the first admin');
      await fillForm();
      await submit();
      expect(http.expectOne(REGISTER).request.body.ticket).toBe(TICKET);
      http.verify();
    });

    it('shows the offline text and keeps the form open with the ticket (BR17)', async () => {
      await open();
      await unlockWith();
      await fillForm();
      await submit();
      await answer(http.expectOne(REGISTER), 'offline');
      expect(alertText()).toContain('We could not reach the server. Check that the app is running and try again.');
      expect(q('h2')!.textContent).toBe('Create the first admin');
      expect(q<HTMLInputElement>('#reg-name')!.value).toBe(NAME);
      expect(q<HTMLInputElement>('#reg-password')!.value).toBe('');
    });
  });

  describe('Nothing secret is kept', () => {
    it('keeps the PIN out of state, URL, storage and history at every step', async () => {
      await open();
      await enterPin(OTHER_PIN);
      expect(heldAnywhere(OTHER_PIN)).toBe(false);
      await answer(http.expectOne(UNLOCK), problem(401, 'bootstrap-pin-invalid', { triesLeft: 4 }));
      expect(heldAnywhere(OTHER_PIN)).toBe(false);

      await enterPin(PIN);
      await answer(http.expectOne(UNLOCK), { ticket: TICKET, expiresInSeconds: 300 });
      expect(heldAnywhere(PIN)).toBe(false);
      expect(document.body.innerHTML).not.toContain(PIN);

      // Self-check of the scan: it does see the ticket the card holds in memory.
      expect(heldAnywhere(TICKET)).toBe(true);
      // The ticket is held in memory only while the card is open, never in the template.
      expect(document.body.innerHTML).not.toContain(TICKET);
      expect(JSON.stringify(history.state ?? null)).not.toContain(TICKET);
      expect(location.href).not.toContain(TICKET);
    });

    /** Reads the page's private fields after the fixture is gone. */
    const privateOf = (page: unknown) => page as { ticket: string | null; createForm: { getRawValue(): object } };

    it('clears the ticket and every form value when the page is destroyed', async () => {
      await open();
      await unlockWith();
      await type('#reg-name', NAME);
      await type('#reg-email', EMAIL);
      await type('#reg-password', PASSWORD);
      const page = harness.routeDebugElement!.componentInstance;
      // Self-check: before the destroy the page really holds them.
      expect(privateOf(page).ticket).toBe(TICKET);
      expect(heldAnywhere(EMAIL, page)).toBe(true);
      expect(heldAnywhere(PASSWORD, page)).toBe(true);

      harness.fixture.destroy();

      expect(privateOf(page).ticket).toBeNull();
      expect(Object.values(privateOf(page).createForm.getRawValue())).toEqual(['', '', '', '']);
      for (const secret of [TICKET, NAME, EMAIL, PASSWORD]) {
        expect(heldAnywhere(secret, page), secret).toBe(false);
      }
      expect(navigate).not.toHaveBeenCalled();
      expect(navigateByUrl).not.toHaveBeenCalled();
    });

    it('cancels an unlock in flight on destroy: a late 200 stores no ticket and navigates nowhere', async () => {
      await open();
      await enterPin();
      const req = http.expectOne(UNLOCK);
      const page = harness.routeDebugElement!.componentInstance;

      harness.fixture.destroy();

      // The call was cancelled, so the answer has nowhere to go.
      expect(req.cancelled).toBe(true);
      expect(() => req.flush({ ticket: TICKET, expiresInSeconds: 300 })).toThrow();
      expect(privateOf(page).ticket).toBeNull();
      expect(heldAnywhere(TICKET, page)).toBe(false);
      expect(heldAnywhere(PIN, page)).toBe(false);
      expect(Object.values(privateOf(page).createForm.getRawValue())).toEqual(['', '', '', '']);
      expect(navigate).not.toHaveBeenCalled();
      expect(navigateByUrl).not.toHaveBeenCalled();
    });

    it('cancels a create in flight on destroy: a late 201 or 404 navigates nowhere', async () => {
      await open();
      await unlockWith();
      await fillForm();
      await submit();
      const req = http.expectOne(REGISTER);

      harness.fixture.destroy();

      expect(req.cancelled).toBe(true);
      expect(() => req.flush(null, { status: 201, statusText: 'Created' })).toThrow();
      expect(navigate).not.toHaveBeenCalled();
      expect(navigateByUrl).not.toHaveBeenCalled();
    });

    it('never writes to the console', async () => {
      const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((name) =>
        vi.spyOn(console, name).mockImplementation(() => undefined),
      );
      await open();
      await unlockWith();
      await fillForm();
      await submit();
      await answer(http.expectOne(REGISTER), problem(401, 'bootstrap-ticket-invalid'));
      for (const spy of spies) {
        expect(spy).not.toHaveBeenCalled();
        spy.mockRestore();
      }
    });
  });
});
