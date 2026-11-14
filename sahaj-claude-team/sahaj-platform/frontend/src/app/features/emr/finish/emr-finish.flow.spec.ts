import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { NEVER, of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext, record } from '../emr.fixtures';
import { EmrFinishCheck } from '../emr-finish.model';
import { EmrRecordView } from '../emr.model';
import { EmrRecordStore } from '../record/emr-record.store';
import { dentalBlock } from './finish.testing';
import { EmrFinishFlow, FinishEvent } from './emr-finish.flow';
import { ErOutcomeDialogComponent } from './er-outcome-dialog.component';
import { FinishDialogComponent } from './finish-dialog.component';

const EMR = '/api/v1/opd/emr/records';
const SHARE = { whatsapp: { available: false as const, reason: 'Needs the WhatsApp service' }, abha: { available: false as const, reason: 'Needs the ABDM service' } };

const check = (over: Partial<EmrFinishCheck> = {}): EmrFinishCheck => ({
  ready: true,
  blockers: [],
  encounterType: 'OPD_VISIT',
  effects: {},
  info: [],
  share: SHARE,
  ...over,
});

const signOutcome = {
  stage: { outcome: 'MOVED', to: 'BILLING' },
  followUp: null,
  billLines: 0,
  billTotalPaise: 0,
  ipdOrders: [],
  round: null,
  emergency: null,
  patientRecord: 'NOT_CHANGED',
  course: null,
  prints: [],
};

