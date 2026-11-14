import { Injectable, inject, signal } from '@angular/core';
import { SpecialtyApi } from '../specialty.api';

export const SECOND_CHECKS_REFRESH_MS = 60_000;

/**
 * The count on the menu item "Second checks" (ADR-023 D13.5): `total` of `GET /api/v1/opd/emr/chemo-checks`,
 * the chemotherapy orders waiting for the signed-in user. The shell starts it while `/me.menu` has the
 * key `secondchecks`: one call at once, then every 60 seconds. The list page passes its own total to
 * `accept`, so the badge follows the page. Only a number is held here: no name or order. A failed call
 * keeps the last count.
 */
@Injectable({ providedIn: 'root' })
export class SecondChecksBadgeStore {
  private readonly api = inject(SpecialtyApi);
  private timer: ReturnType<typeof setInterval> | null = null;

  readonly count = signal(0);

  start(): void {
    if (this.timer !== null) {
      return;
    }
    this.refresh();
    this.timer = setInterval(() => this.refresh(), SECOND_CHECKS_REFRESH_MS);
  }

  stop(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.count.set(0);
  }

  accept(total: number): void {
    if (this.timer !== null) {
      this.count.set(total);
    }
  }

  private refresh(): void {
    this.api.listMyChemoChecks().subscribe({
      next: (page) => this.count.set(page.total),
      error: () => undefined,
    });
  }
}
