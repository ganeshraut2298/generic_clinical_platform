import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { Observable, Subject, of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { LOCKED, NOT_SET, statusOf } from '../../pin/pin.fixtures';
import { PinStatus } from '../../pin/pin.model';
import { SetPinSheetComponent } from '../../pin/set-pin-sheet.component';
import { TOKEN_2, checkOf, hardStop, problem, warning } from '../emr/emr.fixtures';
import { ROW, rowOf, verifiedResult } from './pharmacist.fixtures';
import { PharmacistCheckRow } from './pharmacist.model';
import { VerifySheetComponent, VerifySheetData } from './verify-sheet.component';

const PIN = '/api/v1/platform/me/pin';
const VERIFY = '/api/v1/ipd/medicine-orders/o1/verify';

describe('VerifySheetComponent', () => {
  let fixture: ComponentFixture<VerifySheetComponent>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let ref: { close: ReturnType<typeof vi.fn>; afterOpened: () => Observable<void>; disableClose?: boolean };
  /** The dialog finished opening (Material's afterOpened). */
  let opened: Subject<void>;
  let live: WritableSignal<PharmacistCheckRow | null>;
  let onConflict: ReturnType<typeof vi.fn<() => void>>;
  let dialog: { open: ReturnType<typeof vi.fn> };

  async function open(
    status: PinStatus | 'fail' = statusOf(),
    row: PharmacistCheckRow = ROW,
    setPinResult: Observable<unknown> = of(undefined),
    openedWhen: 'before-status' | 'after-status' | 'never' = 'before-status',
  ): Promise<void> {
    close = vi.fn();
    opened = new Subject<void>();
    ref = { close, afterOpened: () => opened.asObservable() };
    onConflict = vi.fn<() => void>();
    live = signal<PharmacistCheckRow | null>(row);
    dialog = { open: vi.fn(() => ({ afterClosed: () => setPinResult })) };
    const data: VerifySheetData = { row, verifierName: 'Rahul Deshmukh', live, onConflict };
    await TestBed.configureTestingModule({
      imports: [VerifySheetComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
        { provide: MatDialog, useValue: dialog },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(VerifySheetComponent);
    fixture.detectChanges();
    if (openedWhen === 'before-status') {
      opened.next();
    }
    const req = http.expectOne(PIN);
    if (status === 'fail') {
      req.flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'x' });
    } else {
      req.flush(status);
    }
    await settle();
    if (openedWhen === 'after-status') {
      opened.next();
      await settle();
    }
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const pinField = () => q<HTMLInputElement>('#sf-vpin');
  const verifyButton = () => q<HTMLButtonElement>('[data-act=sfverok]')!;
  const tick = () => new Promise<void>((r) => setTimeout(r, 0));

  async function typePin(value: string): Promise<void> {
    const input = pinField()!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  async function submit(pin = '4821'): Promise<TestRequest> {
    await typePin(pin);
    verifyButton().click();
    await settle();
    return http.expectOne(VERIFY);
  }

  const fail = (req: TestRequest, slug: string, status: number, detail: string, extra: Record<string, unknown> = {}) =>
    req.flush(problem(slug, status, detail, undefined, extra), { status, statusText: 'x' });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  describe('layout (mockup sfver)', () => {
    it('has the title, the subtitle, the identity bar and the clear notice', async () => {
      await open();
      expect(q('h2#sf-vh')!.textContent).toBe('Pharmacist check · Tab. Paracetamol 650 mg');
      expect(squash(q('[data-testid=verify-subtitle]'))).toBe('Asha Patil · UH-0001 · ordered by Dr. Meera Kulkarni');
      expect(squash(q('[data-testid=identity-name]'))).toBe('Asha Patil');
      expect(q('[data-testid=allergy-chip]')!.getAttribute('data-tone')).toBe('bad');
      expect(squash(q('[data-testid=verify-clear]'))).toBe('No allergy, duplicate or interaction found.');
      expect(q('[data-testid=verify-warnings]')).toBeNull();
    });

    it('shows each warning with the severity in bold, and the doctor\'s reason', async () => {
      await open(
        statusOf(),
        rowOf({
          overrideReason: { key: 'CARDIOLOGY_STENT', label: 'Recent coronary stent, cardiology advised' },
          check: checkOf({ warnings: [warning('Bleeding risk: heparin with aspirin'), warning('Allergies are not recorded.', 'MODERATE')] }),
        }),
      );
      const lines = Array.from(el().querySelectorAll('[data-testid=verify-warnings] .line'));
      expect(lines.map((l) => squash(l))).toEqual([
        'Major: Bleeding risk: heparin with aspirin',
        'Moderate: Allergies are not recorded.',
        "Doctor's reason: Recent coronary stent, cardiology advised",
      ]);
      expect(squash(lines[0].querySelector('b'))).toBe('Major:');
      expect(squash(lines[2].querySelector('b'))).toBe("Doctor's reason:");
      expect(q('[data-testid=verify-clear]')).toBeNull();
    });

    it('shows "Verified by" as the signed-in user, read-only, and a masked numeric PIN field never prefilled', async () => {
      await open();
      const by = q<HTMLInputElement>('#sf-vby')!;
      expect(by.value).toBe('Rahul Deshmukh');
      expect(by.readOnly).toBe(true);
      expect(squash(q('label[for=sf-vby]'))).toBe('Verified by');
      expect(squash(q('label[for=sf-vpin]'))).toBe('PIN');
      const pin = pinField()!;
      expect(pin.type).toBe('password');
      expect(pin.getAttribute('inputmode')).toBe('numeric');
      expect(pin.maxLength).toBe(4);
      expect(pin.value).toBe('');
      // The browser must not offer to save the PIN (DEC-017 S9/R9).
      expect(pin.autocomplete).toBe('one-time-code');
      expect(Array.from(el().querySelectorAll('.sheet-f button')).map((b) => squash(b))).toEqual(['Cancel', 'Verify order']);
    });

    it('reads the PIN status when it opens and puts the focus in the PIN field', async () => {
      await open();
      await tick();
      expect(document.activeElement?.id).toBe('sf-vpin');
    });

    it('focuses the PIN field when the status answers before the dialog has finished opening', async () => {
      await open(statusOf(), ROW, of(undefined), 'after-status');
      // The dialog's focus trap may move the focus to the dialog itself at the end of the opening.
      q<HTMLButtonElement>('[data-act=vf-x]')!.focus();
      opened.next();
      await settle();
      await tick();
      expect(document.activeElement?.id).toBe('sf-vpin');
    });

    it('does not take the focus while the dialog is still opening, then takes it right after its first focus', async () => {
      await open(statusOf(), ROW, of(undefined), 'never');
      q<HTMLButtonElement>('[data-act=vf-x]')!.focus();
      await tick();
      expect(pinField()).not.toBeNull();
      expect(document.activeElement?.id).not.toBe('sf-vpin');
      opened.next();
      await settle();
      await tick();
      expect(document.activeElement?.id).toBe('sf-vpin');
    });

    it('does not take the focus from the pharmacist later', async () => {
      await open();
      await tick();
      expect(document.activeElement?.id).toBe('sf-vpin');
      q<HTMLButtonElement>('[data-act=vf-cancel]')!.focus();
      await settle();
      await tick();
      expect(document.activeElement).toBe(q('[data-act=vf-cancel]'));
    });

    it('does not move the focus out of the PIN field if the dialog opens while the pharmacist types', async () => {
      await open(statusOf(), ROW, of(undefined), 'after-status');
      pinField()!.focus();
      await typePin('48');
      opened.next();
      await settle();
      await tick();
      expect(document.activeElement).toBe(pinField());
      expect(pinField()!.value).toBe('48');
    });

    it('keeps digits only', async () => {
      await open();
      await typePin('4a8-2 1x9');
      expect(pinField()!.value).toBe('4821');
    });

    it('lists the hard stops if the order has one now', async () => {
      await open(
        statusOf(),
        rowOf({ check: checkOf({ hardStops: [hardStop('Documented allergy: x')] }), canVerify: false, verifyLockReason: 'HARD_STOP' }),
      );
      expect(squash(q('[data-testid=verify-hardstops]'))).toBe('Documented allergy: x');
      expect(q('[data-testid=verify-clear]')).toBeNull();
      expect(squash(q('[data-testid=verify-lock-hint]'))).toBe('Hard stop: reject this order');
      expect(verifyButton().disabled).toBe(true);
    });
  });

  describe('PIN states', () => {
    it.each([
      [null, 'You have not set your PIN yet. Set it to verify orders.'],
      ['RESET_BY_OWNER', 'The clinic owner reset your PIN. Set a new one to verify orders.'],
      ['TOO_MANY_LOCKS', 'Your PIN was switched off after too many wrong tries. Set a new one to verify orders.'],
    ] as const)('not set (%s): notice, "Set my PIN", no PIN field, Verify off', async (reason, text) => {
      await open({ ...NOT_SET, notSetReason: reason });
      expect(squash(q('[data-testid=pin-not-set] span'))).toBe(text);
      expect(squash(q('[data-act=vf-setpin]'))).toBe('Set my PIN');
      expect(pinField()).toBeNull();
      expect(verifyButton().disabled).toBe(true);
    });

    it.each([
      ['not set', () => NOT_SET, '[data-act=vf-setpin]'],
      ['locked', () => LOCKED, '[data-act=vf-newpin]'],
      ['unavailable', () => statusOf({ available: false }), '[data-testid=pin-unavailable]'],
      ['status cannot be read', () => 'fail' as const, '[data-act=vf-retry]'],
    ])('%s: the focus goes to the first action of the notice (or the notice) inside the sheet', async (_name, status, target) => {
      await open(status());
      await tick();
      expect(q(target)).not.toBeNull();
      expect(document.activeElement).toBe(q(target));
      expect(el().contains(document.activeElement)).toBe(true);
    });

    it('not set: the focus waits until the dialog has opened', async () => {
      await open(NOT_SET, ROW, of(undefined), 'never');
      await tick();
      expect(document.activeElement).not.toBe(q('[data-act=vf-setpin]'));
      opened.next();
      await settle();
      await tick();
      expect(document.activeElement).toBe(q('[data-act=vf-setpin]'));
    });

    it('"Set my PIN" opens the Set PIN sheet on top and returns to the PIN field after a save', async () => {
      await open(NOT_SET, ROW, of(statusOf()));
      q<HTMLButtonElement>('[data-act=vf-setpin]')!.click();
      await settle();
      await tick();
      const [component, config] = dialog.open.mock.calls[0];
      expect(component).toBe(SetPinSheetComponent);
      expect(config.ariaLabelledBy).toBe('sp-title');
      expect(q('[data-testid=pin-not-set]')).toBeNull();
      expect(pinField()).not.toBeNull();
      expect(verifyButton().disabled).toBe(false);
      expect(document.activeElement?.id).toBe('sf-vpin');
    });

    it('stays in the no-PIN state when the Set PIN sheet is cancelled', async () => {
      await open(NOT_SET, ROW, of(undefined));
      q<HTMLButtonElement>('[data-act=vf-setpin]')!.click();
      await settle();
      expect(q('[data-testid=pin-not-set]')).not.toBeNull();
      expect(pinField()).toBeNull();
    });

    it('locked: notice with the time, "Set a new PIN", no PIN field, Verify off', async () => {
      await open(LOCKED);
      expect(squash(q('[data-testid=pin-locked] span'))).toBe('Your PIN is locked after 5 wrong tries. Try again at 11:20 AM.');
      expect(squash(q('[data-act=vf-newpin]'))).toBe('Set a new PIN');
      expect(pinField()).toBeNull();
      expect(verifyButton().disabled).toBe(true);
    });

    it('locked: asks the server again when the lock should have ended and shows the PIN field if it has', async () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date('2026-11-06T05:40:00Z'));
      await open(LOCKED);
      await vi.advanceTimersByTimeAsync(10 * 60_000 + 400);
      http.expectNone(PIN);
      await vi.advanceTimersByTimeAsync(200);
      http.expectOne(PIN).flush(statusOf());
      await settle();
      expect(q('[data-testid=pin-locked]')).toBeNull();
      expect(pinField()).not.toBeNull();
      expect(verifyButton().disabled).toBe(false);
    });

    it('locked: the poll that still says locked does not take the focus from the pharmacist', async () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date('2026-11-06T05:49:59Z'));
      await open(LOCKED);
      await vi.advanceTimersByTimeAsync(0);
      expect(document.activeElement).toBe(q('[data-act=vf-newpin]'));
      q<HTMLButtonElement>('[data-act=vf-cancel]')!.focus();
      await vi.advanceTimersByTimeAsync(1600);
      http.expectOne(PIN).flush(LOCKED);
      await settle();
      await vi.advanceTimersByTimeAsync(0);
      expect(document.activeElement).toBe(q('[data-act=vf-cancel]'));
    });

    it('locked: stays locked and asks again later when the server still says locked', async () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date('2026-11-06T05:49:59Z'));
      await open(LOCKED);
      await vi.advanceTimersByTimeAsync(1600);
      http.expectOne(PIN).flush(LOCKED);
      await settle();
      expect(q('[data-testid=pin-locked]')).not.toBeNull();
      await vi.advanceTimersByTimeAsync(15_000);
      http.expectOne(PIN).flush(statusOf());
      await settle();
      expect(pinField()).not.toBeNull();
    });

    it('unavailable: notice, no PIN field, Verify off', async () => {
      await open(statusOf({ available: false }));
      expect(squash(q('[data-testid=pin-unavailable]'))).toBe('PIN checks are not available right now. Try again later.');
      expect(pinField()).toBeNull();
      expect(verifyButton().disabled).toBe(true);
    });

    it('status cannot be read: notice with Try again, Verify off', async () => {
      await open('fail');
      expect(squash(q('[data-testid=pin-load-error]'))).toContain('Try later.');
      expect(verifyButton().disabled).toBe(true);
      q<HTMLButtonElement>('[data-act=vf-retry]')!.click();
      await settle();
      http.expectOne(PIN).flush(statusOf());
      await settle();
      expect(pinField()).not.toBeNull();
    });
  });

  describe('Verify order', () => {
    it('sends the PIN and the checkToken of the row, clears the PIN and closes with the answer', async () => {
      await open();
      const req = await submit('4821');
      expect(req.request.body).toEqual({ pin: '4821', checkToken: 'AAAAAAAAAAAAAAAAAAAAAA' });
      const answer = verifiedResult();
      req.flush(answer);
      await settle();
      expect(close).toHaveBeenCalledWith(answer);
      expect(pinField()!.value).toBe('');
    });

    it('refuses a PIN that is not 4 digits before sending, and puts the focus back', async () => {
      await open();
      await typePin('48');
      verifyButton().click();
      await settle();
      http.expectNone(VERIFY);
      expect(squash(q('[data-testid=pin-error]'))).toBe('Enter the 4-digit PIN.');
      expect(pinField()!.getAttribute('aria-invalid')).toBe('true');
      expect(pinField()!.getAttribute('aria-describedby')).toBe('sf-vpin-err');
      expect(document.activeElement?.id).toBe('sf-vpin');
    });

    it('turns Verify, Cancel and the close button off while it runs, and sends once', async () => {
      await open();
      const req = await submit();
      expect(verifyButton().disabled).toBe(true);
      expect(verifyButton().getAttribute('aria-busy')).toBe('true');
      expect(q<HTMLButtonElement>('[data-act=vf-cancel]')!.disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-act=vf-x]')!.disabled).toBe(true);
      expect(ref.disableClose).toBe(true);
      verifyButton().click();
      await settle();
      http.expectNone(VERIFY);
      req.flush(verifiedResult());
    });

    it('clears the PIN after every failure', async () => {
      await open();
      fail(await submit(), 'server-error', 500, 'Try later.');
      await settle();
      expect(pinField()!.value).toBe('');
      expect(squash(q('[data-testid=verify-failure]'))).toBe('Try later.');
      expect(close).not.toHaveBeenCalled();
    });

    it('shows a generic text when the connection is lost', async () => {
      await open();
      (await submit()).error(new ProgressEvent('error'));
      await settle();
      expect(squash(q('[data-testid=verify-failure]'))).toBe('We could not verify that order. Try again.');
      expect(pinField()!.value).toBe('');
    });

    it('wrong PIN: the detail under the field, the field cleared and focused, Verify still on', async () => {
      await open();
      fail(await submit('1111'), 'wrong-pin', 403, 'That PIN is not right. 2 tries left.');
      await settle();
      await tick();
      expect(squash(q('[data-testid=pin-error]'))).toBe('That PIN is not right. 2 tries left.');
      expect(pinField()!.value).toBe('');
      expect(document.activeElement?.id).toBe('sf-vpin');
      expect(verifyButton().disabled).toBe(false);
      expect(q('[data-testid=verify-failure]')).toBeNull();
    });

    it('locked answer: switches to the locked state and reads the status for the time', async () => {
      await open();
      fail(await submit(), 'pin-locked', 403, 'Your PIN is locked after 5 wrong tries. Try again at 11:20 AM.', {
        retryAfterSeconds: 900,
      });
      await settle();
      http.expectOne(PIN).flush(LOCKED);
      await settle();
      expect(squash(q('[data-testid=pin-locked] span'))).toBe('Your PIN is locked after 5 wrong tries. Try again at 11:20 AM.');
      expect(pinField()).toBeNull();
      expect(verifyButton().disabled).toBe(true);
    });

    it.each([
      ['RESET_BY_OWNER', 'The clinic owner reset your PIN. Set a new one to verify orders.'],
      ['TOO_MANY_LOCKS', 'Your PIN was switched off after too many wrong tries. Set a new one to verify orders.'],
      [null, 'You have not set your PIN yet. Set it to verify orders.'],
    ] as const)('pin-not-set answer: the reason %s comes from GET /me/pin, not from the words of the answer', async (reason, notice) => {
      await open();
      // The detail is deliberately unrelated: the sheet must not read the reason from it.
      fail(await submit(), 'pin-not-set', 409, 'Something the server worded differently.');
      await settle();
      expect(q('[data-testid=pin-not-set]')).toBeNull();
      expect(squash(q('[role=status]'))).toBe('Checking your PIN');
      http.expectOne(PIN).flush({ ...NOT_SET, notSetReason: reason });
      await settle();
      await tick();
      expect(squash(q('[data-testid=pin-not-set] span'))).toBe(notice);
      expect(squash(q('[data-act=vf-setpin]'))).toBe('Set my PIN');
      expect(pinField()).toBeNull();
      expect(verifyButton().disabled).toBe(true);
      expect(document.activeElement).toBe(q('[data-act=vf-setpin]'));
    });

    it('after the third lock the 409 pin-not-set says the PIN was switched off, with Set my PIN (ADR-010 D11)', async () => {
      await open();
      fail(
        await submit('0000'),
        'pin-not-set',
        409,
        'Your PIN was switched off after too many wrong tries. Set a new one, then try again.',
      );
      await settle();
      http.expectOne(PIN).flush({ ...NOT_SET, notSetReason: 'TOO_MANY_LOCKS' });
      await settle();
      expect(squash(q('[data-testid=pin-not-set] span'))).toBe(
        'Your PIN was switched off after too many wrong tries. Set a new one to verify orders.',
      );
      expect(squash(q('[data-act=vf-setpin]'))).toBe('Set my PIN');
      expect(pinField()).toBeNull();
      expect(q('[data-testid=pin-locked]')).toBeNull();
      expect(verifyButton().disabled).toBe(true);
    });

    it('pin-not-set answer and the status cannot be read: the generic "not set yet" text, not the words of the answer', async () => {
      await open();
      fail(
        await submit('0000'),
        'pin-not-set',
        409,
        'Your PIN was switched off after too many wrong tries. Set a new one, then try again.',
      );
      await settle();
      http.expectOne(PIN).flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'x' });
      await settle();
      expect(squash(q('[data-testid=pin-not-set] span'))).toBe('You have not set your PIN yet. Set it to verify orders.');
      expect(q('[data-act=vf-setpin]')).not.toBeNull();
      expect(pinField()).toBeNull();
      expect(verifyButton().disabled).toBe(true);
    });

    it('pin-not-set answer but the status says the PIN is set: the PIN field is back', async () => {
      await open();
      fail(await submit(), 'pin-not-set', 409, 'x');
      await settle();
      http.expectOne(PIN).flush(statusOf());
      await settle();
      expect(q('[data-testid=pin-not-set]')).toBeNull();
      expect(pinField()).not.toBeNull();
    });

    it('rate limit: shows the text and keeps Verify off for retryAfterSeconds', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open();
      fail(await submit(), 'rate-limit', 429, 'Too many PIN tries in a short time. Try again in a few minutes.', {
        retryAfterSeconds: 3,
      });
      await settle();
      expect(squash(q('[data-testid=verify-failure]'))).toBe('Too many PIN tries in a short time. Try again in a few minutes.');
      expect(verifyButton().disabled).toBe(true);
      vi.advanceTimersByTime(2000);
      await settle();
      expect(verifyButton().disabled).toBe(true);
      vi.advanceTimersByTime(1000);
      await settle();
      expect(verifyButton().disabled).toBe(false);
    });

    it('unavailable answer (503): switches to the unavailable state', async () => {
      await open();
      fail(await submit(), 'pin-unavailable', 503, 'PIN checks are not available right now. Try again later.');
      await settle();
      expect(q('[data-testid=pin-unavailable]')).not.toBeNull();
      expect(pinField()).toBeNull();
      expect(verifyButton().disabled).toBe(true);
    });

    it('self-verification: shows the text and keeps Verify off', async () => {
      await open();
      fail(await submit(), 'self-verification', 403, 'The doctor who ordered this cannot verify it. Ask the pharmacist.');
      await settle();
      expect(squash(q('[data-testid=verify-failure]'))).toBe('The doctor who ordered this cannot verify it. Ask the pharmacist.');
      expect(verifyButton().disabled).toBe(true);
      expect(onConflict).not.toHaveBeenCalled();
    });

    it('a 400 on the pin field shows its message under the field', async () => {
      await open();
      (await submit()).flush(
        problem('validation', 400, 'x', [{ field: 'pin', message: 'Enter the 4-digit PIN.' }]),
        { status: 400, statusText: 'Bad Request' },
      );
      await settle();
      expect(squash(q('[data-testid=pin-error]'))).toBe('Enter the 4-digit PIN.');
    });

    it('a 400 on the checkToken asks to reload the page and reloads the panel', async () => {
      await open();
      (await submit()).flush(
        problem('validation', 400, 'x', [{ field: 'checkToken', message: 'Reload the page and try again.' }]),
        { status: 400, statusText: 'Bad Request' },
      );
      await settle();
      expect(squash(q('[data-testid=verify-failure]'))).toBe('Reload the page and try again.');
      expect(onConflict).toHaveBeenCalledTimes(1);
      expect(verifyButton().disabled).toBe(true);
    });
  });

  describe('a conflict while the sheet is open', () => {
    it('order-checks-changed: shows the text, reloads the panel, keeps Verify off until the new row arrives, then sends the new token', async () => {
      await open();
      fail(await submit(), 'order-checks-changed', 409, 'The checks on this order changed. Review them and try again.');
      await settle();
      expect(squash(q('[data-testid=verify-failure]'))).toBe('The checks on this order changed. Review them and try again.');
      expect(onConflict).toHaveBeenCalledTimes(1);
      expect(verifyButton().disabled).toBe(true);

      const changed = rowOf({
        check: checkOf({ warnings: [warning('Bleeding risk: heparin with aspirin')], checkToken: TOKEN_2 }),
      });
      live.set(changed);
      await settle();
      expect(el().querySelectorAll('[data-testid=verify-warnings] .line').length).toBe(1);
      expect(verifyButton().disabled).toBe(false);
      const req = await submit('4821');
      expect(req.request.body.checkToken).toBe(TOKEN_2);
      req.flush(verifiedResult());
    });

    it('order-state: Verify stays off when the order is gone from the panel', async () => {
      await open();
      fail(await submit(), 'order-state', 409, 'Rahul Deshmukh already verified this order.');
      await settle();
      live.set(null);
      await settle();
      expect(verifyButton().disabled).toBe(true);
      expect(squash(q('[data-testid=verify-failure]'))).toBe('Rahul Deshmukh already verified this order.');
    });

    it('order-blocked: the reloaded row has a hard stop, so Verify stays off and the hard stop shows', async () => {
      await open();
      fail(await submit(), 'order-blocked', 409, 'Hard stop on this order. Reject it.');
      await settle();
      live.set(
        rowOf({
          check: checkOf({ hardStops: [hardStop('Documented allergy: x')] }),
          canVerify: false,
          verifyLockReason: 'HARD_STOP',
        }),
      );
      await settle();
      expect(verifyButton().disabled).toBe(true);
      expect(squash(q('[data-testid=verify-hardstops]'))).toBe('Documented allergy: x');
    });

    it('a 404 reloads the panel and keeps Verify off', async () => {
      await open();
      fail(await submit(), 'not-found', 404, 'Order not found.');
      await settle();
      expect(onConflict).toHaveBeenCalledTimes(1);
      expect(verifyButton().disabled).toBe(true);
    });
  });

  it('Cancel and the close button close without an answer', async () => {
    await open();
    q<HTMLButtonElement>('[data-act=vf-cancel]')!.click();
    q<HTMLButtonElement>('[data-act=vf-x]')!.click();
    expect(close).toHaveBeenCalledTimes(2);
    expect(close).toHaveBeenCalledWith();
  });
});
