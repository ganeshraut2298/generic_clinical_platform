import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

export interface EmrConfirmData {
  title: string;
  message: string;
  confirm: string;
  cancel: string;
}

/** A short question with two answers ("Dr. <name> is editing. Take over?"). Staff names only; no patient data. */
@Component({
  selector: 'app-emr-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  styles: `
    button {
      min-height: 44px;
    }
  `,
  template: `
    <h2 mat-dialog-title id="emr-confirm-title">{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button data-act="confirm-no" cdkFocusInitial (click)="ref.close(false)">{{ data.cancel }}</button>
      <button type="button" mat-flat-button data-act="confirm-yes" (click)="ref.close(true)">{{ data.confirm }}</button>
    </mat-dialog-actions>
  `,
})
export class EmrConfirmDialogComponent {
  protected readonly ref = inject<MatDialogRef<EmrConfirmDialogComponent, boolean>>(MatDialogRef);
  protected readonly data = inject<EmrConfirmData>(MAT_DIALOG_DATA);
}
