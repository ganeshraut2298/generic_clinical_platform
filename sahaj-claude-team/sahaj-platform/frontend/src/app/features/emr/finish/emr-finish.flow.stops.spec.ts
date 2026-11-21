import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { EMR_REQUEST_TIMEOUT_MS, NO_ANSWER_TEXT } from '../emr-errors';
import { casePaperContext, record } from '../emr.fixtures';
import { EmrFinishCheck, EmrSignOutcome } from '../emr-finish.model';
import { EmrRecordView } from '../emr.model';
import { EmrRecordStore } from '../record/emr-record.store';
import { EmrFinishFlow, FinishEvent } from './emr-finish.flow';
import { FinishDialogComponent } from './finish-dialog.component';

const EMR = '/api/v1/opd/emr/records';
const FEVER = [{ itemKey: null, text: 'Fever', newToList: true }];

const check = (over: Partial<EmrFinishCheck> = {}): EmrFinishCheck => ({
  ready: true,
  blockers: [],
  encounterType: 'OPD_VISIT',
  effects: {},
  info: [],
  share: { whatsapp: { available: false, reason: 'x' }, abha: { available: false, reason: 'y' } },
  ...over,
});

const OUTCOME: EmrSignOutcome = {
  stage: { outcome: 'MOVED', to: 'BILLING' },
  followUp: null,
  billLines: 1,
  billTotalPaise: 50000,
  ipdOrders: [],
  round: null,
  emergency: null,
  patientRecord: 'NOT_CHANGED',
  course: null,
  prints: [],
} as unknown as EmrSignOutcome;

/**
 * BUG-emr-finish-no-call: Complete never stops without saying why, `starting` is always reset, and on
 * success it goes save → finish check → "Check before completing" → sign (SAH-51-1, SAH-51-2).
 */
