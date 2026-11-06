import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { problem } from '../emr/emr.fixtures';
import { GiveResult, GiveSheetComponent } from './give-sheet.component';
import {
  AT_2PM,
  AT_8AM,
  AT_8PM,
  GIVEN_DOSE,
  ORDER_HEP,
  ORDER_PCM,
  marOf,
  orderOf,
  slotOf,
} from './mar.fixtures';
import { MarTabComponent } from './mar-tab.component';
import { Mar } from './mar.model';

const URL = '/api/v1/ipd/admissions/a1/mar';

describe('MarTabComponent', () => {
  let fixture: ComponentFixture<MarTabComponent>;
  let http: HttpTestingController;
  let closed: Subject<GiveResult | undefined>;
  let dialog: { open: ReturnType<typeof vi.fn> };
  let changes: Mar[];

  async function open(mar: Mar | null = marOf()): Promise<void> {
    closed = new Subject<GiveResult | undefined>();
    dialog = { open: vi.fn(() => ({ afterClosed: () => closed.asObservable() })) };
    changes = [];
    await TestBed.configureTestingModule({
      imports: [MarTabComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialog, useValue: dialog },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MarTabComponent);
    fixture.componentRef.setInput('admissionId', 'a1');
    fixture.componentInstance.marChange.subscribe((m) => changes.push(m));
    fixture.detectChanges();
    if (mar) {
      http.expectOne(URL).flush(mar);
      await settle();
    }
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
  const block = (orderId: string) => q(`[data-testid=mar-orders] [role=listitem][data-order="${orderId}"]`)!;
  const slot = (orderId: string, slotAt: string) =>
    q<HTMLElement>(`[data-testid=mar-slot][data-order="${orderId}"][data-slot="${slotAt}"]`)!;

  afterEach(() => http.verify());

  describe('reading the MAR', () => {
    it('reads the MAR of the admission when the tab opens and tells the sheet around it', async () => {
      await open(null);
      expect(q('[role=status]')!.textContent).toContain('Loading the medicines');
      const mar = marOf();
      http.expectOne(URL).flush(mar);
      await settle();
      expect(changes).toEqual([mar]);
      expect(q('[role=status]')).toBeNull();
    });

    it('shows an error with Try again when it can not be read, and reads again on Try again', async () => {
      await open(null);
      http.expectOne(URL).flush(problem('internal', 500, 'boom'), { status: 500, statusText: 'x' });
      await settle();
      expect(squash(q('[data-testid=mar-load-error]'))).toContain('boom');
      q<HTMLButtonElement>('[data-act=mar-retry]')!.click();
      http.expectOne(URL).flush(marOf());
      await settle();
      expect(q('[data-testid=mar-load-error]')).toBeNull();
      expect(q('[data-testid=mar-orders]')).not.toBeNull();
    });

    it('says "We could not load the medicines. Try again." when the answer has no detail', async () => {
      await open(null);
      http.expectOne(URL).error(new ProgressEvent('error'));
      await settle();
      expect(squash(q('[data-testid=mar-load-error]'))).toContain('We could not load the medicines. Try again.');
    });
  });

  describe('layout (mockup sfMar)', () => {
    it('shows the identity bar first, then the orders, then the closing line', async () => {
      await open();
      expect(squash(q('[data-testid=identity-name]'))).toBe('Ramesh Kale');
      expect(squash(q('[data-testid=identity-line]'))).toBe('PT-00042 · 64 · Male · bed G-03');
      const bar = q('[data-testid=identity-bar]')!;
      const orders = q('[data-testid=mar-orders]')!;
      const legend = q('[data-testid=mar-legend]')!;
      expect(bar.compareDocumentPosition(orders) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(orders.compareDocumentPosition(legend) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(squash(legend)).toBe(
        "Every dose needs the wristband scan and the patient's name and date of birth. High-alert medicines also need a second nurse.",
      );
      expect(legend.querySelector('app-icon')).toBeTruthy();
    });

    it('one block per order: name, small line, status pill', async () => {
      await open();
      const pcm = block('o1');
      expect(squash(pcm.querySelector('[data-testid=mar-drug]'))).toBe('Tab. Paracetamol 650 mg');
      expect(squash(pcm.querySelector('[data-testid=mar-order-line]'))).toBe('Oral · Twice daily · Dr. Meera Kulkarni');
      expect(squash(pcm.querySelector('[data-testid=mar-status]'))).toBe('Active');
      expect(pcm.querySelector('[data-testid=mar-status]')!.classList.contains('p-good')).toBe(true);
      expect(pcm.querySelector('[data-testid=mar-high-alert]')).toBeNull();
      expect(pcm.querySelector('[data-testid=mar-pending-note]')).toBeNull();
    });

    it('a high-alert medicine has the red pill with its class and the hint', async () => {
      await open();
      const pill = block('o2').querySelector<HTMLElement>('[data-testid=mar-high-alert]')!;
      expect(pill.classList.contains('p-crit')).toBe(true);
      expect(pill.textContent).toContain('High-alert · Anticoagulant');
      expect(pill.getAttribute('title')).toBe('High-alert medicine: a second nurse checks every dose');
      expect(squash(pill.querySelector('.sr-only'))).toBe('. High-alert medicine: a second nurse checks every dose');
      expect(pill.querySelector('app-icon')).toBeTruthy();
    });

    it('a pending order has the "Pharmacist check" pill, waiting slots and the lock line', async () => {
      const pending = orderOf({
        orderId: 'o3',
        status: 'PENDING',
        slots: [slotOf({ slotAt: AT_8AM, state: 'WAITING', canGive: false }), slotOf({ slotAt: AT_8PM, state: 'WAITING', canGive: false })],
      });
      await open(marOf({ orders: [pending], doseCount: 0 }));
      const b = block('o3');
      expect(squash(b.querySelector('[data-testid=mar-status]'))).toBe('Pharmacist check');
      expect(b.querySelector('[data-testid=mar-status]')!.classList.contains('p-warn')).toBe(true);
      expect(squash(b.querySelector('[data-testid=mar-pending-note]'))).toBe('Cannot be given until the pharmacist checks the order.');
      expect(b.querySelector('[data-testid=mar-pending-note] app-icon')).toBeTruthy();
      expect(squash(slot('o3', AT_8AM))).toBe('8 AMWaiting for pharmacist');
      expect(slot('o3', AT_8AM).tagName).toBe('SPAN');
    });

    it('with no orders: "No medicines on the chart. The doctor orders them from EMR." and still the closing line', async () => {
      await open(marOf({ orders: [], doseCount: 0 }));
      expect(squash(q('[data-testid=mar-empty]'))).toBe('No medicines on the chart. The doctor orders them from EMR.');
      expect(q('[data-testid=mar-legend]')).not.toBeNull();
    });

    it('IPD read-only and not admitted show their one line; the role shows none', async () => {
      await open(marOf({ canGive: false, giveLockReason: 'IPD_READONLY' }));
      expect(squash(q('[data-testid=mar-lock]'))).toBe('IPD is read-only. Doses cannot be recorded.');
      TestBed.resetTestingModule();
      await open(marOf({ canGive: false, giveLockReason: 'NOT_ADMITTED', status: 'DISCHARGED' }));
      expect(squash(q('[data-testid=mar-lock]'))).toBe('This patient is not admitted any more.');
      TestBed.resetTestingModule();
      await open(marOf({ canGive: false, giveLockReason: 'ROLE' }));
      expect(q('[data-testid=mar-lock]')).toBeNull();
    });

    it('the lock line comes after the identity bar and before the orders', async () => {
      await open(marOf({ canGive: false, giveLockReason: 'IPD_READONLY' }));
      const lock = q('[data-testid=mar-lock]')!;
      expect(q('[data-testid=identity-bar]')!.compareDocumentPosition(lock) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(lock.compareDocumentPosition(q('[data-testid=mar-orders]')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });
  });

  describe('slots, one per time, by state', () => {
    it('Given: a check icon, the time, "<time recorded> · <last name>" and the hint', async () => {
      await open();
      const s = slot('o2', AT_8AM);
      expect(s.tagName).toBe('SPAN');
      expect(s.getAttribute('data-state')).toBe('GIVEN');
      expect(squash(s)).toContain('8 AM8:04 AM · Pawar');
      expect(s.querySelector('app-icon')).toBeTruthy();
      expect(s.getAttribute('title')).toBe('Given by Sr. Anjali Pawar, checked by Sr. Rupali More');
      expect(squash(s.querySelector('.sr-only'))).toBe('. Given by Sr. Anjali Pawar, checked by Sr. Rupali More');
    });

    it('Not given and Refused: the time and "Not given · <reason>" or "Refused · <reason>"', async () => {
      const held = { ...GIVEN_DOSE, outcome: 'HELD' as const, reason: { key: 'NIL_BY_MOUTH', label: 'Nil by mouth' }, secondCheckByName: null };
      const refused = { ...held, outcome: 'REFUSED' as const, reason: { key: 'PATIENT_REFUSED', label: 'Patient refused' } };
      const order = orderOf({
        slots: [
          slotOf({ slotAt: AT_8AM, state: 'HELD', canGive: false, dose: held }),
          slotOf({ slotAt: AT_2PM, state: 'REFUSED', canGive: false, dose: refused }),
        ],
      });
      await open(marOf({ orders: [order], doseCount: 0 }));
      expect(squash(slot('o1', AT_8AM))).toBe('8 AMNot given · Nil by mouth');
      expect(squash(slot('o1', AT_2PM))).toBe('2 PMRefused · Patient refused');
      expect(slot('o1', AT_8AM).tagName).toBe('SPAN');
    });

    it('Due: an amber button "Due now · Give"', async () => {
      await open();
      const s = slot('o1', AT_8AM);
      expect(s.tagName).toBe('BUTTON');
      expect(squash(s)).toBe('8 AMDue now · Give');
      expect(s.classList.contains('warn')).toBe(true);
      expect((s as HTMLButtonElement).disabled).toBe(false);
    });

    it('Late: a red button "1 h late · Give"', async () => {
      const order = orderOf({ slots: [slotOf({ state: 'LATE', lateHours: 1 }), slotOf({ slotAt: AT_8PM, state: 'LATER', canGive: false })] });
      await open(marOf({ orders: [order] }));
      const s = slot('o1', AT_8AM);
      expect(squash(s)).toBe('8 AM1 h late · Give');
      expect(s.classList.contains('crit')).toBe(true);
    });

    it('Later today, Not recorded, Before the order started: grey, not a button', async () => {
      const order = orderOf({
        slots: [
          slotOf({ slotAt: AT_8AM, state: 'BEFORE_START', canGive: false }),
          slotOf({ slotAt: AT_2PM, state: 'NOT_RECORDED', canGive: false }),
          slotOf({ slotAt: AT_8PM, state: 'LATER', canGive: false }),
        ],
      });
      await open(marOf({ orders: [order], doseCount: 0 }));
      expect(squash(slot('o1', AT_8AM))).toBe('8 AMBefore the order started');
      expect(squash(slot('o1', AT_2PM))).toBe('2 PMNot recorded');
      expect(squash(slot('o1', AT_8PM))).toBe('8 PMLater today');
      for (const at of [AT_8AM, AT_2PM, AT_8PM]) {
        expect(slot('o1', at).tagName).toBe('SPAN');
      }
    });

    it('a slot button points at the medicine name for screen readers', async () => {
      await open();
      expect(slot('o1', AT_8AM).getAttribute('aria-describedby')).toBe('mar-name-o1');
      expect(q('#mar-name-o1')!.textContent).toBe('Tab. Paracetamol 650 mg');
    });

    it('a slot button is at least 44 px tall by its class (touch target)', async () => {
      await open();
      expect(slot('o1', AT_8AM).classList.contains('sfs')).toBe(true);
    });
  });

  describe('who can give', () => {
    it('the button is disabled when the whole MAR can not give (DOCTOR, read-only, discharged)', async () => {
      await open(marOf({ canGive: false, giveLockReason: 'ROLE' }));
      const s = slot('o1', AT_8AM) as HTMLButtonElement;
      expect(s.disabled).toBe(true);
      s.click();
      expect(dialog.open).not.toHaveBeenCalled();
    });

    it('the button is disabled when the server says the slot can not be given', async () => {
      const order = orderOf({ slots: [slotOf({ state: 'DUE', canGive: false }), slotOf({ slotAt: AT_8PM, state: 'LATER', canGive: false })] });
      await open(marOf({ orders: [order] }));
      expect((slot('o1', AT_8AM) as HTMLButtonElement).disabled).toBe(true);
    });
  });

  describe('the Give sheet', () => {
    it('opens from a Due slot with the order, the slot and the MAR, autoFocus off', async () => {
      const mar = marOf();
      await open(mar);
      slot('o1', AT_8AM).click();
      expect(dialog.open).toHaveBeenCalledTimes(1);
      const [component, config] = dialog.open.mock.calls[0] as [unknown, Record<string, unknown>];
      expect(component).toBe(GiveSheetComponent);
      expect(config).toMatchObject({
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        ariaLabelledBy: 'sf-gh',
        autoFocus: false,
        data: { mar, order: ORDER_PCM, slot: ORDER_PCM.slots[0] },
      });
    });

    it('a recorded dose replaces the MAR without another request, tells the sheet and focuses the slot (now Given)', async () => {
      await open();
      slot('o1', AT_8AM).click();
      const after = marOf({
        doseCount: 0,
        orders: [
          orderOf({
            slots: [slotOf({ slotAt: AT_8AM, state: 'GIVEN', canGive: false, dose: { ...GIVEN_DOSE, secondCheckByName: null } }), ORDER_PCM.slots[1]],
          }),
          ORDER_HEP,
        ],
      });
      closed.next({ mar: after });
      await settle();
      await tick();
      expect(changes[changes.length - 1]).toBe(after);
      const s = slot('o1', AT_8AM);
      expect(s.getAttribute('data-state')).toBe('GIVEN');
      expect(s.tagName).toBe('SPAN');
      expect(document.activeElement).toBe(s);
    });

    it('a reload result reads the MAR again', async () => {
      await open();
      slot('o1', AT_8AM).click();
      closed.next({ reload: true });
      const again = http.expectOne(URL);
      again.flush(marOf({ doseCount: 2 }));
      await settle();
      await tick();
      expect(changes[changes.length - 1].doseCount).toBe(2);
    });

    describe('the focus after a reload (a 409 closed the sheet)', () => {
      const given = (): Mar =>
        marOf({
          doseCount: 0,
          orders: [
            orderOf({
              slots: [slotOf({ slotAt: AT_8AM, state: 'GIVEN', canGive: false, dose: { ...GIVEN_DOSE, secondCheckByName: null } }), ORDER_PCM.slots[1]],
            }),
            ORDER_HEP,
          ],
        });

      /** The slot is opened, the dialog gives the focus back to its chip, the sheet closes with `reload`. */
      function closeWithReload(): void {
        const chip = slot('o1', AT_8AM);
        chip.click();
        chip.focus();
        closed.next({ reload: true });
      }

      it('waits for the second read: nothing is focused while it is in flight, then the redrawn chip has the focus', async () => {
        await open();
        closeWithReload();
        const spy = vi.spyOn(HTMLElement.prototype, 'focus');
        const again = http.expectOne(URL);
        await tick();
        expect(spy).not.toHaveBeenCalled();
        again.flush(given());
        await settle();
        await tick();
        const s = slot('o1', AT_8AM);
        expect(s.tagName).toBe('SPAN');
        expect(document.activeElement).toBe(s);
        spy.mockRestore();
      });

      it('a chip that is still a button gets the focus back', async () => {
        await open();
        closeWithReload();
        http.expectOne(URL).flush(marOf());
        await settle();
        await tick();
        expect(slot('o1', AT_8AM).tagName).toBe('BUTTON');
        expect(document.activeElement).toBe(slot('o1', AT_8AM));
      });

      it('a chip that can not take the focus (a disabled button, the role may not give) sends it to the list of medicines, not the page body', async () => {
        await open();
        closeWithReload();
        const later = marOf({
          orders: [orderOf({ slots: [slotOf({ slotAt: AT_8AM, state: 'DUE', canGive: false }), ORDER_PCM.slots[1]] }), ORDER_HEP],
        });
        http.expectOne(URL).flush(later);
        await settle();
        await tick();
        expect((slot('o1', AT_8AM) as HTMLButtonElement).disabled).toBe(true);
        const list = q('[data-testid=mar-orders]')!;
        expect(list.getAttribute('tabindex')).toBe('-1');
        expect(document.activeElement).toBe(list);
      });

      it('when the second read fails, the focus still does not fall to the page body', async () => {
        await open();
        closeWithReload();
        (document.activeElement as HTMLElement).blur();
        http.expectOne(URL).flush(problem('internal', 500, 'boom'), { status: 500, statusText: 'x' });
        await settle();
        await tick();
        expect(squash(q('[data-testid=mar-load-error]'))).toContain('boom');
        expect(document.activeElement).not.toBe(document.body);
        expect(document.activeElement).toBe(slot('o1', AT_8AM));
      });

      it('does not take the focus from an element the user has already moved to', async () => {
        await open();
        closeWithReload();
        const other = slot('o2', AT_8AM);
        other.focus();
        http.expectOne(URL).flush(given());
        await settle();
        await tick();
        expect(document.activeElement).toBe(other);
      });
    });

    it('closing without a result changes nothing and puts the focus back on the slot button', async () => {
      await open();
      const before = changes.length;
      slot('o1', AT_8AM).click();
      closed.next(undefined);
      await settle();
      await tick();
      expect(changes.length).toBe(before);
      expect(document.activeElement).toBe(slot('o1', AT_8AM));
      expect(slot('o1', AT_8AM).tagName).toBe('BUTTON');
    });
  });
});
