import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { EmrApi } from './emr.api';
import { MedicineOrderRow, OrderActionResult, stoppedToast } from './emr.model';
import { classifyFailure } from './order-action.logic';
import { REASON_STOP_MISSING, REASON_MAX, reasonProblem } from './order-form.logic';

export interface StopSheetData {
  admissionId: string;
  order: MedicineOrderRow;
}

const STOP_ERROR = 'We could not stop that medicine. Try again.';

/**
 * Stop sheet (SAH-76-1): "Reason for stopping" (NEW, 4 to 200 characters). The request carries the version
 * the doctor saw; a refusal that means the order moved on (409, 404) closes the sheet and the EMR sheet reads
 * again. The reason is clinical text: sent and shown, never logged.
 */
@Component({
  selector: 'app-stop-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent],
  styles: `
    .sheet-f button,
    .notice button {
      min-height: 44px;
    }
  `,
  template: `
    <form class="sheet" (submit)="$event.preventDefault(); submit()" novalidate data-testid="stop-sheet">
      <div class="sheet-h">
        <h2 id="ord-stop-title">Stop · {{ order.drugName }}</h2>
        <button mat-icon-button type="button" aria-label="Close" [disabled]="running()" (click)="cancel()">
          <app-icon name="x" [size]="18" />
        </button>
      </div>
      <div class="sheet-b">
        <div class="field">
          <label for="ord-stop-reason">Reason for stopping</label>
          <textarea
            id="ord-stop-reason"
            class="input"
            rows="3"
            [maxlength]="max"
            [formControl]="reason"
            (input)="problem.set(null)"
            [attr.aria-invalid]="problem() ? 'true' : null"
            [attr.aria-describedby]="problem() ? 'ord-stop-err' : null"
          ></textarea>
          @if (problem(); as message) {
            <span class="err-text" role="alert" id="ord-stop-err">{{ message }}</span>
          }
        </div>
        @if (failure(); as message) {
          <div class="notice err" role="alert" data-testid="stop-failure">
            <app-icon name="alert" [size]="16" />
            <span>{{ message }}</span>
          </div>
        }
      </div>
      <div class="sheet-f">
        <button mat-stroked-button type="button" data-act="stop-cancel" [disabled]="running()" (click)="cancel()">
          Cancel
        </button>
        <button mat-flat-button type="submit" class="danger" data-act="stop-ok" [disabled]="running()" [attr.aria-busy]="running()">
          Stop
        </button>
      </div>
    </form>
  `,
})
export class StopSheetComponent {
  private readonly api = inject(EmrApi);
  private readonly dialogRef = inject<MatDialogRef<StopSheetComponent, OrderActionResult>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly data = inject<StopSheetData>(MAT_DIALOG_DATA);
  private readonly scopeRefusals = inject(ScopeRefusals);

  protected readonly order = this.data.order;
  protected readonly max = REASON_MAX;
  protected readonly reason = new FormControl('', { nonNullable: true });
  protected readonly running = signal(false);
  protected readonly problem = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  private key = newClientKey();

  constructor() {
    effect(() => {
      this.dialogRef.disableClose = this.running();
    });
  }

  protected submit(): void {
    if (this.running()) {
      return;
    }
    const bad = reasonProblem(this.reason.value, REASON_STOP_MISSING);
    this.problem.set(bad);
    if (bad) {
      return;
    }
    this.running.set(true);
    this.failure.set(null);
    this.api
      .stop(this.order.id, {
        clientKey: this.key,
        orderVersion: this.order.orderVersion,
        reason: this.reason.value.trim(),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sheet) => {
          this.running.set(false);
          this.dialogRef.close({ kind: 'done', sheet, toast: stoppedToast(this.order.drugName) });
        },
        error: (error: unknown) => {
          this.running.set(false);
          this.failed(error);
        },
      });
  }

  private failed(error: unknown): void {
    const f = classifyFailure(error, STOP_ERROR);
    switch (f.kind) {
      case 'scope':
        this.scopeRefusals.report(this.data.admissionId, true);
        this.dialogRef.close();
        return;
      case 'conflict':
        this.dialogRef.close(f.result);
        return;
      case 'fields':
        this.problem.set(f.errors.reason ?? null);
        this.failure.set(f.errors.reason ? null : (f.other ?? STOP_ERROR));
        if (f.newKey) {
          this.key = newClientKey();
        }
        return;
      case 'stay':
        this.failure.set(f.message);
        if (f.newKey) {
          this.key = newClientKey();
        }
    }
  }

  protected cancel(): void {
    if (!this.running()) {
      this.dialogRef.close();
    }
  }
}
