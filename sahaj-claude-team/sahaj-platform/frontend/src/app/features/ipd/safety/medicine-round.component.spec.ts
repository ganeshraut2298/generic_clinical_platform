import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { GiveResult } from '../mar/give-sheet.component';
import { GIVEN_DOSE, marOf, orderOf, slotOf } from '../mar/mar.fixtures';
import { Mar } from '../mar/mar.model';
import { MedicineRoundComponent } from './medicine-round.component';
import { AS_OF, ROUND_DUE, ROUND_LATE, SLOT_1030AM, SLOT_8AM, problem, roundOf } from './safety.fixtures';
import { MedicineRound } from './safety.model';

const MAR_A1 = '/api/v1/ipd/admissions/a1/mar';
const MAR_A2 = '/api/v1/ipd/admissions/a2/mar';

/** The MAR of Asha Patil's admission that matches ROUND_LATE (order o2, slot 8 AM, 3 h late). */
const marForLate = (over: Partial<Mar> = {}): Mar =>
  marOf({
    admissionId: 'a1',
    orders: [
      orderOf({
        orderId: 'o2',
        drugName: 'Inj. Heparin 5,000 IU',
        highAlert: 'Anticoagulant',
        slots: [slotOf({ slotAt: SLOT_8AM, state: 'LATE', lateHours: 3, canGive: true })],
      }),
    ],
    ...over,
  });

