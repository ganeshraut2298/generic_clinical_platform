import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason, ApprovalRequest } from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';

export interface ApprovalRejectData {
  requestId: string;
  /** "IND-6020 · ICU → Central Store · 6 items", as the inbox row says. */
  what: string;
}

export const APPROVAL_REJECT_CONFIG = {
  panelClass: 'sheet-panel',
  width: '520px',
  maxWidth: '100vw',
  ariaLabelledBy: 'approval-reject-title',
} as const;

/**
 * "Reject" sheet of the Approvals inbox (story 79-2): a reason chip from the approval-reject list is required, and
 * "Other" needs a note of at least 4 characters. The request is read first, so the decision carries the level and
 * the version the server holds now; a decision taken by someone else meanwhile answers 409 already-decided with
 * the server's sentence. No stock moves on a rejection.
 */
@Component({
  selector: 'app-approval-reject',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    IconComponent,
  ],
  styles: [
    `
      .reasons {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        margin: 6px 0 12px;
      }
      .reasons button {
        min-height: 44px;
        padding: 8px 12px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
        background: var(--glass-hi);
        font-weight: 600;
        font-size: 0.86rem;
      }
      .reasons button[aria-pressed='true'] {
        border: 2px solid var(--pri);
        padding: 7px 11px;
        background: var(--pri-soft);
        color: var(--pri-text);
      }
      .err-text {
        color: var(--crit);
        font-size: 0.8rem;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="approval-reject-title">Reject this request</h2>
        <p>{{ data.what }}</p>
      </div>
      <button mat-icon-button type="button" (click)="close()" aria-label="Close">
        <app-icon name="x" />
      </button>
    </div>

    <form class="sheet-b" [formGroup]="form" (ngSubmit)="save()" novalidate aria-labelledby="approval-reject-title">
      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="reject-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      <span class="field-label" id="reject-reason-label">Reason</span>
      <div class="reasons" role="group" aria-labelledby="reject-reason-label" data-testid="reject-chips">
        @for (reason of reasons(); track reason.reasonKey) {
          <button
            type="button"
            [attr.aria-pressed]="form.controls.reasonKey.value === reason.reasonKey"
            [attr.data-testid]="'reject-' + reason.reasonKey"
            (click)="choose(reason)"
          >
            {{ reason.label }}
          </button>
        }
      </div>
      @if (form.controls.reasonKey.touched && form.controls.reasonKey.errors) {
        <p class="err-text" role="alert" data-testid="reason-error">Choose a reason</p>
      }

      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>{{ needsNote() ? 'Note' : 'Note (optional)' }}</mat-label>
        <textarea matInput rows="3" maxlength="300" formControlName="note" data-testid="f-note"></textarea>
        <mat-hint data-testid="note-hint">Do not add patient details.</mat-hint>
        @if (form.controls.note.errors) {
          <mat-error>Write at least 4 characters</mat-error>
        }
      </mat-form-field>

      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="reject-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </form>

    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close()">Cancel</button>
      <button
        mat-flat-button
        type="button"
        data-testid="confirm-reject"
        [disabled]="saving() || request() === null"
        (click)="save()"
      >
        Reject
      </button>
    </div>
  `,
})
export class ApprovalRejectDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<ApprovalRejectDialog, ApprovalRequest | null>>(MatDialogRef);
  protected readonly data = inject<ApprovalRejectData>(MAT_DIALOG_DATA);

  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly request = signal<ApprovalRequest | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  private readonly chosen = signal<ActiveReason | null>(null);
  protected readonly needsNote = computed(() => this.chosen()?.needsNote === true);

  protected readonly form = new FormGroup({
    reasonKey: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  private attemptKey = newClientKey();

  ngOnInit(): void {
    this.api.activeReasons(['approval-reject']).subscribe({
      next: (answer) =>
        this.reasons.set(answer.lists.find((l) => l.listKey === 'approval-reject')?.reasons ?? []),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
    this.api.approvalRequest(this.data.requestId).subscribe({
      next: (request) => this.request.set(request),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
  }

  protected close(): void {
    this.ref.close(null);
  }

  protected choose(reason: ActiveReason): void {
    this.chosen.set(reason);
    this.form.controls.reasonKey.setValue(reason.reasonKey);
    this.form.controls.reasonKey.setErrors(null);
  }

  protected save(): void {
    const request = this.request();
    if (this.saving() || request === null || request.currentLevel === null) {
      return;
    }
    this.formError.set(null);
    const reason = this.chosen();
    const note = this.form.controls.note.value.trim();
    this.form.controls.reasonKey.setErrors(reason ? null : { required: true });
    this.form.controls.note.setErrors(reason?.needsNote && note.length < 4 ? { note: true } : null);
    this.form.markAllAsTouched();
    if (this.form.invalid || reason === null) {
      return;
    }
    this.saving.set(true);
    this.api
      .decideApproval(request.id, {
        clientKey: this.attemptKey,
        level: request.currentLevel,
        requestVersion: request.version,
        decision: 'REJECTED',
        reasonKey: reason.reasonKey,
        note: note === '' ? null : note,
      })
      .subscribe({
        next: (done) => {
          this.saving.set(false);
          this.ref.close(done);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }
}
