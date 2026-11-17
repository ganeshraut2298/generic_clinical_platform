import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { problem } from '../emr/emr.fixtures';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { DoseErrorPanelComponent } from './dose-error-panel.component';
import { AT_8AM, IN_ERROR_REASONS, ORDER_PCM, doseOf, marOf, slotOf } from './mar.fixtures';
import { MarDoseRow } from './mar.model';

const URL = '/api/v1/ipd/doses/d1/recorded-in-error';
const PATIENT_NAME = 'Ramesh Kale';

describe('DoseErrorPanelComponent (recorded in error, SAH-76-5 D6)', () => {
  let fixture: ComponentFixture<DoseErrorPanelComponent>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;
  let corrected: unknown[];
  let reloads: number;
  let closes: number;

  async function open(dose: MarDoseRow = doseOf({ canMarkInError: true })): Promise<void> {
    snack = vi.fn();
    corrected = [];
    reloads = 0;
    closes = 0;
    await TestBed.configureTestingModule({
      imports: [DoseErrorPanelComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snack, openFromComponent: vi.fn() } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DoseErrorPanelComponent);
    fixture.componentRef.setInput('admissionId', 'a1');
    fixture.componentRef.setInput('order', ORDER_PCM);
    fixture.componentRef.setInput('slot', slotOf({ slotAt: AT_8AM, state: 'GIVEN', canGive: false, dose }));
    fixture.componentRef.setInput('dose', dose);
    fixture.componentRef.setInput('reasons', IN_ERROR_REASONS);
    fixture.componentInstance.corrected.subscribe((m) => corrected.push(m));
    fixture.componentInstance.reload.subscribe(() => (reloads += 1));
    fixture.componentInstance.closed.subscribe(() => (closes += 1));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const reasons = () => Array.from(el().querySelectorAll<HTMLButtonElement>('[data-testid=dose-error-reason]'));
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const tap = async (index: number) => {
    reasons()[index].click();
    fixture.detectChanges();
    await fixture.whenStable();
  };
  const refuse = async (slug: string, status: number, detail: string) => {
    http.expectOne(URL).flush(problem(slug, status, detail), { status, statusText: 'x' });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  afterEach(() => http.verify());

  describe('what it shows', () => {
    it('the medicine and time, the dose details and the four reasons for a dose the nurse may correct', async () => {
      await open();
      expect(squash(q('[data-testid=dose-error-title]'))).toBe('Tab. Paracetamol 650 mg · 8 AM dose');
      expect(squash(q('[data-testid=dose-error-detail]'))).toBe(
        'Given 8:04 AM by Sr. Anjali Pawar · wristband and medicine scanned · checked by Sr. Rupali More',
      );
      expect(reasons().map((b) => squash(b))).toEqual([
        'Dose was not actually given',
        'Documented on the wrong patient',
        'Wrong time recorded',
        'Duplicate entry',
      ]);
    });

    it('shows the details but no reasons when the server does not allow the correction (canMarkInError false)', async () => {
      await open(doseOf({ canMarkInError: false }));
      expect(q('[data-testid=dose-error-detail]')).not.toBeNull();
      expect(q('[data-testid=dose-error-ask]')).toBeNull();
      expect(reasons()).toEqual([]);
    });

    it('names the nurses but never the patient', async () => {
      await open();
      const text = squash(el());
      expect(text).not.toContain(PATIENT_NAME);
      expect(text).not.toContain('PT-00042');
    });

    it('an override dose says why the scan was skipped', async () => {
      await open(
        doseOf({ canMarkInError: true, method: 'OVERRIDE', overrideReason: { key: 'NO_BARCODE', label: 'Medicine has no barcode' } }),
      );
      expect(squash(q('[data-testid=dose-error-detail]'))).toContain('scan override: Medicine has no barcode');
    });

    it('Close tells the screen and sends nothing', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=err-close]')!.click();
      expect(closes).toBe(1);
    });
  });

  describe('choosing a reason', () => {
    it('sends the key once, however often it is tapped, then passes the MAR on and shows the toast', async () => {
      await open();
      await tap(3);
      reasons()[3].click();
      reasons()[0].click();
      const req = http.expectOne(URL);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ clientKey: expect.stringMatching(/^[0-9a-f-]{36}$/), reasonKey: 'DUPLICATE' });
      const mar = marOf();
      req.flush({ replayed: false, dose: doseOf(), mar });
      expect(corrected).toEqual([mar]);
      expect(snack).toHaveBeenCalledWith(
        'Marked as recorded in error: duplicate entry. The original entry is kept.',
        'Close',
        expect.objectContaining({ politeness: 'off' }),
      );
    });

    it('disables the reasons and Close while the answer is awaited', async () => {
      await open();
      await tap(0);
      expect(reasons().every((b) => b.disabled)).toBe(true);
      expect(q<HTMLButtonElement>('[data-act=err-close]')!.disabled).toBe(true);
      http.expectOne(URL).flush({ replayed: false, dose: doseOf(), mar: marOf() });
    });

    it('keeps the key for a retry of the same reason and makes a new one for another reason', async () => {
      await open();
      await tap(1);
      const first = http.expectOne(URL);
      const key = (first.request.body as { clientKey: string }).clientKey;
      first.error(new ProgressEvent('error'));
      fixture.detectChanges();
      await tap(1);
      const retry = http.expectOne(URL);
      expect((retry.request.body as { clientKey: string }).clientKey).toBe(key);
      retry.error(new ProgressEvent('error'));
      fixture.detectChanges();
      await tap(2);
      const other = http.expectOne(URL);
      expect((other.request.body as { clientKey: string }).clientKey).not.toBe(key);
      expect((other.request.body as { reasonKey: string }).reasonKey).toBe('WRONG_TIME');
      other.flush({ replayed: false, dose: doseOf(), mar: marOf() });
    });
  });

  describe('what the server answers with a problem', () => {
    it('a lost answer shows the plain text as an alert, marks nothing and sends no reload', async () => {
      await open();
      await tap(0);
      http.expectOne(URL).error(new ProgressEvent('error'));
      fixture.detectChanges();
      const alert = q('[data-testid=dose-error-failure]')!;
      expect(alert.getAttribute('role')).toBe('alert');
      expect(squash(alert)).toBe('We could not mark the dose. Try again.');
      expect(corrected).toEqual([]);
      expect(reloads).toBe(0);
      expect(reasons().every((b) => !b.disabled)).toBe(true);
    });

    for (const [slug, status, detail] of [
      ['error-window-closed', 409, 'This dose is from an earlier day and cannot be changed.'],
      ['dose-state', 409, 'This dose cannot be marked in error.'],
      ['forbidden', 403, 'Only nurses and the clinic owner can correct a dose.'],
      ['not-admitted', 409, 'This patient is not admitted any more. Doses cannot be recorded.'],
      ['ipd-readonly', 409, 'IPD is read-only. Nothing can be changed.'],
    ] as const) {
      it(`${slug} (${status}): shows the server text in a toast, has the screen read the MAR again and writes no patient name`, async () => {
        await open();
        await tap(0);
        await refuse(slug, status, detail);
        expect(snack).toHaveBeenCalledWith(detail, 'Close', expect.anything());
        expect(snack.mock.calls[0][0]).not.toContain(PATIENT_NAME);
        expect(reloads).toBe(1);
        expect(corrected).toEqual([]);
      });
    }

    it('outside-scope (403) reports the refusal to the screen and shows no toast and no reload', async () => {
      await open();
      const report = vi.spyOn(TestBed.inject(ScopeRefusals), 'report');
      await tap(0);
      await refuse('outside-scope', 403, 'This patient is not in your wards.');
      expect(report).toHaveBeenCalledWith('a1', true);
      expect(snack).not.toHaveBeenCalled();
      expect(reloads).toBe(0);
    });

    it('a refusal leaves the reasons usable again', async () => {
      await open();
      await tap(0);
      await refuse('dose-state', 409, 'This dose cannot be marked in error.');
      expect(reasons().every((b) => !b.disabled)).toBe(true);
    });
  });
});
