import { ChangeDetectionStrategy, Component, DestroyRef, Injectable, inject, signal } from '@angular/core';
import { MAT_SNACK_BAR_DATA, MatSnackBar, MatSnackBarRef } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { newClientKey } from '../../../shared/client-key';
import { sheetToast } from '../../../shared/ui/sheet-toast';
import { problemMessage } from '../../config/config-format';
import { isOutsideScope } from '../ipd-refusal';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { MarApi } from './mar.api';
import { Mar, MarDoseRow, UNDO_ERROR, givenToast, isLostAnswer, undoneToast } from './mar.model';

/** What the toast needs: the dose it can undo and the seconds the server left for it. */
export interface UndoToastData {
  admissionId: string;
  drugName: string;
  /** The recorded dose: `undoSecondsLeft` and `canUndo` are the server's. */
  dose: MarDoseRow;
  /** The dose was recorded with the manual override: the toast says so. */
  override: boolean;
}

/** After an undo or a failed one: `mar` is the MAR now, or null when the screen has to read it again. */
export interface DoseCorrected {
  admissionId: string;
  mar: Mar | null;
}

/**
 * The toast after "Given" (SAH-76-5, D6; reference): "<medicine> recorded as given" with an Undo
 * button and a countdown that starts from the seconds the server sent (`DoseRow.undoSecondsLeft`).
 * The countdown only counts down; the limit is the server's (`undo-expired`). The button leaves when
 * the seconds are used up. The toast outlives the Give sheet and the tab that opened it, so the
 * answer reaches the screen through `corrected`.
 */
@Injectable({ providedIn: 'root' })
export class UndoToast {
  private readonly snackBar = inject(MatSnackBar);
  private readonly api = inject(MarApi);
  private readonly scopeRefusals = inject(ScopeRefusals);

  readonly corrected = new Subject<DoseCorrected>();

  /** Shows the undo toast when the server allows an undo, otherwise the plain "recorded as given" toast. */
  showGiven(data: UndoToastData): void {
    if (!data.dose.canUndo || data.dose.undoSecondsLeft <= 0) {
      this.snackBar.open(givenToast(data.drugName, data.override), 'Close', sheetToast(8000, { announce: false }));
      return;
    }
    this.snackBar.openFromComponent(UndoToastComponent, {
      ...sheetToast(data.dose.undoSecondsLeft * 1000),
      data,
    });
  }

  /** One request per tap; the same key is kept while the answer is lost, so a retry is a replay. */
  undo(data: UndoToastData, clientKey: string) {
    return this.api.undo(data.dose.id, { clientKey });
  }

  done(data: UndoToastData, mar: Mar): void {
    this.snackBar.open(undoneToast(data.drugName), 'Close', sheetToast(6000, { announce: false }));
    this.corrected.next({ admissionId: data.admissionId, mar });
  }

  /** The server refused or the dose changed: say why and have the screen read the MAR again. */
  refused(data: UndoToastData, error: unknown): void {
    if (isOutsideScope(error)) {
      this.scopeRefusals.report(data.admissionId, true);
      return;
    }
    this.snackBar.open(problemMessage(error, UNDO_ERROR), 'Close', sheetToast(8000));
    this.corrected.next({ admissionId: data.admissionId, mar: null });
  }
}

@Component({
  selector: 'app-undo-toast',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="undo" data-testid="undo-toast">
      <span class="msg" data-testid="undo-message">{{ message }}</span>
      @if (secondsLeft() > 0) {
        <button
          type="button"
          class="undo-btn"
          data-act="undo-dose"
          [disabled]="running()"
          [attr.aria-busy]="running()"
          (click)="undo()"
        >
          Undo <span class="secs" data-testid="undo-seconds">{{ secondsLeft() }} s</span>
        </button>
      }
      @if (failure(); as text) {
        <span class="fail" role="alert" data-testid="undo-failure">{{ text }}</span>
      }
      <span class="bar" aria-hidden="true"><span class="fill" [style.width.%]="percent()"></span></span>
    </div>
  `,
  styles: `
    .undo {
      position: relative;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 14px;
      padding-bottom: 6px;
    }
    .msg {
      flex: 1;
      min-width: 160px;
    }
    .undo-btn {
      min-height: 44px;
      padding: 0 14px;
      border: 0;
      border-radius: 10px;
      font-weight: 700;
      font-family: inherit;
      background: var(--surface);
      color: var(--ink);
      display: inline-flex;
      align-items: center;
      gap: 8px;
      cursor: pointer;
    }
    .undo-btn:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
    .secs {
      font-size: 0.78rem;
      font-weight: 600;
      color: var(--ink-3);
    }
    .fail {
      flex-basis: 100%;
      font-size: 0.8rem;
    }
    .bar {
      position: absolute;
      left: 0;
      right: 0;
      bottom: 0;
      height: 3px;
      border-radius: 999px;
      background: color-mix(in srgb, currentColor 20%, transparent);
      overflow: hidden;
    }
    .fill {
      display: block;
      height: 3px;
      background: var(--good);
    }
  `,
})
export class UndoToastComponent {
  private readonly data = inject<UndoToastData>(MAT_SNACK_BAR_DATA);
  private readonly ref = inject(MatSnackBarRef);
  private readonly service = inject(UndoToast);
  private readonly total = Math.max(1, this.data.dose.undoSecondsLeft);
  private readonly key = newClientKey();

  protected readonly message = givenToast(this.data.drugName, this.data.override);
  protected readonly secondsLeft = signal(this.data.dose.undoSecondsLeft);
  protected readonly running = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected percent = () => (this.secondsLeft() / this.total) * 100;

  constructor() {
    const timer = setInterval(() => {
      const next = this.secondsLeft() - 1;
      this.secondsLeft.set(Math.max(0, next));
      if (next <= 0) {
        clearInterval(timer);
      }
    }, 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected undo(): void {
    if (this.running()) {
      return;
    }
    this.running.set(true);
    this.failure.set(null);
    this.service.undo(this.data, this.key).subscribe({
      next: (answer) => {
        this.running.set(false);
        this.ref.dismiss();
        this.service.done(this.data, answer.mar);
      },
      error: (error: unknown) => {
        this.running.set(false);
        if (isLostAnswer(error)) {
          // The answer was lost: nothing is shown as undone. The same key makes a retry safe.
          this.failure.set(UNDO_ERROR);
          return;
        }
        this.ref.dismiss();
        this.service.refused(this.data, error);
      },
    });
  }
}
