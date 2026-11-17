import { Provider, signal } from '@angular/core';
import { NEVER } from 'rxjs';
import { OrderNoticesApi } from './order-notices.api';
import { OrderNoticesStore } from './order-notices.store';

/**
 * Test doubles for the order notice calls (SAH-76-3). A spec that mounts a Nursing sheet, the Safety tab or the
 * Admitted cards but is not about the order notices adds `provideOrderNoticesStubs()`: no poll runs, no request
 * is made, and the panel and the tab stay on "loading". Specs about the notices use the real store and API with
 * `HttpTestingController` instead.
 */
class StubOrderNoticesStore {
  readonly summary = signal(null);
  readonly refreshFailed = signal(false);
  readonly refused = signal(false);
  readonly receivedAtMs = signal(0);
  readonly changeToken = signal<string | null>(null);
  readonly total = signal({ count: 0, stat: 0, overdue: 0 });
  readonly asOf = signal<string | null>(null);
  countFor(): null {
    return null;
  }
  attach(): void {
    return;
  }
  refresh(): void {
    return;
  }
  setCount(): void {
    return;
  }
}

export function provideOrderNoticesStubs(): Provider[] {
  return [
    { provide: OrderNoticesStore, useClass: StubOrderNoticesStore },
    {
      provide: OrderNoticesApi,
      useValue: { forAdmission: () => NEVER, page: () => NEVER, summary: () => NEVER, acknowledge: () => NEVER },
    },
  ];
}
