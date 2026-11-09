import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { problem } from '../emr/emr.fixtures';
import { TOKEN_1, checksOf, rowOf } from './pharmacist.fixtures';
import { PharmacistStore } from './pharmacist.store';

const LIST = '/api/v1/ipd/pharmacist-checks';
const SUMMARY = `${LIST}/summary`;
const TOKEN_B = 'B'.repeat(22);

describe('PharmacistStore', () => {
  let store: PharmacistStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        PharmacistStore,
      ],
    });
    store = TestBed.inject(PharmacistStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  const list = () => http.expectOne((r) => r.url === LIST);

  describe('load', () => {
    it('shows loading until the first answer, then the rows and the count', () => {
      store.load();
      expect(store.loading()).toBe(true);
      const req = list();
      expect(req.request.params.get('limit')).toBe('50');
      req.flush(checksOf({ items: [rowOf(), rowOf({ orderId: 'o2' })], waiting: 7, nextCursor: 'c1' }));
      expect(store.loading()).toBe(false);
      expect(store.items().map((r) => r.orderId)).toEqual(['o1', 'o2']);
      expect(store.waiting()).toBe(7);
      expect(store.nextCursor()).toBe('c1');
    });

    it('keeps the rows and sets refreshFailed when a later reload fails', () => {
      store.load();
      list().flush(checksOf());
      store.load();
      expect(store.loading()).toBe(false);
      list().flush(problem('server-error', 500, 'x'), { status: 500, statusText: 'x' });
      expect(store.refreshFailed()).toBe(true);
      expect(store.items()).toHaveLength(1);
      expect(store.loadError()).toBeNull();
    });

    it('sets loadError when the first load fails', () => {
      store.load();
      list().flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'x' });
      expect(store.loadError()).toBe('Try later.');
      expect(store.loading()).toBe(false);
    });

    it('sets moduleOff on 403 module-off', () => {
      store.load();
      list().flush(problem('module-off', 403, 'IPD is off.'), { status: 403, statusText: 'x' });
      expect(store.moduleOff()).toBe(true);
      expect(store.loadError()).toBeNull();
    });

    it('clears refreshFailed on the next good answer', () => {
      store.load();
      list().flush(checksOf());
      store.load();
      list().error(new ProgressEvent('error'));
      expect(store.refreshFailed()).toBe(true);
      store.load();
      list().flush(checksOf());
      expect(store.refreshFailed()).toBe(false);
    });
  });

  describe('Show more orders', () => {
    it('loads the next 50 from the cursor and appends without repeating a row', () => {
      store.load();
      list().flush(checksOf({ items: [rowOf()], waiting: 3, nextCursor: 'c1' }));
      store.loadMore();
      expect(store.moreLoading()).toBe(true);
      const req = list();
      expect(req.request.params.get('cursor')).toBe('c1');
      expect(req.request.params.get('limit')).toBe('50');
      req.flush(checksOf({ items: [rowOf(), rowOf({ orderId: 'o2' })], waiting: 3, nextCursor: null }));
      expect(store.moreLoading()).toBe(false);
      expect(store.items().map((r) => r.orderId)).toEqual(['o1', 'o2']);
      expect(store.nextCursor()).toBeNull();
    });

    it('does nothing without a cursor or while a page is loading', () => {
      store.loadMore();
      http.expectNone(LIST);
      store.load();
      list().flush(checksOf({ nextCursor: 'c1' }));
      store.loadMore();
      store.loadMore();
      list().flush(checksOf({ nextCursor: null }));
    });

    it('keeps the rows and shows an error when a page fails', () => {
      store.load();
      list().flush(checksOf({ nextCursor: 'c1' }));
      store.loadMore();
      list().flush(problem('server-error', 500, 'x'), { status: 500, statusText: 'x' });
      expect(store.moreError()).toBe('x');
      expect(store.items()).toHaveLength(1);
      expect(store.nextCursor()).toBe('c1');
    });
  });

  describe('apply', () => {
    it('takes the first page of an action answer and the new count', () => {
      store.apply(checksOf({ items: [], waiting: 0 }));
      expect(store.items()).toEqual([]);
      expect(store.waiting()).toBe(0);
      expect(store.meta()?.verifierName).toBe('Rahul Deshmukh');
    });
  });

  describe('summary poll', () => {
    const summary = (waiting: number, changeToken = TOKEN_1) => ({ waiting, changeToken, asOf: '2026-11-06T05:47:00Z' });

    it('fills the count before the list was read, and reads nothing else', () => {
      store.pollSummary();
      http.expectOne(SUMMARY).flush(summary(4));
      expect(store.waiting()).toBe(4);
      http.expectNone(LIST);
    });

    it('does not start a second call while one is in flight', () => {
      store.pollSummary();
      store.pollSummary();
      http.expectOne(SUMMARY).flush(summary(1));
    });

    it('reloads the list when the panel is shown and the count differs from the list', () => {
      store.panelShown.set(true);
      store.load();
      list().flush(checksOf({ waiting: 1 }));
      store.pollSummary();
      http.expectOne(SUMMARY).flush(summary(2));
      list().flush(checksOf({ items: [rowOf(), rowOf({ orderId: 'o2' })], waiting: 2 }));
      expect(store.items()).toHaveLength(2);
    });

    it('does not reload when the count equals the list and nothing was seen before', () => {
      store.panelShown.set(true);
      store.load();
      list().flush(checksOf({ waiting: 1 }));
      store.pollSummary();
      http.expectOne(SUMMARY).flush(summary(1));
      http.expectNone(LIST);
    });

    it('reloads on a changed token with the same count, but only after two summaries', () => {
      store.panelShown.set(true);
      store.load();
      list().flush(checksOf({ waiting: 1 }));
      store.pollSummary();
      http.expectOne(SUMMARY).flush(summary(1, TOKEN_1));
      store.pollSummary();
      http.expectOne(SUMMARY).flush(summary(1, TOKEN_B));
      list().flush(checksOf({ waiting: 1 }));
    });

    it('updates the count but does not read names when the panel is not on screen', () => {
      store.load();
      list().flush(checksOf({ waiting: 1 }));
      store.pollSummary();
      http.expectOne(SUMMARY).flush(summary(5));
      expect(store.waiting()).toBe(5);
      http.expectNone(LIST);
    });

    it('ignores a failed poll and keeps the last count', () => {
      store.load();
      list().flush(checksOf({ waiting: 3 }));
      store.pollSummary();
      http.expectOne(SUMMARY).error(new ProgressEvent('error'));
      expect(store.waiting()).toBe(3);
    });

    it('sets moduleOff when the poll says IPD is off', () => {
      store.pollSummary();
      http.expectOne(SUMMARY).flush(problem('module-off', 403, 'off'), { status: 403, statusText: 'x' });
      expect(store.moduleOff()).toBe(true);
    });

    it('asks again after the first call has ended', () => {
      store.pollSummary();
      http.expectOne(SUMMARY).flush(summary(1));
      store.pollSummary();
      http.expectOne(SUMMARY).flush(summary(1));
    });
  });

  describe('a refused read (SAH-40)', () => {
    const summary = (waiting: number) => ({ waiting, changeToken: TOKEN_1, asOf: '2026-11-06T05:47:00Z' });
    const forbidden = () => ({ status: 403, statusText: 'Forbidden' });
    const FORBIDDEN = problem('forbidden', 403, 'You cannot see the pharmacist checks.');
    const MODULE_OFF = problem('module-off', 403, 'IPD is off.');

    function loaded(waiting = 3, nextCursor: string | null = null) {
      store.load();
      list().flush(checksOf({ items: [rowOf(), rowOf({ orderId: 'o2' })], waiting, nextCursor }));
    }

    function expectCleared() {
      expect(store.meta()).toBeNull();
      expect(store.items()).toEqual([]);
      expect(store.nextCursor()).toBeNull();
      expect(store.waiting()).toBeNull();
      expect(store.refreshFailed()).toBe(false);
      expect(store.moreLoading()).toBe(false);
      expect(store.moreError()).toBeNull();
      expect(store.refused()).toBe(true);
    }

    it('a list reload 403 forbidden clears everything and sets the first-load notice (AC 12)', () => {
      loaded(3, 'c1');
      store.load();
      list().flush(FORBIDDEN, forbidden());
      expectCleared();
      expect(store.loadError()).toBe('You cannot see the pharmacist checks.');
      expect(store.moduleOff()).toBe(false);
    });

    it('a list reload 403 without a message uses the first-load wording', () => {
      loaded();
      store.load();
      list().flush({ type: 'https://sahaj.in/problems/forbidden', title: 'forbidden', status: 403 }, forbidden());
      expect(store.loadError()).toBe('We could not load the pharmacist checks. Try again.');
    });

    it('a list reload 403 module-off clears everything and sets moduleOff (AC 13)', () => {
      loaded();
      store.load();
      list().flush(MODULE_OFF, forbidden());
      expectCleared();
      expect(store.moduleOff()).toBe(true);
      expect(store.loadError()).toBeNull();
    });

    it('a list reload that fails on the network or with 503 keeps the rows (AC 14)', () => {
      loaded();
      store.load();
      list().error(new ProgressEvent('error'));
      expect(store.items()).toHaveLength(2);
      store.load();
      list().flush(problem('unavailable', 503, 'x'), { status: 503, statusText: 'x' });
      expect(store.items()).toHaveLength(2);
      expect(store.refreshFailed()).toBe(true);
      expect(store.refused()).toBe(false);
    });

    it('the first load 403 is a refusal too, with the same visible result', () => {
      store.load();
      list().flush(FORBIDDEN, forbidden());
      expect(store.refused()).toBe(true);
      expect(store.loadError()).toBe('You cannot see the pharmacist checks.');
      expect(store.loading()).toBe(false);
    });

    it('a counts poll 403 forbidden clears the rows and sets the notice (AC 15)', () => {
      loaded();
      store.pollSummary();
      http.expectOne(SUMMARY).flush(FORBIDDEN, forbidden());
      expectCleared();
      expect(store.loadError()).toBe('You cannot see the pharmacist checks.');
    });

    it('a counts poll 403 module-off clears the rows and sets moduleOff (AC 15)', () => {
      loaded();
      store.pollSummary();
      http.expectOne(SUMMARY).flush(MODULE_OFF, forbidden());
      expectCleared();
      expect(store.moduleOff()).toBe(true);
    });

    it('a counts poll that fails on the network or with 503 clears nothing and shows nothing (AC 15)', () => {
      loaded();
      store.pollSummary();
      http.expectOne(SUMMARY).error(new ProgressEvent('error'));
      store.pollSummary();
      http.expectOne(SUMMARY).flush(problem('unavailable', 503, 'x'), { status: 503, statusText: 'x' });
      expect(store.items()).toHaveLength(2);
      expect(store.waiting()).toBe(3);
      expect(store.refused()).toBe(false);
      expect(store.loadError()).toBeNull();
      expect(store.refreshFailed()).toBe(false);
    });

    it('Show more orders 403 forbidden or module-off is a refusal (AC 16)', () => {
      loaded(5, 'c1');
      store.loadMore();
      list().flush(FORBIDDEN, forbidden());
      expectCleared();
      expect(store.loadError()).toBe('You cannot see the pharmacist checks.');

      store.load();
      list().flush(checksOf({ waiting: 5, nextCursor: 'c2' }));
      store.loadMore();
      list().flush(MODULE_OFF, forbidden());
      expectCleared();
      expect(store.moduleOff()).toBe(true);
    });

    it('Show more orders that fails on the network keeps the rows and says so (AC 16)', () => {
      loaded(5, 'c1');
      store.loadMore();
      list().error(new ProgressEvent('error'));
      expect(store.items()).toHaveLength(2);
      expect(store.moreError()).not.toBeNull();
      expect(store.refused()).toBe(false);
    });

    it('reads nothing while refused: no poll and no Show more (OQ-40-3)', () => {
      loaded(5, 'c1');
      store.pollSummary();
      http.expectOne(SUMMARY).flush(FORBIDDEN, forbidden());
      store.pollSummary();
      store.loadMore();
      http.expectNone(SUMMARY);
      http.expectNone(LIST);
    });

    it('ignores a verify or reject answer while refused (AC 19)', () => {
      loaded();
      store.load();
      list().flush(FORBIDDEN, forbidden());
      store.apply(checksOf({ items: [rowOf()], waiting: 1 }));
      expectCleared();
    });

    it('never has a meta, and so never an empty queue, after a refusal (AC 17)', () => {
      loaded();
      store.load();
      list().flush(FORBIDDEN, forbidden());
      expect(store.meta()).toBeNull();
      expect(store.loadError()).not.toBeNull();
    });

    it('load() after a refusal is a first load and shows the rows when access is back (AC 21)', () => {
      loaded();
      store.load();
      list().flush(FORBIDDEN, forbidden());
      store.load();
      expect(store.refused()).toBe(false);
      expect(store.loading()).toBe(true);
      expect(store.loadError()).toBeNull();
      list().flush(checksOf({ waiting: 1 }));
      expect(store.items()).toHaveLength(1);
      expect(store.waiting()).toBe(1);
    });

    it('load() after a refusal shows the refusal again when access is still refused (AC 21)', () => {
      loaded();
      store.load();
      list().flush(FORBIDDEN, forbidden());
      store.load();
      list().flush(FORBIDDEN, forbidden());
      expect(store.refused()).toBe(true);
      expect(store.loadError()).toBe('You cannot see the pharmacist checks.');
    });

    it('a list answer that was in flight when a poll was refused cannot land afterwards', () => {
      loaded();
      store.load();
      const inFlight = list();
      store.pollSummary();
      http.expectOne(SUMMARY).flush(FORBIDDEN, forbidden());
      expect(inFlight.cancelled).toBe(true);
      expectCleared();
    });

    it('clear() empties every field and the last summary, and stops what is in flight', () => {
      loaded(4, 'c1');
      store.pollSummary();
      http.expectOne(SUMMARY).flush(summary(4));
      store.loadMore();
      const more = list();
      store.clear();
      expect(more.cancelled).toBe(true);
      expect(store.meta()).toBeNull();
      expect(store.items()).toEqual([]);
      expect(store.nextCursor()).toBeNull();
      expect(store.waiting()).toBeNull();
      expect(store.refused()).toBe(false);
      expect(store.loadError()).toBeNull();
    });
  });

  it('stop cancels what is in flight', () => {
    store.load();
    const req = list();
    store.stop();
    expect(req.cancelled).toBe(true);
  });
});
