import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PatientApi } from '../patient.api';
import { Patient } from '../patient.model';

export interface MergeConfirmDialogData {
  /** The older record; it survives the merge. */
  keep: Patient;
  /** The newer, likely-duplicate record; its data moves onto `keep` and it is soft-merged away. */
  mergeInto: Patient;
}

/** Confirms a merge of two likely-duplicate patients (acceptance criterion 5). Reversible by
 * the owner later: the merged-away record is kept with a pointer, never hard deleted. */
@Component({
  selector: 'app-merge-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, IconComponent],
  styleUrl: './merge-confirm-dialog.component.scss',
  templateUrl: './merge-confirm-dialog.component.html',
})
export class MergeConfirmDialogComponent {
  private readonly api = inject(PatientApi);
  private readonly dialogRef = inject<MatDialogRef<MergeConfirmDialogComponent, Patient | null>>(MatDialogRef);
  protected readonly data = inject<MergeConfirmDialogData>(MAT_DIALOG_DATA);

  protected readonly merging = signal(false);
  protected readonly error = signal<string | null>(null);

  protected close(): void {
    this.dialogRef.close(null);
  }

  protected confirm(): void {
    if (this.merging()) {
      return;
    }
    this.merging.set(true);
    this.error.set(null);
    this.api.merge(this.data.keep.id, this.data.mergeInto.id).subscribe({
      next: (merged) => {
        this.merging.set(false);
        this.dialogRef.close(merged);
      },
      error: (error: unknown) => {
        this.merging.set(false);
        this.error.set(
          error instanceof ApiError
            ? (error.problem.detail ?? 'We could not merge these records. Try again.')
            : 'We could not merge these records. Try again.',
        );
      },
    });
  }
}
