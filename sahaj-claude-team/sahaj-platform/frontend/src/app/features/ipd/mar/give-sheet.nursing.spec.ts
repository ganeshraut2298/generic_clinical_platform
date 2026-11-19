import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, Subject } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { problem } from '../emr/emr.fixtures';
import { GiveSheetComponent, GiveSheetData } from './give-sheet.component';
import { GiveSheetPresence } from './give-sheet-presence';
import { layoutHeight } from './layout-height';
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
      expect(snack).not.toHaveBeenCalled();
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

  describe('no code loaded (409 no-code-loaded, ipd.yaml 0.4.4 H1)', () => {
    const DETAIL = 'No code is loaded for this medicine. Use Manual override: Medicine has no barcode.';
    const key = (req: TestRequest) => body(req)['clientKey'] as string;

    async function refused(over: { order?: MarOrder } = {}): Promise<TestRequest> {
      await open(over);
      await fillScan();
      const req = await clickGiven();
      fail(req, 'no-code-loaded', 409, DETAIL);
      await settle();
      return req;
    }

    it('keeps the sheet open and does not read the MAR again', async () => {
      await refused();
      expect(ref.close).not.toHaveBeenCalled();
      expect(q('[data-testid=give-sheet]')).not.toBeNull();
    });

    it('shows the server detail under the code box in the polite live region, in the warn style and not as a STOP', async () => {
      await refused();
      const notice = q('[data-testid=no-code-notice]')!;
      expect(squash(notice)).toBe(DETAIL);
      expect(q('#sf-codeok')!.getAttribute('aria-live')).toBe('polite');
      expect(q('#sf-codeok')!.contains(notice)).toBe(true);
      expect(notice.classList.contains('sfwarn')).toBe(true);
      expect(notice.classList.contains('sfbad')).toBe(false);
      expect(q('[data-testid=code-mismatch]')).toBeNull();
      expect(q('[data-testid=code-error]')).toBeNull();
      expect(q('#sf-code')!.getAttribute('aria-invalid')).toBeNull();
      expect(el().textContent).not.toContain('Wrong medicine');
    });

    it('shows the text once when the hasMedicineCode hint with the same words is already there', async () => {
      await open({ order: orderOf({ hasMedicineCode: false }) });
      expect(qa('[data-testid=no-code-line]').length).toBe(1);
      await fillScan();
      fail(await clickGiven(), 'no-code-loaded', 409, DETAIL);
      await settle();
      expect(qa('[data-testid=no-code-notice]').length).toBe(1);
      expect(q('[data-testid=no-code-line]')).toBeNull();
      expect(el().textContent!.split(DETAIL).length - 1).toBe(1);
      expect(q('#sf-codeok')!.contains(q('[data-testid=no-code-notice]'))).toBe(true);
      // The warn style leaves when the nurse types in the code box: the hint is there as before.
      await type('#sf-code', '8901');
      expect(q('[data-testid=no-code-notice]')).toBeNull();
      expect(squash(q('[data-testid=no-code-line]'))).toBe(DETAIL);
    });

    it('shows no second line without the hint either (hasMedicineCode true)', async () => {
      await refused();
      expect(q('[data-testid=no-code-line]')).toBeNull();
      expect(el().textContent!.split(DETAIL).length - 1).toBe(1);
    });

    it('clears the code box and offers the manual override at once, closed and empty', async () => {
      await refused();
      expect(q<HTMLInputElement>('#sf-code')!.value).toBe('');
      const toggle = q<HTMLButtonElement>('[data-act=override-toggle]')!;
      expect(toggle).not.toBeNull();
      expect(squash(toggle)).toBe('Cannot scan the medicine? Manual override');
      expect(toggle.disabled).toBe(false);
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(q('#sf-ov-note')).toBeNull();
      expect(q('[data-testid=override-reasons]')).toBeNull();
      expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(false);
    });

    it('does not apply the after-STOP restriction: the toggle was offered although a STOP had not been shown', async () => {
      await refused();
      expect(q('[data-act=override-toggle]')).not.toBeNull();
      // and an override opened now is sent with the empty code and the entries kept
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      qa('[data-testid=override-reason]')[0].click();
      await type('#sf-ov-note', 'Medicine has no barcode, strip label read');
      const req = await clickGiven();
      expect(body(req)).toMatchObject({
        medicineCode: '',
        wristband: 'PT-00042',
        identityConfirmed: true,
        override: { reasonKey: 'NO_BARCODE', note: 'Medicine has no barcode, strip label read' },
      });
      req.flush(recorded(doseOf({ method: 'OVERRIDE' })), { status: 201, statusText: 'Created' });
    });

    it('keeps the wristband and the identity statement', async () => {
      await refused();
      expect(q<HTMLInputElement>('#sf-id')!.value).toBe('PT-00042');
      expect(q<HTMLInputElement>('#sf-said')!.checked).toBe(true);
    });

    it('keeps the second nurse and clears the PIN for a high-alert medicine', async () => {
      await open({ order: ORDER_HEP, slot: slotOf({ state: 'DUE' }) });
      await fillScan();
      const select = q<HTMLSelectElement>('#sf-wit')!;
      select.value = 'm-rupali';
      select.dispatchEvent(new Event('change'));
      await settle();
      await type('#sf-pin', '1234');
      fail(await clickGiven(), 'no-code-loaded', 409, DETAIL);
      await settle();
      expect(q<HTMLSelectElement>('#sf-wit')!.value).toBe('m-rupali');
      expect(q<HTMLInputElement>('#sf-pin')!.value).toBe('');
      expect(squash(q('[data-testid=no-code-notice]'))).toBe(DETAIL);
      expect(q('[data-testid=pin-error]')).toBeNull();
    });

    it('keeps the same clientKey for the next request', async () => {
      const first = await refused();
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      qa('[data-testid=override-reason]')[0].click();
      await type('#sf-ov-note', 'Medicine has no barcode, strip label read');
      const second = await clickGiven();
      expect(key(second)).toBe(key(first));
      second.flush(recorded(doseOf({ method: 'OVERRIDE' })), { status: 201, statusText: 'Created' });
    });

    it('does not show the crit STOP, closes nothing and makes no toast', async () => {
      await refused();
      expect(snack).not.toHaveBeenCalled();
      expect(q('[data-testid=wristband-mismatch]')).toBeNull();
    });

    it('the warn line leaves when the nurse scans in the code box again', async () => {
      await refused();
      await type('#sf-code', '8901234567890');
      expect(q('[data-testid=no-code-notice]')).toBeNull();
      expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(false);
    });

    it('the warn line leaves when another answer arrives', async () => {
      await refused();
      q<HTMLButtonElement>('[data-act=override-toggle]')!.click();
      await settle();
      qa('[data-testid=override-reason]')[0].click();
      await type('#sf-ov-note', 'Medicine has no barcode, strip label read');
      fail(await clickGiven(), 'validation', 400, 'Scan the wristband first.', [
        { field: 'wristband', message: 'Scan the wristband first.' },
      ]);
      await settle();
      expect(q('[data-testid=no-code-notice]')).toBeNull();
      expect(squash(q('[data-testid=wristband-error]'))).toBe('Scan the wristband first.');
    });

    it('uses the contract detail when the answer carries none', async () => {
      await open();
      await fillScan();
      fail(await clickGiven(), 'no-code-loaded', 409, '');
      await settle();
      expect(squash(q('[data-testid=no-code-notice]'))).toBe(DETAIL);
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
      expect(snack).not.toHaveBeenCalled();
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
      expect(snack).not.toHaveBeenCalled();
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
      expect(snack).not.toHaveBeenCalled();
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

  describe('SAH-76 round 4: lines in view, STOPs kept, toast after close (DEC-068)', () => {
    const DETAIL = 'No code is loaded for this medicine. Use Manual override: Medicine has no barcode.';
    let scroll: ReturnType<typeof vi.fn>;
    const proto = Element.prototype as unknown as { scrollIntoView?: unknown };
    let original: unknown;

    beforeEach(() => {
      original = proto.scrollIntoView;
      scroll = vi.fn();
      proto.scrollIntoView = scroll;
    });
    afterEach(() => {
      proto.scrollIntoView = original;
      vi.unstubAllGlobals();
    });

    const scrolledTo = () =>
      scroll.mock.contexts.map((c) => (c as HTMLElement).getAttribute('data-testid') ?? (c as HTMLElement).id);
    const clickNotGiven = async (): Promise<TestRequest> => {
      q<HTMLButtonElement>('[data-act=sfnogive]')!.click();
      await settle();
      return http.expectOne(`${DOSES}/${order.orderId}/doses`);
    };

    describe('Code #1, R4-1: a bottom line is scrolled into view, not focused', () => {
      it('403 (notice): give-failure is scrolled into view with block nearest and takes no focus', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'forbidden', 403, 'Only nurses and the clinic owner can give medicines.');
        await settle();
        expect(scroll).toHaveBeenCalledWith({ block: 'nearest' });
        expect(scrolledTo()).toEqual(['give-failure']);
        const line = q('[data-testid=give-failure]')!;
        expect(line.getAttribute('role')).toBe('alert');
        expect(line.hasAttribute('tabindex')).toBe(false);
        expect(line.contains(document.activeElement)).toBe(false);
      });

      it('500 and a lost answer (notice): give-failure is scrolled into view', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'internal', 500, 'boom');
        await settle();
        expect(scrolledTo()).toEqual(['give-failure']);
      });

      it('prn-lock: give-prn-lock is scrolled into view', async () => {
        await open({ order: ORDER_PRN, slot: null });
        await fillScan();
        fail(await clickGiven(), 'prn-too-soon', 409, 'Too soon. Next dose allowed at 2:30 PM.');
        await settle();
        expect(scrolledTo()).toEqual(['give-prn-lock']);
        expect(q('[data-testid=give-prn-lock]')!.hasAttribute('tabindex')).toBe(false);
      });

      it('client-key-reused: give-failure is scrolled into view', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'client-key-reused', 409, 'This dose was already recorded with different values. Reload the page.');
        await settle();
        expect(scrolledTo()).toEqual(['give-failure']);
      });

      it('a PIN answer on a sheet without a second nurse: give-failure is scrolled into view', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'wrong-pin', 403, 'That PIN is not right.');
        await settle();
        expect(squash(q('[data-testid=give-failure]'))).toBe('That PIN is not right.');
        expect(scrolledTo()).toEqual(['give-failure']);
      });

      it('the lines under the boxes are scrolled into view too (wristband STOP, code STOP, no code loaded)', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'wristband-mismatch', 409, 'Wristband does not match this patient.');
        await settle();
        expect(scrolledTo()).toEqual(['wristband-line']);
        scroll.mockClear();
        await type('#sf-id', 'PT-00042');
        fail(await clickGiven(), 'medicine-mismatch', 409, 'Wrong medicine. This is Inj. Heparin, not Tab. Paracetamol.');
        await settle();
        expect(scrolledTo()).toEqual(['code-line']);
        scroll.mockClear();
        await type('#sf-code', '8901234567890');
        fail(await clickGiven(), 'no-code-loaded', 409, DETAIL);
        await settle();
        expect(scrolledTo()).toEqual(['code-line']);
      });

      it('a refusal that sits under a control does not scroll a bottom line', async () => {
        await open();
        fail(await clickGiven(), 'validation', 400, 'Scan the wristband first.', [
          { field: 'wristband', message: 'Scan the wristband first.' },
        ]);
        await settle();
        expect(scroll).not.toHaveBeenCalled();
      });

      it('measures the sticky footer into --sheet-foot-h for scroll-padding-bottom and stops watching on destroy', async () => {
        const disconnect = vi.fn();
        let notify: (() => void) | null = null;
        vi.stubGlobal(
          'ResizeObserver',
          class {
            constructor(cb: () => void) {
              notify = cb;
            }
            observe = vi.fn();
            disconnect = disconnect;
          },
        );
        await open();
        const sheet = q<HTMLElement>('[data-testid=give-sheet]')!;
        expect(sheet.style.getPropertyValue('--sheet-foot-h')).toMatch(/^\d+(\.\d+)?px$/);
        const foot = q<HTMLElement>('.sheet-f')!;
        // A fractional height rounds up, so a line is never left a fraction of a pixel under the footer (QA, 0.36 px).
        foot.style.boxSizing = 'border-box';
        foot.style.height = '128.36px';
        notify!();
        expect(sheet.style.getPropertyValue('--sheet-foot-h')).toBe('129px');
        fixture.destroy();
        expect(disconnect).toHaveBeenCalled();
      });

      it('measures the layout size, not the painted one: a scaled getBoundingClientRect (dialog open animation) changes nothing', async () => {
        let notify: (() => void) | null = null;
        vi.stubGlobal(
          'ResizeObserver',
          class {
            constructor(cb: () => void) {
              notify = cb;
            }
            observe = vi.fn();
            disconnect = vi.fn();
          },
        );
        await open();
        const sheet = q<HTMLElement>('[data-testid=give-sheet]')!;
        const foot = q<HTMLElement>('.sheet-f')!;
        const head = q<HTMLElement>('.sheet-h')!;
        foot.style.boxSizing = 'border-box';
        foot.style.height = '77.2px';
        head.style.boxSizing = 'border-box';
        head.style.height = '71.2px';
        // the dialog is at 0.87 of its size: QA saw 67 px for a 77 px footer
        foot.getBoundingClientRect = () => ({ height: 77.2 * 0.87 }) as DOMRect;
        head.getBoundingClientRect = () => ({ height: 71.2 * 0.87 }) as DOMRect;
        notify!();
        expect(sheet.style.getPropertyValue('--sheet-foot-h')).toBe('78px');
        expect(sheet.style.getPropertyValue('--sheet-head-h')).toBe('72px');
      });

      it('adds padding and border to a content-box height, and falls back to offsetHeight where there is no number', async () => {
        await open();
        const foot = q<HTMLElement>('.sheet-f')!;
        foot.style.boxSizing = 'content-box';
        foot.style.height = '50.2px';
        foot.style.paddingTop = '14px';
        foot.style.paddingBottom = '12px';
        foot.style.borderTop = '1px solid red';
        expect(layoutHeight(foot)).toBeCloseTo(77.2, 5);
        const bare = document.createElement('div');
        Object.defineProperty(bare, 'offsetHeight', { value: 41 });
        expect(layoutHeight(bare)).toBe(41);
      });

      it('measures again when the open animation or transition ends, and stops listening on destroy', async () => {
        vi.stubGlobal(
          'ResizeObserver',
          class {
            observe = vi.fn();
            disconnect = vi.fn();
          },
        );
        await open();
        const sheet = q<HTMLElement>('[data-testid=give-sheet]')!;
        const foot = q<HTMLElement>('.sheet-f')!;
        foot.style.boxSizing = 'border-box';
        foot.style.height = '77px';
        sheet.dispatchEvent(new Event('animationend', { bubbles: true }));
        expect(sheet.style.getPropertyValue('--sheet-foot-h')).toBe('77px');
        foot.style.height = '90px';
        sheet.dispatchEvent(new Event('transitionend', { bubbles: true }));
        expect(sheet.style.getPropertyValue('--sheet-foot-h')).toBe('90px');
        fixture.destroy();
        foot.style.height = '120px';
        sheet.dispatchEvent(new Event('animationend', { bubbles: true }));
        expect(sheet.style.getPropertyValue('--sheet-foot-h')).toBe('90px');
      });

      it('measures the sticky header into --sheet-head-h (rounded up) so a scroll keeps the title and Close on screen', async () => {
        let notify: (() => void) | null = null;
        const observed: Element[] = [];
        vi.stubGlobal(
          'ResizeObserver',
          class {
            constructor(cb: () => void) {
              notify = cb;
            }
            observe = (el: Element) => observed.push(el);
            disconnect = vi.fn();
          },
        );
        await open();
        const sheet = q<HTMLElement>('[data-testid=give-sheet]')!;
        const head = q<HTMLElement>('.sheet-h')!;
        expect(observed).toContain(head);
        expect(sheet.style.getPropertyValue('--sheet-head-h')).toMatch(/^\d+px$/);
        head.style.boxSizing = 'border-box';
        head.style.height = '71.2px';
        notify!();
        expect(sheet.style.getPropertyValue('--sheet-head-h')).toBe('72px');
      });

      it('registers as an open give sheet (DEC-068) until it is destroyed', async () => {
        await open();
        const presence = TestBed.inject(GiveSheetPresence);
        const shown = vi.fn();
        presence.whenNoneOpen(shown);
        expect(shown).not.toHaveBeenCalled();
        fixture.destroy();
        expect(shown).toHaveBeenCalledTimes(1);
      });

      it('the sheet is the sticky-foot sheet whose header holds the title and the Close button', async () => {
        await open();
        expect(q('[data-testid=give-sheet]')!.classList.contains('sticky-foot')).toBe(true);
        const head = q<HTMLElement>('.sheet-h')!;
        expect(head.querySelector('h2')).not.toBeNull();
        expect(head.querySelector('[data-act="gv-x"]')).not.toBeNull();
      });
    });

    describe('R4-2: a STOP stays through a failed Record not given', () => {
      it('wrong medicine, then a failed Record not given: the STOP line is still shown and Given is still off', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'medicine-mismatch', 409, 'Wrong medicine. This is Inj. Heparin, not Tab. Paracetamol.');
        await settle();
        const reason = q<HTMLSelectElement>('#sf-why')!;
        reason.value = 'NIL_BY_MOUTH';
        reason.dispatchEvent(new Event('change'));
        await settle();
        fail(await clickNotGiven(), 'internal', 500, 'boom');
        await settle();
        expect(squash(q('[data-testid=code-mismatch]'))).toBe('Wrong medicine. This is Inj. Heparin, not Tab. Paracetamol.');
        expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(true);
        expect(q('[data-act=override-toggle]')).toBeNull();
        expect(q('[data-testid=give-failure]')).not.toBeNull();
      });

      it('wrong patient, then a failed Record not given: the STOP line is still shown and Given is still off', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'wristband-mismatch', 409, 'Wristband does not match this patient.');
        await settle();
        fail(await clickNotGiven(), 'internal', 500, 'boom');
        await settle();
        expect(q('[data-testid=wristband-mismatch]')).not.toBeNull();
        expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(true);
      });

      it('a refusal of Record not given (400 under Reason) keeps the STOP too', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'medicine-mismatch', 409, 'Wrong medicine.');
        await settle();
        fail(await clickNotGiven(), 'validation', 400, 'Choose why the dose is not given.', [
          { field: 'reasonKey', message: 'Choose why the dose is not given.' },
        ]);
        await settle();
        expect(q('[data-testid=code-mismatch]')).not.toBeNull();
        expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(true);
      });

      it('the STOP still goes when the box is edited', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'medicine-mismatch', 409, 'Wrong medicine.');
        await settle();
        fail(await clickNotGiven(), 'internal', 500, 'boom');
        await settle();
        await type('#sf-code', '8901234567891');
        expect(q('[data-testid=code-mismatch]')).toBeNull();
        expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(false);
      });
    });

    describe('Code #2: a 400 for a control that is not drawn is not silent', () => {
      it('secondNurseId on a sheet without a second nurse: the message shows in give-failure', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'validation', 400, 'Choose a second nurse.', [
          { field: 'secondNurseId', message: 'Choose a second nurse.' },
        ]);
        await settle();
        expect(squash(q('[data-testid=give-failure]'))).toBe('Choose a second nurse.');
        expect(scrolledTo()).toEqual(['give-failure']);
        expect(q('[data-testid=second-nurse-error]')).toBeNull();
      });

      it('a note error while the other reason is not chosen shows in give-failure', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'validation', 400, 'Write the reason.', [{ field: 'note', message: 'Write the reason.' }]);
        await settle();
        expect(squash(q('[data-testid=give-failure]'))).toBe('Write the reason.');
      });

      it('a drawn control keeps the line under it and no give-failure', async () => {
        await open();
        fail(await clickGiven(), 'validation', 400, 'Scan the wristband first.', [
          { field: 'wristband', message: 'Scan the wristband first.' },
        ]);
        await settle();
        expect(squash(q('[data-testid=wristband-error]'))).toBe('Scan the wristband first.');
        expect(q('[data-testid=give-failure]')).toBeNull();
      });

      it('with one drawn and one not drawn key, the drawn one carries it', async () => {
        await open();
        fail(await clickGiven(), 'validation', 400, 'Fix the form.', [
          { field: 'secondNurseId', message: 'Choose a second nurse.' },
          { field: 'wristband', message: 'Scan the wristband first.' },
        ]);
        await settle();
        expect(squash(q('[data-testid=wristband-error]'))).toBe('Scan the wristband first.');
        expect(q('[data-testid=give-failure]')).toBeNull();
      });
    });

    describe('Code #3: a repeat no-code-loaded is drawn again', () => {
      it('the notice is cleared when the next request starts and drawn as a new line when the answer comes', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'no-code-loaded', 409, DETAIL);
        await settle();
        const first = q('[data-testid=no-code-notice]')!;
        expect(first).not.toBeNull();
        const req = await clickGiven();
        expect(q('[data-testid=no-code-notice]')).toBeNull();
        fail(req, 'no-code-loaded', 409, DETAIL);
        await settle();
        const second = q('[data-testid=no-code-notice]')!;
        expect(squash(second)).toBe(DETAIL);
        expect(second).not.toBe(first);
      });

      it('the notice is no STOP: Given stays on after it and after the repeat', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'no-code-loaded', 409, DETAIL);
        await settle();
        expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(false);
        const req = await clickGiven();
        fail(req, 'no-code-loaded', 409, DETAIL);
        await settle();
        expect(q<HTMLButtonElement>('[data-act=sfgiveok]')!.disabled).toBe(false);
        expect(q('[data-testid=code-mismatch]')).toBeNull();
      });
    });

    describe('Nit #7: the sheet closes first, then the toast', () => {
      it('Given: close, then the Undo toast', async () => {
        await open();
        await fillScan();
        (await clickGiven()).flush(recorded(), { status: 201, statusText: 'Created' });
        expect(ref.close.mock.invocationCallOrder[0]).toBeLessThan(showGiven.mock.invocationCallOrder[0]);
      });

      it('Record not given: close, then the toast', async () => {
        await open();
        const reason = q<HTMLSelectElement>('#sf-why')!;
        reason.value = 'NIL_BY_MOUTH';
        reason.dispatchEvent(new Event('change'));
        await settle();
        (await clickNotGiven()).flush(recorded(), { status: 201, statusText: 'Created' });
        expect(snack).toHaveBeenCalledTimes(1);
        expect(ref.close.mock.invocationCallOrder[0]).toBeLessThan(snack.mock.invocationCallOrder[0]);
      });

      it('an answer that reloads the MAR: close, then the toast', async () => {
        await open();
        await fillScan();
        fail(await clickGiven(), 'dose-recorded', 409, 'This dose was already recorded.');
        await settle();
        expect(snack).toHaveBeenCalledTimes(1);
        expect(ref.close.mock.invocationCallOrder[0]).toBeLessThan(snack.mock.invocationCallOrder[0]);
      });
    });
  });
});
