import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { KeyLabel } from '../emr/emr.model';
import { problemMessage } from '../../config/config-format';
import { PharmacistApi } from './pharmacist.api';
import {
  PharmacistActionResult,
  PharmacistCheckRow,
  REASON_MISSING,
  REJECT_ERROR,
  rejectSubtitle,
  rejectTitle,
} from './pharmacist.model';

export interface RejectSheetData {
  row: PharmacistCheckRow;
  /** `PharmacistChecks.rejectReasons`: the four options in select order; the first is preselected. */
  reasons: KeyLabel[];
  /** Asks the panel behind the sheet to reload (called on a 409 or 404). */
  onConflict: () => void;
}

/**
 * Reject sheet (SAH-31), mockup `sfrej` and `sfrejok`: "Reject · <drug>", the select "Reason, sent
 * to the doctor" (first reason preselected) and the footer Cancel and "Reject order" (red). No PIN.
 *
 * - "Reject order", Cancel and the close button are off while the request runs; Esc and a click
 *   outside do nothing then.
 * - Every failure keeps the sheet open with the chosen reason. A 409 or 404 (someone else already
 *   verified, rejected or stopped it, or the patient left) shows the server's text, reloads the
 *   panel behind the sheet and switches "Reject order" off: there is nothing left to reject.
 * - Closes with the answer (`PharmacistActionResult`) on success; the panel shows the toast.
 */
@Component({
  selector: 'app-reject-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent],
  styles: `
    .sub {
      font-size: 0.86rem;
    }
    .sheet-f button,
    .notice button {
      min-height: 44px;
    }
  `,
  template: `
    <form class="sheet" [formGroup]="form" (ngSubmit)="submit()" novalidate data-testid="reject-sheet">
      <div class="sheet-h">
        <div>
          <h2 id="sf-rj-title">{{ title }}</h2>
          <p class="muted sub" data-testid="reject-subtitle">{{ subtitle }}</p>
        </div>
        <button
          mat-icon-button
          type="button"
          aria-label="Close"
          data-act="rj-x"
          [disabled]="running()"
          (click)="cancel()"
        >
          <app-icon name="x" [size]="18" />
        </button>
      </div>
      <div class="sheet-b">
        <div class="field">
          <label for="sf-rj">Reason, sent to the doctor</label>
          <select
            id="sf-rj"
            class="input"
            formControlName="reason"
            (change)="reasonError.set(null)"
            [attr.aria-invalid]="reasonError() ? 'true' : null"
            [attr.aria-describedby]="reasonError() ? 'sf-rj-err' : null"
          >
            @for (r of reasons; track r.key) {
              <option [value]="r.key">{{ r.label }}</option>
            }
          </select>
          @if (reasonError(); as message) {
            <span class="err-text" role="alert" id="sf-rj-err">{{ message }}</span>
          }
        </div>
        @if (failure(); as message) {
          <div class="notice err" role="alert" data-testid="reject-failure">
            <app-icon name="alert" [size]="16" />
            <span>{{ message }}</span>
          </div>
        }
      </div>
      <div class="sheet-f">
        <button mat-stroked-button type="button" data-act="rj-cancel" [disabled]="running()" (click)="cancel()">
          Cancel
        </button>
        <button
          mat-flat-button
          type="submit"
          class="danger"
          data-act="sfrejok"
          [disabled]="running() || locked()"
          [attr.aria-busy]="running()"
        >
          Reject order
        </button>
      </div>
    </form>
  `,
})
export class RejectSheetComponent {
  private readonly api = inject(PharmacistApi);
  private readonly dialogRef = inject<MatDialogRef<RejectSheetComponent, PharmacistActionResult>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly data = inject<RejectSheetData>(MAT_DIALOG_DATA);

  protected readonly reasons = this.data.reasons;
  protected readonly title = rejectTitle(this.data.row);
  protected readonly subtitle = rejectSubtitle(this.data.row);

  protected readonly running = signal(false);
  protected readonly locked = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly reasonError = signal<string | null>(null);

  protected readonly form = new FormGroup({
    reason: new FormControl(this.data.reasons[0]?.key ?? '', { nonNullable: true }),
  });

  constructor() {
    effect(() => {
      this.dialogRef.disableClose = this.running();
    });
  }

  protected submit(): void {
    if (this.running() || this.locked()) {
      return;
    }
    const reasonKey = this.form.controls.reason.value;
    if (!this.reasons.some((r) => r.key === reasonKey)) {
      this.reasonError.set(REASON_MISSING);
      this.host.nativeElement.querySelector<HTMLElement>('#sf-rj')?.focus();
      return;
    }
    this.running.set(true);
    this.failure.set(null);
    this.api
      .reject(this.data.row.orderId, { reasonKey })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (answer) => {
          this.running.set(false);
          this.dialogRef.close(answer);
        },
        error: (error: unknown) => {
          this.running.set(false);
          this.failed(error);
        },
      });
  }

  private failed(error: unknown): void {
    if (error instanceof ApiError) {
      if (error.status === 400) {
        const field = error.fieldErrors.find((f) => f.field === 'reasonKey');
        if (field) {
          this.reasonError.set(field.message || REASON_MISSING);
          return;
        }
      } else if (error.status === 409 || error.status === 404) {
        this.locked.set(true);
        this.data.onConflict();
      } else if (error.status === 403) {
        this.locked.set(true);
      }
    }
    this.failure.set(problemMessage(error, REJECT_ERROR));
  }

  protected cancel(): void {
    if (!this.running()) {
      this.dialogRef.close();
    }
  }
}