describe('EmrFinishFlow · Complete never stops silently (BUG-emr-finish-no-call)', () => {
  let flow: EmrFinishFlow;
  let store: EmrRecordStore;
  let http: HttpTestingController;
  let dialogOpen: ReturnType<typeof vi.fn>;
  let dialogClosed: Subject<unknown>;
  let events: FinishEvent[];

  function ready(over: Partial<EmrRecordView> = {}): EmrRecordView {
    const view = record(over);
    view.layout.sections = view.layout.sections.map((s) => ({ ...s, required: false }));
    return view;
  }

  function boot(view: EmrRecordView = ready()): void {
    dialogClosed = new Subject();
    dialogOpen = vi.fn(() => ({ afterClosed: () => dialogClosed }));
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

  afterEach(() => {
    vi.useRealTimers();
    http.match((req) => req.url.endsWith('/case-paper')).forEach((req) => req.flush(casePaperContext()));
    http.verify();
  });

  it('does it all on success: save, check, "Check before completing", sign, then the signed event for the queue', () => {
    boot();
    store.setValue('complaints', 'complaint', FEVER);
    flow.begin();
    expect(flow.starting()).toBe(true);
    const save = http.expectOne(`${EMR}/r1/draft`);
    expect(save.request.body.saveKind).toBe('AUTO');
    save.flush(ready({ revision: 4 }));
    http.expectOne(`${EMR}/r1/finish-check`).flush(check());
    expect(flow.starting()).toBe(false);
    expect(dialogOpen).toHaveBeenCalledWith(FinishDialogComponent, expect.anything());
    const closed = vi.fn();
    flow.sign(closed);
    const sign = http.expectOne(`${EMR}/r1/sign`);
    expect(sign.request.body.revision).toBe(4);
    sign.flush({ replayed: false, outcome: OUTCOME, record: ready({ status: 'SIGNED', readOnly: true, readOnlyReason: 'SIGNED', revision: 5 }) });
    expect(closed).toHaveBeenCalled();
    expect(events.at(-1)).toMatchObject({ kind: 'signed', outcome: { stage: { outcome: 'MOVED', to: 'BILLING' } } });
  });

  describe('the save before the check stops: the page is told why', () => {
    it('a field marked in red in a collapsed section: the field to open, no request, Complete free again', () => {
      boot();
      store.toggleCollapsed('vitals');
      store.setValue('vitals', 'pulse', 9_999_999);
      flow.begin();
      http.expectNone(`${EMR}/r1/draft`);
      http.expectNone(`${EMR}/r1/finish-check`);
      expect(events).toEqual([
        { kind: 'save-stopped', stop: { message: 'Fix the fields marked in red, then save again.', sectionKey: 'vitals', fieldKey: 'pulse', reload: false } },
      ]);
      expect(flow.starting()).toBe(false);
    });

    it('a record changed elsewhere: the conflict message with Reload', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      flow.begin();
      const p = problem('emr-record-changed', 409);
      http.expectOne(`${EMR}/r1/draft`).flush(p.body, p.init);
      http.expectNone(`${EMR}/r1/finish-check`);
      expect(events[0]).toEqual({
        kind: 'save-stopped',
        stop: { message: 'This EMR was changed elsewhere. Reload to see the latest.', sectionKey: null, fieldKey: null, reload: true },
      });
      expect(flow.starting()).toBe(false);
    });

    it('a conflict already on screen: said again, nothing sent', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.conflict.set(true);
      flow.begin();
      http.expectNone(`${EMR}/r1/draft`);
      expect(events[0]).toMatchObject({ kind: 'save-stopped', stop: { reload: true } });
    });

    it('503 database-busy (DEC-054): the server words, and Complete works on the next tap', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      flow.begin();
      const p = problem('database-busy', 503, { detail: 'The system is busy. Try again in a few seconds.' });
      http.expectOne(`${EMR}/r1/draft`).flush(p.body, p.init);
      expect(events[0]).toMatchObject({ kind: 'save-stopped', stop: { message: 'The system is busy. Try again in a few seconds.' } });
      expect(flow.starting()).toBe(false);
      expect(store.saving()).toBe(false);
      flow.begin();
      http.expectOne(`${EMR}/r1/draft`).flush(ready({ revision: 4 }));
      http.expectOne(`${EMR}/r1/finish-check`).flush(check());
      expect(dialogOpen).toHaveBeenCalled();
    });

    it('a save that never answers: "We could not reach the server. Try again." after 30 s', () => {
      vi.useFakeTimers();
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      flow.begin();
      http.expectOne(`${EMR}/r1/draft`);
      vi.advanceTimersByTime(EMR_REQUEST_TIMEOUT_MS);
      expect(events[0]).toMatchObject({ kind: 'save-stopped', stop: { message: NO_ANSWER_TEXT } });
      expect(flow.starting()).toBe(false);
      expect(store.saving()).toBe(false);
    });
  });

  describe('still saving: Complete waits for the save, then goes on', () => {
    it('an autosave is running: no second save of the same entries, the check follows its answer', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.autosaveTick();
      flow.begin();
      const reqs = http.match(`${EMR}/r1/draft`);
      expect(reqs).toHaveLength(1);
      http.expectNone(`${EMR}/r1/finish-check`);
      reqs[0].flush(ready({ revision: 4 }));
      http.expectOne(`${EMR}/r1/finish-check`).flush(check());
      expect(dialogOpen).toHaveBeenCalled();
      expect(flow.starting()).toBe(false);
    });
  });

  describe('the check itself', () => {
    it('a check that never answers says so and frees Complete', () => {
      vi.useFakeTimers();
      boot();
      flow.begin();
      http.expectOne(`${EMR}/r1/finish-check`);
      vi.advanceTimersByTime(EMR_REQUEST_TIMEOUT_MS);
      expect(events).toEqual([{ kind: 'blocked', message: NO_ANSWER_TEXT, sectionKey: null }]);
      expect(flow.starting()).toBe(false);
    });

    it('a failed check says the server words and frees Complete', () => {
      boot();
      flow.begin();
      const p = problem('internal', 500, { detail: 'Something went wrong on our side.' });
      http.expectOne(`${EMR}/r1/finish-check`).flush(p.body, p.init);
      expect(events).toEqual([{ kind: 'blocked', message: 'Something went wrong on our side.', sectionKey: null }]);
      expect(flow.starting()).toBe(false);
    });
  });

  describe('Complete on a read-only record says why', () => {
    it('another doctor holds the visit', () => {
      boot();
      store.lostEditing.set({ reason: 'HELD', holderName: 'Mehta' });
      flow.begin();
      http.expectNone(`${EMR}/r1/finish-check`);
      expect(events).toEqual([{ kind: 'blocked', message: 'Another doctor is editing this visit.', sectionKey: null }]);
    });
  });

  describe('sign', () => {
    function open(): void {
      boot();
      flow.begin();
      http.expectOne(`${EMR}/r1/finish-check`).flush(check());
    }

    it('a sign that never answers: the words, signing reset, and the retry keeps the same key', () => {
      vi.useFakeTimers();
      open();
      flow.sign(() => undefined);
      const first = http.expectOne(`${EMR}/r1/sign`);
      const key = first.request.headers.get('Idempotency-Key');
      expect(flow.signing()).toBe(true);
      vi.advanceTimersByTime(EMR_REQUEST_TIMEOUT_MS);
      expect(flow.signing()).toBe(false);
      expect(store.signInFlight()).toBe(false);
      expect(flow.signError()).toBe(NO_ANSWER_TEXT);
      flow.sign(() => undefined);
      const second = http.expectOne(`${EMR}/r1/sign`);
      expect(second.request.headers.get('Idempotency-Key')).toBe(key);
      second.flush({ replayed: true, outcome: OUTCOME, record: ready({ status: 'SIGNED', readOnly: true, readOnlyReason: 'SIGNED' }) });
    });

    it('503 database-busy on sign: the server words, signing reset', () => {
      open();
      flow.sign(() => undefined);
      const p = problem('database-busy', 503, { detail: 'The system is busy. Try again in a few seconds.' });
      http.expectOne(`${EMR}/r1/sign`).flush(p.body, p.init);
      expect(flow.signing()).toBe(false);
      expect(flow.signError()).toBe('The system is busy. Try again in a few seconds.');
    });
  });
});