describe('MedicineRoundComponent', () => {
  let fixture: ComponentFixture<MedicineRoundComponent>;
  let http: HttpTestingController;
  let closed: Subject<GiveResult | undefined>;
  let dialog: { open: ReturnType<typeof vi.fn> };
  let snack: ReturnType<typeof vi.fn>;
  let answered: number;

  async function open(round: MedicineRound = roundOf()): Promise<void> {
    closed = new Subject<GiveResult | undefined>();
    dialog = { open: vi.fn(() => ({ afterClosed: () => closed.asObservable() })) };
    snack = vi.fn();
    answered = 0;
    await TestBed.configureTestingModule({
      imports: [MedicineRoundComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MedicineRoundComponent);
    fixture.componentRef.setInput('round', round);
    fixture.componentRef.setInput('asOf', AS_OF);
    fixture.componentInstance.answered.subscribe(() => (answered += 1));
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const tick = () => new Promise<void>((r) => setTimeout(r, 0));
  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const rows = () => Array.from(el().querySelectorAll<HTMLElement>('[data-testid=round-row]'));
  const gives = () => Array.from(el().querySelectorAll<HTMLButtonElement>('[data-testid=round-give]'));
  const toasts = () => snack.mock.calls.map((c) => c[0] as string);

  afterEach(() => http.verify());

  describe('layout (mockup doseRow)', () => {
    it('has the heading with the pill icon and the subtitle with the server clock in IST', async () => {
      await open();
      expect(squash(q('h2#sf-round-h'))).toBe('Medicine round');
      expect(q('h2#sf-round-h app-icon')).not.toBeNull();
      expect(q('h2#sf-round-h')!.getAttribute('tabindex')).toBe('-1');
      expect(squash(q('[data-testid=round-subtitle]'))).toBe(
        'Doses due in the next hour and doses not given on time. Clock: 11:17 AM.',
      );
    });

    it('shows medicine, High-alert pill, "patient · bed · time dose", the status pill and Give', async () => {
      await open();
      const [late, due] = rows();
      expect(squash(late.querySelector('[data-testid=round-drug]'))).toBe('Inj. Heparin 5,000 IU High-alert');
      expect(late.querySelector('[data-testid=round-high-alert]')!.className).toContain('p-crit');
      expect(squash(late.querySelector('[data-testid=round-line]'))).toBe('Asha Patil · bed 101-A · 8 AM dose');
      expect(squash(late.querySelector('[data-testid=round-pill]'))).toBe('3 h late');
      expect(late.querySelector('[data-testid=round-pill]')!.className).toContain('p-crit');
      expect(squash(late.querySelector('[data-testid=round-give]'))).toBe('Give');

      expect(squash(due.querySelector('[data-testid=round-drug]'))).toBe('Tab. Paracetamol 650 mg');
      expect(due.querySelector('[data-testid=round-high-alert]')).toBeNull();
      expect(squash(due.querySelector('[data-testid=round-line]'))).toBe('Ravi Shah · bed 202-B · 10:30 AM dose');
      expect(squash(due.querySelector('[data-testid=round-pill]'))).toBe('Due now');
      expect(due.querySelector('[data-testid=round-pill]')!.className).toContain('p-warn');
    });

    it('keeps the order the server sent (Late first is the server\'s job)', async () => {
      await open(roundOf({ rows: [ROUND_LATE, ROUND_DUE] }));
      expect(rows().map((r) => r.dataset['state'])).toEqual(['LATE', 'DUE']);
      await open2(roundOf({ rows: [ROUND_DUE, ROUND_LATE] }));
      expect(rows().map((r) => r.dataset['state'])).toEqual(['DUE', 'LATE']);
    });

    it('says at least 1 h late for a Late row without hours', async () => {
      await open(roundOf({ rows: [{ ...ROUND_LATE, lateHours: null }] }));
      expect(squash(q('[data-testid=round-pill]'))).toBe('1 h late');
    });

    it('shows "No doses due right now." when the list is empty', async () => {
      await open(roundOf({ rows: [] }));
      expect(squash(q('[data-testid=round-empty]'))).toBe('No doses due right now.');
      expect(q('[data-testid=round-list]')).toBeNull();
    });

    it('shows "Showing the 200 most overdue doses." only when truncated', async () => {
      await open(roundOf({ truncated: true }));
      expect(squash(q('[data-testid=round-truncated]'))).toBe('Showing the 200 most overdue doses.');
      await open2(roundOf({ truncated: false }));
      expect(q('[data-testid=round-truncated]')).toBeNull();
    });

    it('never says that anyone was alerted', async () => {
      await open();
      expect(el().textContent!.toLowerCase()).not.toContain('alert sent');
      expect(el().textContent!.toLowerCase()).not.toContain('notified');
    });
  });

  /** Replace the data of the open panel (a reload). */
  async function open2(round: MedicineRound): Promise<void> {
    fixture.componentRef.setInput('round', round);
    await settle();
  }

  describe('who can give', () => {
    it('draws no Give button at all (not a disabled one) when canGive is false for a doctor, with no line', async () => {
      await open(roundOf({ canGive: false, giveLockReason: 'ROLE' }));
      expect(rows().length).toBe(2);
      expect(gives()).toEqual([]);
      expect(q('[data-act=sfgive]')).toBeNull();
      expect(q('[data-testid=round-readonly]')).toBeNull();
    });

    it('shows the read-only line and no Give when IPD is read-only', async () => {
      await open(roundOf({ canGive: false, giveLockReason: 'IPD_READONLY' }));
      expect(squash(q('[data-testid=round-readonly]'))).toBe('IPD is read-only. Doses cannot be recorded.');
      expect(q('[data-testid=round-readonly] app-icon')).not.toBeNull();
      expect(gives()).toEqual([]);
      expect(rows().length).toBe(2);
    });

    it('shows the read-only line also when the list is empty', async () => {
      await open(roundOf({ canGive: false, giveLockReason: 'IPD_READONLY', rows: [] }));
      expect(q('[data-testid=round-readonly]')).not.toBeNull();
      expect(q('[data-testid=round-empty]')).not.toBeNull();
    });

    it('names the list "Doses due or late", not the heading it already sits under (R35-5)', async () => {
      await open();
      expect(q('[data-testid=round-list]')!.getAttribute('aria-label')).toBe('Doses due or late');
      expect(squash(q('h2#sf-round-h'))).toBe('Medicine round');
    });

    it('names each Give button "Give <medicine> to <patient name>"', async () => {
      await open();
      expect(gives().map((b) => b.getAttribute('aria-label'))).toEqual([
        'Give Inj. Heparin 5,000 IU to Asha Patil',
        'Give Tab. Paracetamol 650 mg to Ravi Shah',
      ]);
      expect(gives().every((b) => b.dataset['from'] === 'safety')).toBe(true);
    });
  });

  describe('Give', () => {
    it('reads the MAR of that patient once, then opens the unchanged Give sheet with the order and the slot', async () => {
      await open();
      gives()[0].click();
      await settle();
      // One at a time while the MAR is read. The buttons stay focusable (aria-disabled, not the disabled attribute).
      expect(gives().every((b) => b.getAttribute('aria-disabled') === 'true' && !b.disabled)).toBe(true);
      expect(gives()[0].getAttribute('aria-busy')).toBe('true');
      expect(dialog.open).not.toHaveBeenCalled();
      const mar = marForLate();
      http.expectOne(MAR_A1).flush(mar);
      http.expectNone(MAR_A2);
      await settle();
      expect(dialog.open).toHaveBeenCalledTimes(1);
      const [, config] = dialog.open.mock.calls[0];
      expect(config.data.mar).toEqual(mar);
      expect(config.data.order.orderId).toBe('o2');
      expect(config.data.slot.slotAt).toBe(SLOT_8AM);
      expect(config.autoFocus).toBe(false);
      expect(config.panelClass).toBe('sheet-panel');
      expect(gives().every((b) => b.getAttribute('aria-disabled') !== 'true')).toBe(true);
      expect(answered).toBe(0);
    });

    it('keeps the focus on the tapped Give button while the MAR is read (R35-2)', async () => {
      await open();
      document.body.appendChild(el());
      try {
        const first = gives()[0];
        first.focus();
        first.click();
        await settle();
        expect(first.disabled).toBe(false);
        expect(first.getAttribute('aria-disabled')).toBe('true');
        expect(first.getAttribute('aria-busy')).toBe('true');
        expect(document.activeElement).toBe(first);
        expect(gives()[1].getAttribute('aria-busy')).toBeNull();
        http.expectOne(MAR_A1).flush(marForLate());
        await settle();
      } finally {
        el().remove();
      }
    });

    it('does nothing for a second tap while the MAR is read', async () => {
      await open();
      gives()[0].click();
      await settle();
      gives()[1].click(); // aria-disabled: the click reaches give(), whose guard ignores it
      http.expectOne(MAR_A1).flush(marForLate());
      http.expectNone(MAR_A2);
    });

    it('asks the board to reload when a dose was recorded (sheet closed with the MAR)', async () => {
      await open();
      gives()[0].click();
      http.expectOne(MAR_A1).flush(marForLate());
      await settle();
      closed.next({ mar: marForLate() });
      expect(answered).toBe(1);
    });

    it('asks the board to reload when the sheet closed because a 409 changed the state', async () => {
      await open();
      gives()[0].click();
      http.expectOne(MAR_A1).flush(marForLate());
      await settle();
      closed.next({ reload: true });
      expect(answered).toBe(1);
    });

    it('does not reload when the sheet is cancelled, and gives the focus back to the same Give', async () => {
      await open();
      document.body.appendChild(el());
      gives()[1].click();
      http.expectOne(MAR_A2).flush(
        marOf({
          admissionId: 'a2',
          orders: [orderOf({ orderId: 'o1', slots: [slotOf({ slotAt: SLOT_1030AM, state: 'DUE' })] })],
        }),
      );
      await settle();
      closed.next(undefined);
      await tick();
      expect(answered).toBe(0);
      expect(document.activeElement).toBe(gives()[1]);
      el().remove();
    });

    describe('the slot can no longer be given: no sheet, the existing text, the board reloads', () => {
      const cases: [string, Mar, string][] = [
        [
          'recorded by someone else (Given)',
          marForLate({
            orders: [
              orderOf({
                orderId: 'o2',
                slots: [slotOf({ slotAt: SLOT_8AM, state: 'GIVEN', canGive: false, dose: GIVEN_DOSE })],
              }),
            ],
          }),
          'This dose is already recorded.',
        ],
        [
          'recorded as not given (Held)',
          marForLate({
            orders: [orderOf({ orderId: 'o2', slots: [slotOf({ slotAt: SLOT_8AM, state: 'HELD', canGive: false })] })],
          }),
          'This dose is already recorded.',
        ],
        [
          'recorded as refused',
          marForLate({
            orders: [orderOf({ orderId: 'o2', slots: [slotOf({ slotAt: SLOT_8AM, state: 'REFUSED', canGive: false })] })],
          }),
          'This dose is already recorded.',
        ],
        [
          'the order is stopped (not in the MAR)',
          marForLate({ orders: [orderOf({ orderId: 'other' })] }),
          'This medicine was stopped. Its doses can no longer be recorded.',
        ],
        [
          'the patient is discharged',
          marForLate({ status: 'DISCHARGED', canGive: false, giveLockReason: 'NOT_ADMITTED' }),
          'This patient is not admitted any more. Doses cannot be recorded.',
        ],
        [
          'IPD became read-only',
          marForLate({ canGive: false, giveLockReason: 'IPD_READONLY' }),
          'IPD is read-only. Doses cannot be recorded.',
        ],
        [
          'the slot is not in the MAR',
          marForLate({ orders: [orderOf({ orderId: 'o2', slots: [slotOf({ slotAt: SLOT_1030AM })] })] }),
          'Reload the page and try again.',
        ],
        [
          'the slot is not Due or Late any more',
          marForLate({
            orders: [
              orderOf({ orderId: 'o2', slots: [slotOf({ slotAt: SLOT_8AM, state: 'LATER', canGive: false })] }),
            ],
          }),
          'Reload the page and try again.',
        ],
        [
          'the server says the slot can not be given',
          marForLate({
            orders: [orderOf({ orderId: 'o2', slots: [slotOf({ slotAt: SLOT_8AM, state: 'LATE', canGive: false })] })],
          }),
          'Reload the page and try again.',
        ],
      ];

      for (const [name, mar, text] of cases) {
        it(name, async () => {
          await open();
          gives()[0].click();
          http.expectOne(MAR_A1).flush(mar);
          await settle();
          expect(dialog.open).not.toHaveBeenCalled();
          expect(toasts()).toEqual([text]);
          expect(answered).toBe(1);
          expect(gives().every((b) => b.getAttribute('aria-disabled') !== 'true')).toBe(true);
        });
      }
    });

    it('shows the MAR text when the MAR can not be read, keeps the board and gives the focus back', async () => {
      await open();
      document.body.appendChild(el());
      gives()[0].click();
      http.expectOne(MAR_A1).error(new ProgressEvent('error'));
      await settle();
      await tick();
      expect(toasts()).toEqual(['We could not load the medicines. Try again.']);
      expect(dialog.open).not.toHaveBeenCalled();
      expect(answered).toBe(0);
      expect(document.activeElement).toBe(gives()[0]);
      el().remove();
    });

    it('shows the server detail of a refused MAR read (403, 404)', async () => {
      await open();
      gives()[0].click();
      http
        .expectOne(MAR_A1)
        .flush(problem('not-found', 404, 'This patient was not found.'), { status: 404, statusText: 'x' });
      await settle();
      expect(toasts()).toEqual(['This patient was not found.']);
      expect(answered).toBe(0);
    });

    it('does not read anything when canGive turned false (the button is gone anyway)', async () => {
      await open(roundOf({ canGive: false, giveLockReason: 'ROLE' }));
      expect(gives().length).toBe(0);
      http.expectNone(MAR_A1);
    });
  });

  describe('focus after the board reloaded (restoreFocus)', () => {
    async function giveAndRecord(): Promise<void> {
      gives()[0].click();
      http.expectOne(MAR_A1).flush(marForLate());
      await settle();
      closed.next({ mar: marForLate() });
    }

    it('goes to the Give button of the same dose when the row is still there', async () => {
      await open();
      document.body.appendChild(el());
      await giveAndRecord();
      await open2(roundOf({ rows: [ROUND_LATE, ROUND_DUE] }));
      fixture.componentInstance.restoreFocus();
      expect(document.activeElement).toBe(gives()[0]);
      el().remove();
    });

    it('goes to the panel heading (tabindex -1) when the row is gone', async () => {
      await open();
      document.body.appendChild(el());
      await giveAndRecord();
      await open2(roundOf({ rows: [ROUND_DUE] }));
      fixture.componentInstance.restoreFocus();
      expect(document.activeElement).toBe(q('#sf-round-h'));
      el().remove();
    });

    it('goes to the panel heading when the list is empty', async () => {
      await open();
      document.body.appendChild(el());
      await giveAndRecord();
      await open2(roundOf({ rows: [] }));
      fixture.componentInstance.restoreFocus();
      expect(document.activeElement).toBe(q('#sf-round-h'));
      el().remove();
    });

    it('does nothing when no Give was used', async () => {
      await open();
      document.body.appendChild(el());
      fixture.componentInstance.restoreFocus();
      expect(document.activeElement).toBe(document.body);
      el().remove();
    });
  });
});
