import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_SNACK_BAR_DATA, MatSnackBar, MatSnackBarRef } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { problem } from '../emr/emr.fixtures';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { doseOf, marOf } from './mar.fixtures';
import { DoseCorrected, UndoToast, UndoToastComponent, UndoToastData } from './undo-toast.component';

const UNDO = '/api/v1/ipd/doses/d1/undo';

const data = (over: Partial<UndoToastData['dose']> = {}, override = false): UndoToastData => ({
  admissionId: 'a1',
  drugName: 'Tab. Paracetamol 650 mg',
  dose: doseOf({ canUndo: true, undoSecondsLeft: 10, ...over }),
  override,
});

describe('UndoToast service', () => {
  let service: UndoToast;
  let snack: { open: ReturnType<typeof vi.fn>; openFromComponent: ReturnType<typeof vi.fn> };
  let http: HttpTestingController;

  beforeEach(() => {
    snack = { open: vi.fn(), openFromComponent: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: snack },
      ],
    });
    service = TestBed.inject(UndoToast);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('opens the undo toast for the seconds the server sent', () => {
    const d = data({ undoSecondsLeft: 8 });
    service.showGiven(d);
    expect(snack.openFromComponent).toHaveBeenCalledTimes(1);
    const [, config] = snack.openFromComponent.mock.calls[0];
    expect(config.duration).toBe(8000);
    expect(config.data).toBe(d);
    expect(snack.open).not.toHaveBeenCalled();
  });

  it('shows the plain toast when the server does not allow an undo', () => {
    service.showGiven(data({ canUndo: false, undoSecondsLeft: 0 }));
    expect(snack.open).toHaveBeenCalledWith('Tab. Paracetamol 650 mg recorded as given', 'Close', expect.anything());
    expect(snack.openFromComponent).not.toHaveBeenCalled();
  });

  it('says "(override, reason saved)" for an override dose', () => {
    service.showGiven(data({ canUndo: false, undoSecondsLeft: 0 }, true));
    expect(snack.open).toHaveBeenCalledWith(
      'Tab. Paracetamol 650 mg recorded as given (override, reason saved)',
      'Close',
      expect.anything(),
    );
  });

  describe('shown only to the recorder (the server decides: DoseRow.canUndo)', () => {
    it('a dose another nurse recorded (canUndo false, no seconds) gets the plain toast with no Undo', () => {
      service.showGiven(data({ canUndo: false, undoSecondsLeft: 0 }));
      expect(snack.openFromComponent).not.toHaveBeenCalled();
      expect(snack.open).toHaveBeenCalledTimes(1);
    });

    it('canUndo false wins over seconds left: no Undo is offered', () => {
      service.showGiven(data({ canUndo: false, undoSecondsLeft: 8 }));
      expect(snack.openFromComponent).not.toHaveBeenCalled();
      expect(snack.open).toHaveBeenCalledTimes(1);
    });

    it('canUndo true with no seconds left gets the plain toast too', () => {
      service.showGiven(data({ canUndo: true, undoSecondsLeft: 0 }));
      expect(snack.openFromComponent).not.toHaveBeenCalled();
      expect(snack.open).toHaveBeenCalledTimes(1);
    });

    it('the plain toast is not announced twice (the sheet and the list already say it)', () => {
      service.showGiven(data({ canUndo: false, undoSecondsLeft: 0 }));
      const config = snack.open.mock.calls[0][2];
      expect(config.politeness).toBe('off');
    });

    it('the undo toast is announced to a screen reader (live region is not switched off)', () => {
      service.showGiven(data());
      const config = snack.openFromComponent.mock.calls[0][1];
      expect(config.politeness).not.toBe('off');
    });
  });

  describe('ends at the server window, not at the device clock', () => {
    afterEach(() => vi.useRealTimers());

    it('the toast lasts exactly the seconds the server sent', () => {
      service.showGiven(data({ undoSecondsLeft: 7 }));
      expect(snack.openFromComponent.mock.calls[0][1].duration).toBe(7000);
    });

    it('does not read the device clock: a clock far in the future or past gives the same duration', () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2031-01-01T00:00:00Z'));
      service.showGiven(data({ undoSecondsLeft: 6 }));
      vi.setSystemTime(new Date('2019-01-01T00:00:00Z'));
      service.showGiven(data({ undoSecondsLeft: 6 }));
      expect(snack.openFromComponent.mock.calls.map((c) => c[1].duration)).toEqual([6000, 6000]);
    });
  });

  it('done: "Undone. <medicine> is due again." and the MAR goes to the screen', () => {
    const seen: DoseCorrected[] = [];
    service.corrected.subscribe((c) => seen.push(c));
    const mar = marOf();
    service.done(data(), mar);
    expect(snack.open).toHaveBeenCalledWith('Undone. Tab. Paracetamol 650 mg is due again.', 'Close', expect.anything());
    expect(seen).toEqual([{ admissionId: 'a1', mar }]);
  });

  it('refused: the server text, and the screen reads the MAR again', () => {
    const seen: DoseCorrected[] = [];
    service.corrected.subscribe((c) => seen.push(c));
    service.undo(data(), 'k').subscribe({ error: (e: unknown) => service.refused(data(), e) });
    http
      .expectOne(UNDO)
      .flush(problem('undo-expired', 409, 'The time to undo has passed. Mark it as recorded in error instead.'), {
        status: 409,
        statusText: 'x',
      });
    expect(snack.open).toHaveBeenCalledWith(
      'The time to undo has passed. Mark it as recorded in error instead.',
      'Close',
      expect.anything(),
    );
    expect(seen).toEqual([{ admissionId: 'a1', mar: null }]);
  });

  it('refused outside the nurse scope reports the refusal and shows no toast', () => {
    const refusals = TestBed.inject(ScopeRefusals);
    const report = vi.spyOn(refusals, 'report');
    service.undo(data(), 'k').subscribe({ error: (e: unknown) => service.refused(data(), e) });
    http.expectOne(UNDO).flush(problem('outside-scope', 403, 'This patient is not in your wards.'), {
      status: 403,
      statusText: 'x',
    });
    expect(report).toHaveBeenCalledWith('a1', true);
    expect(snack.open).not.toHaveBeenCalled();
  });
});

