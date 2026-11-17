import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DestroyRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { OrderNoticesApi } from './order-notices.api';
import { TOKEN_A, TOKEN_B, count, listOf, pageOf, summaryOf } from './order-notices.fixtures';
import { NOTICES_POLL_MS, OrderNoticesStore } from './order-notices.store';

const BASE = '/api/v1/ipd';

/** A screen that can be closed on demand. */
function screen(): { ref: DestroyRef; close: () => void } {
  const callbacks: (() => void)[] = [];
  return {
    ref: { onDestroy: (cb: () => void) => (callbacks.push(cb), () => undefined) } as DestroyRef,
    close: () => callbacks.forEach((cb) => cb()),
  };
}

describe('OrderNoticesApi', () => {
  let api: OrderNoticesApi;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(OrderNoticesApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('reads the list of one admission', () => {
    api.forAdmission('a1').subscribe((l) => expect(l.admissionId).toBe('a1'));
    const req = http.expectOne(`${BASE}/admissions/a1/order-notices`);
    expect(req.request.method).toBe('GET');
    req.flush(listOf());
  });

  it('reads a page with the limit and the cursor', () => {
    api.page(50, 'c1').subscribe();
    const req = http.expectOne((r) => r.url === `${BASE}/order-notices`);
    expect(req.request.params.get('limit')).toBe('50');
    expect(req.request.params.get('cursor')).toBe('c1');
    req.flush(pageOf());
    api.page(50).subscribe();
    const first = http.expectOne((r) => r.url === `${BASE}/order-notices`);
    expect(first.request.params.has('cursor')).toBe(false);
    first.flush(pageOf());
  });

  it('reads the counts', () => {
    api.summary().subscribe();
    const req = http.expectOne(`${BASE}/order-notices/summary`);
    expect(req.request.method).toBe('GET');
    req.flush(summaryOf());
  });

  it('acknowledges with no body', () => {
    api.acknowledge('n1').subscribe();
    const req = http.expectOne(`${BASE}/order-notices/n1/acknowledge`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush(listOf());
  });
});

describe('OrderNoticesStore (SAH-76-3, OQ-76-7)', () => {
  let store: OrderNoticesStore;
  let http: HttpTestingController;
  const SUMMARY = `${BASE}/order-notices/summary`;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    store = TestBed.inject(OrderNoticesStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('reads the counts once when a screen opens and every 30 seconds after', () => {
    const s = screen();
    store.attach(s.ref);
    http.expectOne(SUMMARY).flush(summaryOf({ total: count(2, 1, 0) }));
    expect(store.total()).toEqual(count(2, 1, 0));
    vi.advanceTimersByTime(NOTICES_POLL_MS);
    http.expectOne(SUMMARY).flush(summaryOf({ changeToken: TOKEN_B }));
    expect(store.changeToken()).toBe(TOKEN_B);
    s.close();
  });

  it('shares one poll between screens and stops it when the last one closes', () => {
    const a = screen();
    const b = screen();
    store.attach(a.ref);
    http.expectOne(SUMMARY).flush(summaryOf());
    store.attach(b.ref);
    http.expectNone(SUMMARY);
    vi.advanceTimersByTime(NOTICES_POLL_MS);
    http.expectOne(SUMMARY).flush(summaryOf());
    a.close();
    vi.advanceTimersByTime(NOTICES_POLL_MS);
    http.expectOne(SUMMARY).flush(summaryOf());
    b.close();
    vi.advanceTimersByTime(NOTICES_POLL_MS * 3);
    http.expectNone(SUMMARY);
  });

  it('does not ask while the browser tab is hidden, and asks once when it shows again', () => {
    const s = screen();
    store.attach(s.ref);
    http.expectOne(SUMMARY).flush(summaryOf());
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    vi.advanceTimersByTime(NOTICES_POLL_MS * 2);
    http.expectNone(SUMMARY);
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
    document.dispatchEvent(new Event('visibilitychange'));
    http.expectOne(SUMMARY).flush(summaryOf());
    s.close();
  });

  it('keeps the last counts and says so when a refresh fails, and recovers on the next answer', () => {
    const s = screen();
    store.attach(s.ref);
    http.expectOne(SUMMARY).flush(summaryOf({ total: count(3) }));
    vi.advanceTimersByTime(NOTICES_POLL_MS);
    http.expectOne(SUMMARY).error(new ProgressEvent('error'));
    expect(store.refreshFailed()).toBe(true);
    expect(store.total()).toEqual(count(3));
    vi.advanceTimersByTime(NOTICES_POLL_MS);
    http.expectOne(SUMMARY).flush(summaryOf());
    expect(store.refreshFailed()).toBe(false);
    s.close();
  });

  it('stops and forgets the counts when the role is refused (403)', () => {
    const s = screen();
    store.attach(s.ref);
    http.expectOne(SUMMARY).flush({ type: 'https://sahaj.in/problems/forbidden', title: 'x', status: 403 }, { status: 403, statusText: 'Forbidden' });
    expect(store.refused()).toBe(true);
    expect(store.summary()).toBeNull();
    vi.advanceTimersByTime(NOTICES_POLL_MS * 2);
    http.expectNone(SUMMARY);
    s.close();
  });

  const FORBIDDEN = { type: 'https://sahaj.in/problems/forbidden', title: 'x', status: 403 };

  it('polls again for a new screen after a 403, and clears the refusal', () => {
    const a = screen();
    store.attach(a.ref);
    http.expectOne(SUMMARY).flush(FORBIDDEN, { status: 403, statusText: 'Forbidden' });
    expect(store.refused()).toBe(true);
    vi.advanceTimersByTime(NOTICES_POLL_MS * 2);
    http.expectNone(SUMMARY);

    const b = screen();
    store.attach(b.ref);
    expect(store.refused()).toBe(false);
    http.expectOne(SUMMARY).flush(summaryOf({ total: count(4, 1, 0) }));
    expect(store.total()).toEqual(count(4, 1, 0));
    vi.advanceTimersByTime(NOTICES_POLL_MS);
    http.expectOne(SUMMARY).flush(summaryOf());
    a.close();
    vi.advanceTimersByTime(NOTICES_POLL_MS);
    http.expectOne(SUMMARY).flush(summaryOf());
    b.close();
    vi.advanceTimersByTime(NOTICES_POLL_MS * 3);
    http.expectNone(SUMMARY);
  });

  it('stops again at once when the new screen is refused too', () => {
    const a = screen();
    store.attach(a.ref);
    http.expectOne(SUMMARY).flush(FORBIDDEN, { status: 403, statusText: 'Forbidden' });
    const b = screen();
    store.attach(b.ref);
    http.expectOne(SUMMARY).flush(FORBIDDEN, { status: 403, statusText: 'Forbidden' });
    expect(store.refused()).toBe(true);
    vi.advanceTimersByTime(NOTICES_POLL_MS * 2);
    http.expectNone(SUMMARY);
    a.close();
    b.close();
  });

  it('does not start a second poll when a screen opens while the first still polls', () => {
    const a = screen();
    const b = screen();
    store.attach(a.ref);
    http.expectOne(SUMMARY).flush(summaryOf());
    store.attach(b.ref);
    vi.advanceTimersByTime(NOTICES_POLL_MS);
    http.expectOne(SUMMARY).flush(summaryOf());
    a.close();
    b.close();
  });

  it('gives the count of one admission, zero when it has none, and null before any answer', () => {
    expect(store.countFor('a1')).toBeNull();
    const s = screen();
    store.attach(s.ref);
    http
      .expectOne(SUMMARY)
      .flush(summaryOf({ byAdmission: [{ admissionId: 'a1', count: 2, stat: 1, overdue: 0, statDueBy: null }] }));
    expect(store.countFor('a1')).toEqual(count(2, 1, 0));
    expect(store.countFor('a2')).toEqual(count(0));
    s.close();
  });

  it('follows the list on screen with setCount, and drops an admission that has none left', () => {
    const s = screen();
    store.attach(s.ref);
    http
      .expectOne(SUMMARY)
      .flush(summaryOf({ byAdmission: [{ admissionId: 'a1', count: 2, stat: 0, overdue: 0, statDueBy: null }], changeToken: TOKEN_A }));
    store.setCount('a1', count(1));
    expect(store.countFor('a1')).toEqual(count(1));
    store.setCount('a1', count(0));
    expect(store.summary()!.byAdmission).toEqual([]);
    expect(store.changeToken()).toBe(TOKEN_A);
    s.close();
  });

  it('skips a call while one is running', () => {
    const s = screen();
    store.attach(s.ref);
    store.refresh();
    http.expectOne(SUMMARY).flush(summaryOf());
    s.close();
  });
});
