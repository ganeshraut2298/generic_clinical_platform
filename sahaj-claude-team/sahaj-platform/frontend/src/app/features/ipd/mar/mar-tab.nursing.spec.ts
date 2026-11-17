import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { problem } from '../emr/emr.fixtures';
import { GiveResult } from './give-sheet.component';
import {
  AT_8AM,
  AT_8PM,
  ORDER_PCM,
  ORDER_PRN,
  PRN_LINE,
  doseOf,
  marOf,
  orderOf,
  slotOf,
} from './mar.fixtures';
import { MarTabComponent } from './mar-tab.component';
import { Mar } from './mar.model';
import { UndoToast } from './undo-toast.component';

const URL = '/api/v1/ipd/admissions/a1/mar';

describe('MarTabComponent, SAH-76 nursing integration', () => {
  let fixture: ComponentFixture<MarTabComponent>;
  let http: HttpTestingController;
  let dialog: { open: ReturnType<typeof vi.fn> };
  let closed: Subject<GiveResult | undefined>;
  let snack: ReturnType<typeof vi.fn>;
  let undoToast: UndoToast;

  async function open(mar: Mar): Promise<void> {
    closed = new Subject<GiveResult | undefined>();
    dialog = { open: vi.fn(() => ({ afterClosed: () => closed.asObservable() })) };
    snack = vi.fn();
    await TestBed.configureTestingModule({
      imports: [MarTabComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: { open: snack, openFromComponent: vi.fn() } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    undoToast = TestBed.inject(UndoToast);
    fixture = TestBed.createComponent(MarTabComponent);
    fixture.componentRef.setInput('admissionId', 'a1');
    fixture.detectChanges();
    http.expectOne(URL).flush(mar);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el().querySelectorAll<HTMLElement>(sel));
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const block = (orderId: string) => q(`[data-testid=mar-orders] [role=listitem][data-order="${orderId}"]`);

  afterEach(() => http.verify());

  describe('counters and fold', () => {
    it('shows "N late", "N due now" and "N given" with the hint of the reference', async () => {
      await open(marOf({ counters: { late: 2, dueNow: 3, given: 4 } }));
      expect(squash(q('[data-testid=mar-count-late]'))).toBe('2 late');
      expect(squash(q('[data-testid=mar-count-due]'))).toBe('3 due now');
      expect(squash(q('[data-testid=mar-count-given]'))).toBe('4 given');
      expect(squash(q('.sfhint'))).toBe('Due doses open the scan flow · tap a given dose to mark it recorded in error');
    });

    it('folds the blocks the server marks and shows them behind "Show N medicines with nothing due now"', async () => {
      const folded = orderOf({ orderId: 'f1', drugName: 'Tab. Folded', folded: true });
      await open(marOf({ orders: [ORDER_PCM, folded], foldedCount: 1 }));
      expect(block('o1')).not.toBeNull();
      expect(block('f1')).toBeNull();
      const button = q<HTMLButtonElement>('[data-act=mar-fold]')!;
      expect(squash(button)).toBe('Show 1 medicine with nothing due now');
      button.click();
      await settle();
      expect(block('f1')).not.toBeNull();
      expect(squash(q('[data-act=mar-fold]'))).toBe('Hide medicines with nothing due');
      expect(q('[data-act=mar-fold]')!.getAttribute('aria-expanded')).toBe('true');
    });

    it('has no fold button when nothing is folded', async () => {
      await open(marOf());
      expect(q('[data-act=mar-fold]')).toBeNull();
    });

    it('says "Nothing due right now" when nothing is late or due', async () => {
      await open(marOf({ counters: { late: 0, dueNow: 0, given: 1 } }));
      expect(squash(q('[data-testid=mar-nothing-due]'))).toBe(
        'Nothing due right now. Medicines with nothing due are folded away.',
      );
    });

    it('does not say it while something is due', async () => {
      await open(marOf());
      expect(q('[data-testid=mar-nothing-due]')).toBeNull();
    });
  });

  describe('order block', () => {
    it('shows "Changed · v2" for a changed order and "Not pharmacist checked" for an order that needed no check', async () => {
      const changed = orderOf({
        orderId: 'c1',
        changed: true,
        orderVersion: 2,
        details: { ...ORDER_PCM.details, pharmacistChecked: false },
      });
      await open(marOf({ orders: [changed] }));
      expect(squash(q('[data-testid=mar-changed]'))).toBe('Changed · v2');
      expect(squash(q('[data-testid=mar-not-checked]'))).toBe('Not pharmacist checked');
    });

    it('shows the status pills of the new statuses', async () => {
      await open(
        marOf({
          orders: [
            orderOf({ orderId: 'q', status: 'QUERIED', slots: [slotOf({ state: 'WAITING', canGive: false })] }),
            orderOf({
              orderId: 'h',
              status: 'ON_HOLD',
              hold: { heldAt: '2026-11-06T05:00:00Z', heldByName: 'Dr. Meera Kulkarni', reason: null },
              slots: [slotOf({ state: 'ON_HOLD', canGive: false, stateBy: 'Dr. Meera Kulkarni' })],
            }),
          ],
        }),
      );
      expect(squash(block('q')!.querySelector('[data-testid=mar-status]'))).toBe('Queried');
      expect(squash(block('q')!.querySelector('[data-testid=mar-order-lock]'))).toBe(
        'Cannot be given: the pharmacist has a query for the doctor.',
      );
      expect(squash(block('q')!.querySelector('[data-testid=mar-slot]'))).toBe('8 AMQueried');
      expect(squash(block('h')!.querySelector('[data-testid=mar-status]'))).toBe('On hold');
      expect(squash(block('h')!.querySelector('[data-testid=mar-order-lock]'))).toBe('On hold by Dr. Meera Kulkarni');
      expect(block('h')!.querySelector<HTMLButtonElement>('button[data-testid=mar-slot]')).toBeNull();
    });

    it('a STAT order waiting for the pharmacist still offers Give and has no pharmacist lock line', async () => {
      const stat = orderOf({
        orderId: 's1',
        status: 'PENDING',
        frequencyKey: null,
        frequencyLabel: 'STAT',
        details: { ...ORDER_PCM.details, kind: 'ONCE', frequencyKey: null, priority: 'STAT', pharmacistChecked: null },
        slots: [slotOf({ state: 'DUE', canGive: true, beforeVerification: true })],
      });
      await open(marOf({ orders: [stat] }));
      expect(block('s1')!.querySelector('[data-testid=mar-order-lock]')).toBeNull();
      expect(squash(block('s1')!.querySelector('[data-testid=mar-slot]'))).toBe('8 AMSTAT · Give');
      expect(block('s1')!.querySelector<HTMLButtonElement>('button[data-testid=mar-slot]')!.disabled).toBe(false);
    });
  });

  describe('struck-through history', () => {
    it('shows the original struck through with the correction beside it', async () => {
      const struck = doseOf({
        correction: {
          kind: 'IN_ERROR',
          reason: { key: 'DUPLICATE', label: 'Duplicate entry' },
          correctedByName: 'Sr. Rupali More',
          correctedAt: '2026-11-06T05:50:00Z',
        },
      });
      await open(marOf({ orders: [orderOf({ slots: [slotOf({ state: 'DUE', history: [struck] })] })] }));
      const line = q('[data-testid=mar-history]')!;
      expect(squash(line.querySelector('s'))).toBe('Given 8:04 AM by Sr. Anjali Pawar');
      expect(squash(line)).toContain('recorded in error by Sr. Rupali More at 11:20 AM: Duplicate entry');
    });
  });

  describe('PRN line', () => {
    it('shows the chip, the indication, the interval, the doses used and Give', async () => {
      await open(marOf({ orders: [ORDER_PRN] }));
      expect(squash(q('[data-testid=mar-prn-chip]'))).toBe('PRN');
      expect(squash(q('[data-testid=mar-prn-indication]'))).toBe('Fever above 38 C');
      expect(squash(q('[data-testid=mar-prn-interval]'))).toBe('Minimum interval 4 h');
      expect(squash(q('[data-testid=mar-prn-used]'))).toBe('2 of 4 in 24 h');
      expect(q('[data-testid=mar-prn-give]')).not.toBeNull();
      expect(q('[data-testid=mar-slot]')).toBeNull();
    });

    it('Give opens the Give sheet without a slot', async () => {
      await open(marOf({ orders: [ORDER_PRN] }));
      q<HTMLButtonElement>('[data-testid=mar-prn-give]')!.click();
      expect(dialog.open).toHaveBeenCalledTimes(1);
      const data = ((dialog.open.mock.calls[0] as unknown[])[1] as { data: { slot: unknown; order: { orderId: string } } }).data;
      expect(data.slot).toBeNull();
      expect(data.order.orderId).toBe('o3');
    });

    it('shows "Too soon" with the server time and no Give', async () => {
      const prn = { ...PRN_LINE, lockReason: 'TOO_SOON' as const, canGive: false, nextAllowedAt: '2026-11-06T09:00:00Z' };
      await open(marOf({ orders: [{ ...ORDER_PRN, prn }] }));
      expect(squash(q('[data-testid=mar-prn-lock]'))).toBe('Too soon. Next dose allowed at 2:30 PM.');
      expect(q('[data-testid=mar-prn-give]')).toBeNull();
    });

    it('shows "Daily maximum reached" and no Give', async () => {
      const prn = { ...PRN_LINE, lockReason: 'MAX_REACHED' as const, canGive: false, nextAllowedAt: '2026-11-06T12:40:00Z' };
      await open(marOf({ orders: [{ ...ORDER_PRN, prn }] }));
      expect(squash(q('[data-testid=mar-prn-lock]'))).toBe('Daily maximum reached. Next dose allowed at 6:10 PM.');
      expect(q('[data-testid=mar-prn-give]')).toBeNull();
    });

    it.each([
      ['NOT_STARTED', 'Not started yet'],
      ['ENDED', 'Ended'],
    ] as const)('lock reason %s shows "%s" and no Give button', async (lockReason, text) => {
      const prn = { ...PRN_LINE, lockReason, canGive: false };
      await open(marOf({ orders: [{ ...ORDER_PRN, prn }] }));
      expect(squash(q('[data-testid=mar-prn-lock]'))).toBe(text);
      expect(q('[data-testid=mar-prn-give]')).toBeNull();
    });

    it('disables Give for a role that cannot give', async () => {
      await open(marOf({ orders: [ORDER_PRN], canGive: false, giveLockReason: 'ROLE' }));
      expect(q<HTMLButtonElement>('[data-testid=mar-prn-give]')!.disabled).toBe(true);
      q<HTMLButtonElement>('[data-testid=mar-prn-give]')!.click();
      expect(dialog.open).not.toHaveBeenCalled();
    });
  });

  describe('recorded in error', () => {
    const correctable = doseOf({ canMarkInError: true, canUndo: false });
    const given = orderOf({
      orderId: 'g1',
      slots: [slotOf({ slotAt: AT_8AM, state: 'GIVEN', canGive: false, dose: correctable }), slotOf({ slotAt: AT_8PM, state: 'LATER', canGive: false })],
    });

    it('a given dose the nurse may correct is a button that opens the panel with the four reasons', async () => {
      await open(marOf({ orders: [given] }));
      const chip = q<HTMLButtonElement>('button[data-testid=mar-slot][data-correctable]')!;
      expect(chip).not.toBeNull();
      chip.click();
      await settle();
      const panel = q('[data-testid=dose-error-panel]')!;
      expect(squash(panel.querySelector('[data-testid=dose-error-title]'))).toBe('Tab. Paracetamol 650 mg · 8 AM dose');
      expect(squash(panel.querySelector('[data-testid=dose-error-detail]'))).toBe(
        'Given 8:04 AM by Sr. Anjali Pawar · wristband and medicine scanned · checked by Sr. Rupali More',
      );
      expect(squash(panel.querySelector('[data-testid=dose-error-ask]'))).toBe(
        'Mark as recorded in error · the original entry is kept, struck through, with your name, time and reason',
      );
      expect(qa('[data-testid=dose-error-reason]').map((b) => squash(b))).toEqual([
        'Dose was not actually given',
        'Documented on the wrong patient',
        'Wrong time recorded',
        'Duplicate entry',
      ]);
    });

    it('a given dose the user may not correct is not a button (a doctor, or the next day)', async () => {
      await open(marOf({ orders: [orderOf({ slots: [slotOf({ state: 'GIVEN', canGive: false, dose: doseOf() })] })] }));
      expect(q('button[data-testid=mar-slot]')).toBeNull();
      expect(q('[data-testid=dose-error-panel]')).toBeNull();
    });

    it('choosing a reason sends it, shows the toast and replaces the MAR with the answer', async () => {
      await open(marOf({ orders: [given] }));
      q<HTMLButtonElement>('button[data-testid=mar-slot][data-correctable]')!.click();
      await settle();
      qa('[data-testid=dose-error-reason]')[3].click();
      await settle();
      const req = http.expectOne('/api/v1/ipd/doses/d1/recorded-in-error');
      expect(req.request.body).toEqual({ clientKey: expect.stringMatching(/^[0-9a-f-]{36}$/), reasonKey: 'DUPLICATE' });
      const after = marOf({ orders: [orderOf({ orderId: 'g1', slots: [slotOf({ state: 'DUE', history: [doseOf()] })] })] });
      req.flush({ replayed: false, dose: doseOf(), mar: after });
      await settle();
      expect(snack).toHaveBeenCalledWith(
        'Marked as recorded in error: duplicate entry. The original entry is kept.',
        'Close',
        expect.anything(),
      );
      expect(q('[data-testid=dose-error-panel]')).toBeNull();
      expect(q('[data-testid=mar-history]')).not.toBeNull();
    });

    it('a refusal (error-window-closed) reads the MAR again and closes the panel', async () => {
      await open(marOf({ orders: [given] }));
      q<HTMLButtonElement>('button[data-testid=mar-slot][data-correctable]')!.click();
      await settle();
      qa('[data-testid=dose-error-reason]')[0].click();
      await settle();
      http
        .expectOne('/api/v1/ipd/doses/d1/recorded-in-error')
        .flush(problem('error-window-closed', 409, 'This dose is from an earlier day and cannot be changed.'), {
          status: 409,
          statusText: 'x',
        });
      await settle();
      expect(snack).toHaveBeenCalledWith('This dose is from an earlier day and cannot be changed.', 'Close', expect.anything());
      http.expectOne(URL).flush(marOf());
      await settle();
      expect(q('[data-testid=dose-error-panel]')).toBeNull();
    });

    it('Close closes the panel and sends nothing', async () => {
      await open(marOf({ orders: [given] }));
      q<HTMLButtonElement>('button[data-testid=mar-slot][data-correctable]')!.click();
      await settle();
      q<HTMLButtonElement>('[data-act=err-close]')!.click();
      await settle();
      expect(q('[data-testid=dose-error-panel]')).toBeNull();
    });
  });

  describe('undo toast answers', () => {
    it('replaces the MAR with the one of an undo for this admission', async () => {
      await open(marOf());
      const after = marOf({ counters: { late: 0, dueNow: 2, given: 0 } });
      undoToast.corrected.next({ admissionId: 'a1', mar: after });
      await settle();
      expect(squash(q('[data-testid=mar-count-due]'))).toBe('2 due now');
    });

    it('ignores an undo of another admission', async () => {
      await open(marOf());
      undoToast.corrected.next({ admissionId: 'other', mar: marOf({ counters: { late: 9, dueNow: 9, given: 9 } }) });
      await settle();
      expect(squash(q('[data-testid=mar-count-late]'))).toBe('0 late');
    });

    it('reads the MAR again when the undo was refused', async () => {
      await open(marOf());
      undoToast.corrected.next({ admissionId: 'a1', mar: null });
      http.expectOne(URL).flush(marOf());
      await settle();
    });
  });
});
