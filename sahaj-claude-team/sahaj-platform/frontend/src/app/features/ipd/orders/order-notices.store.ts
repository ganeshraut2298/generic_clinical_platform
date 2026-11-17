import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, Injector, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { isRefusal } from '../ipd-refusal';
import { startVisiblePoll } from '../safety/visible-poll';
import { OrderNoticesApi } from './order-notices.api';
import { NoticeCount, OrderNoticeSummary } from './order.model';

/** Counts are read again every 30 seconds while a nursing screen is shown (OQ-76-7). */
export const NOTICES_POLL_MS = 30_000;

const NO_COUNT: NoticeCount = { count: 0, stat: 0, overdue: 0 };

/**
 * The counts of unacknowledged order notices (SAH-76-3): one summary call every 30 seconds while the
 * Admitted list, a Nursing sheet or the Safety tab is on screen (and once when such a screen opens), through
 * the existing `startVisiblePoll`, so nothing is read while the browser tab is hidden. Counts only: no names,
 * medicines or text travel in the poll. Push alerts are Later: this is a badge and a poll, nothing more.
 *
 * - A screen calls `attach(destroyRef)`; the poll runs while at least one screen is attached.
 * - `changeToken` differs from the last one: a screen that shows the list reloads it (it watches `changeToken`).
 * - A failed refresh keeps the last counts and sets `refreshFailed` ("Could not refresh. Showing data from
 *   11:05 AM.", the SAH-33 pattern; the time is `asOf` of the last good answer).
 * - A 403 on the summary (a role without the badge) stops the poll and clears the counts until a screen
 *   attaches again.
 */
@Injectable({ providedIn: 'root' })
export class OrderNoticesStore {
  // Read on first use: a card that is only drawn (the setup preview, a test) needs no HTTP client.
  private readonly injector = inject(Injector);
  private readonly doc = inject(DOCUMENT);

  readonly summary = signal<OrderNoticeSummary | null>(null);
  readonly refreshFailed = signal(false);
  readonly refused = signal(false);
  /** `performance.now()` when the last summary arrived: the stopwatch the STAT countdown counts from. */
  readonly receivedAtMs = signal(0);

  readonly changeToken = computed(() => this.summary()?.changeToken ?? null);
  readonly total = computed(() => this.summary()?.total ?? NO_COUNT);
  readonly asOf = computed(() => this.summary()?.asOf ?? null);

  private consumers = 0;
  private stopPoll: (() => void) | null = null;
  private inFlight: Subscription | null = null;

  /** The badge of one admission from the last summary; null until a summary has been read. */
  countFor(admissionId: string): NoticeCount | null {
    const s = this.summary();
    if (!s) {
      return null;
    }
    const row = s.byAdmission.find((r) => r.admissionId === admissionId);
    return row ? { count: row.count, stat: row.stat, overdue: row.overdue } : NO_COUNT;
  }

  /**
   * A screen that shows counts is open until `consumer` is destroyed. A new screen clears an earlier refusal
   * (403) and starts the poll again, so one refused screen does not leave the others without counts.
   */
  attach(consumer: DestroyRef): void {
    this.consumers += 1;
    const wasRefused = this.refused();
    this.refused.set(false);
    if (!this.stopPoll) {
      this.stopPoll = startVisiblePoll(this.doc, NOTICES_POLL_MS, () => this.refresh());
    }
    if (this.consumers === 1 || wasRefused) {
      this.refresh();
    }
    consumer.onDestroy(() => {
      this.consumers -= 1;
      if (this.consumers === 0) {
        this.stopPoll?.();
        this.stopPoll = null;
        this.inFlight?.unsubscribe();
        this.inFlight = null;
      }
    });
  }

  /** One summary call; skipped while one is running or after a refusal. */
  refresh(): void {
    if (this.refused() || (this.inFlight && !this.inFlight.closed)) {
      return;
    }
    this.inFlight = this.injector.get(OrderNoticesApi).summary().subscribe({
      next: (summary) => {
        this.summary.set(summary);
        this.receivedAtMs.set(performance.now());
        this.refreshFailed.set(false);
      },
      error: (error: unknown) => {
        if (isRefusal(error)) {
          this.refused.set(true);
          this.summary.set(null);
          this.refreshFailed.set(false);
          this.stopPoll?.();
          this.stopPoll = null;
        } else {
          this.refreshFailed.set(true);
        }
      },
    });
  }

  /** The list on screen answered with counts of its own: this admission's badge follows it until the next poll. */
  setCount(admissionId: string, count: NoticeCount): void {
    const s = this.summary();
    if (!s) {
      return;
    }
    const others = s.byAdmission.filter((r) => r.admissionId !== admissionId);
    const old = s.byAdmission.find((r) => r.admissionId === admissionId);
    this.summary.set({
      ...s,
      byAdmission: count.count === 0 ? others : [...others, { admissionId, ...count, statDueBy: old?.statDueBy ?? null }],
    });
  }
}