describe('UndoToastComponent', () => {
  let fixture: ComponentFixture<UndoToastComponent>;
  let http: HttpTestingController;
  let dismiss: ReturnType<typeof vi.fn>;
  let snack: { open: ReturnType<typeof vi.fn>; openFromComponent: ReturnType<typeof vi.fn> };
  let service: UndoToast;
  let seen: DoseCorrected[];

  async function open(d: UndoToastData = data()): Promise<void> {
    dismiss = vi.fn();
    snack = { open: vi.fn(), openFromComponent: vi.fn() };
    seen = [];
    await TestBed.configureTestingModule({
      imports: [UndoToastComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: snack },
        { provide: MAT_SNACK_BAR_DATA, useValue: d },
        { provide: MatSnackBarRef, useValue: { dismiss } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(UndoToast);
    service.corrected.subscribe((c) => seen.push(c));
    fixture = TestBed.createComponent(UndoToastComponent);
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const button = () => el().querySelector<HTMLButtonElement>('[data-act=undo-dose]');

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('shows the message and the button with the seconds', async () => {
    await open();
    expect(squash(el().querySelector('[data-testid=undo-message]'))).toBe('Tab. Paracetamol 650 mg recorded as given');
    expect(squash(button())).toBe('Undo 10 s');
  });

  it('counts down every second and the button leaves at 0', async () => {
    vi.useFakeTimers();
    await open(data({ undoSecondsLeft: 3 }));
    vi.advanceTimersByTime(1000);
    fixture.detectChanges();
    expect(squash(button())).toBe('Undo 2 s');
    vi.advanceTimersByTime(2000);
    fixture.detectChanges();
    expect(button()).toBeNull();
  });

  it('Undo sends the request once, dismisses and tells the screen', async () => {
    await open();
    button()!.click();
    button()?.click();
    const req = http.expectOne(UNDO);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ clientKey: expect.stringMatching(/^[0-9a-f-]{36}$/) });
    const mar = marOf();
    req.flush({ replayed: false, dose: doseOf(), mar });
    expect(dismiss).toHaveBeenCalled();
    expect(snack.open).toHaveBeenCalledWith('Undone. Tab. Paracetamol 650 mg is due again.', 'Close', expect.anything());
    expect(seen).toEqual([{ admissionId: 'a1', mar }]);
  });

  describe('countdown follows the server seconds, not the device clock', () => {
    it('a device clock that jumps an hour forward does not shorten the window', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
      vi.setSystemTime(new Date('2026-11-06T05:35:00Z'));
      await open(data({ undoSecondsLeft: 10 }));
      vi.advanceTimersByTime(1000);
      vi.setSystemTime(new Date('2026-11-06T06:35:00Z'));
      fixture.detectChanges();
      expect(squash(button())).toBe('Undo 9 s');
    });

    it('a device clock that jumps back does not lengthen it: the button leaves after the server seconds', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
      vi.setSystemTime(new Date('2026-11-06T05:35:00Z'));
      await open(data({ undoSecondsLeft: 2 }));
      vi.setSystemTime(new Date('2020-01-01T00:00:00Z'));
      vi.advanceTimersByTime(2000);
      fixture.detectChanges();
      expect(button()).toBeNull();
    });

    it('the countdown timer is cleared when the toast is destroyed', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open(data({ undoSecondsLeft: 5 }));
      const clear = vi.spyOn(globalThis, 'clearInterval');
      fixture.destroy();
      expect(clear).toHaveBeenCalled();
      clear.mockRestore();
    });
  });

  describe('a second tap sends one request', () => {
    it('while the answer is awaited the button is disabled and busy, and another tap sends nothing', async () => {
      await open();
      button()!.click();
      fixture.detectChanges();
      expect(button()!.disabled).toBe(true);
      expect(button()!.getAttribute('aria-busy')).toBe('true');
      button()!.click();
      button()!.click();
      const req = http.expectOne(UNDO);
      req.flush({ replayed: false, dose: doseOf(), mar: marOf() });
      expect(seen.length).toBe(1);
    });

    it('after a lost answer the button is usable again and the retry is one more request', async () => {
      await open();
      button()!.click();
      http.expectOne(UNDO).error(new ProgressEvent('error'));
      fixture.detectChanges();
      expect(button()!.disabled).toBe(false);
      expect(button()!.getAttribute('aria-busy')).toBe('false');
      button()!.click();
      http.expectOne(UNDO).flush({ replayed: true, dose: doseOf(), mar: marOf() });
      expect(seen.length).toBe(1);
    });
  });

  describe('focus and live announcement', () => {
    it('opening does not take the focus from where the nurse is working', async () => {
      const field = document.createElement('input');
      document.body.appendChild(field);
      field.focus();
      await open();
      expect(document.activeElement).toBe(field);
      field.remove();
    });

    it('Undo is a real button in the tab order, with its seconds in its name', async () => {
      await open();
      const b = button()!;
      expect(b.tagName).toBe('BUTTON');
      expect(b.getAttribute('type')).toBe('button');
      expect(b.hasAttribute('tabindex')).toBe(false);
      expect(b.textContent).toContain('Undo');
      expect(b.textContent).toContain('10 s');
    });

    it('the progress bar is hidden from a screen reader (the seconds are in the button)', async () => {
      await open();
      expect(el().querySelector('.bar')!.getAttribute('aria-hidden')).toBe('true');
    });

    it('a lost answer is announced as an alert next to the button, in plain words', async () => {
      await open();
      button()!.click();
      http.expectOne(UNDO).error(new ProgressEvent('error'));
      fixture.detectChanges();
      const alert = el().querySelector('[data-testid=undo-failure]')!;
      expect(alert.getAttribute('role')).toBe('alert');
      expect(squash(alert)).toBe('We could not undo the dose. Try again.');
    });

    it('a refusal is announced by its toast and the undone toast is not read twice', async () => {
      await open();
      button()!.click();
      http
        .expectOne(UNDO)
        .flush(problem('undo-expired', 409, 'The time to undo has passed. Mark it as recorded in error instead.'), {
          status: 409,
          statusText: 'x',
        });
      expect(snack.open.mock.calls[0][2].politeness).not.toBe('off');
      snack.open.mockClear();
      service.done(data(), marOf());
      expect(snack.open.mock.calls[0][2].politeness).toBe('off');
    });
  });

  it('a refusal dismisses the toast, says why and has the MAR read again', async () => {
    await open();
    button()!.click();
    http.expectOne(UNDO).flush(problem('undo-expired', 409, 'The time to undo has passed. Mark it as recorded in error instead.'), {
      status: 409,
      statusText: 'x',
    });
    expect(dismiss).toHaveBeenCalled();
    expect(seen).toEqual([{ admissionId: 'a1', mar: null }]);
  });

  it('a lost answer keeps the toast and the button, and a retry uses the same key', async () => {
    await open();
    button()!.click();
    const first = http.expectOne(UNDO);
    const key = (first.request.body as { clientKey: string }).clientKey;
    first.error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(dismiss).not.toHaveBeenCalled();
    expect(squash(el().querySelector('[data-testid=undo-failure]'))).toBe('We could not undo the dose. Try again.');
    button()!.click();
    const second = http.expectOne(UNDO);
    expect((second.request.body as { clientKey: string }).clientKey).toBe(key);
    second.flush({ replayed: false, dose: doseOf(), mar: marOf() });
  });
});
