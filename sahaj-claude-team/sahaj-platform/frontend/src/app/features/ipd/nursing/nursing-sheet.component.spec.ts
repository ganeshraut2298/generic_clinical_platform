import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { marOf } from '../mar/mar.fixtures';
import { NursingSheetComponent } from './nursing-sheet.component';
import { LatestVitals, NursingSheet, VitalsSaved } from './nursing.model';

const SHEET_URL = '/api/v1/ipd/admissions/a1/nursing';
const VITALS_URL = '/api/v1/ipd/admissions/a1/vitals';
const AS_OF = '2026-11-05T10:00:00Z';
const minutesBefore = (m: number): string => new Date(new Date(AS_OF).getTime() - m * 60000).toISOString();
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const LATEST: LatestVitals = {
  id: 'v1',
  bp: '168/98',
  bpSystolic: 168,
  bpDiastolic: 98,
  pulse: 88,
  spo2: 97,
  tempC: 37,
  rr: 18,
  ewsScore: 0,
  ewsRisk: 'LOW',
  recordedAt: minutesBefore(35),
  recordedByName: 'Sr. Anjali Pawar',
};

const SHEET: NursingSheet = {
  admissionId: 'a1',
  patientName: 'Asha Bendre',
  bedCode: '202',
  status: 'ADMITTED',
  state: 'on',
  canRecord: true,
  readOnlyReason: null,
  asOf: AS_OF,
  latest: LATEST,
  vitalsDue: false,
  doseCount: 0,
  recentNotes: [
    { id: 'n1', text: 'BP rechecked after medicine: 154/92.', recordedAt: minutesBefore(45), recordedByName: 'Sr. Anjali Pawar' },
    { id: 'n2', text: 'Slept well.', recordedAt: minutesBefore(300), recordedByName: 'Sr. Meena Rao' },
  ],
};

function savedAnswer(
  risk: 'LOW' | 'MEDIUM' | 'HIGH',
  score: number,
  target: 15 | 60 | null,
  extra: Partial<VitalsSaved> = {},
  replayed = false,
): VitalsSaved {
  return {
    ...SHEET,
    asOf: '2026-11-05T10:20:00Z',
    latest: {
      ...LATEST,
      id: 'v2',
      bp: '120/80',
      bpSystolic: 120,
      bpDiastolic: 80,
      pulse: 80,
      spo2: 98,
      tempC: 37,
      rr: 16,
      ewsScore: score,
      ewsRisk: risk,
      recordedAt: '2026-11-05T10:20:00Z',
    },
    recentNotes: [
      { id: 'n3', text: 'New note', recordedAt: '2026-11-05T10:20:00Z', recordedByName: 'Sr. Anjali Pawar' },
      ...SHEET.recentNotes,
    ].slice(0, 3),
    saved: {
      vitalsId: 'v2',
      ewsScore: score,
      ewsRisk: risk,
      recordedAt: '2026-11-05T10:20:00Z',
      noteAdded: true,
      replayed,
    },
    escalation: {
      outcome: target ? 'RAISED' : 'NONE',
      open:
        target && risk !== 'LOW'
          ? { id: 'e1', ewsScore: score, ewsRisk: risk, targetMinutes: target, status: 'OPEN', raisedAt: AS_OF }
          : null,
    },
    ...extra,
  };
}

