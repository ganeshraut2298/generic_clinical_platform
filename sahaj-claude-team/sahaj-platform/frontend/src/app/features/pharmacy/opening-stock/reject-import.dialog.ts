import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { map, startWith } from 'rxjs';
import { IconComponent } from '../../../shared/icon/icon.component';
import { saveErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { OpeningStockImport, REJECT_REASON_LABELS, RejectReasonKey } from '../pharmacy.model';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';

export interface RejectImportData {
  storeId: string;
  importId: string;
  storeName: string;
}

export const REJECT_IMPORT_CONFIG = {
  panelClass: 'sheet-panel',
  width: '520px',
  maxWidth: '100vw',
  ariaLabelledBy: 'reject-title',
} as const;

const REASONS: readonly RejectReasonKey[] = ['WRONG_FILE', 'WRONG_QUANTITIES', 'WRONG_STORE', 'OTHER'];

/**
 * "Reject" sheet of an opening-stock file (story 78-7): a reason from the short list is required, and
 * "Other" needs a note of 4 to 300 characters. Any owner may reject, the uploader included; nothing is
 * kept as stock and the uploader can upload a corrected file.
 */
@Component({
  selector: 'app-reject-import',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="reject-title">Reject this file</h2>
        <p>{{ data.storeName }}</p>
      </div>
      <button mat-icon-button type="button" (click)="close()" aria-label="Close">
        <app-icon name="x" />
      </button>
    </div>

    <form class="sheet-b" [formGroup]="form" (ngSubmit)="save()" novalidate aria-labelledby="reject-title">
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Reason</mat-label>
        <mat-select formControlName="reasonKey" placeholder="Choose an option" data-testid="f-reason">
          @for (reason of reasons; track reason) {
            <mat-option [value]="reason">{{ labels[reason] }}</mat-option>
          }
        </mat-select>
        @if (form.controls.reasonKey.errors) {
          <mat-error>Choose a reason</mat-error>
        }
      </mat-form-field>

      @if (isOther()) {
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Note</mat-label>
          <textarea
            matInput
            rows="3"
            formControlName="note"
            maxlength="300"
            data-testid="f-note"
          ></textarea>
          <mat-hint data-testid="note-hint">Do not add patient details.</mat-hint>
          @if (form.controls.note.errors) {
            <mat-error>Write at least 4 characters</mat-error>
          }
        </mat-form-field>
      }

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
        [disabled]="saving()"
        (click)="save()"
      >
        Reject file
      </button>
    </div>
  `,
  styles: [SHEET_COLUMN_STYLES],
})
export class RejectImportDialog {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<RejectImportDialog, OpeningStockImport | null>>(MatDialogRef);
  protected readonly data = inject<RejectImportData>(MAT_DIALOG_DATA);

  protected readonly reasons = REASONS;
  protected readonly labels = REJECT_REASON_LABELS;
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected readonly form = new FormGroup({
    reasonKey: new FormControl<RejectReasonKey | ''>('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  protected readonly isOther = toSignal(
    this.form.controls.reasonKey.valueChanges.pipe(
      startWith(''),
      map((key) => key === 'OTHER'),
    ),
    { initialValue: false },
  );

  protected close(): void {
    this.ref.close(null);
  }

  protected save(): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    const { reasonKey, note } = this.form.getRawValue();
    const text = note.trim();
    const noReason = reasonKey === '';
    const badNote = reasonKey === 'OTHER' && (text.length < 4 || text.length > 300);
    this.form.controls.reasonKey.setErrors(noReason ? { required: true } : null);
    this.form.controls.note.setErrors(badNote ? { note: true } : null);
    this.form.markAllAsTouched();
    if (reasonKey === '' || badNote) {
      return;
    }
    this.saving.set(true);
    this.api
      .rejectOpeningStockImport(this.data.storeId, this.data.importId, {
        reasonKey,
        note: reasonKey === 'OTHER' ? text : null,
      })
      .subscribe({
        next: (item) => {
          this.saving.set(false);
          this.ref.close(item);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }
}
