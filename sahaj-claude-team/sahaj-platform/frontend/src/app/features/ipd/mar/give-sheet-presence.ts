import { Injectable } from '@angular/core';

/**
 * Which give sheets are open (DEC-068: no toast over a give sheet). The give sheet registers itself when it is created
 * and releases when it is destroyed, so the rule is about the give sheet only: the Nursing sheet and the admission
 * sheet behind the MAR do not hold a toast back. A late Undo answer asks `whenNoneOpen` and is shown at once, or when
 * the last give sheet has closed. It is never dropped.
 */
@Injectable({ providedIn: 'root' })
export class GiveSheetPresence {
  private open = 0;
  private waiting: (() => void)[] = [];

  /** A give sheet has opened. Call the returned function once, when it is gone. */
  enter(): () => void {
    this.open += 1;
    let released = false;
    return () => {
      if (released) {
        return;
      }
      released = true;
      this.open -= 1;
      this.flush();
    };
  }

  /** Runs `show` now when no give sheet is open, otherwise once the last one has closed. */
  whenNoneOpen(show: () => void): void {
    if (this.open === 0) {
      show();
      return;
    }
    this.waiting.push(show);
  }

  private flush(): void {
    if (this.open > 0) {
      return;
    }
    const due = this.waiting;
    this.waiting = [];
    due.forEach((show) => show());
  }
}
