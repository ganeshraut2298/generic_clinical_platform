import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, Subject } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { problem } from '../emr/emr.fixtures';
import { GiveSheetComponent, GiveSheetData } from './give-sheet.component';
import { ORDER_HEP, ORDER_PCM, ORDER_PRN, doseOf, marOf, orderOf, slotOf } from './mar.fixtures';
import { DoseRecorded, Mar, MarOrder, MarSlot } from './mar.model';
import { UndoToast } from './undo-toast.component';

const DOSES = '/api/v1/ipd/medicine-orders';

describe('GiveSheetComponent, SAH-76 nursing integration', () => {
  let fixture: ComponentFixture<GiveSheetComponent>;
  let http: HttpTestingController;
  let ref: { close: ReturnType<typeof vi.fn>; afterOpened: () => Observable<void>; disableClose?: boolean };
  let snack: ReturnType<typeof vi.fn>;
  let showGiven: ReturnType<typeof vi.fn>;
  let order: MarOrder;

  async function open(over: { order?: MarOrder; slot?: MarSlot | null; mar?: Mar } = {}): Promise<void> {
    const opened = new Subject<void>();
    ref = { close: vi.fn(), afterOpened: () => opened.asObservable() };
    snack = vi.fn();
    showGiven = vi.fn();
    order = over.order ?? ORDER_PCM;
    const mar = over.mar ?? marOf();
    const slot = over.slot === undefined ? order.slots[0] : over.slot;
    const data: GiveSheetData = { mar, order, slot };
    await TestBed.configureTestingModule({
      imports: [GiveSheetComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
        { provide: MatSnackBar, useValue: { open: snack } },
        { provide: UndoToast, useValue: { showGiven } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GiveSheetComponent);
    fixture.detectChanges();
    opened.next();
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    await new Promise<void>((r) => setTimeout(r, 0));
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el().querySelectorAll<HTMLElement>(sel));
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();

  async function type(selector: string, value: string): Promise<void> {
    const input = q<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  }
  async function fillScan(): Promise<void> {
    await type('#sf-id', 'PT-00042');
    await type('#sf-code', '8901234567890');
    const said = q<HTMLInputElement>('#sf-said')!;
    said.checked = true;
    said.dispatchEvent(new Event('change'));
    await settle();
  }
  async function clickGiven(): Promise<TestRequest> {
    q<HTMLButtonElement>('[data-act=sfgiveok]')!.click();
    await settle();
    return http.expectOne(`${DOSES}/${order.orderId}/doses`);
  }
  const fail = (req: TestRequest, slug: string, status: number, detail: string, fields?: { field: string; message: string }[]) =>
    req.flush(problem(slug, status, detail, fields), { status, statusText: 'x' });
  const body = (req: TestRequest) => req.request.body as Record<string, unknown>;
  const recorded = (dose = doseOf()): DoseRecorded => ({ replayed: false, dose, mar: marOf({ doseCount: 0 }) });

  afterEach(() => http.verify());

  describe('medicine code step', () => {
    it('has step 2 with the box named "Medicine code"', async () => {
      await open();
      expect(squash(q('[data-testid=give-step-code] > b'))).toBe('2 · Scan or type the medicine code');
      expect(q('#sf-code')!.getAttribute('aria-label')).toBe('Medicine code');
      expect(q('[data-testid=no-code-line]')).toBeNull();
    });

    it('says so when no code is loaded for the medicine', async () => {
      await open({ order: orderOf({ hasMedicineCode: false }) });
      expect(squash(q('[data-testid=no-code-line]'))).toBe(
        'No code is loaded for this medicine. Use Manual override: Medicine has no barcode.',
      );
    });

    it('Enter in the code box does not submit', async () => {
      await open();
      const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
      q('#sf-code')!.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
      http.expectNone(`${DOSES}/o1/doses`);
    });

    it('sends the code and the order version with Given', async () => {
      await open({ slot: slotOf({ orderVersion: 3 }) });
      await fillScan();
      const req = await clickGiven();
      expect(body(req)).toMatchObject({ medicineCode: '8901234567890', orderVersion: 3, wristband: 'PT-00042', override: null });
      req.flush(recorded(), { status: 201, statusText: 'Created' });
    });

    it('a 400 on the code shows the server text under the box', async () => {
      await open();
      await type('#sf-id', 'PT-00042');
      fail(await clickGiven(), 'validation', 400, 'Scan or type the medicine code.', [
        { field: 'medicineCode', message: 'Scan or type the medicine code.' },
      ]);
      await settle();
      expect(squash(q('#sf-code-err'))).toBe('Scan or type the medicine code.');
      expect(q('#sf-code')!.getAttribute('aria-invalid')).toBe('true');
    });
  });

  describe('Checked by the system', () => {
    it('appears once both boxes hold something, with the lines of the story', async () => {
      await open({ order: orderOf({ lastGivenAt: '2026-11-06T02:34:00Z' }) });
      expect(q('[data-testid=give-checks]')).toBeNull();
      await type('#sf-id', 'PT-00042');
      expect(q('[data-testid=give-checks]')).toBeNull();
      await type('#sf-code', '8901');
      const lines = qa('[data-testid=give-check]').map((l) => squash(l));
      expect(squash(q('[data-testid=give-checks] > b'))).toBe('Checked by the system');
      expect(lines).toEqual([
        'Right patient · checked when you press Given',
        'Right medicine and dose · checked when you press Given',
        'Right route · Oral',
        'Right time',
        'Allergy checked · no clash with Penicillin (rash)',
        'Last given 8:04 AM',
      ]);
    });

    it('says how late a late dose is', async () => {
      await open({ slot: slotOf({ state: 'LATE', lateHours: 1, lateMinutes: 40 }) });
      await type('#sf-id', 'PT-00042');
      await type('#sf-code', '8901');
      expect(qa('[data-testid=give-check]').map((l) => squash(l))).toContain('Late: 40 min after schedule');
    });
  });

  describe('wrong medicine (409 medicine-mismatch)', () => {
    async function mismatch(): Promise<void> {
      await open();
      await fillScan();
      fail(
        await clickGiven(),
        'medicine-mismatch',
        409,
        'Wrong medicine. This is Inj. Heparin 5,000 IU, not Tab. Paracetamol 650 mg.',
      );
      await settle();
    }

    it('shows the STOP line under the box and keeps the sheet open', async () => {
      await mismatch();
      expect(squash(q('[data-testid=code-mismatch]'))).toBe(
        'Wrong medicine. This is Inj. Heparin 5,000 IU, not Tab. Paracetamol 650 mg.',
      );
      expect(snack).toHaveBeenCalled();
      expect(ref.close).not.toHaveBeenCalled();
    });

    it('Given stays off and the override is not offered until the code is changed (and then only while the code box is empty)', async () => {
      await mismatch();
      expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(true);
      expect(q('[data-act=override-toggle]')).toBeNull();
      await type('#sf-code', '8901234567891');
      expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(false);
      expect(q('[data-act=override-toggle]')).toBeNull();
      await type('#sf-code', '');
      expect(q('[data-act=override-toggle]')).not.toBeNull();
    });
  });

  describe('wrong patient STOP', () => {
    it('turns Given off and hides the override until the wristband is changed', async () => {
      await open();
      await fillScan();
      fail(await clickGiven(), 'wristband-mismatch', 409, 'Wristband does not match this patient.');
      await settle();
      expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(true);
      expect(q('[data-act=override-toggle]')).toBeNull();
      await type('#sf-id', 'PT-00042');
      expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(false);
    });
  });

  describe('manual override', () => {
    it('opens with the line, the three reasons and the note, and closes with Cancel override', async () => {
      await open();
      const toggle = q<HTMLButtonElement>('[data-act=override-toggle]')!;
      expect(squash(toggle)).toBe('Cannot scan the medicine? Manual override');
      toggle.click();
      await settle();
      expect(squash(q('[data-testid=give-override]'))).toContain(
        'Only when the medicine code cannot be scanned. The wristband must still match. The override, your reason and your name go to the pharmacy and quality team.',
      );
      expect(qa('[data-testid=override-reason]').map((b) => squash(b))).toEqual([
        'Medicine has no barcode',
        'Scanner not working',
        'Emergency',
      ]);
      expect(q('#sf-ov-note')!.getAttribute('placeholder')).toBe(
        'What you checked instead, e.g. the strip label read against the chart',
      );
      expect(squash(q('[data-act=override-toggle]'))).toBe('Cancel override');
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      expect(q('#sf-ov-note')).toBeNull();
    });

    it('does not offer WRISTBAND_DAMAGED even when the MAR lists it', async () => {
      const mar = marOf({
        overrideReasons: [{ key: 'WRISTBAND_DAMAGED', label: 'Wristband damaged or missing' }, ...marOf().overrideReasons],
      });
      await open({ mar });
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      expect(qa('[data-testid=override-reason]').map((b) => b.getAttribute('data-key'))).toEqual([
        'NO_BARCODE',
        'SCANNER_DOWN',
        'EMERGENCY',
      ]);
    });

    it('sits under the medicine code box and is offered only while the code box is empty', async () => {
      await open();
      const step = q('[data-testid=give-step-code]')!;
      const override = q('[data-testid=give-override]')!;
      expect(step.compareDocumentPosition(override) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(override.compareDocumentPosition(q('[data-testid=give-step-last]')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      await type('#sf-code', '8901234567890');
      expect(q('[data-act=override-toggle]')).toBeNull();
      await type('#sf-code', '');
      expect(q('[data-act=override-toggle]')).not.toBeNull();
    });

    it('a code typed after the override was filled is sent and the override is not', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      qa('[data-testid=override-reason]')[0].click();
      await type('#sf-ov-note', 'Read the strip label against the chart');
      await fillScan();
      const req = await clickGiven();
      expect(body(req)).toMatchObject({ medicineCode: '8901234567890', wristband: 'PT-00042', override: null });
      req.flush(recorded(), { status: 201, statusText: 'Created' });
    });

    it('sends the reason, the note, the wristband and the statement; only the code box is empty', async () => {
      await open();
      await type('#sf-id', 'PT-00042');
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      qa('[data-testid=override-reason]')[0].click();
      await settle();
      await type('#sf-ov-note', 'Read the strip label against the chart');
      const said = q<HTMLInputElement>('#sf-said')!;
      said.checked = true;
      said.dispatchEvent(new Event('change'));
      await settle();
      const req = await clickGiven();
      expect(body(req)).toMatchObject({
        override: { reasonKey: 'NO_BARCODE', note: 'Read the strip label against the chart' },
        identityConfirmed: true,
        wristband: 'PT-00042',
        medicineCode: '',
      });
      req.flush(recorded(doseOf({ method: 'OVERRIDE' })), { status: 201, statusText: 'Created' });
      await settle();
      expect(showGiven).toHaveBeenCalledWith(expect.objectContaining({ override: true, drugName: 'Tab. Paracetamol 650 mg' }));
    });

    it('shows the panel "Checked by the system" once the wristband, the override reason and a note are in', async () => {
      await open();
      await type('#sf-id', 'PT-00042');
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      qa('[data-testid=override-reason]')[0].click();
      await type('#sf-ov-note', 'ab');
      expect(q('[data-testid=give-checks]')).toBeNull();
      await type('#sf-ov-note', 'Name and DOB read from the case file');
      expect(q('[data-testid=give-checks]')).not.toBeNull();
    });

    it('the panel does not show for an override without the wristband', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      qa('[data-testid=override-reason]')[0].click();
      await type('#sf-ov-note', 'Read the strip label against the chart');
      expect(q('[data-testid=give-checks]')).toBeNull();
    });

    it('400 "Scan the wristband first." shows under the wristband box, also with an override', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      qa('[data-testid=override-reason]')[0].click();
      await type('#sf-ov-note', 'Read the strip label against the chart');
      fail(await clickGiven(), 'validation', 400, 'Scan the wristband first.', [
        { field: 'wristband', message: 'Scan the wristband first.' },
      ]);
      await settle();
      expect(squash(q('[data-testid=wristband-error]'))).toBe('Scan the wristband first.');
      expect(q('#sf-id')!.getAttribute('aria-invalid')).toBe('true');
      expect(ref.close).not.toHaveBeenCalled();
    });

    it('409 override-after-mismatch keeps the sheet open, closes the override and shows the detail under the code box', async () => {
      const detail =
        'A scan for this dose did not match in the last 15 minutes. Check the patient and the medicine, then scan the medicine code.';
      await open();
      await type('#sf-id', 'PT-00042');
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      qa('[data-testid=override-reason]')[1].click();
      await type('#sf-ov-note', 'Read the strip label against the chart');
      fail(await clickGiven(), 'override-after-mismatch', 409, detail);
      await settle();
      expect(squash(q('[data-testid=code-mismatch]'))).toBe(detail);
      expect(q('#sf-codeok')!.getAttribute('aria-live')).toBe('polite');
      expect(q('#sf-ov-note')).toBeNull();
      expect(q('[data-act=override-toggle]')).toBeNull();
      expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(true);
      expect(ref.close).not.toHaveBeenCalled();
      expect(snack).toHaveBeenCalled();
      // The nurse scans the medicine code: the line goes and Given is on again.
      await type('#sf-code', '8901234567890');
      expect(q('[data-testid=code-mismatch]')).toBeNull();
      expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(false);
    });

    it('shows the server messages for a missing reason and a short note', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      fail(await clickGiven(), 'validation', 400, 'Choose why you cannot scan.', [
        { field: 'override.reasonKey', message: 'Choose why you cannot scan.' },
        { field: 'override.note', message: 'Write what you checked instead.' },
      ]);
      await settle();
      expect(squash(q('[data-testid=override-reason-error]'))).toBe('Choose why you cannot scan.');
      expect(squash(q('[data-testid=override-note-error]'))).toBe('Write what you checked instead.');
    });

    it('a high-alert medicine still needs the second nurse and the PIN with an override', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      qa('[data-testid=override-reason]')[2].click();
      await type('#sf-ov-note', 'Emergency, name read from the file');
      q<HTMLButtonElement>('[data-act=sfgiveok]')!.click();
      await settle();
      http.expectNone(`${DOSES}/o2/doses`);
      expect(squash(q('#sf-pin-err'))).toBe('High-alert medicine: a second nurse must check it and enter a 4-digit PIN.');
    });
  });

  describe('PRN', () => {
    it('opens for a line without a slot: indication, interval, no time in the subtitle', async () => {
      await open({ order: ORDER_PRN, slot: null });
      expect(squash(q('[data-testid=give-subtitle]'))).toBe('Oral · As needed (PRN)');
      expect(squash(q('[data-testid=give-prn-indication]'))).toBe('Fever above 38 C');
      expect(squash(q('[data-testid=give-prn]'))).toContain('Minimum interval 4 h');
    });

    it('sends no slot', async () => {
      await open({ order: ORDER_PRN, slot: null });
      await fillScan();
      const req = await clickGiven();
      expect(Object.keys(body(req))).not.toContain('slotAt');
      expect(body(req)['orderVersion']).toBe(1);
      req.flush(recorded(), { status: 201, statusText: 'Created' });
    });

    it('409 prn-too-soon shows the time, turns Given off and reads the MAR again on close', async () => {
      await open({ order: ORDER_PRN, slot: null });
      await fillScan();
      fail(await clickGiven(), 'prn-too-soon', 409, 'Too soon. Next dose allowed at 2:30 PM.');
      await settle();
      expect(squash(q('[data-testid=give-prn-lock]'))).toBe('Too soon. Next dose allowed at 2:30 PM.');
      expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(true);
      expect(ref.close).not.toHaveBeenCalled();
      q<HTMLButtonElement>('[data-act=gv-x]')!.click();
      expect(ref.close).toHaveBeenCalledWith({ reload: true });
    });
  });

  describe('409 answers after Given', () => {
    it('dose-not-due for a PRN line closes the sheet and reads the MAR again', async () => {
      await open({ order: ORDER_PRN, slot: null });
      await fillScan();
      fail(await clickGiven(), 'dose-not-due', 409, 'This dose cannot be given now. Reload the page.');
      await settle();
      expect(ref.close).toHaveBeenCalledWith({ reload: true });
      expect(snack.mock.calls[0][0]).toBe('This dose cannot be given now. Reload the page.');
    });

    it('client-key-reused shows the contract text, keeps the sheet and sends a new key next time', async () => {
      await open();
      await fillScan();
      const first = await clickGiven();
      const firstKey = body(first)['clientKey'];
      fail(first, 'client-key-reused', 409, 'This dose was already recorded with different values. Reload the page.');
      await settle();
      expect(squash(q('[data-testid=give-failure]'))).toBe(
        'This dose was already recorded with different values. Reload the page.',
      );
      expect(ref.close).not.toHaveBeenCalled();
      const second = await clickGiven();
      expect(body(second)['clientKey']).not.toBe(firstKey);
      second.flush(recorded(), { status: 201, statusText: 'Created' });
    });
  });

  describe('STAT before the pharmacist check', () => {
    it('shows the notice', async () => {
      const stat = orderOf({ status: 'PENDING', slots: [slotOf({ beforeVerification: true })] });
      await open({ order: stat });
      expect(squash(q('[data-testid=give-stat-notice]'))).toBe(
        'STAT: this dose can be given before the pharmacist check. The pharmacist reviews it after.',
      );
    });

    it('shows no notice for a normal dose', async () => {
      await open();
      expect(q('[data-testid=give-stat-notice]')).toBeNull();
    });
  });

  describe('order changed or on hold', () => {
    it('409 order-changed closes the sheet and asks for a reload', async () => {
      await open();
      await fillScan();
      fail(await clickGiven(), 'order-changed', 409, 'This order was changed. Reload and check the new order.');
      await settle();
      expect(snack).toHaveBeenCalledWith('This order was changed. Reload and check the new order.', 'Close', expect.anything());
      expect(ref.close).toHaveBeenCalledWith({ reload: true });
    });

    it('409 order-on-hold closes the sheet and asks for a reload', async () => {
      await open();
      await fillScan();
      fail(await clickGiven(), 'order-on-hold', 409, 'This order is on hold. Do not give it.');
      await settle();
      expect(ref.close).toHaveBeenCalledWith({ reload: true });
    });
  });

  describe('Record not given: Vomited and Other', () => {
    async function pick(value: string): Promise<void> {
      const select = q<HTMLSelectElement>('#sf-why')!;
      select.value = value;
      select.dispatchEvent(new Event('change'));
      await settle();
    }

    it('the reason Other shows a note and sends it', async () => {
      await open();
      expect(q('#sf-why-note')).toBeNull();
      await pick('OTHER');
      await type('#sf-why-note', 'Patient asleep');
      q<HTMLButtonElement>('[data-act=sfnogive]')!.click();
      await settle();
      const req = http.expectOne(`${DOSES}/o1/doses`);
      expect(body(req)).toMatchObject({ outcome: 'NOT_GIVEN', reasonKey: 'OTHER', note: 'Patient asleep', orderVersion: 1 });
      req.flush(recorded(doseOf({ outcome: 'HELD', reason: { key: 'OTHER', label: 'Other' } })), { status: 201, statusText: 'Created' });
    });

    it('a short note shows the server message under the note', async () => {
      await open();
      await pick('OTHER');
      q<HTMLButtonElement>('[data-act=sfnogive]')!.click();
      await settle();
      fail(http.expectOne(`${DOSES}/o1/doses`), 'validation', 400, 'Write what happened.', [
        { field: 'note', message: 'Write what happened.' },
      ]);
      await settle();
      expect(squash(q('#sf-why-note-err'))).toBe('Write what happened.');
    });

    it('Vomited sends no note', async () => {
      await open();
      await pick('VOMITED');
      expect(q('#sf-why-note')).toBeNull();
      q<HTMLButtonElement>('[data-act=sfnogive]')!.click();
      await settle();
      const req = http.expectOne(`${DOSES}/o1/doses`);
      expect(Object.keys(body(req))).not.toContain('note');
      req.flush(recorded(doseOf({ outcome: 'HELD', reason: { key: 'VOMITED', label: 'Vomited' } })), { status: 201, statusText: 'Created' });
    });
  });

  describe('after Given', () => {
    it('hands the dose to the undo toast, which holds the countdown', async () => {
      await open();
      await fillScan();
      const dose = doseOf({ canUndo: true, undoSecondsLeft: 10 });
      (await clickGiven()).flush(recorded(dose), { status: 201, statusText: 'Created' });
      await settle();
      expect(showGiven).toHaveBeenCalledWith({ admissionId: 'a1', drugName: 'Tab. Paracetamol 650 mg', dose, override: false });
      expect(ref.close).toHaveBeenCalledWith(expect.objectContaining({ mar: expect.anything() }));
    });
  });
});
