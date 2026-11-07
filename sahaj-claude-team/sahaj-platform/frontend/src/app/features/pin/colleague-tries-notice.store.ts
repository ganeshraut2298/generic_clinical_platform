import { Injectable, computed, inject, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { sheetToast } from '../../shared/ui/sheet-toast';
import { PinApi } from './pin.api';
import { ColleagueTriesNotice, GOT_IT_ERROR, PinStatus } from './pin.model';

/** After a failed load the shell tries again at the next route change, at most once in this time. */
export const NOTICE_RETRY_MS = 60_000;

/**
 * The notice to the PIN's owner (SAH-38, ADR-014 D8). Held for the whole signed-in session by the
 * app shell: one `GET /me/pin/colleague-tries` after the first screen is shown (never during
 * sign-in), and one more at a route change if the first failed, at most once a minute.
 *
 * - Counts and the PIN state only. Nobody is named, and no time of a try is kept on screen.
 * - A failed load shows no banner and loses nothing: the tries stay on the server.
 * - "Got it" closes the banner at once and posts `seenThrough = latestAt`. If the post fails the
 *   banner comes back with a short toast so the user can tap it again (nothing is retried silently).
 */
@Injectable({ providedIn: 'root' })
export class ColleagueTriesNoticeStore {
  private readonly api = inject(PinApi);
  private readonly snackBar = inject(MatSnackBar);

  private loading = false;
  private failed = false;
  private lastAttempt = 0;

  /** The notice to show, or null when there is none (zero unseen tries, not loaded, or dismissed). */
  readonly notice = signal<ColleagueTriesNotice | null>(null);
  readonly visible = computed(() => (this.notice()?.unseenCount ?? 0) > 0);

  load(now: number = Date.now()): void {
    if (this.loading) {
      return;
    }
    this.loading = true;
    this.lastAttempt = now;
    this.api.colleagueTries().subscribe({
      next: (notice) => {
        this.loading = false;
        this.failed = false;
        this.notice.set(notice.unseenCount > 0 ? notice : null);
      },
      error: () => {
        this.loading = false;
        this.failed = true;
      },
    });
  }

  /** Called at every route change: tries the load again only after a failure and after a minute. */
  retryAfterFailure(now: number = Date.now()): void {
    if (this.failed && !this.loading && now - this.lastAttempt >= NOTICE_RETRY_MS) {
      this.load(now);
    }
  }

  /** "Got it". Safe to call twice: the second call finds no notice. */
  dismiss(): void {
    const notice = this.notice();
    if (!notice) {
      return;
    }
    this.notice.set(null);
    if (notice.latestAt) {
      this.api.markColleagueTriesSeen(notice.latestAt).subscribe({
        error: () => {
          // Keep the banner so the user can try again; a newer notice (a reload) is not overwritten.
          this.notice.update((current) => current ?? notice);
          this.snackBar.open(GOT_IT_ERROR, 'Close', sheetToast(8000));
        },
      });
    }
  }

  /** The Set PIN sheet saved a new PIN: the PIN state line follows it; the tries stay until "Got it". */
  applyStatus(status: Pick<PinStatus, 'state' | 'lockedUntil' | 'notSetReason'>): void {
    this.notice.update((n) =>
      n
        ? { ...n, state: status.state, lockedUntil: status.lockedUntil, notSetReason: status.notSetReason }
        : n,
    );
  }

  /** Sign-out or shell teardown: nothing of this person stays for the next one. */
  reset(): void {
    this.notice.set(null);
    this.loading = false;
    this.failed = false;
    this.lastAttempt = 0;
  }
}
