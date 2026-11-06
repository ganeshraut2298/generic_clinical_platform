import { Injectable, computed, inject, signal } from '@angular/core';
import { EmergencyApi } from './emergency.api';
import { EmergencyCounts } from './emergency.model';

export const BADGE_REFRESH_MS = 60_000;

/**
 * Count on the menu item "Emergency" (ADR-005 D3). The shell starts it while `/me.menu` has the
 * key `emergency`: one call to `GET /ipd/emergency/summary` at once, then every 60 seconds. Any
 * `EmergencyQueue` answer the app sees is passed to `accept`, so the badge follows the page.
 * Counts only: no name or complaint is held here. A failed call keeps the last count.
 */
@Injectable({ providedIn: 'root' })
export class EmergencyBadgeStore {
  private readonly api = inject(EmergencyApi);
  private timer: ReturnType<typeof setInterval> | null = null;

  readonly counts = signal<EmergencyCounts | null>(null);
  /** The badge is shown only above 0. */
  readonly count = computed(() => this.counts()?.queue ?? 0);

  start(): void {
    if (this.timer !== null) {
      return;
    }
    this.refresh();
    this.timer = setInterval(() => this.refresh(), BADGE_REFRESH_MS);
  }

  stop(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.counts.set(null);
  }

  accept(counts: EmergencyCounts): void {
    if (this.timer !== null) {
      this.counts.set(counts);
    }
  }

  private refresh(): void {
    this.api.summary().subscribe({
      next: (counts) => this.counts.set(counts),
      error: () => undefined,
    });
  }
}
