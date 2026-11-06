import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { NOT_SET, statusOf } from './pin.fixtures';
import { PinStatus } from './pin.model';
import { SetPinSheetComponent } from './set-pin-sheet.component';

const STATUS = '/api/v1/platform/me/pin';
const CODE = '/api/v1/platform/me/pin/code';

const problem = (
  slug: string,
  status: number,
  detail: string,
  fieldErrors?: { field: string; message: string }[],
  extra: Record<string, unknown> = {},
) => ({
  type: `https://sahaj.in/problems/${slug}`,
  title: slug,
  status,
  detail,
  ...(fieldErrors ? { fieldErrors } : {}),
  ...extra,
});

describe('SetPinSheetComponent', () => {
  let fixture: ComponentFixture<SetPinSheetComponent>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;
  let close: ReturnType<typeof vi.fn>;
  let ref: { close: ReturnType<typeof vi.fn>; disableClose?: boolean };

  async function open(status: PinStatus = NOT_SET): Promise<void> {
    snack = vi.fn();
    close = vi.fn();
    ref = { close };
    await TestBed.configureTestingModule({
      imports: [SetPinSheetComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: ref },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SetPinSheetComponent);
    fixture.detectChanges();
    http.expectOne(STATUS).flush(status);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const code = () => q<HTMLInputElement>('#sp-code')!;
  const newPin = () => q<HTMLInputElement>('#sp-new')!;
  const confirmPin = () => q<HTMLInputElement>('#sp-confirm')!;
  const send = () => q<HTMLButtonElement>('[data-act=sp-send]')!;
  const save = () => q<HTMLButtonElement>('[data-act=sp-save]')!;
  const tick = () => new Promise<void>((r) => setTimeout(r, 0));

  async function typeInto(input: HTMLInputElement, value: string): Promise<void> {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  async function sendCode(resendAfterSeconds = 30): Promise<void> {
    send().click();
    await settle();
    http.expectOne(CODE).flush({ resendAfterSeconds, mobileMasked: '••••••7410' }, { status: 202, statusText: 'Accepted' });
    await settle();
  }

  async function fill(c = '493017', p = '4821', again = p): Promise<void> {
    await typeInto(code(), c);
    await typeInto(newPin(), p);
    await typeInto(confirmPin(), again);
  }

  async function press(): Promise<TestRequest> {
    save().click();
    await settle();
    return http.expectOne(STATUS);
  }

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  describe('first screen', () => {
    it('is titled "Set my PIN" when there is none, with the masked number and Send code', async () => {
      await open();
      expect(q('h2#sp-title')!.textContent).toBe('Set my PIN');
      expect(squash(q('[data-testid=sp-mobile-line]'))).toBe('We will send a 6-digit code to ••••••7410.');
      expect(squash(send())).toBe('Send code');
      expect(code()).toBeNull();
      expect(save().disabled).toBe(true);
    });

    it('is titled "Change my PIN" when one is set', async () => {
      await open(statusOf());
      expect(q('h2#sp-title')!.textContent).toBe('Change my PIN');
    });

    it('says PIN checks are unavailable and turns Send code and Save PIN off', async () => {
      await open(statusOf({ available: false }));
      expect(squash(q('[data-testid=sp-unavailable]'))).toBe('PIN checks are not available right now. Try again later.');
      expect(send()).toBeNull();
      expect(save().disabled).toBe(true);
    });

    it('shows the load error with Try again', async () => {
      snack = vi.fn();
      ref = { close: vi.fn() };
      await TestBed.configureTestingModule({
        imports: [SetPinSheetComponent],
        providers: [
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
          { provide: MatDialogRef, useValue: ref },
          { provide: MatSnackBar, useValue: { open: snack } },
        ],
      }).compileComponents();
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(SetPinSheetComponent);
      fixture.detectChanges();
      http.expectOne(STATUS).flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'x' });
      await settle();
      expect(squash(q('[data-testid=sp-load-error]'))).toContain('Try later.');
      q<HTMLButtonElement>('[data-act=sp-retry]')!.click();
      await settle();
      http.expectOne(STATUS).flush(NOT_SET);
      await settle();
      expect(send()).not.toBeNull();
    });
  });

  describe('sending the code', () => {
    it('sends no number (the server knows it), shows the fields, a countdown and moves the focus to the code', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open();
      send().click();
      await settle();
      expect(send().disabled).toBe(true);
      const req = http.expectOne(CODE);
      expect(req.request.body).toBeNull();
      req.flush({ resendAfterSeconds: 30, mobileMasked: '••••••7410' }, { status: 202, statusText: 'Accepted' });
      await settle();
      await tick();
      expect(squash(q('[data-testid=sp-mobile-line]'))).toBe('Code sent to ••••••7410.');
      expect(squash(q('[data-testid=sp-resend-wait]'))).toBe('Resend code in 0:30');
      expect(document.activeElement?.id).toBe('sp-code');
      vi.advanceTimersByTime(2000);
      await settle();
      expect(squash(q('[data-testid=sp-resend-wait]'))).toBe('Resend code in 0:28');
      vi.advanceTimersByTime(28_000);
      await settle();
      expect(q('[data-testid=sp-resend-wait]')).toBeNull();
      expect(squash(send())).toBe('Resend code');
    });

    it('masks the two PIN fields and not the code, and keeps digits only', async () => {
      await open();
      await sendCode();
      expect(code().type).toBe('text');
      expect(code().getAttribute('inputmode')).toBe('numeric');
      expect(newPin().type).toBe('password');
      expect(confirmPin().type).toBe('password');
      expect(newPin().getAttribute('autocomplete')).toBe('new-password');
      expect(confirmPin().getAttribute('autocomplete')).toBe('new-password');
      expect(newPin().maxLength).toBe(4);
      await typeInto(newPin(), '48a2x1');
      expect(newPin().value).toBe('4821');
      await typeInto(code(), '49 30 17');
      expect(code().value).toBe('493017');
    });

    it('starts the countdown from retryAfterSeconds on a 429 and shows the server text', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open();
      send().click();
      await settle();
      http.expectOne(CODE).flush(
        problem('rate-limit', 429, 'Wait before requesting another code for this number.', undefined, { retryAfterSeconds: 20 }),
        { status: 429, statusText: 'Too Many' },
      );
      await settle();
      expect(squash(q('[data-testid=sp-failure]'))).toBe('Wait before requesting another code for this number.');
      expect(squash(q('[data-testid=sp-resend-wait]'))).toBe('Resend code in 0:20');
    });

    it('shows the 423 text when the number is locked', async () => {
      await open();
      send().click();
      await settle();
      http.expectOne(CODE).flush(problem('otp-locked', 423, 'Too many wrong codes. Try again in 15 minutes.'), {
        status: 423,
        statusText: 'Locked',
      });
      await settle();
      expect(squash(q('[data-testid=sp-failure]'))).toBe('Too many wrong codes. Try again in 15 minutes.');
    });

    it('switches to the unavailable notice on a 503', async () => {
      await open();
      send().click();
      await settle();
      http.expectOne(CODE).flush(problem('pin-unavailable', 503, 'PIN checks are not available right now. Try again later.'), {
        status: 503,
        statusText: 'Unavailable',
      });
      await settle();
      expect(q('[data-testid=sp-unavailable]')).not.toBeNull();
      expect(save().disabled).toBe(true);
    });

    it('turns Send code and the close button off while the request runs', async () => {
      await open();
      send().click();
      await settle();
      expect(send().disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-act=sp-cancel]')!.disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-act=sp-x]')!.disabled).toBe(true);
      expect(ref.disableClose).toBe(true);
      http.expectOne(CODE).flush({ resendAfterSeconds: 30, mobileMasked: '••••••7410' });
    });
  });

  describe('saving', () => {
    it('refuses short values with the contract texts, sends nothing and focuses the first one', async () => {
      await open();
      await sendCode();
      await fill('49', '48', '12');
      save().click();
      await settle();
      http.expectNone(STATUS);
      expect(squash(q('#sp-code-err'))).toBe('Enter the 6-digit code.');
      expect(squash(q('#sp-new-err'))).toBe('Enter a 4-digit PIN.');
      expect(squash(q('#sp-confirm-err'))).toBe('The two PINs do not match.');
      expect(code().getAttribute('aria-invalid')).toBe('true');
      expect(code().getAttribute('aria-describedby')).toBe('sp-code-err');
      expect(document.activeElement?.id).toBe('sp-code');
    });

    it('sends the code and the PIN twice, toasts "Your PIN is set." and closes with the status', async () => {
      await open();
      await sendCode();
      await fill();
      const req = await press();
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ code: '493017', newPin: '4821', confirmPin: '4821' });
      const saved = statusOf({ setAt: '2026-11-06T06:00:00Z' });
      req.flush(saved);
      await settle();
      expect(snack.mock.calls.map((c) => c[0])).toEqual(['Your PIN is set.']);
      expect(close).toHaveBeenCalledWith(saved);
      expect(newPin().value).toBe('');
    });

    it('toasts "Your PIN is changed." when a PIN was set before', async () => {
      await open(statusOf());
      await sendCode();
      await fill();
      (await press()).flush(statusOf({ setAt: '2026-11-06T06:00:00Z' }));
      await settle();
      expect(snack.mock.calls.map((c) => c[0])).toEqual(['Your PIN is changed.']);
    });

    it('turns Save PIN, Cancel and the close button off while it runs, and sends once', async () => {
      await open();
      await sendCode();
      await fill();
      const req = await press();
      expect(save().disabled).toBe(true);
      expect(save().getAttribute('aria-busy')).toBe('true');
      expect(q<HTMLButtonElement>('[data-act=sp-cancel]')!.disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-act=sp-x]')!.disabled).toBe(true);
      expect(ref.disableClose).toBe(true);
      save().click();
      await settle();
      http.expectNone(STATUS);
      req.flush(statusOf());
    });

    it('shows a weak PIN under the new PIN, clears both PIN fields and keeps the code', async () => {
      await open();
      await sendCode();
      await fill('493017', '1234');
      (await press()).flush(
        problem('validation', 400, 'Some fields are missing or not valid.', [
          { field: 'newPin', message: 'This PIN is too easy to guess. Choose another one.' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
      await settle();
      expect(squash(q('#sp-new-err'))).toBe('This PIN is too easy to guess. Choose another one.');
      expect(newPin().value).toBe('');
      expect(confirmPin().value).toBe('');
      expect(code().value).toBe('493017');
      expect(document.activeElement?.id).toBe('sp-new');
      expect(close).not.toHaveBeenCalled();
    });

    it('shows a wrong code under the code field and clears it', async () => {
      await open();
      await sendCode();
      await fill();
      (await press()).flush(
        problem('validation', 400, 'Some fields are missing or not valid.', [{ field: 'code', message: 'That code is not right.' }]),
        { status: 400, statusText: 'Bad Request' },
      );
      await settle();
      expect(squash(q('#sp-code-err'))).toBe('That code is not right.');
      expect(code().value).toBe('');
      expect(newPin().value).toBe('');
    });

    it('shows the 423 text as a notice', async () => {
      await open();
      await sendCode();
      await fill();
      (await press()).flush(problem('otp-locked', 423, 'Too many wrong codes. Try again in 15 minutes.'), {
        status: 423,
        statusText: 'Locked',
      });
      await settle();
      expect(squash(q('[data-testid=sp-failure]'))).toBe('Too many wrong codes. Try again in 15 minutes.');
      expect(newPin().value).toBe('');
    });

    it('shows the 429 text as a notice', async () => {
      await open();
      await sendCode();
      await fill();
      (await press()).flush(
        problem('rate-limit', 429, 'Too many PIN tries in a short time. Try again in a few minutes.', undefined, { retryAfterSeconds: 120 }),
        { status: 429, statusText: 'Too Many' },
      );
      await settle();
      expect(squash(q('[data-testid=sp-failure]'))).toBe('Too many PIN tries in a short time. Try again in a few minutes.');
    });

    it('switches to the unavailable notice on a 503', async () => {
      await open();
      await sendCode();
      await fill();
      (await press()).flush(problem('pin-unavailable', 503, 'PIN checks are not available right now. Try again later.'), {
        status: 503,
        statusText: 'Unavailable',
      });
      await settle();
      expect(q('[data-testid=sp-unavailable]')).not.toBeNull();
      expect(save().disabled).toBe(true);
    });

    describe('after a lost answer', () => {
      it('reads the status again and counts a new setAt as a success', async () => {
        await open();
        await sendCode();
        await fill();
        (await press()).error(new ProgressEvent('error'));
        await settle();
        expect(newPin().value).toBe('');
        http.expectOne(STATUS).flush(statusOf({ setAt: '2026-11-06T06:00:00Z' }));
        await settle();
        expect(snack.mock.calls.map((c) => c[0])).toEqual(['Your PIN is set.']);
        expect(close).toHaveBeenCalled();
      });

      it('says to send a new code when the status did not change', async () => {
        await open();
        await sendCode();
        await fill();
        (await press()).flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'x' });
        await settle();
        http.expectOne(STATUS).flush(NOT_SET);
        await settle();
        expect(squash(q('[data-testid=sp-failure]'))).toBe('We could not save your PIN. Send a new code and try again.');
        expect(close).not.toHaveBeenCalled();
      });

      it('shows the same text when the status cannot be read either', async () => {
        await open();
        await sendCode();
        await fill();
        (await press()).error(new ProgressEvent('error'));
        await settle();
        http.expectOne(STATUS).error(new ProgressEvent('error'));
        await settle();
        expect(squash(q('[data-testid=sp-failure]'))).toBe('We could not save your PIN. Send a new code and try again.');
      });
    });
  });

  it('Cancel and the close button close without a status', async () => {
    await open();
    q<HTMLButtonElement>('[data-act=sp-cancel]')!.click();
    q<HTMLButtonElement>('[data-act=sp-x]')!.click();
    expect(close).toHaveBeenCalledTimes(2);
    expect(close).toHaveBeenCalledWith();
  });
});
