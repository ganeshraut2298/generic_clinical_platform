import { Injectable, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { problemMessage } from '../../config/config-format';
import { isModuleOff, isRefusal } from '../ipd-refusal';
import { PharmacistApi } from './pharmacist.api';
import {
  CHECKS_LOAD_ERROR,
  MORE_ERROR,
  PharmacistCheckRow,
  PharmacistChecks,
  PharmacistChecksSummary,
  summaryChanged,
} from './pharmacist.model';

/** First page of the queue (the contract default). */
export const FIRST_PAGE = 50;
/** "Show more orders" (contract wording): 50 more from the cursor of the last page. */
export const NEXT_PAGE = 50;

/**
 * The pharmacist queue of one open IPD page (SAH-31): the rows, the count for the Safety tab and
 * the tile, the counts-only summary poll and "Show more orders". Provided by the page (not the
 * root), so patient names and allergy text are dropped when the page is left.
 *
 * - `waiting` comes from every answer that has it (the list, the summary, the action answers); the
 *   tab number and the tile read only this.
 * - The poll asks only for counts. When the count or the change token differs from the last summary
 *   (or, for the first one, the count differs from the list) and the panel is on screen, the list is
 *   read again, so names never travel in a poll.
 * - A reload shows the first page again; rows that left the queue disappear, and "Show more orders"
 *   starts over from the new cursor.
 * - A failed reload (network error, 5xx) keeps the rows and sets `refreshFailed`.
 * - A 403 on any read (the list, "Show more orders" or the counts poll) is a refusal (SAH-40, DEC-025
 *   G-2): the rows, the cursor, the count and the as-of time leave the memory, and the panel shows the
 *   notice of the first load; IPD off (403 `module-off`) sets `moduleOff` so the page shows its lock
 *   notice. While refused the store reads nothing (no poll, no "Show more"), and a verify or reject
 *   answer is ignored: only `load()` (Try again) starts again.
 */
@Injectable()
export class PharmacistStore {
  private readonly api = inject(PharmacistApi);

  /** The newest first-page answer: state, as-of time, verifier name and reject reasons. */
  readonly meta = signal<PharmacistChecks | null>(null);
  readonly items = signal<PharmacistCheckRow[]>([]);
  readonly nextCursor = signal<string | null>(null);
  /** Orders waiting; null until something has been read. */
  readonly waiting = signal<number | null>(null);
  readonly loading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly refreshFailed = signal(false);
  readonly moreLoading = signal(false);
  readonly moreError = signal<string | null>(null);
  readonly moduleOff = signal(false);
  /** SAH-40: a read was answered 403; nothing is read again until `load()`. */
  readonly refused = signal(false);
  /** True while the panel is drawn: only then does a changed summary reload the list. */
  readonly panelShown = signal(false);

  private lastSummary: PharmacistChecksSummary | null = null;
  private listSub: Subscription | null = null;
  private moreSub: Subscription | null = null;
  private summarySub: Subscription | null = null;

  /** First page. Shows "loading" only while nothing has been read yet. */
  load(): void {
    this.listSub?.unsubscribe();
    this.moreSub?.unsubscribe();
    this.moreLoading.set(false);
    this.refused.set(false);
    const first = this.meta() === null;
    if (first) {
      this.loading.set(true);
    }
    this.loadError.set(null);
    this.listSub = this.api.checks(FIRST_PAGE).subscribe({
      next: (checks) => this.apply(checks),
      error: (error: unknown) => {
        this.loading.set(false);
        if (isRefusal(error)) {
          this.refuse(error);
        } else if (first) {
          this.loadError.set(problemMessage(error, CHECKS_LOAD_ERROR));
        } else {
          this.refreshFailed.set(true);
        }
      },
    });
  }

  /** Takes a first-page answer: from a read, or from the verify and reject answers. */
  apply(checks: PharmacistChecks): void {
    if (this.refused()) {
      return;
    }
    this.moreSub?.unsubscribe();
    this.moreLoading.set(false);
    this.moreError.set(null);
    this.meta.set(checks);
    this.items.set(checks.items);
    this.nextCursor.set(checks.nextCursor);
    this.waiting.set(checks.waiting);
    this.loading.set(false);
    this.loadError.set(null);
    this.refreshFailed.set(false);
    // The token of an older read is not comparable with this list: the next summary starts over.
    this.lastSummary = null;
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (cursor === null || this.moreLoading() || this.refused()) {
      return;
    }
    this.moreLoading.set(true);
    this.moreError.set(null);
    this.moreSub = this.api.checks(NEXT_PAGE, cursor).subscribe({
      next: (page) => {
        this.moreLoading.set(false);
        const known = new Set(this.items().map((r) => r.orderId));
        this.items.set([...this.items(), ...page.items.filter((r) => !known.has(r.orderId))]);
        this.nextCursor.set(page.nextCursor);
        this.waiting.set(page.waiting);
      },
      error: (error: unknown) => {
        this.moreLoading.set(false);
        if (isRefusal(error)) {
          this.refuse(error);
        } else {
          this.moreError.set(problemMessage(error, MORE_ERROR));
        }
      },
    });
  }

  /** One summary call (the page runs it every 60 seconds while the browser tab is visible). */
  pollSummary(): void {
    if (this.refused() || (this.summarySub && !this.summarySub.closed)) {
      return;
    }
    this.summarySub = this.api.summary().subscribe({
      next: (summary) => {
        const changed = summaryChanged(this.lastSummary, summary, this.waiting());
        this.lastSummary = summary;
        this.waiting.set(summary.waiting);
        if (changed && this.panelShown()) {
          this.load();
        }
      },
      error: (error: unknown) => {
        if (isRefusal(error)) {
          this.refuse(error);
        }
      },
    });
  }

  /**
   * Empties everything patient-related that the store holds (rows, cursor, count, as-of time, the
   * last summary) and stops what is in flight. No notice: the page calls this when its own list is
   * refused (SAH-40).
   */
  clear(): void {
    this.stop();
    this.meta.set(null);
    this.items.set([]);
    this.nextCursor.set(null);
    this.waiting.set(null);
    this.lastSummary = null;
    this.loading.set(false);
    this.refreshFailed.set(false);
    this.moreLoading.set(false);
    this.moreError.set(null);
  }

  /** The panel is gone: stop what is in flight. */
  stop(): void {
    this.listSub?.unsubscribe();
    this.moreSub?.unsubscribe();
    this.summarySub?.unsubscribe();
  }

  /** A 403 on a read: clear, then the lock notice (IPD off) or the error notice of the first load. */
  private refuse(error: unknown): void {
    this.clear();
    this.refused.set(true);
    if (isModuleOff(error)) {
      this.loadError.set(null);
      this.moduleOff.set(true);
    } else {
      this.loadError.set(problemMessage(error, CHECKS_LOAD_ERROR));
    }
  }
}
