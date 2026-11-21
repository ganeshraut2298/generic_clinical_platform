import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { EMR_REQUEST_TIMEOUT_MS, NO_ANSWER_TEXT } from '../emr-errors';
import { casePaperContext, record } from '../emr.fixtures';
import { EmrRecordView } from '../emr.model';
import { EmrRecordStore } from './emr-record.store';

const EMR = '/api/v1/opd/emr/records';
const FEVER = [{ itemKey: null, text: 'Fever', newToList: true }];
const FIX = 'Fix the fields marked in red, then save again.';

/**
 * BUG-emr-finish-no-call: every way a save can stop without a request says why (lastStop), and `saving`
 * is never left on after an error, a busy server, a request that never answers or a screen that closed.
 */
describe('EmrRecordStore · a save never stops silently (BUG-emr-finish-no-call, SAH-51-3)', () => {
  let store: EmrRecordStore;
  let http: HttpTestingController;

  function boot(view: EmrRecordView = record()): void {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), EmrRecordStore],
    });
    store = TestBed.inject(EmrRecordStore);
    http = TestBed.inject(HttpTestingController);
    store.load(view.id);
    http.expectOne(`${EMR}/${view.id}`).flush(view);
    http.expectOne(`${EMR}/${view.id}/case-paper`).flush(casePaperContext());
  }

  const problem = (slug: string, status: number, extra: object = {}) => ({
    body: { type: `https://sahaj.in/problems/${slug}`, title: 'x', status, ...extra },
    init: { status, statusText: 'x' },
  });

  function save(kind: 'MANUAL' | 'AUTO' = 'MANUAL'): { result: () => boolean | undefined } {
    let result: boolean | undefined;
    store.save(kind).subscribe((ok) => (result = ok));
    return { result: () => result };
  }

  afterEach(() => {
    vi.useRealTimers();
    http.match((req) => req.url.endsWith('/case-paper')).forEach((req) => req.flush(casePaperContext()));
    http.verify();
  });

  describe('stops before any request, with the reason', () => {
    it('names the first field marked in red, even in a collapsed section', () => {
      boot();
      store.toggleCollapsed('vitals');
      store.setValue('complaints', 'complaint', FEVER);
      store.setValue('vitals', 'pulse', 9_999_999);
      const s = save();
      http.expectNone(`${EMR}/r1/draft`);
      expect(s.result()).toBe(false);
      expect(store.lastStop()).toEqual({ message: FIX, sectionKey: 'vitals', fieldKey: 'pulse', reload: false });
      expect(store.saving()).toBe(false);
    });

    it('picks the first field in the order the fields are drawn', () => {
      boot();
      store.setValue('vitals', 'pulse', 9_999_999);
      store.setValue('vitals', 'bp_systolic', 9_999_999);
      save('AUTO');
      expect(store.lastStop()).toMatchObject({ sectionKey: 'vitals', fieldKey: 'bp_systolic' });
      expect(store.saveState()).toEqual({ kind: 'failed', reason: 'fix the fields marked in red' });
    });

    it('offers Reload while the record was changed elsewhere', () => {
      boot();
      store.conflict.set(true);
      store.setValue('complaints', 'complaint', FEVER);
      const s = save();
      http.expectNone(`${EMR}/r1/draft`);
      expect(s.result()).toBe(false);
      expect(store.lastStop()).toEqual({ message: 'This EMR was changed elsewhere. Reload to see the latest.', sectionKey: null, fieldKey: null, reload: true });
    });

    it('says the visit is held by another doctor', () => {
      boot();
      store.lostEditing.set({ reason: 'HELD', holderName: 'Mehta' });
      save();
      http.expectNone(`${EMR}/r1/draft`);
      expect(store.lastStop()?.message).toBe('Another doctor is editing this visit.');
    });

    it('says the visit is signed', () => {
      boot(record({ readOnly: true, readOnlyReason: 'SIGNED', status: 'SIGNED' }));
      save();
      http.expectNone(`${EMR}/r1/draft`);
      expect(store.lastStop()?.message).toBe('This visit is already signed. It is now read-only.');
    });

    it('uses the IPD wording when IPD is read-only', () => {
      boot(record({ readOnly: true, readOnlyReason: 'IPD_READ_ONLY' }));
      save();
      expect(store.lastStop()?.message).toBe('IPD is read-only. Nothing can be changed.');
    });
  });

  describe('Save draft with nothing changed (SAH-51-3)', () => {
    it('still sends the position, so the click is never a no-op, and shows the time it was saved', () => {
      boot();
      const s = save();
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect(req.request.body.sections).toEqual([]);
      expect(req.request.body.saveKind).toBeUndefined();
      req.flush(record({ revision: 4 }));
      expect(s.result()).toBe(true);
      expect(store.saveState().kind).toBe('saved');
      expect(store.lastStop()).toBeNull();
    });
  });

  describe('still saving: the second caller waits instead of being dropped', () => {
    it('an AUTO save waits and emits the first result when nothing new was typed', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      save();
      const second = save('AUTO');
      const reqs = http.match(`${EMR}/r1/draft`);
      expect(reqs).toHaveLength(1);
      expect(second.result()).toBeUndefined();
      reqs[0].flush(record({ revision: 4 }));
      expect(second.result()).toBe(true);
      http.expectNone(`${EMR}/r1/draft`);
    });

    it('an AUTO save waits, then sends what was typed meanwhile with the new revision', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      save('AUTO');
      const second = save('AUTO');
      store.setValue('vitals', 'pulse', 72);
      http.expectOne(`${EMR}/r1/draft`).flush(record({ revision: 4 }));
      const next = http.expectOne(`${EMR}/r1/draft`);
      expect(next.request.body.revision).toBe(4);
      expect(next.request.body.sections.map((s: { key: string }) => s.key)).toEqual(['vitals']);
      next.flush(record({ revision: 5 }));
      expect(second.result()).toBe(true);
      expect(store.saving()).toBe(false);
    });

    it('Save draft waits for an autosave, then sends its own save', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      save('AUTO');
      const manual = save();
      http.expectOne(`${EMR}/r1/draft`).flush(record({ revision: 4 }));
      const own = http.expectOne(`${EMR}/r1/draft`);
      expect(own.request.body.saveKind).toBeUndefined();
      own.flush(record({ revision: 5 }));
      expect(manual.result()).toBe(true);
    });

    it('a waiting caller gets false and the reason when the running save fails', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      save();
      const second = save('AUTO');
      const p = problem('emr-record-changed', 409);
      http.expectOne(`${EMR}/r1/draft`).flush(p.body, p.init);
      expect(second.result()).toBe(false);
      expect(store.lastStop()?.reload).toBe(true);
      http.expectNone(`${EMR}/r1/draft`);
    });
  });

  describe('saving is reset on every error', () => {
    it('after a server error, with the server words', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      const s = save();
      const p = problem('internal', 500, { detail: 'Something went wrong on our side.' });
      http.expectOne(`${EMR}/r1/draft`).flush(p.body, p.init);
      expect(s.result()).toBe(false);
      expect(store.saving()).toBe(false);
      expect(store.lastStop()?.message).toBe('Something went wrong on our side.');
    });

    it('after 503 database-busy (DEC-054), then the next save goes through', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      save();
      const p = problem('database-busy', 503, { detail: 'The system is busy. Try again in a few seconds.' });
      http.expectOne(`${EMR}/r1/draft`).flush(p.body, p.init);
      expect(store.saving()).toBe(false);
      expect(store.lastStop()?.message).toBe('The system is busy. Try again in a few seconds.');
      expect(store.dirty()).toBe(true);
      const again = save();
      http.expectOne(`${EMR}/r1/draft`).flush(record({ revision: 4 }));
      expect(again.result()).toBe(true);
    });

    it('after a request that never answers: "We could not reach the server. Try again." after 30 s', () => {
      vi.useFakeTimers();
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      const s = save();
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect(store.saving()).toBe(true);
      vi.advanceTimersByTime(EMR_REQUEST_TIMEOUT_MS - 1);
      expect(store.saving()).toBe(true);
      vi.advanceTimersByTime(1);
      expect(req.cancelled).toBe(true);
      expect(s.result()).toBe(false);
      expect(store.saving()).toBe(false);
      expect(store.saveError()).toBe(NO_ANSWER_TEXT);
      expect(store.lastStop()?.message).toBe(NO_ANSWER_TEXT);
      expect(store.saveState()).toEqual({ kind: 'failed', reason: 'we could not reach the server' });
      expect(store.dirty()).toBe(true);
    });

    it('after the caller left before the answer (the screen closed)', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      const sub = store.save().subscribe();
      const req = http.expectOne(`${EMR}/r1/draft`);
      sub.unsubscribe();
      expect(req.cancelled).toBe(true);
      expect(store.saving()).toBe(false);
      save();
      http.expectOne(`${EMR}/r1/draft`).flush(record({ revision: 4 }));
    });
  });
});
