import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatSnackBar } from '@angular/material/snack-bar';
import { newClientKey } from '../../../shared/client-key';
import { sheetToast } from '../../../shared/ui/sheet-toast';
import { problemMessage } from '../../config/config-format';
import { isOutsideScope } from '../ipd-refusal';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { MarApi } from './mar.api';
import {
  IN_ERROR_ERROR,
  IN_ERROR_HEAD,
  Mar,
  MarDoseRow,
  MarOrder,
  MarSlot,
  clockText,
  doseDetailLine,
  inErrorToast,
  isLostAnswer,
} from './mar.model';

/**
 * The panel under the medicines when a given dose is tapped (SAH-76-5, D6; reference "errDose"): the
 * dose details and, for a nurse who may correct it (`dose.canMarkInError`, the server's), the four
 * reasons of "Mark as recorded in error · the original entry is kept, struck through, with your name,
 * time and reason". Choosing a reason sends it at once; the MAR in the answer replaces the one on
 * screen, where the original stays struck through and the slot can be given again.
 *
 * clientKey: made when the panel opens, kept for a retry of the same reason, new for another reason.
 * A refusal (`dose-state`, `error-window-closed`, 403) reads the MAR again. Names are on screen only.
 */
@Component({
  selector: 'app-dose-error-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="errpanel" aria-label="Recorded in error" data-testid="dose-error-panel">
      <div class="errhead">
        <b id="err-title" data-testid="dose-error-title">{{ title() }}</b>
        <button type="button" class="linkbtn closebtn" data-act="err-close" [disabled]="running()" (click)="closed.emit()">
          Close
        </button>
      </div>
      <span class="muted" data-testid="dose-error-detail">{{ detail() }}</span>
      @if (dose().canMarkInError) {
        <span class="errask" data-testid="dose-error-ask">{{ head }}</span>
        <div class="chips" role="group" aria-labelledby="err-title" data-testid="dose-error-reasons">
          @for (r of reasons(); track r.key) {
            <button
              type="button"
              class="chip crit"
              data-testid="dose-error-reason"
              [attr.data-key]="r.key"
              [disabled]="running()"
              (click)="mark(r.key, r.label)"
            >
              {{ r.label }}
            </button>
          }
        </div>
      }
      @if (failure(); as text) {
        <span class="errfail" role="alert" data-testid="dose-error-failure">{{ text }}</span>
      }
    </section>
  `,
  styles: `
    .errpanel {
      border: 1px solid var(--crit);
      background: color-mix(in srgb, var(--crit) 5%, transparent);
      border-radius: 14px;
      padding: 12px 14px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .errhead {
      display: flex;
      gap: 10px;
      align-items: center;
    }
    .errhead b {
      flex: 1;
      font-size: 0.9rem;
    }
    .closebtn {
      min-height: 44px;
      min-width: 44px;
      color: var(--ink-2);
    }
    .errask {
      font-size: 0.78rem;
      font-weight: 700;
    }
    .chips {
      display: flex;
      gap: 6px;
      flex-wrap: wrap;
    }
    .chip {
      min-height: 44px;
      padding: 0 12px;
      border-radius: 999px;
      border: 1px solid var(--crit);
      background: var(--surface);
      color: var(--crit);
      font: inherit;
      font-size: 0.82rem;
      font-weight: 600;
      cursor: pointer;
    }
    .chip:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
    .errfail {
      color: var(--crit);
      font-size: 0.82rem;
    }
  `,
})
export class DoseErrorPanelComponent {
  private readonly api = inject(MarApi);
  private readonly snackBar = inject(MatSnackBar);
  private readonly scopeRefusals = inject(ScopeRefusals);
  private readonly destroyRef = inject(DestroyRef);

  readonly admissionId = input.required<string>();
  readonly order = input.required<MarOrder>();
  readonly slot = input.required<MarSlot>();
  readonly dose = input.required<MarDoseRow>();
  readonly reasons = input.required<{ key: string; label: string }[]>();

  /** The MAR now: the dose is marked and the slot is back to its clock state. */
  readonly corrected = output<Mar>();
  readonly closed = output<void>();
  /** The dose changed meanwhile or the server refused: the tab reads the MAR again. */
  readonly reload = output<void>();

  protected readonly head = IN_ERROR_HEAD;
  protected readonly running = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly title = computed(() => `${this.order().drugName} · ${clockText(this.slot().slotAt)} dose`);
  protected readonly detail = computed(() => doseDetailLine(this.dose()));

  private key = newClientKey();
  private sentReason: string | null = null;

  protected mark(reasonKey: string, label: string): void {
    if (this.running()) {
      return;
    }
    if (this.sentReason !== null && this.sentReason !== reasonKey) {
      this.key = newClientKey();
    }
    this.sentReason = reasonKey;
    this.running.set(true);
    this.failure.set(null);
    this.api
      .recordedInError(this.dose().id, { clientKey: this.key, reasonKey })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (answer) => {
          this.running.set(false);
          this.snackBar.open(inErrorToast(label), 'Close', sheetToast(8000, { announce: false }));
          this.corrected.emit(answer.mar);
        },
        error: (error: unknown) => {
          this.running.set(false);
          if (isOutsideScope(error)) {
            this.scopeRefusals.report(this.admissionId(), true);
            return;
          }
          if (isLostAnswer(error)) {
            // Nothing is shown as marked until the server answered; the same key makes a retry safe.
            this.failure.set(IN_ERROR_ERROR);
            return;
          }
          this.snackBar.open(problemMessage(error, IN_ERROR_ERROR), 'Close', sheetToast(8000));
          this.reload.emit();
        },
      });
  }
}
