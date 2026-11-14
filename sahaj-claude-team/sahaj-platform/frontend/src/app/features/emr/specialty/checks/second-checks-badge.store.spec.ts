import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SecondChecksBadgeStore } from './second-checks-badge.store';

const CHECKS = '/api/v1/opd/emr/chemo-checks';
const page = (total: number) => ({ items: [], total, page: 0, pageSize: 20 });

describe('SecondChecksBadgeStore (ADR-023 D13.5)', () => {
  let store: SecondChecksBadgeStore;
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    store = TestBed.inject(SecondChecksBadgeStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    store.stop();
    http.verify();
    vi.useRealTimers();
  });

  it('shows nothing until the first answer, then the total of the checker’s list', () => {
    expect(store.count()).toBe(0);
    store.start();
    http.expectOne(CHECKS).flush(page(2));
    expect(store.count()).toBe(2);
  });

  it('asks again every 60 seconds, once only however often it is started, and stops cleanly', () => {
    store.start();
    store.start();
    http.expectOne(CHECKS).flush(page(1));
    vi.advanceTimersByTime(60_000);
    http.expectOne(CHECKS).flush(page(4));
    expect(store.count()).toBe(4);
    store.stop();
    expect(store.count()).toBe(0);
    vi.advanceTimersByTime(120_000);
    http.expectNone(CHECKS);
  });

  it('keeps the last count when a call fails, and follows the list page through accept', () => {
    store.start();
    http.expectOne(CHECKS).flush(page(3));
    vi.advanceTimersByTime(60_000);
    http.expectOne(CHECKS).flush({}, { status: 500, statusText: 'Server Error' });
    expect(store.count()).toBe(3);
    store.accept(1);
    expect(store.count()).toBe(1);
  });

  it('ignores a page total when it was never started (the role has no such menu item)', () => {
    store.accept(5);
    expect(store.count()).toBe(0);
  });
});
