import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { ApiError } from '../../../core/http/problem-detail';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { PharmacistApi } from './pharmacist.api';
import {
  PharmacistActionResult,
  QUERY_ERROR,
  QUESTION_LONG,
  QUESTION_MAX,
  QUESTION_MISSING,
} from './pharmacist.model';

export interface QuerySheetData {
  orderId: string;
  orderVersion: number;
  drugName: string;
  /** For the subtitle: "<patient> · ordered by <doctor>". Names on screen only. */
  subtitle: string;
  /** Asks the panel behind the sheet to reload (called on a 409 or 404). */
  onConflict: () => void;
}

/**
 * Query doctor sheet (SAH-76-2; NEW, OQ-76-28): one required question, up to 300 characters. The order
 * becomes Queried and cannot be given until the doctor replies or changes it. Nothing is sent to the doctor:
 * the sheet says so on the EMR. The question is clinical text: shown to doctors, pharmacists and the owner,
 * never logged. A 409 or 404 (the order moved on) keeps the sheet open, reloads the panel behind it and
 * switches the button off.
 */
@Component({
  selector: 'app-query-sheet',
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
    <form class="sheet" (submit)="$event.preventDefault(); submit()" novalidate data-testid="query-sheet">
      <div class="sheet-h">
        <div>
          <h2 id="sf-qd-title">Query doctor · {{ data.drugName }}</h2>
          <p class="muted sub">{{ data.subtitle }}</p>
        </div>
        <button mat-icon-button type="button" aria-label="Close" [disabled]="running()" (click)="cancel()">
          <app-icon name="x" [size]="18" />
        </button>
      </div>
      <div class="sheet-b">
        <div class="field">
          <label for="sf-qd">Your question for the doctor</label>
          <textarea
            id="sf-qd"
            class="input"
            rows="3"
            [maxlength]="max"
            [formControl]="question"
            (input)="problem.set(null)"
            [attr.aria-invalid]="problem() ? 'true' : null"
            [attr.aria-describedby]="problem() ? 'sf-qd-err' : null"
          ></textarea>
          @if (problem(); as message) {
            <span class="err-text" role="alert" id="sf-qd-err">{{ message }}</span>
          }
        </div>
        @if (failure(); as message) {
          <div class="notice err" role="alert" data-testid="query-failure">
            <app-icon name="alert" [size]="16" />
            <span>{{ message }}</span>
          </div>
        }
      </div>
      <div class="sheet-f">
        <button mat-stroked-button type="button" data-act="qd-cancel" [disabled]="running()" (click)="cancel()">
          Cancel
        </button>
        <button
          mat-flat-button
          type="submit"
          data-act="qd-ok"
          [disabled]="running() || locked()"
          [attr.aria-busy]="running()"
        >
          Send question
        </button>
      </div>
    </form>
  `,
})
export class QuerySheetComponent {
  private readonly api = inject(PharmacistApi);
  private readonly dialogRef = inject<MatDialogRef<QuerySheetComponent, PharmacistActionResult>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly data = inject<QuerySheetData>(MAT_DIALOG_DATA);

  protected readonly max = QUESTION_MAX;
  protected readonly question = new FormControl('', { nonNullable: true });
  protected readonly running = signal(false);
  protected readonly locked = signal(false);
  protected readonly problem = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  private key = newClientKey();

  constructor() {
    effect(() => {
      this.dialogRef.disableClose = this.running();
    });
  }

  protected submit(): void {
    if (this.running() || this.locked()) {
      return;
    }
    const text = this.question.value.trim();
    if (text === '') {
      this.problem.set(QUESTION_MISSING);
      return;
    }
    if (text.length > QUESTION_MAX) {
      this.problem.set(QUESTION_LONG);
      return;
    }
    this.running.set(true);
    this.failure.set(null);
    this.api
      .query(this.data.orderId, { clientKey: this.key, orderVersion: this.data.orderVersion, question: text })
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
        const field = error.fieldErrors.find((f) => f.field === 'question');
        if (field) {
          this.problem.set(field.message);
          return;
        }
      } else if (error.status === 409 && error.problem.type.endsWith('client-key-reused')) {
        this.key = newClientKey();
      } else if (error.status === 409 || error.status === 404) {
        this.locked.set(true);
        this.data.onConflict();
      } else if (error.status === 403) {
        this.locked.set(true);
      }
    }
    this.failure.set(problemMessage(error, QUERY_ERROR));
  }

  protected cancel(): void {
    if (!this.running()) {
      this.dialogRef.close();
    }
  }
}
