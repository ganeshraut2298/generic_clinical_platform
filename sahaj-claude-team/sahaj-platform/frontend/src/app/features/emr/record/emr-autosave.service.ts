import { DestroyRef, Injectable, inject } from '@angular/core';
import { EmrRecordStore } from './emr-record.store';

/** SAH-51-3: the draft is saved every 30 seconds while there are changes. */
export const AUTOSAVE_MS = 30_000;

/**
 * The autosave clock of one open EMR screen (SAH-51-3, ADR-025 D5): a tick every 30 seconds saves the
 * changes (or renews the edit hold when there are none), "Save draft" restarts the clock, and when the
 * browser says the connection is back the pending entries are saved at once. It holds no values:
 * the entries stay in the record store, in memory only, and nothing is written to browser storage.
 */
@Injectable()
export class EmrAutosave {
  private readonly store = inject(EmrRecordStore);
  private readonly destroyRef = inject(DestroyRef);
  private timer: ReturnType<typeof setInterval> | null = null;
  private readonly onOnline = (): void => this.store.autosaveTick();

  constructor() {
    this.destroyRef.onDestroy(() => this.stop());
  }

  start(): void {
    this.stop();
    this.timer = setInterval(() => this.store.autosaveTick(), AUTOSAVE_MS);
    globalThis.addEventListener?.('online', this.onOnline);
  }

  /** A manual save has just happened: the next tick is 30 seconds from now. */
  restart(): void {
    if (this.timer) {
      this.start();
    }
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    globalThis.removeEventListener?.('online', this.onOnline);
  }
}
