import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EmergencyBadgeStore } from './emergency-badge.store';

const SUMMARY = '/api/v1/ipd/emergency/summary';

describe('EmergencyBadgeStore', () => {
  let store: EmergencyBadgeStore;
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    store = TestBed.inject(EmergencyBadgeStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    store.stop();
    http.verify();
    vi.useRealTimers();
  });

  it('shows no count until the first answer, and nothing at 0', () => {
    expect(store.count()).toBe(0);
    store.start();
    http.expectOne(SUMMARY).flush({ writable: true, queue: 0, overTarget: 0 });
    expect(store.count()).toBe(0);
  });

  it('reads the summary at once and then every 60 seconds', () => {
    store.start();
    http.expectOne(SUMMARY).flush({ writable: true, queue: 3, overTarget: 1 });
    expect(store.count()).toBe(3);

    vi.advanceTimersByTime(59_999);
    http.expectNone(SUMMARY);
    vi.advanceTimersByTime(1);
    http.expectOne(SUMMARY).flush({ writable: true, queue: 5, overTarget: 2 });
    expect(store.count()).toBe(5);

    vi.advanceTimersByTime(60_000);
    http.expectOne(SUMMARY).flush({ writable: true, queue: 4, overTarget: 0 });
    expect(store.count()).toBe(4);
  });

  it('starts only once, however often the shell asks', () => {
    store.start();
    store.start();
    http.expectOne(SUMMARY).flush({ writable: true, queue: 1, overTarget: 0 });
  });

  it('stops polling and clears the count when the menu loses the key', () => {
    store.start();
    http.expectOne(SUMMARY).flush({ writable: true, queue: 2, overTarget: 0 });
    store.stop();
    expect(store.count()).toBe(0);
    vi.advanceTimersByTime(180_000);
    http.expectNone(SUMMARY);
  });

  it('takes the counts of any queue answer while it is running', () => {
    store.start();
    http.expectOne(SUMMARY).flush({ writable: true, queue: 2, overTarget: 0 });
    store.accept({ writable: true, queue: 7, overTarget: 1 });
    expect(store.count()).toBe(7);
  });

  it('ignores a queue answer when the menu has no Emergency item', () => {
    store.accept({ writable: true, queue: 7, overTarget: 1 });
    expect(store.count()).toBe(0);
  });

  it('keeps the last count when a poll fails', () => {
    store.start();
    http.expectOne(SUMMARY).flush({ writable: true, queue: 2, overTarget: 0 });
    vi.advanceTimersByTime(60_000);
    http.expectOne(SUMMARY).flush({}, { status: 500, statusText: 'Server Error' });
    expect(store.count()).toBe(2);
  });

  it('keeps the last count through several failed polls and takes the next good answer', () => {
    store.start();
    http.expectOne(SUMMARY).flush({ writable: true, queue: 2, overTarget: 1 });
    for (let i = 0; i < 3; i++) {
      vi.advanceTimersByTime(60_000);
      http.expectOne(SUMMARY).flush({}, { status: 503, statusText: 'Unavailable' });
      expect(store.count()).toBe(2);
      expect(store.counts()).toEqual({ writable: true, queue: 2, overTarget: 1 });
    }
    vi.advanceTimersByTime(60_000);
    http.expectOne(SUMMARY).flush({ writable: true, queue: 6, overTarget: 0 });
    expect(store.count()).toBe(6);
  });

  it('keeps the first count when a failed poll comes before any answer', () => {
    store.start();
    http.expectOne(SUMMARY).flush({}, { status: 500, statusText: 'Server Error' });
    expect(store.count()).toBe(0);
    expect(store.counts()).toBeNull();
  });
});