describe('NursingSheetComponent', () => {
  let fixture: ComponentFixture<NursingSheetComponent>;
  let http: HttpTestingController;
  let dialogRef: { close: ReturnType<typeof vi.fn> };
  let snack: ReturnType<typeof vi.fn>;

  async function setup(sheet: NursingSheet | null = SHEET): Promise<void> {
    dialogRef = { close: vi.fn() };
    snack = vi.fn();
    await TestBed.configureTestingModule({
      imports: [NursingSheetComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: { admissionId: 'a1' } },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatSnackBar, useValue: { open: snack } },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NursingSheetComponent);
    fixture.detectChanges();
    if (sheet) {
      http.expectOne(SHEET_URL).flush(sheet);
      await settle();
    }
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => (el().textContent ?? '').replace(/\s+/g, ' ');
  const input = (key: string) => el().querySelector<HTMLInputElement>(`#nv-${key}`)!;
  const note = () => el().querySelector<HTMLTextAreaElement>('#nv-note')!;
  const save = () => el().querySelector<HTMLButtonElement>('[data-testid="nursing-save"]');
  const errorOf = (key: string) => el().querySelector<HTMLElement>(`#nv-${key}-err`);

  async function type(key: string, value: string): Promise<void> {
    const control = key === 'note' ? note() : input(key);
    control.value = value;
    control.dispatchEvent(new Event('input'));
    await settle();
  }

  async function fillValid(): Promise<void> {
    await type('bp', '120/80');
    await type('pulse', '80');
    await type('spo2', '98');
    await type('temp', '37.0');
    await type('rr', '16');
  }

  const sameValuesNotice = () => el().querySelector<HTMLElement>('[data-testid="nursing-same-values"]');

  /** One tap on Save. */
  async function submitOnce(): Promise<void> {
    save()!.click();
    await settle();
  }

  /** Taps Save, and taps again when the "same values as the last reading" step asks for it. */
  async function submit(): Promise<void> {
    await submitOnce();
    if (sameValuesNotice()) {
      await submitOnce();
    }
  }

  function expectSave(): TestRequest {
    return http.expectOne(VITALS_URL);
  }

  afterEach(() => http.verify());

  describe('the sheet', () => {
    it('shows the title, the subtitle from asOf and the five fields with units, prefilled', async () => {
      await setup();
      expect(el().querySelector('h2')!.textContent).toBe('Nursing · Asha Bendre');
      expect(el().querySelector('[data-testid="nursing-subtitle"]')!.textContent).toBe(
        'Bed 202 · last vitals 35 min ago',
      );
      const labels = Array.from(el().querySelectorAll('.nvg label')).map((l) => l.textContent!.replace(/\s+/g, ' ').trim());
      expect(labels).toEqual(['BP mmHg', 'Pulse /min', 'SpO₂ %', 'Temp °C', 'RR /min']);
      expect(['bp', 'pulse', 'spo2', 'temp', 'rr'].map((k) => input(k).value)).toEqual([
        '168/98',
        '88',
        '97',
        '37.0',
        '18',
      ]);
      expect(['bp', 'pulse', 'spo2', 'temp', 'rr'].map((k) => input(k).placeholder)).toEqual([
        '120/80',
        '80',
        '98',
        '37.0',
        '16',
      ]);
    });

    it('has a Nursing note area of 3 rows that is always empty on open', async () => {
      await setup();
      expect(note().rows).toBe(3);
      expect(note().placeholder).toBe('What you observed and did');
      expect(note().value).toBe('');
    });

    it('lists the recent notes with their ago text from asOf', async () => {
      await setup();
      const rows = Array.from(el().querySelectorAll('[data-testid="nursing-notes"] > div')).map((d) =>
        (d.textContent ?? '').replace(/\s+/g, ' ').trim(),
      );
      expect(rows).toEqual([
        '45 min ago · Sr. Anjali PawarBP rechecked after medicine: 154/92.',
        '5 h ago · Sr. Meena RaoSlept well.',
      ]);
      expect(text()).toContain('Recent notes');
    });

    it('says No notes yet when there are none', async () => {
      await setup({ ...SHEET, recentNotes: [] });
      expect(text()).toContain('No notes yet');
    });

    it('has the footer Close and Save vitals and note', async () => {
      await setup();
      const labels = Array.from(el().querySelectorAll('.sheet-f button')).map((b) => b.textContent!.trim());
      expect(labels).toEqual(['Close', 'Save vitals and note']);
      expect(save()!.querySelector('app-icon')).toBeTruthy();
    });

    it('shows no notice, empty fields and "Bed 202" when there are no vitals yet', async () => {
      await setup({ ...SHEET, latest: null, recentNotes: [] });
      expect(el().querySelector('[data-testid="nursing-ews"]')).toBeNull();
      expect(el().querySelector('[data-testid="nursing-subtitle"]')!.textContent).toBe('Bed 202');
      expect(['bp', 'pulse', 'spo2', 'temp', 'rr'].map((k) => input(k).value)).toEqual(['', '', '', '', '']);
    });

    it('shows the score notice before the fields: green Low, amber Medium, red High', async () => {
      await setup({ ...SHEET, latest: { ...LATEST, ewsScore: 2, ewsRisk: 'LOW' } });
      const notice = el().querySelector('[data-testid="nursing-ews"]')!;
      expect(notice.classList.contains('good')).toBe(true);
      expect(notice.textContent!.replace(/\s+/g, ' ').trim()).toBe(
        'Early warning score 2 · Low. Routine vitals every 4 hours.',
      );
      expect(notice.compareDocumentPosition(el().querySelector('.nvg')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('shows the amber and red notices with their advice', async () => {
      await setup({ ...SHEET, latest: { ...LATEST, ewsScore: 5, ewsRisk: 'MEDIUM' } });
      let notice = el().querySelector('[data-testid="nursing-ews"]')!;
      expect(notice.classList.contains('warn')).toBe(true);
      expect(notice.textContent).toContain('Early warning score 5 · Medium. Repeat vitals within 1 hour.');
      TestBed.resetTestingModule();
      await setup({ ...SHEET, latest: { ...LATEST, ewsScore: 8, ewsRisk: 'HIGH' } });
      notice = el().querySelector('[data-testid="nursing-ews"]')!;
      expect(notice.classList.contains('crit')).toBe(true);
      expect(notice.textContent).toContain(
        'Early warning score 8 · High. Inform the doctor now and repeat vitals every 30 minutes.',
      );
    });

    it('shows an error with Try again when the sheet cannot be loaded', async () => {
      await setup(null);
      http.expectOne(SHEET_URL).flush({ type: 'x', title: 'Server error', status: 500 }, { status: 500, statusText: 'x' });
      await settle();
      expect(text()).toContain('We could not load the Nursing sheet. Try again.');
      const retry = Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.trim() === 'Try again')!;
      retry.click();
      http.expectOne(SHEET_URL).flush(SHEET);
      await settle();
      expect(el().querySelector('h2')!.textContent).toBe('Nursing · Asha Bendre');
    });

    it('shows the server detail when the sheet is refused (403)', async () => {
      await setup(null);
      http
        .expectOne(SHEET_URL)
        .flush(
          { type: 'forbidden', title: 'Forbidden', status: 403, detail: 'Your role cannot open this sheet.' },
          { status: 403, statusText: 'Forbidden' },
        );
      await settle();
      expect(text()).toContain('Your role cannot open this sheet.');
    });

    it('closes with false when nothing was saved', async () => {
      await setup();
      el().querySelector<HTMLButtonElement>('.sheet-f button')!.click();
      expect(dialogRef.close).toHaveBeenCalledWith(false);
    });
  });

  describe('view-only states', () => {
    it('for a role that cannot record (DOCTOR): fields disabled, the role notice, no save button', async () => {
      await setup({ ...SHEET, canRecord: false, readOnlyReason: 'ROLE' });
      for (const k of ['bp', 'pulse', 'spo2', 'temp', 'rr']) {
        expect(input(k).disabled, k).toBe(true);
      }
      expect(note().disabled).toBe(true);
      expect(el().querySelector('[data-testid="nursing-role-notice"]')!.textContent).toContain(
        'Your role can view vitals but not record them.',
      );
      expect(save()).toBeNull();
      expect(input('bp').value).toBe('168/98');
    });

    it('puts the role notice after the note and before Recent notes', async () => {
      await setup({ ...SHEET, canRecord: false, readOnlyReason: 'ROLE' });
      const noticeEl = el().querySelector('[data-testid="nursing-role-notice"]')!;
      expect(note().compareDocumentPosition(noticeEl) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(noticeEl.compareDocumentPosition(el().querySelector('[data-testid="nursing-notes"]')!) &
        Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    for (const reason of ['IPD_READONLY', 'NOT_ADMITTED'] as const) {
      it(`for ${reason}: fields disabled, no save button and no role notice`, async () => {
        await setup({
          ...SHEET,
          canRecord: false,
          readOnlyReason: reason,
          status: reason === 'NOT_ADMITTED' ? 'DISCHARGED' : 'ADMITTED',
          state: reason === 'IPD_READONLY' ? 'readonly' : 'on',
        });
        expect(input('pulse').disabled).toBe(true);
        expect(note().disabled).toBe(true);
        expect(save()).toBeNull();
        expect(el().querySelector('[data-testid="nursing-role-notice"]')).toBeNull();
        expect(text()).toContain('Recent notes');
      });
    }
  });

  describe('checking the values before sending', () => {
    it('sends nothing when a vital is missing, marks it invalid and focuses the first one', async () => {
      await setup({ ...SHEET, latest: null, recentNotes: [] });
      await type('bp', '120/80');
      await type('spo2', '98');
      await submit();
      http.expectNone(VITALS_URL);
      expect(input('pulse').getAttribute('aria-invalid')).toBe('true');
      expect(input('pulse').getAttribute('aria-describedby')).toBe('nv-pulse-err');
      expect(errorOf('pulse')!.getAttribute('role')).toBe('alert');
      expect(errorOf('pulse')!.textContent).toBe('Enter all five vitals. BP as 120/80.');
      expect(input('bp').getAttribute('aria-invalid')).toBeNull();
      expect(document.activeElement).toBe(input('pulse'));
    });

    it('marks every failing field and focuses the first in screen order', async () => {
      await setup({ ...SHEET, latest: null, recentNotes: [] });
      await submit();
      http.expectNone(VITALS_URL);
      for (const k of ['bp', 'pulse', 'spo2', 'temp', 'rr']) {
        expect(errorOf(k)!.textContent, k).toBe('Enter all five vitals. BP as 120/80.');
      }
      expect(document.activeElement).toBe(input('bp'));
    });

    it('refuses BP typed without the slash', async () => {
      await setup();
      await type('bp', '12080');
      await submit();
      http.expectNone(VITALS_URL);
      expect(errorOf('bp')!.textContent).toBe('Enter all five vitals. BP as 120/80.');
    });

    it('shows the contract range message for an implausible temperature', async () => {
      await setup();
      await type('temp', '98.6');
      await submit();
      http.expectNone(VITALS_URL);
      expect(errorOf('temp')!.textContent).toBe('Check the temperature. Enter 25.0 to 45.0 °C.');
      expect(document.activeElement).toBe(input('temp'));
    });

    it('refuses a note over 1,000 characters on the note field', async () => {
      await setup();
      await type('note', 'a'.repeat(1001));
      await submit();
      http.expectNone(VITALS_URL);
      expect(errorOf('note')!.textContent).toBe('Keep the note to 1,000 characters.');
      expect(note().getAttribute('aria-invalid')).toBe('true');
      expect(document.activeElement).toBe(note());
    });
  });

  describe('saving', () => {
    it('posts the five typed strings, the note and a clientKey, with no score', async () => {
      await setup();
      await fillValid();
      await type('note', 'Line one\nLine two');
      await submit();
      const req = expectSave();
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({
        clientKey: expect.stringMatching(UUID),
        bp: '120/80',
        pulse: '80',
        spo2: '98',
        temp: '37.0',
        rr: '16',
        note: 'Line one\nLine two',
      });
      expect(Object.keys(req.request.body)).not.toContain('ewsScore');
      req.flush(savedAnswer('LOW', 1, null), { status: 201, statusText: 'Created' });
    });

    it('sends the prefilled values unchanged when the nurse does not touch them', async () => {
      await setup();
      await submit();
      const req = expectSave();
      expect(req.request.body).toMatchObject({ bp: '168/98', pulse: '88', spo2: '97', temp: '37.0', rr: '18', note: null });
      req.flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
    });

    it('turns Save off while the request runs and ignores a second tap', async () => {
      await setup();
      await submit();
      expect(save()!.disabled).toBe(true);
      expect(save()!.getAttribute('aria-busy')).toBe('true');
      save()!.click();
      const req = expectSave();
      req.flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
      await settle();
      expect(save()!.getAttribute('aria-busy')).toBe('false');
    });

    it('Low: toast "Vitals saved", sheet reloaded with the new score, last vitals 0 min ago and the new note', async () => {
      await setup();
      await fillValid();
      await type('note', 'New note');
      await submit();
      const answer = savedAnswer('LOW', 2, null);
      expectSave().flush({ ...answer, asOf: '2026-11-05T10:20:00Z' }, { status: 201, statusText: 'Created' });
      await settle();
      expect(snack).toHaveBeenCalledWith('Vitals saved for Asha Bendre · early warning 2 (low)', 'Close', expect.anything());
      expect(el().querySelector('[data-testid="nursing-subtitle"]')!.textContent).toBe('Bed 202 · last vitals 0 min ago');
      expect(el().querySelector('[data-testid="nursing-ews"]')!.textContent).toContain('Early warning score 2 · Low');
      expect(note().value).toBe('');
      expect(input('bp').value).toBe('120/80');
      expect(input('temp').value).toBe('37.0');
      expect(text()).toContain('0 min ago · Sr. Anjali PawarNew note');
      expect(dialogRef.close).not.toHaveBeenCalled();
    });

    it('Medium: toast "Escalation raised ... within 1 hour" and tells the doctor must be told', async () => {
      await setup();
      await submit();
      expectSave().flush(savedAnswer('MEDIUM', 5, 60), { status: 201, statusText: 'Created' });
      expect(snack).toHaveBeenCalledWith(
        'Escalation raised for Asha Bendre · EWS 5 · doctor review within 1 hour. Tell the doctor now: no alert is sent.',
        'Close',
        expect.anything(),
      );
    });

    it('High: toast "Escalation raised ... within 15 min", tells the doctor, never "duty doctor alerted"', async () => {
      await setup();
      await submit();
      expectSave().flush(savedAnswer('HIGH', 8, 15), { status: 201, statusText: 'Created' });
      const message = snack.mock.calls[0][0] as string;
      expect(message).toBe(
        'Escalation raised for Asha Bendre · EWS 8 · doctor review within 15 min. Tell the doctor now: no alert is sent.',
      );
      expect(message.toLowerCase()).not.toContain('alerted');
      await settle();
      expect(text().toLowerCase()).not.toContain('duty doctor');
    });

    it('closes with true after a save', async () => {
      await setup();
      await submit();
      expectSave().flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
      await settle();
      el().querySelector<HTMLButtonElement>('.sheet-f button')!.click();
      expect(dialogRef.close).toHaveBeenCalledWith(true);
    });

    it('treats a 200 replay as a success', async () => {
      await setup();
      await submit();
      expectSave().flush(savedAnswer('HIGH', 8, 15, {}, true), { status: 200, statusText: 'OK' });
      await settle();
      expect(snack).toHaveBeenCalledWith(
        'Escalation raised for Asha Bendre · EWS 8 · doctor review within 15 min. Tell the doctor now: no alert is sent.',
        'Close',
        expect.anything(),
      );
      expect(el().querySelector('[data-testid="nursing-failure"]')).toBeNull();
    });
  });

  describe('clientKey lifecycle (ADR-006 D5)', () => {
    const keyOf = (req: TestRequest): string => (req.request.body as { clientKey: string }).clientKey;
    const lost = (req: TestRequest) => req.error(new ProgressEvent('error'));

    it('keeps the key for a retry of the same values after a failure', async () => {
      await setup();
      await submit();
      const first = expectSave();
      const key = keyOf(first);
      lost(first);
      await settle();
      await submit();
      const retry = expectSave();
      expect(keyOf(retry)).toBe(key);
      retry.flush(savedAnswer('LOW', 0, null, {}, true), { status: 200, statusText: 'OK' });
    });

    it('makes a new key after any edit of a field', async () => {
      await setup();
      await submit();
      const first = expectSave();
      const key = keyOf(first);
      lost(first);
      await settle();
      await type('pulse', '82');
      await submit();
      const second = expectSave();
      expect(keyOf(second)).not.toBe(key);
      second.flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
    });

    it('makes a new key after an edit of the note', async () => {
      await setup();
      await submit();
      const first = expectSave();
      const key = keyOf(first);
      lost(first);
      await settle();
      await type('note', 'Called the doctor');
      await submit();
      const second = expectSave();
      expect(keyOf(second)).not.toBe(key);
      second.flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
    });

    it('makes a new key after a success, even when the next save has the same values', async () => {
      await setup();
      await submit();
      const first = expectSave();
      const key = keyOf(first);
      first.flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
      await settle();
      await type('pulse', '81');
      await submit();
      const second = expectSave();
      expect(keyOf(second)).not.toBe(key);
      expect(keyOf(second)).toMatch(UUID);
      second.flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
    });

    it('keeps the key after a field error from the server', async () => {
      await setup();
      await submit();
      const first = expectSave();
      const key = keyOf(first);
      first.flush(
        { type: 'validation', title: 'Invalid', status: 400, fieldErrors: [{ field: 'temp', message: 'Check the temperature. Enter 25.0 to 45.0 °C.' }] },
        { status: 400, statusText: 'Bad Request' },
      );
      await settle();
      await submit();
      const retry = expectSave();
      expect(keyOf(retry)).toBe(key);
      retry.flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
    });
  });

  describe('failures keep what was typed', () => {
    const problem = (status: number, type: string, detail: string, fieldErrors?: { field: string; message: string }[]) => ({
      body: { type, title: 'Problem', status, detail, fieldErrors },
      init: { status, statusText: 'Problem' },
    });

    it('400 with field errors: shows them on the fields, focuses the first, keeps the typing', async () => {
      await setup();
      await fillValid();
      await type('note', 'My note');
      await submit();
      const p = problem(400, 'validation', 'Check the pulse. Enter 1 to 300 per minute.', [
        { field: 'rr', message: 'Check the RR. Enter 1 to 300 per minute.' },
        { field: 'pulse', message: 'Check the pulse. Enter 1 to 300 per minute.' },
      ]);
      expectSave().flush(p.body, p.init);
      await settle();
      expect(errorOf('pulse')!.textContent).toBe('Check the pulse. Enter 1 to 300 per minute.');
      expect(errorOf('rr')!.textContent).toBe('Check the RR. Enter 1 to 300 per minute.');
      expect(input('pulse').getAttribute('aria-invalid')).toBe('true');
      expect(document.activeElement).toBe(input('pulse'));
      expect(input('bp').value).toBe('120/80');
      expect(note().value).toBe('My note');
      expect(save()!.disabled).toBe(false);
      expect(snack).not.toHaveBeenCalled();
    });

    it('400 on the note field shows the message under the note', async () => {
      await setup();
      await type('note', 'bad');
      await submit();
      const p = problem(400, 'validation', 'x', [{ field: 'note', message: 'Note cannot contain control or text-direction characters' }]);
      expectSave().flush(p.body, p.init);
      await settle();
      expect(errorOf('note')!.textContent).toBe('Note cannot contain control or text-direction characters');
      expect(document.activeElement).toBe(note());
    });

    it('400 on clientKey tells the user to reload the page', async () => {
      await setup();
      await submit();
      const p = problem(400, 'validation', 'x', [{ field: 'clientKey', message: 'Reload the page and try again.' }]);
      expectSave().flush(p.body, p.init);
      await settle();
      expect(el().querySelector('[data-testid="nursing-failure"]')!.textContent).toContain('Reload the page and try again.');
    });

    it('409 not-admitted: shows the server message, keeps the typing and turns Save off', async () => {
      await setup();
      await type('note', 'Typed note');
      await submit();
      const p = problem(409, 'not-admitted', 'This patient is not admitted any more. Vitals cannot be saved.');
      expectSave().flush(p.body, p.init);
      await settle();
      expect(el().querySelector('[data-testid="nursing-failure"]')!.textContent).toContain(
        'This patient is not admitted any more. Vitals cannot be saved.',
      );
      expect(snack).toHaveBeenCalledWith('This patient is not admitted any more. Vitals cannot be saved.', 'Close', expect.anything());
      expect(note().value).toBe('Typed note');
      expect(save()!.disabled).toBe(true);
    });

    it('409 ipd-readonly: shows the server message and turns Save off', async () => {
      await setup();
      await submit();
      const p = problem(409, 'ipd-readonly', 'IPD is read-only. Nothing can be changed.');
      expectSave().flush(p.body, p.init);
      await settle();
      expect(text()).toContain('IPD is read-only. Nothing can be changed.');
      expect(save()!.disabled).toBe(true);
    });

    it('403: shows the server message and keeps the typing', async () => {
      await setup();
      await submit();
      const p = problem(403, 'forbidden', 'You do not have access to record vitals.');
      expectSave().flush(p.body, p.init);
      await settle();
      expect(text()).toContain('You do not have access to record vitals.');
      expect(input('bp').value).toBe('168/98');
    });

    it('404: shows the server message', async () => {
      await setup();
      await submit();
      const p = problem(404, 'not-found', 'This admission was not found.');
      expectSave().flush(p.body, p.init);
      await settle();
      expect(text()).toContain('This admission was not found.');
    });

    it('network error: says the answer may be lost, offers Check the sheet and keeps the typing', async () => {
      await setup();
      await type('pulse', '90');
      await type('note', 'Typed note');
      await submit();
      expectSave().error(new ProgressEvent('error'));
      await settle();
      const message = el().querySelector('[data-testid="nursing-failure"]')!.textContent!.replace(/\s+/g, ' ');
      expect(message).toContain('We could not confirm that the vitals were saved.');
      expect(message).toContain('before saving again');
      expect(Array.from(el().querySelectorAll('button')).some((b) => b.textContent!.trim() === 'Check the sheet')).toBe(true);
      expect(input('pulse').value).toBe('90');
      expect(note().value).toBe('Typed note');
      expect(save()!.disabled).toBe(false);
    });

    it('a server error (500) is treated as a lost answer', async () => {
      await setup();
      await submit();
      const p = problem(500, 'internal', 'boom');
      expectSave().flush(p.body, p.init);
      await settle();
      expect(el().querySelector('[data-testid="nursing-failure"]')!.textContent).toContain(
        'We could not confirm that the vitals were saved.',
      );
    });

    it('Check the sheet reads the sheet again and keeps what was typed', async () => {
      await setup();
      await type('pulse', '90');
      await type('note', 'Typed note');
      await submit();
      expectSave().error(new ProgressEvent('error'));
      await settle();
      Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.trim() === 'Check the sheet')!.click();
      http.expectOne(SHEET_URL).flush(savedAnswer('LOW', 1, null) as NursingSheet);
      await settle();
      expect(el().querySelector('[data-testid="nursing-subtitle"]')!.textContent).toBe('Bed 202 · last vitals 0 min ago');
      expect(input('pulse').value).toBe('90');
      expect(note().value).toBe('Typed note');
    });

    it('Check the sheet finding a newer last reading makes Close answer true', async () => {
      await setup();
      await type('pulse', '90');
      await submit();
      expectSave().error(new ProgressEvent('error'));
      await settle();
      Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.trim() === 'Check the sheet')!.click();
      http.expectOne(SHEET_URL).flush(savedAnswer('LOW', 1, null) as NursingSheet);
      await settle();
      el().querySelector<HTMLButtonElement>('.sheet-f button')!.click();
      expect(dialogRef.close).toHaveBeenCalledWith(true);
    });

    it('Check the sheet finding the same last reading leaves Close answering false', async () => {
      await setup();
      await type('pulse', '90');
      await submit();
      expectSave().error(new ProgressEvent('error'));
      await settle();
      Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.trim() === 'Check the sheet')!.click();
      http.expectOne(SHEET_URL).flush(SHEET);
      await settle();
      el().querySelector<HTMLButtonElement>('.sheet-f button')!.click();
      expect(dialogRef.close).toHaveBeenCalledWith(false);
    });

    it('409 client-key-reused: shows the server message, makes a new key, keeps the typing and Save on', async () => {
      await setup();
      await fillValid();
      await type('note', 'My note');
      await submit();
      const first = expectSave();
      const key = (first.request.body as { clientKey: string }).clientKey;
      const p = problem(409, 'client-key-reused', 'This request was already used with different values. Review and save again.');
      first.flush(p.body, p.init);
      await settle();
      expect(el().querySelector('[data-testid="nursing-failure"]')!.textContent).toContain(
        'This request was already used with different values. Review and save again.',
      );
      expect(input('bp').value).toBe('120/80');
      expect(note().value).toBe('My note');
      expect(save()!.disabled).toBe(false);
      await submit();
      const retry = expectSave();
      expect((retry.request.body as { clientKey: string }).clientKey).not.toBe(key);
      retry.flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
    });
  });

  describe('Save after a success (code review M4)', () => {
    it('stays off after a success until something is typed in a field', async () => {
      await setup();
      await type('pulse', '90');
      await submit();
      expectSave().flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
      await settle();
      expect(save()!.disabled).toBe(true);
      save()!.click();
      http.expectNone(VITALS_URL);
      await type('rr', '17');
      expect(save()!.disabled).toBe(false);
    });

    it('stays off after a success until something is typed in the note', async () => {
      await setup();
      await type('pulse', '90');
      await submit();
      expectSave().flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
      await settle();
      expect(save()!.disabled).toBe(true);
      await type('note', 'Patient comfortable');
      expect(save()!.disabled).toBe(false);
    });

    it('does not come back from the form being reset by the answer (no input event)', async () => {
      await setup();
      await type('pulse', '90');
      await submit();
      expectSave().flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
      await settle();
      // The reset changed every value, yet Save is still off: only a typed input counts.
      expect(input('bp').value).toBe('120/80');
      expect(save()!.disabled).toBe(true);
    });

    it('a 200 replay also holds Save', async () => {
      await setup();
      await type('pulse', '90');
      await submit();
      expectSave().flush(savedAnswer('LOW', 0, null, {}, true), { status: 200, statusText: 'OK' });
      await settle();
      expect(save()!.disabled).toBe(true);
    });

    it('a failure does not hold Save', async () => {
      await setup();
      await type('pulse', '90');
      await submit();
      expectSave().error(new ProgressEvent('error'));
      await settle();
      expect(save()!.disabled).toBe(false);
    });
  });

  describe('same values as the last reading (security M3)', () => {
    it('shows the notice on the first tap and sends nothing', async () => {
      await setup();
      await submitOnce();
      http.expectNone(VITALS_URL);
      const notice = sameValuesNotice()!;
      expect(notice.textContent!.replace(/\s+/g, ' ').trim()).toBe(
        'These are the same values as the last reading. Check them against the patient, then choose Save again to confirm.',
      );
      expect(notice.getAttribute('role')).toBe('status');
    });

    it('saves on the second tap and removes the notice', async () => {
      await setup();
      await submitOnce();
      await submitOnce();
      const req = expectSave();
      expect(req.request.body).toMatchObject({ bp: '168/98', pulse: '88', spo2: '97', temp: '37.0', rr: '18', note: null });
      expect(sameValuesNotice()).toBeNull();
      req.flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
    });

    it('also asks when only the note was typed (the five vitals are still the last values)', async () => {
      await setup();
      await type('note', 'Rounds done');
      await submitOnce();
      http.expectNone(VITALS_URL);
      expect(sameValuesNotice()).not.toBeNull();
    });

    it('does not appear when one value differs', async () => {
      await setup();
      await type('pulse', '89');
      await submitOnce();
      expect(sameValuesNotice()).toBeNull();
      expectSave().flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
    });

    it('appears when a value was typed and put back to the same value (F6)', async () => {
      await setup();
      await type('pulse', '89');
      await type('pulse', '88');
      await submitOnce();
      http.expectNone(VITALS_URL);
      expect(sameValuesNotice()).not.toBeNull();
      await submitOnce();
      expectSave().flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
    });

    it('compares the temperature to one decimal (37 is the same as 37.0)', async () => {
      await setup();
      await type('temp', '37');
      await submitOnce();
      http.expectNone(VITALS_URL);
      expect(sameValuesNotice()).not.toBeNull();
    });

    it('appears again after a save when the values are typed back to the saved ones', async () => {
      await setup();
      await submit();
      expectSave().flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
      await settle();
      // The saved reading has pulse 80: change it and put it back.
      await type('pulse', '91');
      await type('pulse', '80');
      await submitOnce();
      http.expectNone(VITALS_URL);
      expect(sameValuesNotice()).not.toBeNull();
    });

    it('does not appear when there is no last reading', async () => {
      await setup({ ...SHEET, latest: null, recentNotes: [] });
      await fillValid();
      await submitOnce();
      expect(sameValuesNotice()).toBeNull();
      expectSave().flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
    });

    it('starts over when any field changes after the notice', async () => {
      await setup();
      await submitOnce();
      expect(sameValuesNotice()).not.toBeNull();
      await type('temp', '37.5');
      expect(sameValuesNotice()).toBeNull();
      await submitOnce();
      expect(sameValuesNotice()).toBeNull();
      expectSave().flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
    });

    it('starts over when the note changes after the notice', async () => {
      await setup();
      await submitOnce();
      await type('note', 'x');
      expect(sameValuesNotice()).toBeNull();
      await submitOnce();
      http.expectNone(VITALS_URL);
      expect(sameValuesNotice()).not.toBeNull();
    });

    it('asks again for the next reading after a save when the values were not typed', async () => {
      await setup();
      await submit();
      expectSave().flush(savedAnswer('LOW', 0, null), { status: 201, statusText: 'Created' });
      await settle();
      // Only the note is typed: the five vitals are the values just saved.
      await type('note', 'Second round');
      await submitOnce();
      http.expectNone(VITALS_URL);
      expect(sameValuesNotice()).not.toBeNull();
    });

    it('does not ask again when retrying the same confirmed values after a lost answer', async () => {
      await setup();
      await submit();
      expectSave().error(new ProgressEvent('error'));
      await settle();
      await submitOnce();
      expect(sameValuesNotice()).toBeNull();
      expectSave().flush(savedAnswer('LOW', 0, null, {}, true), { status: 200, statusText: 'OK' });
    });

    it('does not appear while a field is invalid', async () => {
      await setup();
      await type('pulse', '');
      await submitOnce();
      expect(sameValuesNotice()).toBeNull();
      expect(errorOf('pulse')).not.toBeNull();
    });
  });

  describe('accessibility and field errors (code review N9)', () => {
    it('the score notice is a status region', async () => {
      await setup();
      expect(el().querySelector('[data-testid="nursing-ews"]')!.getAttribute('role')).toBe('status');
    });

    it('the loading line is a status region', async () => {
      await setup(null);
      expect(el().querySelector('p[aria-busy="true"]')!.getAttribute('role')).toBe('status');
      http.expectOne(SHEET_URL).flush(SHEET);
    });

    it('the failure notice is an alert', async () => {
      await setup();
      await submit();
      expectSave().error(new ProgressEvent('error'));
      await settle();
      expect(el().querySelector('[data-testid="nursing-failure"]')!.getAttribute('role')).toBe('alert');
    });

    it('clears a field error as soon as that field is edited, and only that field', async () => {
      await setup({ ...SHEET, latest: null, recentNotes: [] });
      await submit();
      expect(errorOf('bp')).not.toBeNull();
      expect(errorOf('pulse')).not.toBeNull();
      await type('bp', '1');
      expect(errorOf('bp')).toBeNull();
      expect(input('bp').getAttribute('aria-invalid')).toBeNull();
      expect(errorOf('pulse')).not.toBeNull();
    });

    it('clears the note error when the note is edited', async () => {
      await setup();
      await type('note', 'a'.repeat(1001));
      await submit();
      expect(errorOf('note')).not.toBeNull();
      await type('note', 'short');
      expect(errorOf('note')).toBeNull();
    });
  });

  describe('the tabs (SAH-32, mockup sfNsTabs)', () => {
    const MAR_URL = '/api/v1/ipd/admissions/a1/mar';
    const tab = (key: 'vit' | 'mar') => el().querySelector<HTMLButtonElement>(`#nv-tab-${key}`)!;
    const panel = (key: 'vit' | 'mar') => el().querySelector<HTMLElement>(`#nv-panel-${key}`);
    const key = (target: HTMLElement, k: string): KeyboardEvent => {
      const event = new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true });
      target.dispatchEvent(event);
      return event;
    };

    async function openMar(mar = marOf()): Promise<void> {
      tab('mar').click();
      await settle();
      http.expectOne(MAR_URL).flush(mar);
      await settle();
    }

    it('has a tablist named "Nursing" with the two tabs, icons and no count when nothing is due', async () => {
      await setup();
      const list = el().querySelector('[role=tablist]')!;
      expect(list.getAttribute('aria-label')).toBe('Nursing');
      const tabs = Array.from(list.querySelectorAll('[role=tab]'));
      expect(tabs.map((t) => (t.textContent ?? '').replace(/\s+/g, ' ').trim())).toEqual(['Vitals and notes', 'Medicines (MAR)']);
      expect(tab('vit').querySelector('app-icon')).toBeTruthy();
      expect(tab('mar').querySelector('app-icon')).toBeTruthy();
      expect(el().querySelector('[data-testid="nursing-dose-count"]')).toBeNull();
    });

    it('"Vitals and notes" is selected when the sheet opens, with a roving tabindex and a tabpanel', async () => {
      await setup();
      expect(tab('vit').getAttribute('aria-selected')).toBe('true');
      expect(tab('mar').getAttribute('aria-selected')).toBe('false');
      expect(tab('vit').tabIndex).toBe(0);
      expect(tab('mar').tabIndex).toBe(-1);
      expect(tab('vit').getAttribute('aria-controls')).toBe('nv-panel-vit');
      const p = panel('vit')!;
      expect(p.getAttribute('role')).toBe('tabpanel');
      expect(p.getAttribute('aria-labelledby')).toBe('nv-tab-vit');
      expect(panel('mar')).toBeNull();
    });

    it('aria-controls is only on the selected tab (the other panel is not in the DOM); panels are focusable', async () => {
      await setup();
      expect(tab('vit').getAttribute('aria-controls')).toBe('nv-panel-vit');
      expect(tab('mar').hasAttribute('aria-controls')).toBe(false);
      expect(panel('vit')!.getAttribute('tabindex')).toBe('0');
      await openMar();
      expect(tab('mar').getAttribute('aria-controls')).toBe('nv-panel-mar');
      expect(tab('vit').hasAttribute('aria-controls')).toBe(false);
      expect(panel('mar')!.getAttribute('tabindex')).toBe('0');
      for (const t of [tab('vit'), tab('mar')]) {
        const target = t.getAttribute('aria-controls');
        if (target) {
          expect(el().querySelector(`#${target}`)).toBeTruthy();
        }
      }
    });

    it('shows the count of due and late doses on the tab, only above 0', async () => {
      await setup({ ...SHEET, doseCount: 3 });
      const badge = el().querySelector('[data-testid="nursing-dose-count"]')!;
      expect(badge.textContent).toBe('3');
      expect(tab('mar').textContent).toContain('3');
    });

    it('shows both tabs to a role that can only view (DOCTOR)', async () => {
      await setup({ ...SHEET, canRecord: false, readOnlyReason: 'ROLE' });
      expect(tab('vit')).toBeTruthy();
      expect(tab('mar')).toBeTruthy();
    });

    it('the Medicines tab is the mockup "wide" sheet (footer stays at the bottom edge); the Vitals tab is not', async () => {
      await setup();
      const sheet = el().querySelector('[data-testid="nursing-sheet"]')!;
      expect(sheet.classList.contains('wide')).toBe(false);
      await openMar();
      expect(sheet.classList.contains('wide')).toBe(true);
      tab('vit').click();
      await settle();
      http.expectOne(SHEET_URL).flush(SHEET);
      await settle();
      expect(sheet.classList.contains('wide')).toBe(false);
    });

    it('choosing "Medicines (MAR)" reads the MAR; the title stays, the subtitle and the footer change', async () => {
      await setup({ ...SHEET, doseCount: 1 });
      await openMar();
      expect(el().querySelector('h2')!.textContent).toBe('Nursing · Asha Bendre');
      expect(el().querySelector('[data-testid="nursing-subtitle"]')!.textContent).toBe(
        'Bed G-03 · medicine round, today 11:05 AM',
      );
      expect(tab('mar').getAttribute('aria-selected')).toBe('true');
      expect(tab('mar').tabIndex).toBe(0);
      expect(tab('vit').tabIndex).toBe(-1);
      const p = panel('mar')!;
      expect(p.getAttribute('role')).toBe('tabpanel');
      expect(p.getAttribute('aria-labelledby')).toBe('nv-tab-mar');
      expect(panel('vit')).toBeNull();
      expect(p.querySelector('[data-testid="mar-tab"]')).toBeTruthy();
      expect(Array.from(el().querySelectorAll('.sheet-f button')).map((b) => b.textContent!.trim())).toEqual(['Done']);
      expect(save()).toBeNull();
    });

    it('while the MAR loads the subtitle shows the bed alone', async () => {
      await setup();
      tab('mar').click();
      await settle();
      expect(el().querySelector('[data-testid="nursing-subtitle"]')!.textContent).toBe('Bed 202');
      http.expectOne(MAR_URL).flush(marOf());
    });

    it('the count on the tab follows the MAR while it is shown', async () => {
      await setup({ ...SHEET, doseCount: 3 });
      await openMar(marOf({ doseCount: 0 }));
      expect(el().querySelector('[data-testid="nursing-dose-count"]')).toBeNull();
    });

    it('Done closes the sheet (nothing was saved)', async () => {
      await setup();
      await openMar();
      el().querySelector<HTMLButtonElement>('[data-act="mar-done"]')!.click();
      expect(dialogRef.close).toHaveBeenCalledWith(false);
    });

    it('choosing "Vitals and notes" reads the sheet again, keeps what was typed and brings the footer back', async () => {
      await setup();
      await type('pulse', '91');
      await type('note', 'Typed note');
      await openMar();
      tab('vit').click();
      await settle();
      http.expectOne(SHEET_URL).flush({ ...SHEET, doseCount: 2 });
      await settle();
      expect(input('pulse').value).toBe('91');
      expect(note().value).toBe('Typed note');
      expect(save()).not.toBeNull();
      expect(el().querySelector('[data-testid="nursing-subtitle"]')!.textContent).toBe('Bed 202 · last vitals 35 min ago');
      expect(el().querySelector('[data-testid="nursing-dose-count"]')!.textContent).toBe('2');
    });

    it('choosing the tab that is already shown sends nothing', async () => {
      await setup();
      tab('vit').click();
      await settle();
      http.expectNone(SHEET_URL);
      http.expectNone(MAR_URL);
    });

    it('ArrowRight and ArrowLeft move between the tabs and focus the new one', async () => {
      await setup();
      const right = key(tab('vit'), 'ArrowRight');
      await settle();
      expect(right.defaultPrevented).toBe(true);
      http.expectOne(MAR_URL).flush(marOf());
      await settle();
      expect(tab('mar').getAttribute('aria-selected')).toBe('true');
      expect(document.activeElement).toBe(tab('mar'));
      key(tab('mar'), 'ArrowLeft');
      await settle();
      http.expectOne(SHEET_URL).flush(SHEET);
      await settle();
      expect(tab('vit').getAttribute('aria-selected')).toBe('true');
      expect(document.activeElement).toBe(tab('vit'));
    });

    it('End goes to the last tab and Home to the first', async () => {
      await setup();
      key(tab('vit'), 'End');
      await settle();
      http.expectOne(MAR_URL).flush(marOf());
      await settle();
      expect(tab('mar').getAttribute('aria-selected')).toBe('true');
      key(tab('mar'), 'Home');
      await settle();
      http.expectOne(SHEET_URL).flush(SHEET);
      await settle();
      expect(tab('vit').getAttribute('aria-selected')).toBe('true');
    });

    it('other keys do nothing and are not prevented', async () => {
      await setup();
      const event = key(tab('vit'), 'ArrowDown');
      await settle();
      expect(event.defaultPrevented).toBe(false);
      expect(tab('vit').getAttribute('aria-selected')).toBe('true');
      http.expectNone(MAR_URL);
    });

    it('a failed re-read of the sheet shows a toast and keeps the sheet', async () => {
      await setup();
      await openMar();
      tab('vit').click();
      await settle();
      http.expectOne(SHEET_URL).flush({ type: 'x', title: 'Server error', status: 500 }, { status: 500, statusText: 'x' });
      await settle();
      expect(snack).toHaveBeenCalledWith('We could not load the Nursing sheet. Try again.', 'Close', expect.anything());
      expect(input('bp').value).toBe('168/98');
    });
  });

  describe('loading the sheet', () => {
    it('Try again is off while a load runs, so a repeated tap sends one request', async () => {
      await setup(null);
      http.expectOne(SHEET_URL).flush({ type: 'x', title: 'Server error', status: 500 }, { status: 500, statusText: 'x' });
      await settle();
      const retry = Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.trim() === 'Try again')!;
      retry.click();
      retry.click();
      await settle();
      http.expectOne(SHEET_URL).flush(SHEET);
      await settle();
      expect(el().querySelector('h2')!.textContent).toBe('Nursing · Asha Bendre');
    });
  });

  describe('toast position (sheetToast, SAH-32 follow-up)', () => {
    const originalMatchMedia = window.matchMedia;

    function viewport(phone: boolean): void {
      window.matchMedia = ((query: string) => ({ matches: phone && query.includes('max-width') })) as unknown as typeof window.matchMedia;
    }

    afterEach(() => {
      window.matchMedia = originalMatchMedia;
    });

    it('on a phone the saved toast goes to the top, 6 s, and stays announced', async () => {
      viewport(true);
      await setup();
      await submit();
      expectSave().flush(savedAnswer('LOW', 2, null), { status: 201, statusText: 'Created' });
      await settle();
      expect(snack).toHaveBeenCalledWith(expect.any(String), 'Close', { duration: 6000, verticalPosition: 'top' });
    });

    it('on a phone the save failure toast goes to the top, 8 s, and stays announced', async () => {
      viewport(true);
      await setup();
      await submit();
      expectSave().flush(
        { type: 'not-admitted', title: 'Problem', status: 409, detail: 'This patient is not admitted any more. Vitals cannot be saved.' },
        { status: 409, statusText: 'Problem' },
      );
      await settle();
      expect(snack).toHaveBeenCalledWith(expect.any(String), 'Close', { duration: 8000, verticalPosition: 'top' });
    });

    it('on a phone the failed re-read toast goes to the top, 8 s, and stays announced', async () => {
      viewport(true);
      await setup();
      const tabButton = (k: 'vit' | 'mar') => el().querySelector<HTMLButtonElement>(`#nv-tab-${k}`)!;
      tabButton('mar').click();
      await settle();
      http.expectOne('/api/v1/ipd/admissions/a1/mar').flush(marOf());
      await settle();
      tabButton('vit').click();
      await settle();
      http.expectOne(SHEET_URL).flush({ type: 'x', title: 'Server error', status: 500 }, { status: 500, statusText: 'x' });
      await settle();
      expect(snack).toHaveBeenCalledWith(expect.any(String), 'Close', { duration: 8000, verticalPosition: 'top' });
    });

    it('on a wide screen the saved toast stays at the bottom', async () => {
      viewport(false);
      await setup();
      await submit();
      expectSave().flush(savedAnswer('LOW', 2, null), { status: 201, statusText: 'Created' });
      await settle();
      expect(snack).toHaveBeenCalledWith(expect.any(String), 'Close', { duration: 6000, verticalPosition: 'bottom' });
    });
  });
});
