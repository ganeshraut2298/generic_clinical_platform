import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, Subject } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { problem } from '../emr/emr.fixtures';
import { GiveSheetComponent, GiveSheetData } from './give-sheet.component';
import { AT_8AM, GIVEN_DOSE, ORDER_HEP, ORDER_PCM, marOf, slotOf } from './mar.fixtures';
import { DoseRecorded, Mar, MarOrder, MarSlot } from './mar.model';

const DOSES = '/api/v1/ipd/medicine-orders';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const HIGH_ALERT_MESSAGE = 'High-alert medicine: a second nurse must check it and enter a 4-digit PIN.';

describe('GiveSheetComponent', () => {
  let fixture: ComponentFixture<GiveSheetComponent>;
  let http: HttpTestingController;
  let ref: { close: ReturnType<typeof vi.fn>; afterOpened: () => Observable<void>; disableClose?: boolean };
  let opened: Subject<void>;
  let snack: ReturnType<typeof vi.fn>;
  let mar: Mar;
  let order: MarOrder;

  async function open(
    over: { order?: MarOrder; slot?: MarSlot; mar?: Mar } = {},
  ): Promise<void> {
    opened = new Subject<void>();
    ref = { close: vi.fn(), afterOpened: () => opened.asObservable() };
    snack = vi.fn();
    order = over.order ?? ORDER_PCM;
    mar = over.mar ?? marOf();
    const slot = over.slot ?? order.slots[0];
    const data: GiveSheetData = { mar, order, slot };
    await TestBed.configureTestingModule({
      imports: [GiveSheetComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GiveSheetComponent);
    fixture.detectChanges();
    opened.next();
    await settle();
    await tick();
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
  const box = () => q<HTMLInputElement>('#sf-id')!;
  const said = () => q<HTMLInputElement>('#sf-said')!;
  const nurse = () => q<HTMLSelectElement>('#sf-wit')!;
  const pin = () => q<HTMLInputElement>('#sf-pin')!;
  const why = () => q<HTMLSelectElement>('#sf-why')!;
  const givenButton = () => q<HTMLButtonElement>('[data-act=sfgiveok]')!;
  const notGivenButton = () => q<HTMLButtonElement>('[data-act=sfnogive]')!;

  async function typeBox(value: string): Promise<void> {
    box().value = value;
    box().dispatchEvent(new Event('input'));
    await settle();
  }
  async function tickSaid(on = true): Promise<void> {
    said().checked = on;
    said().dispatchEvent(new Event('change'));
    await settle();
  }
  async function choose(select: HTMLSelectElement, value: string): Promise<void> {
    select.value = value;
    select.dispatchEvent(new Event('change'));
    await settle();
  }
  async function typePin(value: string): Promise<void> {
    pin().value = value;
    pin().dispatchEvent(new Event('input'));
    await settle();
  }

  /** The box and the statement filled for the patient of the fixture. */
  async function fillIdentity(): Promise<void> {
    await typeBox('PT-00042');
    await tickSaid();
  }

  async function fillHighAlert(): Promise<void> {
    await fillIdentity();
    await choose(nurse(), 'm-rupali');
    await typePin('4821');
  }

  async function clickGiven(): Promise<TestRequest> {
    givenButton().click();
    await settle();
    return http.expectOne(`${DOSES}/${order.orderId}/doses`);
  }

  async function clickNotGiven(): Promise<TestRequest> {
    notGivenButton().click();
    await settle();
    return http.expectOne(`${DOSES}/${order.orderId}/doses`);
  }

  const bodyOf = (req: TestRequest) => req.request.body as Record<string, unknown>;

  const recorded = (outcome: 'GIVEN' | 'HELD' | 'REFUSED' = 'GIVEN', reason: string | null = null): DoseRecorded => ({
    replayed: false,
    dose:
      outcome === 'GIVEN'
        ? GIVEN_DOSE
        : {
            ...GIVEN_DOSE,
            outcome,
            secondCheckByName: null,
            reason: reason ? { key: reason, label: reason === 'PATIENT_REFUSED' ? 'Patient refused' : 'Nil by mouth' } : null,
          },
    mar: marOf({ doseCount: 0 }),
  });

  const fail = (req: TestRequest, slug: string, status: number, detail: string, fields?: { field: string; message: string }[], extra: Record<string, unknown> = {}) =>
    req.flush(problem(slug, status, detail, fields, extra), { status, statusText: 'x' });

  /** A 400 validation answer with the fields the server names. */
  const fail400 = (req: TestRequest, detail: string, fields: { field: string; message: string }[]) =>
    fail(req, 'validation', 400, detail, fields);

  afterEach(() => http.verify());

  describe('layout (mockup sfGive)', () => {
    it('has the title, the subtitle and the identity bar', async () => {
      await open();
      expect(q('h2')!.textContent).toBe('Give · Tab. Paracetamol 650 mg');
      expect(q('h2')!.id).toBe('sf-gh');
      expect(squash(q('[data-testid=give-subtitle]'))).toBe('8 AM dose · Oral · Twice daily');
      expect(squash(q('[data-testid=identity-name]'))).toBe('Ramesh Kale');
      expect(squash(q('[data-testid=identity-line]'))).toBe('PT-00042 · 64 · Male · bed G-03');
    });

    it('adds the hours late to the subtitle of a late slot', async () => {
      await open({ slot: slotOf({ state: 'LATE', lateHours: 1 }) });
      expect(squash(q('[data-testid=give-subtitle]'))).toBe('8 AM dose · Oral · Twice daily · 1 h late');
    });

    it('step 1: the box has the accessible name "Wristband MRN", the placeholder and no autocomplete', async () => {
      await open();
      expect(squash(q('[data-testid=give-step-one] > b'))).toBe('1 · Confirm the patient with two identifiers');
      expect(box().getAttribute('aria-label')).toBe('Wristband MRN');
      expect(box().placeholder).toBe('Scan the wristband or type the MRN');
      expect(box().autocomplete).toBe('off');
      expect(squash(q('label.sfchk'))).toBe('The patient said their name and date of birth, or a relative confirmed them');
      expect(said().type).toBe('checkbox');
      expect(said().checked).toBe(false);
    });

    it('has NO "Scan wristband" button (OQ-32-1, DEC-018)', async () => {
      await open();
      expect(Array.from(el().querySelectorAll('button')).some((b) => /scan/i.test(b.textContent ?? ''))).toBe(false);
      expect(q('[data-act=sfscan]')).toBeNull();
    });

    it('the line under the box is a polite live region and starts empty', async () => {
      await open();
      const line = q('#sf-idok')!;
      expect(line.getAttribute('aria-live')).toBe('polite');
      expect(squash(line)).toBe('');
      expect(box().getAttribute('aria-describedby')).toBe('sf-idok');
    });

    it('without high-alert: no second nurse step, the last step is numbered 2', async () => {
      await open();
      expect(q('[data-testid=give-step-second]')).toBeNull();
      expect(squash(q('[data-testid=give-step-last] > b'))).toBe('2 · If you are not giving it');
    });

    it('reason select: "Choose a reason" first, then the five reasons in order, none chosen', async () => {
      await open();
      expect(squash(q('label[for=sf-why]'))).toBe('Reason');
      expect(Array.from(why().options).map((o) => o.textContent)).toEqual([
        'Choose a reason',
        'Patient refused',
        "Held on doctor's advice",
        'Nil by mouth',
        'Medicine not available',
        'Patient away from the ward',
      ]);
      expect(why().value).toBe('');
    });

    it('high-alert: step 2 with the class in lower case, the small text, "Checked by" and a masked "Their PIN"', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      expect(squash(q('[data-testid=give-step-second] > b'))).toBe('2 · Second nurse check · high-alert anticoagulant');
      expect(squash(q('[data-testid=give-step-second] > small'))).toBe(
        'A second nurse checks the drug, dose, patient and route, then enters their PIN.',
      );
      expect(squash(q('label[for=sf-wit]'))).toBe('Checked by');
      expect(Array.from(nurse().options).map((o) => o.textContent)).toEqual([
        'Choose a nurse',
        'Sr. Rupali More',
        'Br. Nikhil Jadhav',
      ]);
      expect(nurse().value).toBe('');
      expect(squash(q('label[for=sf-pin]'))).toBe('Their PIN');
      expect(pin().type).toBe('password');
      expect(pin().getAttribute('inputmode')).toBe('numeric');
      expect(pin().maxLength).toBe(4);
      expect(pin().placeholder).toBe('4 digits');
      expect(pin().value).toBe('');
      expect(pin().autocomplete).toBe('one-time-code');
      expect(squash(q('[data-testid=give-step-last] > b'))).toBe('3 · If you are not giving it');
    });

    it('the footer is "Record not given" then "Given" with a check icon', async () => {
      await open();
      expect(Array.from(el().querySelectorAll('.sheet-f button')).map((b) => squash(b))).toEqual(['Record not given', 'Given']);
      expect(givenButton().querySelector('app-icon')).toBeTruthy();
    });

    it('puts the focus in the box once the dialog has opened (autoFocus is off)', async () => {
      await open();
      expect(document.activeElement).toBe(box());
    });

    it('Enter in the box does not submit (a scanner ends its scan with Enter)', async () => {
      await open();
      await fillIdentity();
      const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
      box().dispatchEvent(event);
      await settle();
      expect(event.defaultPrevented).toBe(true);
      http.expectNone(`${DOSES}/o1/doses`);
      expect(givenButton().disabled).toBe(false);
    });

    it('the sheet is not a form, so Enter in the PIN field does not submit either', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      expect(q('form')).toBeNull();
      pin().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
      http.expectNone(`${DOSES}/o2/doses`);
    });
  });

  describe('Given', () => {
    it('without high-alert sends the box and the statement, no second nurse and no PIN', async () => {
      await open();
      await fillIdentity();
      const req = await clickGiven();
      expect(req.request.method).toBe('POST');
      expect(bodyOf(req)).toEqual({
        clientKey: expect.stringMatching(UUID),
        slotAt: AT_8AM,
        outcome: 'GIVEN',
        wristband: 'PT-00042',
        identityConfirmed: true,
      });
      req.flush(recorded(), { status: 201, statusText: 'Created' });
    });

    it('sends the box exactly as typed (the server normalises) and the ticks as they are', async () => {
      await open();
      await typeBox('  pt-00042\t');
      const req = await clickGiven();
      expect(bodyOf(req)).toMatchObject({ wristband: '  pt-00042\t', identityConfirmed: false });
      req.flush(recorded(), { status: 201, statusText: 'Created' });
    });

    it('high-alert adds the chosen nurse and the PIN', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillHighAlert();
      const req = await clickGiven();
      expect(bodyOf(req)).toEqual({
        clientKey: expect.stringMatching(UUID),
        slotAt: AT_8AM,
        outcome: 'GIVEN',
        wristband: 'PT-00042',
        identityConfirmed: true,
        secondNurseId: 'm-rupali',
        secondNursePin: '4821',
      });
      req.flush(recorded(), { status: 201, statusText: 'Created' });
    });

    it('high-alert with nothing chosen sends nurse null and the empty PIN: the server answers', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillIdentity();
      const req = await clickGiven();
      expect(bodyOf(req)).toMatchObject({ secondNurseId: null, secondNursePin: '' });
      req.flush(recorded(), { status: 201, statusText: 'Created' });
    });

    it('the PIN field keeps digits only', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await typePin('48a2-1');
      expect(pin().value).toBe('4821');
    });

    it('a 201 shows the toast, closes the sheet with the MAR now and clears the PIN', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillHighAlert();
      const answer = recorded();
      (await clickGiven()).flush(answer, { status: 201, statusText: 'Created' });
      await settle();
      expect(snack).toHaveBeenCalledWith('Inj. Heparin 5,000 IU recorded as given to Ramesh Kale', 'Close', expect.anything());
      expect(ref.close).toHaveBeenCalledWith({ mar: answer.mar });
      expect(pin().value).toBe('');
    });

    it('a 200 replay counts as recorded: same toast, same close', async () => {
      await open();
      await fillIdentity();
      const answer = { ...recorded(), replayed: true };
      (await clickGiven()).flush(answer, { status: 200, statusText: 'OK' });
      expect(snack).toHaveBeenCalledWith('Tab. Paracetamol 650 mg recorded as given to Ramesh Kale', 'Close', expect.anything());
      expect(ref.close).toHaveBeenCalledWith({ mar: answer.mar });
    });

    it('is not shown as given before the server answers: the sheet stays open and nothing closes', async () => {
      await open();
      await fillIdentity();
      const req = await clickGiven();
      expect(ref.close).not.toHaveBeenCalled();
      expect(snack).not.toHaveBeenCalled();
      req.flush(recorded(), { status: 201, statusText: 'Created' });
    });
  });

  describe('Record not given', () => {
    it('sends the reason only: no wristband, statement, nurse or PIN', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillHighAlert();
      await choose(why(), 'NIL_BY_MOUTH');
      const req = await clickNotGiven();
      expect(bodyOf(req)).toEqual({
        clientKey: expect.stringMatching(UUID),
        slotAt: AT_8AM,
        outcome: 'NOT_GIVEN',
        reasonKey: 'NIL_BY_MOUTH',
      });
      req.flush(recorded('HELD', 'NIL_BY_MOUTH'), { status: 201, statusText: 'Created' });
    });

    it('toast "Recorded: <medicine> not given · <reason>. The doctor sees this on the chart." and closes', async () => {
      await open();
      await choose(why(), 'NIL_BY_MOUTH');
      const answer = recorded('HELD', 'NIL_BY_MOUTH');
      (await clickNotGiven()).flush(answer, { status: 201, statusText: 'Created' });
      expect(snack).toHaveBeenCalledWith(
        'Recorded: Tab. Paracetamol 650 mg not given · Nil by mouth. The doctor sees this on the chart.',
        'Close',
        expect.anything(),
      );
      expect(ref.close).toHaveBeenCalledWith({ mar: answer.mar });
    });

    it('without a reason sends null and shows the server message under Reason, with the focus there', async () => {
      await open();
      const req = await clickNotGiven();
      expect(bodyOf(req)['reasonKey']).toBeNull();
      fail400(req, 'Choose why the dose is not given.', [{ field: 'reasonKey', message: 'Choose why the dose is not given.' }]);
      await settle();
      await tick();
      expect(squash(q('#sf-why-err'))).toBe('Choose why the dose is not given.');
      expect(q('#sf-why-err')!.getAttribute('role')).toBe('alert');
      expect(why().getAttribute('aria-invalid')).toBe('true');
      expect(why().getAttribute('aria-describedby')).toBe('sf-why-err');
      expect(document.activeElement).toBe(why());
      expect(snack).toHaveBeenCalledWith('Choose why the dose is not given.', 'Close', expect.anything());
      expect(ref.close).not.toHaveBeenCalled();
    });

    it('choosing a reason removes its error', async () => {
      await open();
      fail400(await clickNotGiven(), 'Choose why the dose is not given.', [{ field: 'reasonKey', message: 'Choose why the dose is not given.' }]);
      await settle();
      await choose(why(), 'PATIENT_REFUSED');
      expect(q('#sf-why-err')).toBeNull();
    });
  });

  describe('double tap and busy state (AC 13)', () => {
    it('a second tap on Given while the request runs sends nothing more', async () => {
      await open();
      await fillIdentity();
      givenButton().click();
      givenButton().click();
      await settle();
      const req = http.expectOne(`${DOSES}/o1/doses`);
      req.flush(recorded(), { status: 201, statusText: 'Created' });
    });

    it('every button is off while the request runs, the dialog can not be closed, and it all comes back after a failure', async () => {
      await open();
      await fillIdentity();
      const req = await clickGiven();
      expect(givenButton().disabled).toBe(true);
      expect(notGivenButton().disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-act=gv-x]')!.disabled).toBe(true);
      expect(givenButton().getAttribute('aria-busy')).toBe('true');
      expect(ref.disableClose).toBe(true);
      q<HTMLButtonElement>('[data-act=gv-x]')!.click();
      expect(ref.close).not.toHaveBeenCalled();
      notGivenButton().click();
      http.expectNone(`${DOSES}/o1/doses`);
      req.error(new ProgressEvent('error'));
      await settle();
      expect(givenButton().disabled).toBe(false);
      expect(notGivenButton().disabled).toBe(false);
      expect(q<HTMLButtonElement>('[data-act=gv-x]')!.disabled).toBe(false);
      expect(ref.disableClose).toBe(false);
    });

    it('the close button closes the sheet with no result when nothing runs', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=gv-x]')!.click();
      expect(ref.close).toHaveBeenCalledWith();
    });
  });

  describe('wristband mismatch (409 wristband-mismatch)', () => {
    async function mismatch(): Promise<void> {
      await open();
      await typeBox('PT-99999');
      await tickSaid();
      fail(
        await clickGiven(),
        'wristband-mismatch',
        409,
        'Wristband does not match this patient. Stop. Check the patient before giving anything.',
      );
      await settle();
      await tick();
    }

    it('shows the toast and the line under the box, built from the MAR (the detail has no name)', async () => {
      await mismatch();
      expect(snack).toHaveBeenCalledWith(
        'Wristband does not match Ramesh Kale. Stop. Check the patient before giving anything.',
        'Close',
        expect.anything(),
      );
      expect(squash(q('#sf-idok'))).toBe("Wrong patient. This band is not Ramesh Kale's.");
      expect(q('#sf-idok app-icon')).toBeTruthy();
      expect(ref.close).not.toHaveBeenCalled();
    });

    it('keeps the sheet open, the focus in the box and what was typed', async () => {
      await mismatch();
      expect(document.activeElement).toBe(box());
      expect(box().value).toBe('PT-99999');
      expect(said().checked).toBe(true);
    });

    it('selects the text in the box (does not clear it), so the next scan replaces it', async () => {
      await mismatch();
      expect(document.activeElement).toBe(box());
      expect(box().value).toBe('PT-99999');
      expect(box().selectionStart).toBe(0);
      expect(box().selectionEnd).toBe('PT-99999'.length);
    });

    it('does not select the text after other refusals (only a mismatch does)', async () => {
      await open();
      await typeBox('PT-00042');
      box().setSelectionRange(8, 8);
      fail400(await clickGiven(), 'Confirm the second identifier: name and date of birth.', [
        { field: 'identityConfirmed', message: 'Confirm the second identifier: name and date of birth.' },
      ]);
      await settle();
      await tick();
      expect(box().selectionStart).toBe(8);
      expect(box().selectionEnd).toBe(8);
    });

    it('editing the box removes the line', async () => {
      await mismatch();
      await typeBox('PT-0004');
      expect(squash(q('#sf-idok'))).toBe('');
    });

    it('never shows a PIN state or reads the PIN status', async () => {
      await mismatch();
      expect(el().textContent).not.toContain('Set my PIN');
      http.expectNone('/api/v1/platform/me/pin');
    });
  });

  describe('the box and the toast (review SAH-32 R11, I6)', () => {
    it('the wristband box takes at most 200 characters (the server treats more as a mismatch)', async () => {
      await open();
      expect(box().getAttribute('maxlength')).toBe('200');
    });

    it('the toast is shown but not announced: the field alert or the live line already says it', async () => {
      await open();
      fail400(await clickGiven(), 'Scan the wristband first.', [{ field: 'wristband', message: 'Scan the wristband first.' }]);
      await settle();
      await tick();
      expect(snack).toHaveBeenCalledWith(
        'Scan the wristband first.',
        'Close',
        expect.objectContaining({ duration: 8000, politeness: 'off' }),
      );
      expect(q('#sf-id-err')!.getAttribute('role')).toBe('alert');
      expect(q('#sf-idok')!.getAttribute('aria-live')).toBe('polite');
    });
  });

  describe('400 messages, one at a time, under their control', () => {
    it('empty box: "Scan the wristband first." under the box, focus in the box', async () => {
      await open();
      fail400(await clickGiven(), 'Scan the wristband first.', [{ field: 'wristband', message: 'Scan the wristband first.' }]);
      await settle();
      await tick();
      expect(squash(q('#sf-id-err'))).toBe('Scan the wristband first.');
      expect(box().getAttribute('aria-invalid')).toBe('true');
      expect(document.activeElement).toBe(box());
      expect(snack).toHaveBeenCalledWith('Scan the wristband first.', 'Close', expect.anything());
    });

    it('statement not ticked: under the checkbox, focus on it', async () => {
      await open();
      await typeBox('PT-00042');
      fail400(await clickGiven(), 'Confirm the second identifier: name and date of birth.', [
        { field: 'identityConfirmed', message: 'Confirm the second identifier: name and date of birth.' },
      ]);
      await settle();
      await tick();
      expect(squash(q('#sf-said-err'))).toBe('Confirm the second identifier: name and date of birth.');
      expect(document.activeElement).toBe(said());
      await tickSaid();
      expect(q('#sf-said-err')).toBeNull();
    });

    it('high-alert without nurse or PIN: the message once, under Their PIN', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillIdentity();
      fail400(await clickGiven(), HIGH_ALERT_MESSAGE, [
        { field: 'secondNurseId', message: HIGH_ALERT_MESSAGE },
        { field: 'secondNursePin', message: HIGH_ALERT_MESSAGE },
      ]);
      await settle();
      await tick();
      expect(squash(q('#sf-pin-err'))).toBe(HIGH_ALERT_MESSAGE);
      expect(q('#sf-wit-err')).toBeNull();
      expect(document.activeElement).toBe(pin());
    });

    it('same nurse: under Checked by, focus on the list', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillHighAlert();
      fail400(await clickGiven(), 'The second check must be a different nurse.', [
        { field: 'secondNurseId', message: 'The second check must be a different nurse.' },
      ]);
      await settle();
      await tick();
      expect(squash(q('#sf-wit-err'))).toBe('The second check must be a different nurse.');
      expect(document.activeElement).toBe(nurse());
      expect(pin().value).toBe('');
      await choose(nurse(), 'm-nikhil');
      expect(q('#sf-wit-err')).toBeNull();
    });

    it('typing in the PIN removes the PIN error', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillHighAlert();
      fail(await clickGiven(), 'wrong-pin', 403, 'That PIN is not right. 2 tries left.');
      await settle();
      expect(q('#sf-pin-err')).not.toBeNull();
      await typePin('1');
      expect(q('#sf-pin-err')).toBeNull();
    });
  });

  describe('the second nurse PIN: errors under "Their PIN", PIN cleared', () => {
    async function givenWithPin(): Promise<TestRequest> {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillHighAlert();
      return clickGiven();
    }

    it('wrong PIN: the server text under the field, the PIN cleared, the focus in the PIN field', async () => {
      const req = await givenWithPin();
      fail(req, 'wrong-pin', 403, 'That PIN is not right. 2 tries left.');
      await settle();
      await tick();
      expect(squash(q('#sf-pin-err'))).toBe('That PIN is not right. 2 tries left.');
      expect(q('#sf-pin-err')!.getAttribute('role')).toBe('alert');
      expect(pin().getAttribute('aria-describedby')).toBe('sf-pin-err');
      expect(pin().getAttribute('aria-invalid')).toBe('true');
      expect(pin().value).toBe('');
      expect(document.activeElement).toBe(pin());
      expect(nurse().value).toBe('m-rupali');
      expect(box().value).toBe('PT-00042');
      expect(ref.close).not.toHaveBeenCalled();
      expect(snack).toHaveBeenCalledWith('That PIN is not right. 2 tries left.', 'Close', expect.anything());
    });

    it('locked: the text with the time under the field, the PIN cleared, the focus on the nurse list', async () => {
      const req = await givenWithPin();
      fail(req, 'pin-locked', 403, "The second nurse's PIN is locked after 5 wrong tries. Choose another nurse, or try again at 11:20 AM.", undefined, {
        retryAfterSeconds: 900,
      });
      await settle();
      await tick();
      expect(squash(q('#sf-pin-err'))).toContain('locked after 5 wrong tries');
      expect(pin().value).toBe('');
      expect(document.activeElement).toBe(nurse());
    });

    it('pin-not-set is the second nurse state: text under Their PIN, never "Set my PIN", no PIN status read', async () => {
      const req = await givenWithPin();
      fail(req, 'pin-not-set', 409, 'The second nurse has not set a PIN yet. Choose another nurse.');
      await settle();
      await tick();
      expect(squash(q('#sf-pin-err'))).toBe('The second nurse has not set a PIN yet. Choose another nurse.');
      expect(el().textContent).not.toContain('Set my PIN');
      expect(Array.from(el().querySelectorAll('button')).some((b) => /set .*pin/i.test(b.textContent ?? ''))).toBe(false);
      http.expectNone('/api/v1/platform/me/pin');
      expect(ref.close).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(nurse());
    });

    it('rate limit and PIN unavailable are shown under Their PIN as well', async () => {
      const req = await givenWithPin();
      fail(req, 'rate-limit', 429, 'Too many PIN tries in a short time. Try again in a few minutes.', undefined, { retryAfterSeconds: 120 });
      await settle();
      expect(squash(q('#sf-pin-err'))).toBe('Too many PIN tries in a short time. Try again in a few minutes.');
      await typePin('4821');
      const again = await clickGiven();
      fail(again, 'pin-unavailable', 503, 'PIN checks are not available right now. Try again later.');
      await settle();
      expect(squash(q('#sf-pin-err'))).toBe('PIN checks are not available right now. Try again later.');
      expect(pin().value).toBe('');
    });

    it('clears the PIN after every answer, also after a mismatch and a lost answer', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillHighAlert();
      fail(await clickGiven(), 'wristband-mismatch', 409, 'Wristband does not match this patient.');
      await settle();
      expect(pin().value).toBe('');
      await typePin('4821');
      (await clickGiven()).error(new ProgressEvent('error'));
      await settle();
      expect(pin().value).toBe('');
    });
  });

  describe('409s after which the sheet closes and the MAR is read again', () => {
    for (const [slug, detail] of [
      ['dose-recorded', 'This dose is already recorded.'],
      ['order-state', 'This medicine was stopped. Its doses can no longer be recorded.'],
      ['order-not-verified', 'This order is not checked by the pharmacist yet.'],
      ['dose-not-due', 'This dose is not due yet. It can be given from 1 hour before its time.'],
      ['dose-before-start', 'This dose time is before the pharmacist checked the order.'],
      ['not-admitted', 'This patient is not admitted any more. Doses cannot be recorded.'],
      ['ipd-readonly', 'IPD is read-only. Nothing can be changed.'],
    ] as const) {
      it(`${slug}: toast with the detail, close with reload`, async () => {
        await open();
        await fillIdentity();
        fail(await clickGiven(), slug, 409, detail);
        await settle();
        expect(snack).toHaveBeenCalledWith(detail, 'Close', expect.anything());
        expect(ref.close).toHaveBeenCalledWith({ reload: true });
      });
    }

    it('also after Record not given', async () => {
      await open();
      await choose(why(), 'NIL_BY_MOUTH');
      fail(await clickNotGiven(), 'dose-recorded', 409, 'This dose is already recorded.');
      await settle();
      expect(ref.close).toHaveBeenCalledWith({ reload: true });
    });

    it('400 on slotAt (a screen left open past midnight) also closes and reloads', async () => {
      await open();
      await fillIdentity();
      const text = 'This is not a dose time of this medicine today. Reload the page and try again.';
      fail(await clickGiven(), 'validation', 400, text, [{ field: 'slotAt', message: text }]);
      await settle();
      expect(ref.close).toHaveBeenCalledWith({ reload: true });
    });
  });

  describe('other failures keep the sheet open', () => {
    it('403 forbidden: the server text in the notice, the entries kept', async () => {
      await open();
      await fillIdentity();
      fail(await clickGiven(), 'forbidden', 403, 'Only nurses and the clinic owner can give medicines.');
      await settle();
      expect(squash(q('[data-testid=give-failure]'))).toBe('Only nurses and the clinic owner can give medicines.');
      expect(q('[data-testid=give-failure]')!.getAttribute('role')).toBe('alert');
      expect(box().value).toBe('PT-00042');
      expect(ref.close).not.toHaveBeenCalled();
    });

    it('a server error or lost connection: fixed text, everything entered kept, PIN cleared, buttons back', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillHighAlert();
      fail(await clickGiven(), 'internal', 500, 'boom');
      await settle();
      const message = 'We could not record the dose. What you entered is kept, except the PIN. Try again.';
      expect(squash(q('[data-testid=give-failure]'))).toBe(message);
      expect(snack).toHaveBeenCalledWith(message, 'Close', expect.anything());
      expect(box().value).toBe('PT-00042');
      expect(said().checked).toBe(true);
      expect(nurse().value).toBe('m-rupali');
      expect(pin().value).toBe('');
      expect(givenButton().disabled).toBe(false);
      expect(ref.close).not.toHaveBeenCalled();
    });

    it('the notice goes when the next request starts', async () => {
      await open();
      await fillIdentity();
      (await clickGiven()).error(new ProgressEvent('error'));
      await settle();
      expect(q('[data-testid=give-failure]')).not.toBeNull();
      givenButton().click();
      await settle();
      expect(q('[data-testid=give-failure]')).toBeNull();
      http.expectOne(`${DOSES}/o1/doses`).flush(recorded(), { status: 201, statusText: 'Created' });
    });
  });

  describe('clientKey (ADR-011 D8)', () => {
    const keyOf = (req: TestRequest) => bodyOf(req)['clientKey'] as string;

    it('keeps the key for a retry of the same request after a lost answer', async () => {
      await open();
      await fillIdentity();
      const first = await clickGiven();
      const key = keyOf(first);
      first.error(new ProgressEvent('error'));
      await settle();
      const retry = await clickGiven();
      expect(keyOf(retry)).toBe(key);
      retry.flush({ ...recorded(), replayed: true }, { status: 200, statusText: 'OK' });
    });

    it('keeps the key after a refusal that wrote nothing (a wrong PIN, then a new try with the same nurse)', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillHighAlert();
      const first = await clickGiven();
      const key = keyOf(first);
      fail(first, 'wrong-pin', 403, 'That PIN is not right. 2 tries left.');
      await settle();
      await typePin('1234');
      const retry = await clickGiven();
      expect(keyOf(retry)).toBe(key);
      expect(bodyOf(retry)['secondNursePin']).toBe('1234');
      retry.flush(recorded(), { status: 201, statusText: 'Created' });
    });

    it('makes a new key when the second nurse is another one (the server compares it)', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillHighAlert();
      const first = await clickGiven();
      const key = keyOf(first);
      first.error(new ProgressEvent('error'));
      await settle();
      await choose(nurse(), 'm-nikhil');
      await typePin('1234');
      const second = await clickGiven();
      expect(keyOf(second)).not.toBe(key);
      expect(keyOf(second)).toMatch(UUID);
      second.flush(recorded(), { status: 201, statusText: 'Created' });
    });

    it('makes a new key when Given is followed by Record not given, and another when the reason changes', async () => {
      await open();
      await fillIdentity();
      const first = await clickGiven();
      const key = keyOf(first);
      first.error(new ProgressEvent('error'));
      await settle();
      await choose(why(), 'NIL_BY_MOUTH');
      const second = await clickNotGiven();
      const key2 = keyOf(second);
      expect(key2).not.toBe(key);
      second.error(new ProgressEvent('error'));
      await settle();
      const same = await clickNotGiven();
      expect(keyOf(same)).toBe(key2);
      same.error(new ProgressEvent('error'));
      await settle();
      await choose(why(), 'PATIENT_REFUSED');
      const third = await clickNotGiven();
      expect(keyOf(third)).not.toBe(key2);
      third.flush(recorded('REFUSED', 'PATIENT_REFUSED'), { status: 201, statusText: 'Created' });
    });

    it('a changed box does not change the key (the server does not compare it)', async () => {
      await open();
      await typeBox('PT-0004');
      await tickSaid();
      const first = await clickGiven();
      const key = keyOf(first);
      first.error(new ProgressEvent('error'));
      await settle();
      await typeBox('PT-00042');
      const retry = await clickGiven();
      expect(keyOf(retry)).toBe(key);
      retry.flush(recorded(), { status: 201, statusText: 'Created' });
    });

    it('409 client-key-reused: the message, a new key, the sheet stays open with the entries', async () => {
      await open();
      await fillIdentity();
      const first = await clickGiven();
      const key = keyOf(first);
      fail(first, 'client-key-reused', 409, 'This dose was already recorded with different values. Reload the page.');
      await settle();
      expect(squash(q('[data-testid=give-failure]'))).toBe('This dose was already recorded with different values. Reload the page.');
      expect(ref.close).not.toHaveBeenCalled();
      expect(box().value).toBe('PT-00042');
      const retry = await clickGiven();
      expect(keyOf(retry)).not.toBe(key);
      retry.flush(recorded(), { status: 201, statusText: 'Created' });
    });

    it('a new sheet makes a new key', async () => {
      await open();
      await fillIdentity();
      const first = await clickGiven();
      const key = keyOf(first);
      first.flush(recorded(), { status: 201, statusText: 'Created' });
      TestBed.resetTestingModule();
      await open();
      await fillIdentity();
      const second = await clickGiven();
      expect(keyOf(second)).not.toBe(key);
      second.flush(recorded(), { status: 201, statusText: 'Created' });
    });
  });

  describe('privacy', () => {
    it('does not write the box or the PIN to the console', async () => {
      const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((m) => vi.spyOn(console, m).mockImplementation(() => undefined));
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillHighAlert();
      fail(await clickGiven(), 'wrong-pin', 403, 'That PIN is not right. 2 tries left.');
      await settle();
      for (const spy of spies) {
        expect(spy).not.toHaveBeenCalled();
        spy.mockRestore();
      }
    });

    it('does not use storage', async () => {
      const set = vi.spyOn(Storage.prototype, 'setItem');
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillHighAlert();
      (await clickGiven()).flush(recorded(), { status: 201, statusText: 'Created' });
      expect(set).not.toHaveBeenCalled();
      set.mockRestore();
    });
  });
});
