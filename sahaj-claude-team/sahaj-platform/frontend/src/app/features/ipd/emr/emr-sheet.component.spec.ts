import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { EmrSheetComponent } from './emr-sheet.component';
import {
  ORDER,
  PATIENT,
  TOKEN_1,
  TOKEN_2,
  checkOf,
  hardStop,
  problem,
  sheetOf,
  warning,
} from './emr.fixtures';
import { EmrSheet, MedicineOrderAnswer } from './emr.model';

const EMR = '/api/v1/ipd/admissions/a1/emr';
const ORDERS = '/api/v1/ipd/admissions/a1/medicine-orders';
const STOP = '/api/v1/ipd/medicine-orders/o1/stop';

const answer = (over: Partial<MedicineOrderAnswer>): MedicineOrderAnswer => ({
  outcome: 'PLACED',
  replayed: false,
  check: checkOf(),
  order: null,
  sheet: sheetOf(),
  ...over,
});

describe('EmrSheetComponent', () => {
  let fixture: ComponentFixture<EmrSheetComponent>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;
  let close: ReturnType<typeof vi.fn>;
  let ref: { close: ReturnType<typeof vi.fn>; disableClose?: boolean };

  async function open(first: EmrSheet | { status: number; body: object } = sheetOf()): Promise<void> {
    snack = vi.fn();
    close = vi.fn();
    ref = { close };
    await TestBed.configureTestingModule({
      imports: [EmrSheetComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { admissionId: 'a1' } },
        { provide: MatDialogRef, useValue: ref },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EmrSheetComponent);
    fixture.detectChanges();
    const req = http.expectOne(EMR);
    if ('status' in first && 'body' in first) {
      req.flush(first.body, { status: first.status, statusText: 'x' });
    } else {
      req.flush(first);
    }
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
  const drug = () => q<HTMLSelectElement>('#emr-drug')!;
  const freq = () => q<HTMLSelectElement>('#emr-freq')!;
  const reason = () => q<HTMLSelectElement>('#emr-reason');
  const orderButton = () => q<HTMLButtonElement>('[data-act=sfordchk]')!;
  const toasts = () => snack.mock.calls.map((c) => c[0] as string);
  const tick = () => new Promise<void>((r) => setTimeout(r, 0));

  async function choose(select: HTMLSelectElement, value: string): Promise<void> {
    select.value = value;
    select.dispatchEvent(new Event('change'));
    await settle();
  }

  async function press(): Promise<TestRequest> {
    orderButton().click();
    await settle();
    return http.expectOne(ORDERS);
  }

  afterEach(() => http.verify());

  describe('layout (mockup pcEmr)', () => {
    it('shows the title, the subtitle, the identity bar, the strip, the medicines, the form and the footer in order', async () => {
      await open();
      expect(q('h2#emr-title')!.textContent).toBe('EMR · Asha Patil');
      expect(squash(q('[data-testid=emr-subtitle]'))).toBe('UH-0001 · 42 · Female · Dr. Meera Kulkarni');
      const blocks = Array.from(el().querySelectorAll('.sheet-b > *')).map(
        (n) => n.tagName.toLowerCase() + '.' + (n.className.split(' ')[0] ?? ''),
      );
      expect(blocks).toEqual(['app-identity-bar.', 'div.ipsum', 'section.ipbill', 'form.sford', 'p.faint']);
      expect(squash(q('[data-testid=identity-name]'))).toBe('Asha Patil');
      expect(squash(q('[data-testid=identity-line]'))).toBe('UH-0001 · 42 · Female · bed 101-A');
      const strip = Array.from(el().querySelectorAll('.ipsum > div')).map((d) => [
        squash(d.querySelector('small')),
        squash(d.querySelector('b, .dx')),
      ]);
      expect(strip).toEqual([
        ['Diagnosis', 'Community acquired pneumonia'],
        ['Day', '3'],
      ]);
      expect(squash(q('.ipbh b'))).toBe('Medicines on chart');
      expect(squash(q('.ipbh small'))).toBe("Doctor's orders · pharmacist checked before the first dose");
      expect(squash(q('.logline'))).toBe('Opening this record is logged in the clinical audit trail.');
      expect(Array.from(el().querySelectorAll('.sheet-f button')).map((b) => squash(b))).toEqual(['Done']);
    });

    it('shows the allergy chip in red with the allergy text', async () => {
      await open();
      const chip = q('[data-testid=allergy-chip]')!;
      expect(chip.getAttribute('data-tone')).toBe('bad');
      expect(squash(chip)).toBe('Allergy: Penicillin (rash)');
    });

    it('shows "Allergies not recorded" as its own chip', async () => {
      await open(sheetOf({ patient: { ...PATIENT, allergyText: null, allergyStatus: 'NOT_RECORDED' } }));
      const chip = q('[data-testid=allergy-chip]')!;
      expect(chip.getAttribute('data-tone')).toBe('nr');
      expect(squash(chip)).toBe('Allergies not recorded');
    });

    it('shows no "Last dose" line while no dose was recorded', async () => {
      await open();
      expect(q('[data-testid=order-last-dose]')).toBeNull();
    });

    it('shows the "Last dose" line under the small line (OQ-32-4): Given in the normal colour', async () => {
      const given = {
        id: 'd1',
        slotAt: '2026-11-06T02:30:00Z',
        outcome: 'GIVEN' as const,
        reason: null,
        recordedAt: '2026-11-06T02:34:00Z',
        recordedByName: 'Sr. Anjali Pawar',
        secondCheckByName: null,
      };
      await open(sheetOf({ orders: [{ ...ORDER, lastDose: given }] }));
      const line = q('[data-testid=order-last-dose]')!;
      expect(squash(line)).toBe('Last dose 8 AM: Given · 8:04 AM · Pawar');
      expect(line.getAttribute('data-warn')).toBe('false');
      expect(line.classList.contains('warnline')).toBe(false);
      expect(q('[data-testid=order-line]')!.compareDocumentPosition(line) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('shows Refused and Not given in the warn colour, with the reason in words, and another day with its date', async () => {
      const base = {
        id: 'd2',
        slotAt: '2026-11-06T02:30:00Z',
        reason: { key: 'PATIENT_REFUSED', label: 'Patient refused' },
        recordedAt: '2026-11-06T02:34:00Z',
        recordedByName: 'Sr. Anjali Pawar',
        secondCheckByName: null,
      };
      await open(
        sheetOf({
          orders: [
            { ...ORDER, id: 'o1', lastDose: { ...base, outcome: 'REFUSED' as const } },
            { ...ORDER, id: 'o2', lastDose: { ...base, outcome: 'HELD' as const, reason: { key: 'NIL_BY_MOUTH', label: 'Nil by mouth' } } },
            { ...ORDER, id: 'o3', lastDose: { ...base, outcome: 'REFUSED' as const, slotAt: '2026-10-24T14:30:00Z' } },
          ],
        }),
      );
      const lines = Array.from(el().querySelectorAll('[data-testid=order-last-dose]'));
      expect(lines.map((l) => squash(l))).toEqual([
        'Last dose 8 AM: Refused · Patient refused',
        'Last dose 8 AM: Not given · Nil by mouth',
        'Last dose 24 Oct, 8 PM: Refused · Patient refused',
      ]);
      expect(lines.every((l) => l.classList.contains('warnline') && l.getAttribute('data-warn') === 'true')).toBe(true);
    });

    it('lists the medicines with the High-alert pill, the small line and the status pill', async () => {
      const hep = {
        ...ORDER,
        id: 'o2',
        drugName: 'Inj. Heparin 5,000 IU',
        route: 'SC',
        highAlert: 'Anticoagulant',
        status: 'PENDING' as const,
        overrideReason: { key: 'CARDIOLOGY_STENT', label: 'Recent coronary stent, cardiology advised' },
      };
      const rejected = {
        ...ORDER,
        id: 'o3',
        status: 'REJECTED' as const,
        canStop: false,
        rejectReason: { key: 'WRONG_DOSE', label: 'Wrong dose or frequency' },
      };
      const stopped = { ...ORDER, id: 'o4', status: 'STOPPED' as const, canStop: false };
      await open(sheetOf({ orders: [ORDER, hep, rejected, stopped] }));
      const rows = Array.from(el().querySelectorAll('[data-order]'));
      expect(rows.map((r) => r.getAttribute('data-order'))).toEqual(['o1', 'o2', 'o3', 'o4']);
      expect(rows.map((r) => squash(r.querySelector('[data-testid=order-pill]')))).toEqual([
        'Active',
        'Pharmacist check',
        'Rejected',
        'Stopped',
      ]);
      expect(rows[1].querySelector('[data-testid=high-alert]')!.textContent).toBe('High-alert');
      expect(rows[0].querySelector('[data-testid=high-alert]')).toBeNull();
      expect(squash(rows[1].querySelector('[data-testid=order-line]'))).toBe(
        'SC · Once daily · Dr. Meera Kulkarni · override: Recent coronary stent, cardiology advised',
      );
      expect(squash(rows[2].querySelector('[data-testid=order-line]'))).toBe(
        'Oral · Once daily · Dr. Meera Kulkarni · rejected: Wrong dose or frequency',
      );
      expect(rows[0].querySelector('[data-act=sfstop]')).not.toBeNull();
      expect(rows[2].querySelector('[data-act=sfstop]')).toBeNull();
      expect(rows[3].querySelector('[data-act=sfstop]')).toBeNull();
      expect(rows[0].querySelector('.pill.p-good')).not.toBeNull();
      expect(rows[2].querySelector('.pill.p-crit')).not.toBeNull();
    });

    it('says "No medicines ordered yet" for an empty chart', async () => {
      await open(sheetOf({ orders: [] }));
      expect(squash(q('[data-testid=emr-orders]'))).toBe('No medicines ordered yet');
    });

    it('fills the selects: the catalogue in order, high-alert marked, the first preselected', async () => {
      await open();
      expect(Array.from(drug().options).map((o) => o.textContent)).toEqual([
        'Tab. Paracetamol 650 mg',
        'Tab. Amoxicillin + clavulanate 625 mg',
        'Tab. Aspirin 75 mg',
        'Inj. Heparin 5,000 IU · high-alert',
      ]);
      expect(drug().value).toBe('pcm');
      expect(Array.from(freq().options).map((o) => o.textContent)).toEqual([
        'Once daily',
        'Twice daily',
        'Three times daily',
        '8-hourly',
      ]);
      expect(freq().value).toBe('od');
      expect(squash(q('label[for=emr-drug]'))).toBe('Medicine');
      expect(squash(q('label[for=emr-freq]'))).toBe('How often');
      expect(squash(orderButton())).toBe('Check and order');
      expect(q('[data-act=sfordx]')).toBeNull();
      expect(squash(q('.sford .row small'))).toBe(
        'Checks allergies, duplicates and interactions. A pharmacist checks every order before the first dose.',
      );
    });

    it('shows the role line instead of the form for a nurse', async () => {
      await open(sheetOf({ canOrder: false, orderLockReason: 'ROLE', orders: [{ ...ORDER, canStop: false }] }));
      expect(q('form.sford')).toBeNull();
      expect(squash(q('[data-testid=order-role-lock]'))).toBe('Only doctors can order medicines.');
      expect(q('[data-act=sfstop]')).toBeNull();
    });

    it('says the sheet is read-only when IPD is read-only, and shows no order form', async () => {
      await open(sheetOf({ canOrder: false, orderLockReason: 'IPD_READONLY' }));
      expect(q('form.sford')).toBeNull();
      expect(q('[data-testid=order-role-lock]')).toBeNull();
      expect(squash(q('[data-testid=order-lock]'))).toBe('IPD is read-only. Medicines can be stopped but not added.');
      expect(q('[data-testid=order-lock]')!.getAttribute('data-reason')).toBe('IPD_READONLY');
    });

    it('says the patient is not admitted any more when the order lock is NOT_ADMITTED', async () => {
      await open(sheetOf({ canOrder: false, orderLockReason: 'NOT_ADMITTED', status: 'DISCHARGED' }));
      expect(q('form.sford')).toBeNull();
      expect(q('[data-testid=order-role-lock]')).toBeNull();
      expect(squash(q('[data-testid=order-lock]'))).toBe('This patient is not admitted any more.');
    });

    it('shows no lock line when the doctor can order', async () => {
      await open();
      expect(q('[data-testid=order-lock]')).toBeNull();
      expect(q('[data-testid=order-role-lock]')).toBeNull();
    });

    it('says the details are hidden when the server refuses the role (403 forbidden)', async () => {
      await open({ status: 403, body: problem('forbidden', 403, 'Clinical details are hidden for your role.') });
      expect(squash(q('[data-testid=emr-hidden]'))).toBe('Clinical details are hidden for your role.');
      expect(q('.ipsum')).toBeNull();
    });

    it('shows the load error with Try again, and loads again', async () => {
      await open({ status: 500, body: problem('server-error', 500, 'Try later.') });
      expect(squash(q('[data-testid=emr-load-error]'))).toContain('Try later.');
      q<HTMLButtonElement>('[data-act=emr-retry]')!.click();
      await settle();
      http.expectOne(EMR).flush(sheetOf());
      await settle();
      expect(q('.ipsum')).not.toBeNull();
    });
  });

  describe('Check and order', () => {
    it('sends the first medicine and frequency with a client key and no reason, then shows the placed order', async () => {
      await open();
      const req = await press();
      expect(req.request.body).toEqual({
        clientKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
        drugKey: 'pcm',
        frequencyKey: 'od',
      });
      const placed = { ...ORDER, id: 'o9', status: 'PENDING' as const, canStop: true };
      req.flush(answer({ outcome: 'PLACED', order: placed, sheet: sheetOf({ orders: [ORDER, placed] }) }), {
        status: 201,
        statusText: 'Created',
      });
      await settle();
      expect(toasts()).toEqual([
        'Tab. Paracetamol 650 mg ordered. It goes to the pharmacist before the first dose.',
      ]);
      expect(el().querySelectorAll('[data-order]').length).toBe(2);
      expect(q('[data-testid=order-blocked]')).toBeNull();
      expect(q('[data-act=sfordx]')).toBeNull();
    });

    it('treats a 200 PLACED (a repeat) like a placed order', async () => {
      await open();
      const req = await press();
      req.flush(answer({ outcome: 'PLACED', replayed: true, order: { ...ORDER, status: 'PENDING' } }));
      await settle();
      expect(toasts()).toHaveLength(1);
      expect(toasts()[0]).toContain('ordered. It goes to the pharmacist');
    });

    it('shows the hard stop notice with every message on its own line and keeps the button as it was', async () => {
      await open();
      await choose(drug(), 'amox');
      const req = await press();
      expect(req.request.body.drugKey).toBe('amox');
      req.flush(
        answer({
          outcome: 'BLOCKED',
          check: checkOf({
            hardStops: [
              hardStop('Documented allergy: Penicillin (rash). Tab. Amoxicillin + clavulanate 625 mg is a penicillin.'),
              hardStop('Tab. Amoxicillin + clavulanate 625 mg is already on the chart. Change the existing order instead of adding a second one.'),
            ],
          }),
        }),
      );
      await settle();
      const notice = q('[data-testid=order-blocked]')!;
      expect(notice.classList).toContain('crit');
      expect(notice.getAttribute('role')).toBe('alert');
      expect(squash(notice.querySelector('b'))).toBe('Hard stop. This order cannot be placed.');
      expect(Array.from(notice.querySelectorAll('.line')).map((l) => squash(l))).toEqual([
        'Documented allergy: Penicillin (rash). Tab. Amoxicillin + clavulanate 625 mg is a penicillin.',
        'Tab. Amoxicillin + clavulanate 625 mg is already on the chart. Change the existing order instead of adding a second one.',
      ]);
      expect(squash(orderButton())).toBe('Check and order');
      expect(reason()).toBeNull();
      expect(q('[data-act=sfordx]')).not.toBeNull();
      expect(toasts()).toEqual([]);
    });

    it('asks for a reason on warnings: notice, reason select, and "Order with this reason"', async () => {
      await open();
      await choose(drug(), 'asp');
      const req = await press();
      req.flush(
        answer({
          outcome: 'NEEDS_REASON',
          check: checkOf({
            warnings: [
              warning('Bleeding risk: heparin with aspirin (Inj. Heparin 5,000 IU is on the chart).'),
              warning('Allergies are not recorded. Ask the patient and record them before the first dose.', 'MODERATE'),
            ],
          }),
        }),
      );
      await settle();
      const notice = q('[data-testid=order-warnings]')!;
      expect(notice.classList).toContain('warn');
      expect(squash(notice.querySelector('b'))).toBe('Check before ordering');
      const lines = Array.from(notice.querySelectorAll('.line'));
      expect(lines.map((l) => squash(l))).toEqual([
        'Major: Bleeding risk: heparin with aspirin (Inj. Heparin 5,000 IU is on the chart).',
        'Moderate: Allergies are not recorded. Ask the patient and record them before the first dose.',
      ]);
      expect(squash(lines[0].querySelector('b'))).toBe('Major:');
      expect(squash(q('label[for=emr-reason]'))).toBe('Reason to continue (recorded in the audit trail)');
      expect(Array.from(reason()!.options).map((o) => o.textContent)).toEqual([
        'Choose a reason',
        'Benefit outweighs the risk, monitoring in place',
        'Specialist advised this combination',
        'Allergy history checked with the patient: mild rash only, no anaphylaxis',
        'Recent coronary stent, cardiology advised',
      ]);
      expect(reason()!.value).toBe('');
      expect(squash(orderButton())).toBe('Order with this reason');
      expect(toasts()).toEqual([]);
    });

    describe('with a reason', () => {
      async function toReason(): Promise<string> {
        await open();
        await choose(drug(), 'asp');
        const first = await press();
        const key = first.request.body.clientKey as string;
        first.flush(
          answer({
            outcome: 'NEEDS_REASON',
            check: checkOf({ warnings: [warning('Bleeding risk: heparin with aspirin')], checkToken: TOKEN_1 }),
          }),
        );
        await settle();
        return key;
      }

      it('sends the reason and the checkToken it was shown, with the same client key', async () => {
        const key = await toReason();
        await choose(reason()!, 'CARDIOLOGY_STENT');
        const req = await press();
        expect(req.request.body).toEqual({
          clientKey: key,
          drugKey: 'asp',
          frequencyKey: 'od',
          overrideReasonKey: 'CARDIOLOGY_STENT',
          checkToken: TOKEN_1,
        });
        req.flush(answer({ outcome: 'PLACED', order: { ...ORDER, drugName: 'Tab. Aspirin 75 mg', status: 'PENDING' } }), {
          status: 201,
          statusText: 'Created',
        });
        await settle();
        expect(toasts()).toEqual(['Tab. Aspirin 75 mg ordered. It goes to the pharmacist before the first dose.']);
        expect(q('[data-testid=order-warnings]')).toBeNull();
        expect(drug().value).toBe('pcm');
      });

      it('shows the "Choose a reason" toast when the button is pressed again with no reason and the same medicine', async () => {
        const key = await toReason();
        const req = await press();
        expect(req.request.body).toEqual({ clientKey: key, drugKey: 'asp', frequencyKey: 'od' });
        req.flush(
          answer({
            outcome: 'NEEDS_REASON',
            check: checkOf({ warnings: [warning('Bleeding risk: heparin with aspirin')] }),
          }),
        );
        await settle();
        expect(toasts()).toEqual(['Choose a reason to continue, or change the medicine.']);
        expect(q('[data-testid=order-warnings]')).not.toBeNull();
      });

      it('clears the chosen reason and shows the new warnings when the server says they changed', async () => {
        await toReason();
        await choose(reason()!, 'BENEFIT_MONITORED');
        const req = await press();
        req.flush(
          answer({
            outcome: 'NEEDS_REASON',
            check: checkOf({
              warnings: [warning('Bleeding risk: heparin with aspirin'), warning('Stomach bleeding risk: aspirin with an NSAID', 'MODERATE')],
              checkToken: TOKEN_2,
            }),
          }),
        );
        await settle();
        expect(el().querySelectorAll('[data-testid=order-warnings] .line').length).toBe(2);
        expect(reason()!.value).toBe('');
        // the next send carries the new token
        await choose(reason()!, 'SPECIALIST_ADVISED');
        const again = await press();
        expect(again.request.body.checkToken).toBe(TOKEN_2);
        again.flush(answer({ outcome: 'PLACED', order: { ...ORDER, status: 'PENDING' } }));
      });

      it('replaces the client key when the chosen reason changes to another one', async () => {
        const key = await toReason();
        await choose(reason()!, 'BENEFIT_MONITORED');
        await choose(reason()!, 'SPECIALIST_ADVISED');
        const req = await press();
        expect(req.request.body.clientKey).not.toBe(key);
        req.flush(answer({ outcome: 'PLACED', order: { ...ORDER, status: 'PENDING' } }));
      });

      it('Clear removes the notice and the reason without a request, and Check and order is back', async () => {
        await toReason();
        await choose(reason()!, 'BENEFIT_MONITORED');
        q<HTMLButtonElement>('[data-act=sfordx]')!.click();
        await settle();
        expect(q('[data-testid=order-warnings]')).toBeNull();
        expect(reason()).toBeNull();
        expect(q('[data-act=sfordx]')).toBeNull();
        expect(squash(orderButton())).toBe('Check and order');
      });
    });

    it('replaces the client key and drops the check when the medicine or the frequency changes', async () => {
      await open();
      const first = await press();
      const key = first.request.body.clientKey as string;
      first.flush(
        answer({ outcome: 'BLOCKED', check: checkOf({ hardStops: [hardStop('Documented allergy: x')] }) }),
      );
      await settle();
      expect(q('[data-testid=order-blocked]')).not.toBeNull();
      await choose(freq(), 'bd');
      expect(q('[data-testid=order-blocked]')).toBeNull();
      const second = await press();
      expect(second.request.body.clientKey).not.toBe(key);
      expect(second.request.body.frequencyKey).toBe('bd');
      second.flush(answer({ outcome: 'PLACED', order: { ...ORDER, status: 'PENDING' } }));
      await settle();
      // after PLACED the next order has yet another key
      const third = await press();
      expect(third.request.body.clientKey).not.toBe(second.request.body.clientKey);
      third.flush(answer({ outcome: 'PLACED', order: { ...ORDER, status: 'PENDING' } }));
    });

    it('keeps the client key when the same values are retried after a failure', async () => {
      await open();
      const first = await press();
      first.flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'x' });
      await settle();
      const second = await press();
      expect(second.request.body.clientKey).toBe(first.request.body.clientKey);
      second.flush(answer({ outcome: 'PLACED', order: { ...ORDER, status: 'PENDING' } }));
    });

    it('makes a new client key after 409 client-key-reused and shows the detail as a toast', async () => {
      await open();
      const first = await press();
      first.flush(
        problem('client-key-reused', 409, 'This order was already placed with different values. Reload the page and try again.'),
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      expect(toasts()).toEqual([
        'This order was already placed with different values. Reload the page and try again.',
      ]);
      const second = await press();
      expect(second.request.body.clientKey).not.toBe(first.request.body.clientKey);
      second.flush(answer({ outcome: 'PLACED', order: { ...ORDER, status: 'PENDING' } }));
    });

    it('reads the sheet again after 409 not-admitted so the form goes away', async () => {
      await open();
      const req = await press();
      req.flush(problem('not-admitted', 409, 'This patient is not admitted any more. Medicines cannot be ordered or changed.'), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      http.expectOne(EMR).flush(sheetOf({ canOrder: false, orderLockReason: 'NOT_ADMITTED', status: 'DISCHARGED' }));
      await settle();
      expect(q('form.sford')).toBeNull();
      expect(squash(q('[data-testid=order-lock]'))).toBe('This patient is not admitted any more.');
      expect(toasts()).toEqual(['This patient is not admitted any more. Medicines cannot be ordered or changed.']);
    });

    it('shows the field errors of a 400 under the selects and sends no placed order', async () => {
      await open();
      const req = await press();
      req.flush(
        problem('validation', 400, 'Some fields are missing or not valid.', [
          { field: 'drugKey', message: 'Choose a medicine from the list' },
          { field: 'frequencyKey', message: 'Choose how often' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
      await settle();
      expect(squash(q('#emr-drug-err'))).toBe('Choose a medicine from the list');
      expect(squash(q('#emr-freq-err'))).toBe('Choose how often');
      expect(drug().getAttribute('aria-invalid')).toBe('true');
      expect(drug().getAttribute('aria-describedby')).toBe('emr-drug-err');
      expect(toasts()).toEqual([]);
      await choose(drug(), 'asp');
      expect(q('#emr-drug-err')).toBeNull();
    });

    it('shows a generic toast when the connection is lost and never shows the order as placed', async () => {
      await open();
      const req = await press();
      req.error(new ProgressEvent('error'));
      await settle();
      expect(toasts()).toEqual(['We could not place that order. Try again.']);
      expect(el().querySelectorAll('[data-order]').length).toBe(1);
    });

    it('turns Check and order, Clear, Stop, Done and the close button off while the request runs, and sends once', async () => {
      await open();
      const req = await press();
      expect(orderButton().disabled).toBe(true);
      expect(orderButton().getAttribute('aria-busy')).toBe('true');
      expect(q<HTMLButtonElement>('[data-act=sfstop]')!.disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-act=emr-done]')!.disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-act=emr-x]')!.disabled).toBe(true);
      orderButton().click();
      await settle();
      http.expectNone(ORDERS);
      expect(ref.disableClose).toBe(true);
      req.flush(answer({ outcome: 'PLACED', order: { ...ORDER, status: 'PENDING' } }));
      await settle();
      expect(orderButton().disabled).toBe(false);
      expect(ref.disableClose).toBe(false);
    });
  });

  describe('Stop', () => {
    const openStopConfirm = async (): Promise<void> => {
      q<HTMLButtonElement>('[data-act=sfstop]')!.click();
      await settle();
    };

    it('asks first, with Keep focused, and sends nothing until Stop is confirmed', async () => {
      await open();
      await openStopConfirm();
      http.expectNone(STOP);
      const confirm = q('[data-testid=stop-confirm]')!;
      expect(squash(confirm)).toContain('Stop this medicine?');
      expect(Array.from(confirm.querySelectorAll('button')).map((b) => squash(b))).toEqual(['Stop', 'Keep']);
      await tick();
      expect(document.activeElement?.id).toBe('emr-keep-o1');
    });

    it('Keep closes the question and gives the focus back to the Stop button', async () => {
      await open();
      await openStopConfirm();
      q<HTMLButtonElement>('[data-act=sfstopkeep]')!.click();
      await settle();
      await tick();
      expect(q('[data-testid=stop-confirm]')).toBeNull();
      expect(document.activeElement?.id).toBe('emr-stop-o1');
    });

    it('stops the order, shows the new sheet, the toast, and puts the focus on the Medicines heading', async () => {
      await open();
      await openStopConfirm();
      q<HTMLButtonElement>('[data-act=sfstopok]')!.click();
      await settle();
      expect(q<HTMLButtonElement>('[data-act=sfstopok]')!.disabled).toBe(true);
      const req = http.expectOne(STOP);
      expect(req.request.method).toBe('POST');
      req.flush(sheetOf({ orders: [{ ...ORDER, status: 'STOPPED', canStop: false }] }));
      await settle();
      await tick();
      expect(toasts()).toEqual(['Tab. Paracetamol 650 mg stopped. Remaining doses are removed from the MAR.']);
      expect(squash(q('[data-testid=order-pill]'))).toBe('Stopped');
      expect(q('[data-act=sfstop]')).toBeNull();
      expect(document.activeElement?.id).toBe('emr-meds-h');
    });

    it('shows the server text and reads the sheet again on a 409', async () => {
      await open();
      await openStopConfirm();
      q<HTMLButtonElement>('[data-act=sfstopok]')!.click();
      await settle();
      http.expectOne(STOP).flush(problem('order-state', 409, 'This order is already stopped.'), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      http.expectOne(EMR).flush(sheetOf({ orders: [{ ...ORDER, status: 'STOPPED', canStop: false }] }));
      await settle();
      expect(toasts()).toEqual(['This order is already stopped.']);
      expect(q('[data-testid=stop-confirm]')).toBeNull();
      expect(squash(q('[data-testid=order-pill]'))).toBe('Stopped');
    });

    it('shows a generic toast when the connection is lost', async () => {
      await open();
      await openStopConfirm();
      q<HTMLButtonElement>('[data-act=sfstopok]')!.click();
      await settle();
      http.expectOne(STOP).error(new ProgressEvent('error'));
      await settle();
      expect(toasts()).toEqual(['We could not stop that medicine. Try again.']);
    });

    it('turns every button off while the stop runs', async () => {
      await open();
      await openStopConfirm();
      q<HTMLButtonElement>('[data-act=sfstopok]')!.click();
      await settle();
      expect(q<HTMLButtonElement>('[data-act=sfstopkeep]')!.disabled).toBe(true);
      expect(orderButton().disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-act=emr-done]')!.disabled).toBe(true);
      expect(ref.disableClose).toBe(true);
      http.expectOne(STOP).flush(sheetOf());
    });
  });

  describe('closing', () => {
    it('the close button is a direct child of the sheet header (the shared 44 px rule targets it there)', async () => {
      await open();
      const x = q<HTMLButtonElement>('[data-act=emr-x]')!;
      expect(x.parentElement!.classList.contains('sheet-h')).toBe(true);
      expect(x.classList.contains('mat-mdc-icon-button')).toBe(true);
      expect(x.getAttribute('aria-label')).toBe('Close');
    });


    it('Done and the close button close the sheet', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=emr-done]')!.click();
      q<HTMLButtonElement>('[data-act=emr-x]')!.click();
      expect(close).toHaveBeenCalledTimes(2);
    });
  });
});