describe('EmrFinishFlow (SAH-51-1, SAH-51-2, SAH-67-1, SAH-67-2)', () => {
  let flow: EmrFinishFlow;
  let store: EmrRecordStore;
  let http: HttpTestingController;
  let dialogOpen: ReturnType<typeof vi.fn>;
  let dialogResult: unknown;
  let events: FinishEvent[];

  function ready(over: Partial<EmrRecordView> = {}): EmrRecordView {
    const view = record(over);
    view.layout.sections = view.layout.sections.map((s) => ({ ...s, required: false }));
    return view;
  }

  function boot(view: EmrRecordView = ready()): void {
    dialogResult = undefined;
    // The finish dialog stays open until the test says otherwise; the ER outcome question answers at once.
    dialogOpen = vi.fn((component: unknown) => ({ afterClosed: () => (component === FinishDialogComponent ? NEVER : of(dialogResult)) }));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialog, useValue: { open: dialogOpen } },
        EmrRecordStore,
        EmrFinishFlow,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(EmrRecordStore);
    flow = TestBed.inject(EmrFinishFlow);
    events = [];
    flow.events.subscribe((e) => events.push(e));
    store.load(view.id);
    http.expectOne(`${EMR}/${view.id}`).flush(view);
    http.expectOne(`${EMR}/${view.id}/case-paper`).flush(casePaperContext());
  }

  const problem = (slug: string, status: number, extra: object = {}) => ({
    body: { type: `https://sahaj.in/problems/${slug}`, title: 'x', status, ...extra },
    init: { status, statusText: 'x' },
  });

  afterEach(() => http.verify());

  describe('start', () => {
    it('does not call the server when the record is not ready, and names the first missing section', () => {
      boot(record());
      flow.begin();
      http.expectNone(`${EMR}/r1/finish-check`);
      expect(events).toEqual([{ kind: 'blocked', message: 'Still needed: chief complaints, diagnosis.', sectionKey: 'complaints' }]);
    });

    it('checks on the server and opens "Check before completing" when ready', () => {
      boot();
      flow.begin();
      http.expectOne(`${EMR}/r1/finish-check`).flush(check());
      expect(dialogOpen).toHaveBeenCalledWith(FinishDialogComponent, expect.objectContaining({ ariaLabelledBy: 'finish-title' }));
      expect(flow.check()).not.toBeNull();
    });

    it('saves unsaved entries first as AUTO (no audit entry), then checks', () => {
      boot();
      store.setValue('complaints', 'complaint', [{ itemKey: null, text: 'Fever', newToList: true }]);
      flow.begin();
      const save = http.expectOne(`${EMR}/r1/draft`);
      expect(save.request.body.saveKind).toBe('AUTO');
      save.flush(ready({ revision: 4 }));
      http.expectOne(`${EMR}/r1/finish-check`).flush(check());
    });

    it('lists what the server says is missing and opens no dialog', () => {
      boot();
      flow.begin();
      http.expectOne(`${EMR}/r1/finish-check`).flush(
        check({ ready: false, blockers: [{ key: 'STILL_NEEDED', message: 'Still needed: diagnosis.', sectionKeys: ['diagnosis'] }] }),
      );
      expect(dialogOpen).not.toHaveBeenCalled();
      expect(events).toEqual([{ kind: 'blocked', message: 'Still needed: diagnosis.', sectionKey: 'diagnosis' }]);
    });

    it('opens the dialog even when a medicine is refused, so the doctor can remove it (SAH-67-1)', () => {
      boot(ready({ encounterType: 'IPD_ROUND' }));
      flow.begin();
      http.expectOne(`${EMR}/r1/finish-check`).flush(
        check({
          ready: false,
          encounterType: 'IPD_ROUND',
          blockers: [{ key: 'IPD_ORDER_BLOCKED', message: 'A medicine is not ordered.', rowIds: ['o1'] }],
          effects: { ipdOrders: [{ rowId: 'o1', drugName: 'Amoxicillin', frequencyLabel: 'Twice a day', outcome: 'BLOCKED', texts: ['Allergy to penicillins'], checkToken: null }] },
        }),
      );
      expect(dialogOpen).toHaveBeenCalled();
      expect(flow.canConfirm()).toBe(false);
    });

    it('asks the ER outcome before anything is sent, and does nothing when it is cancelled', () => {
      boot(ready({ encounterType: 'ER_ASSESSMENT' }));
      dialogResult = null;
      flow.begin();
      expect(dialogOpen).toHaveBeenCalledWith(ErOutcomeDialogComponent, expect.anything());
      http.expectNone(`${EMR}/r1/finish-check`);
      expect(flow.starting()).toBe(false);
    });

    it('sends the chosen ER outcome with the check', () => {
      boot(ready({ encounterType: 'ER_ASSESSMENT' }));
      dialogResult = { kind: 'REFER_OUT', referTo: 'District hospital', referNote: 'CT head' };
      flow.begin();
      const req = http.expectOne(`${EMR}/r1/finish-check`);
      expect(req.request.body).toEqual({ erOutcome: { kind: 'REFER_OUT', referTo: 'District hospital', referNote: 'CT head' } });
      req.flush(check({ encounterType: 'ER_ASSESSMENT' }));
    });

    it('asks the outcome again when the server says it is required', () => {
      boot(ready({ encounterType: 'ER_ASSESSMENT' }));
      dialogResult = { kind: 'DISCHARGE' };
      flow.begin();
      http.expectOne(`${EMR}/r1/finish-check`).flush(check({ ready: false, blockers: [{ key: 'ER_OUTCOME_REQUIRED', message: 'Choose the ER outcome.' }] }));
      expect(flow.erOutcome()).toBeNull();
      expect(events[0]).toMatchObject({ kind: 'blocked', message: 'Choose the ER outcome.' });
    });
  });

  describe('choices', () => {
    it('sends only what applies: nothing for a plain OPD visit', () => {
      boot();
      expect(flow.choices()).toEqual({});
    });

    it('sends the tick "Remove from the patient record" only when it is on', () => {
      boot();
      flow.removeAllergy.set(true);
      expect(flow.choices()).toEqual({ confirmAllergyRemoval: true });
    });

    it('dental sends the next sitting; null when every row is done', () => {
      const view = ready();
      view.layout = { ...view.layout, starterKey: 'dental' };
      view.specialty = { profile: 'dental' } as never;
      boot(view);
      flow.dentalInterval.set('D14');
      expect(flow.choices()).toEqual({ dental: { nextInterval: 'D14' } });
      flow.check.set(check({ effects: { profile: dentalBlock({ allRowsDone: true, nextSittingInterval: null }) } }));
      expect(flow.choices()).toEqual({ dental: { nextInterval: null } });
    });

    it('physio sends the chosen slot, or null when none', () => {
      const view = ready();
      view.layout = { ...view.layout, starterKey: 'physio' };
      view.specialty = { profile: 'physio' } as never;
      boot(view);
      expect(flow.choices()).toEqual({ physio: { nextSlot: null } });
      flow.physioSlot.set({ date: '2026-11-18', time: '17:00' });
      expect(flow.choices()).toEqual({ physio: { nextSlot: { date: '2026-11-18', time: '17:00' } } });
    });

    it('IPD sends the reason and token of a medicine that needs one', () => {
      boot(ready({ encounterType: 'IPD_ROUND' }));
      flow.check.set(
        check({
          encounterType: 'IPD_ROUND',
          effects: { ipdOrders: [{ rowId: 'o1', drugName: 'X', frequencyLabel: 'Once a day', outcome: 'NEEDS_REASON', texts: ['Duplicate'], checkToken: 'A'.repeat(22) }] },
        }),
      );
      expect(flow.canConfirm()).toBe(false);
      flow.orderReasons.set({ o1: 'CLINICAL_NEED' });
      expect(flow.canConfirm()).toBe(true);
      expect(flow.choices()).toEqual({ ipdOrderReasons: [{ rowId: 'o1', overrideReasonKey: 'CLINICAL_NEED', checkToken: 'A'.repeat(22) }] });
    });
  });

  describe('sign', () => {
    function open(): void {
      boot();
      flow.begin();
      http.expectOne(`${EMR}/r1/finish-check`).flush(check());
    }

    it('signs with the revision, an Idempotency-Key and the choices, then adopts the signed record', () => {
      open();
      const closed = vi.fn();
      flow.sign(closed);
      const req = http.expectOne(`${EMR}/r1/sign`);
      expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
      expect(req.request.body).toEqual({ revision: 3, choices: {} });
      req.flush({ replayed: false, outcome: signOutcome, record: ready({ status: 'SIGNED', readOnly: true, readOnlyReason: 'SIGNED', revision: 4 }) });
      http.match(`${EMR}/r1/case-paper`).forEach((r) => r.flush(casePaperContext()));
      expect(closed).toHaveBeenCalled();
      expect(store.record()!.status).toBe('SIGNED');
      expect(events.at(-1)).toMatchObject({ kind: 'signed', sessionNo: null });
    });

    it('keeps the same key for a retry after a lost connection (one signature)', () => {
      open();
      flow.sign(() => undefined);
      const first = http.expectOne(`${EMR}/r1/sign`);
      const key = first.request.headers.get('Idempotency-Key');
      first.error(new ProgressEvent('error'));
      expect(flow.signError()).toContain('not signed');
      flow.sign(() => undefined);
      const second = http.expectOne(`${EMR}/r1/sign`);
      expect(second.request.headers.get('Idempotency-Key')).toBe(key);
      second.flush({ replayed: true, outcome: signOutcome, record: ready({ status: 'SIGNED', readOnly: true, readOnlyReason: 'SIGNED' }) });
      http.match(`${EMR}/r1/case-paper`).forEach((r) => r.flush(casePaperContext()));
    });

    it('makes a new key when the choices changed (the server refuses one key with two bodies)', () => {
      open();
      flow.sign(() => undefined);
      const first = http.expectOne(`${EMR}/r1/sign`);
      const key = first.request.headers.get('Idempotency-Key');
      first.error(new ProgressEvent('error'));
      flow.removeAllergy.set(true);
      flow.sign(() => undefined);
      const second = http.expectOne(`${EMR}/r1/sign`);
      expect(second.request.headers.get('Idempotency-Key')).not.toBe(key);
      second.flush(problem('already-billed', 409, { detail: 'Already billed. Ask billing to adjust.' }).body, problem('already-billed', 409).init);
    });

    it('says what failed and offers a retry when the move is refused (nothing is half done)', () => {
      open();
      flow.sign(() => undefined);
      const p = problem('workflow-move-refused', 409, { detail: 'The Queue step could not be moved.' });
      http.expectOne(`${EMR}/r1/sign`).flush(p.body, p.init);
      expect(flow.signError()).toBe('The Queue step could not be moved.');
      expect(flow.signing()).toBe(false);
      expect(store.record()!.status).toBe('DRAFT');
    });

    it('closes the dialog and lists the blockers when the server says not ready', () => {
      open();
      const closed = vi.fn();
      flow.sign(closed);
      const p = problem('emr-not-ready', 409, { blockers: [{ key: 'STILL_NEEDED', message: 'Still needed: diagnosis.', sectionKeys: ['diagnosis'] }] });
      http.expectOne(`${EMR}/r1/sign`).flush(p.body, p.init);
      expect(closed).toHaveBeenCalled();
      expect(events.at(-1)).toEqual({ kind: 'blocked', message: 'Still needed: diagnosis.', sectionKey: 'diagnosis' });
    });

    it('checks again when a medicine could not be ordered at the moment of signing', () => {
      boot(ready({ encounterType: 'IPD_ROUND' }));
      flow.begin();
      http.expectOne(`${EMR}/r1/finish-check`).flush(check({ encounterType: 'IPD_ROUND' }));
      flow.sign(() => undefined);
      const p = problem('ipd-order-not-placed', 409, { detail: 'A medicine was not ordered.', rows: [{ rowId: 'o1', outcome: 'BLOCKED' }] });
      http.expectOne(`${EMR}/r1/sign`).flush(p.body, p.init);
      expect(flow.signError()).toContain('A medicine was not ordered.');
      http.expectOne(`${EMR}/r1/finish-check`).flush(check({ encounterType: 'IPD_ROUND' }));
    });

    it('turns the screen read-only with the holder name when another doctor holds the record', () => {
      open();
      const closed = vi.fn();
      flow.sign(closed);
      const p = problem('emr-record-held', 409, { holderName: 'Dr Rao' });
      http.expectOne(`${EMR}/r1/sign`).flush(p.body, p.init);
      expect(closed).toHaveBeenCalled();
      expect(store.lostEditing()).toEqual({ reason: 'HELD', holderName: 'Dr Rao' });
    });

    it('reloads when it was already signed by someone else', () => {
      open();
      flow.sign(() => undefined);
      const p = problem('emr-record-signed', 409, { detail: 'Already signed by Dr Rao.' });
      http.expectOne(`${EMR}/r1/sign`).flush(p.body, p.init);
      expect(store.saveError()).toBe('Already signed by Dr Rao.');
      expect(events.at(-1)).toEqual({ kind: 'reload' });
    });

    it('offers Reload when the record was changed elsewhere', () => {
      open();
      flow.sign(() => undefined);
      const p = problem('emr-record-changed', 409);
      http.expectOne(`${EMR}/r1/sign`).flush(p.body, p.init);
      expect(store.conflict()).toBe(true);
      expect(flow.signError()).toContain('Reload');
    });

    it('turns an IPD round read-only with the IPD wording when IPD is read-only, and keeps the entries', () => {
      boot(ready({ encounterType: 'IPD_ROUND' }));
      flow.begin();
      http.expectOne(`${EMR}/r1/finish-check`).flush(check({ encounterType: 'IPD_ROUND' }));
      store.setValue('allergy', 'drug_allergy', 'Penicillin');
      const closed = vi.fn();
      flow.sign(closed);
      const p = problem('ipd-read-only', 409, { detail: 'IPD is read-only. Nothing can be changed.' });
      http.expectOne(`${EMR}/r1/sign`).flush(p.body, p.init);
      expect(closed).toHaveBeenCalled();
      expect(flow.signError()).toBeNull();
      expect(flow.signing()).toBe(false);
      expect(store.readOnly()).toBe(true);
      expect(store.record()!.readOnlyReason).toBe('IPD_READ_ONLY');
      expect(store.values()['allergy']?.['drug_allergy']).toBe('Penicillin');
      expect(events.at(-1)).toEqual({ kind: 'blocked', message: 'IPD is read-only. Nothing can be changed.', sectionKey: null });
    });

    it('uses the existing IPD wording when the refusal has no detail', () => {
      boot(ready({ encounterType: 'ER_ASSESSMENT' }));
      flow.erOutcome.set({ kind: 'DISCHARGE' });
      flow.check.set(check({ encounterType: 'ER_ASSESSMENT' }));
      flow.sign(() => undefined);
      const p = problem('ipd-read-only', 409);
      http.expectOne(`${EMR}/r1/sign`).flush(p.body, p.init);
      expect(events.at(-1)).toMatchObject({ kind: 'blocked', message: 'IPD is read-only. Nothing can be changed.' });
      expect(store.record()!.readOnly).toBe(true);
    });

    describe('a refused physio slot (400 on choices.physio.nextSlot)', () => {
      const slotProblem = problem('validation', 400, { fieldErrors: [{ field: 'choices.physio.nextSlot', message: 'That slot is not free any more.' }] });

      function openPhysio(): void {
        const view = ready();
        view.layout = { ...view.layout, starterKey: 'physio' };
        view.specialty = { profile: 'physio' } as never;
        boot(view);
        flow.check.set(check());
        flow.dentalInterval.set('D7');
        flow.removeAllergy.set(true);
        flow.physioSlot.set({ date: '2026-11-18', time: '17:00' });
      }

      it('shows it on the picker, reads the free slots again, drops the refused slot and keeps the other choices', () => {
        openPhysio();
        const closed = vi.fn();
        flow.sign(closed);
        http.expectOne(`${EMR}/r1/sign`).flush(slotProblem.body, slotProblem.init);
        expect(closed).not.toHaveBeenCalled();
        expect(flow.slotError()).toBe('That slot is not free any more.');
        expect(flow.signError()).toBeNull();
        http.expectOne((r) => r.url === `${EMR}/r1/free-slots`).flush([{ date: '2026-11-19', time: '10:00' }]);
        http.match(`${EMR}/r1/finish-check`).forEach((r) => r.flush(check()));
        expect(flow.freeSlots()).toEqual([{ date: '2026-11-19', time: '10:00' }]);
        expect(flow.physioSlot()).toBeNull();
        expect(flow.removeAllergy()).toBe(true);
        expect(flow.canConfirm()).toBe(false);
        flow.chooseSlot({ date: '2026-11-19', time: '10:00' });
        http.match(`${EMR}/r1/finish-check`).forEach((r) => r.flush(check()));
        expect(flow.slotError()).toBeNull();
        expect(flow.canConfirm()).toBe(true);
      });

      it('keeps a chosen slot that is still free, and does not block Confirm when no slot is free', () => {
        openPhysio();
        flow.sign(() => undefined);
        http.expectOne(`${EMR}/r1/sign`).flush(slotProblem.body, slotProblem.init);
        http.expectOne((r) => r.url === `${EMR}/r1/free-slots`).flush([{ date: '2026-11-18', time: '17:00' }]);
        http.match(`${EMR}/r1/finish-check`).forEach((r) => r.flush(check()));
        expect(flow.physioSlot()).toEqual({ date: '2026-11-18', time: '17:00' });
        flow.physioSlot.set(null);
        flow.freeSlots.set([]);
        expect(flow.canConfirm()).toBe(true);
      });
    });

    it('does not sign twice while a sign is running', () => {
      open();
      flow.sign(() => undefined);
      flow.sign(() => undefined);
      const req = http.expectOne(`${EMR}/r1/sign`);
      req.flush(problem('workflow-move-refused', 409).body, problem('workflow-move-refused', 409).init);
    });
  });
});
