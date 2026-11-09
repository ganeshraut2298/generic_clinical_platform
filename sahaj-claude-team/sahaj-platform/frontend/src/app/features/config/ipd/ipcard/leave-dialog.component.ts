import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';

export const LEAVE_TITLE = 'Leave without saving?';
export const LEAVE_TEXT = 'Your unsaved changes will be lost. A draft you already saved is kept.';

/** The warning of AC4. Closes with `true` when the owner chooses to leave. */
@Component({
  selector: 'app-card-leave-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  styles: `
    button {
      min-height: 44px;
    }
  `,
  template: `
    <h2 mat-dialog-title>{{ title }}</h2>
    <mat-dialog-content>
      <p>{{ text }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button
        type="button"
        mat-stroked-button
        data-act="leave-stay"
        cdkFocusInitial
        (click)="ref.close(false)"
      >
        Stay on this page
      </button>
      <button type="button" mat-flat-button data-act="leave-go" (click)="ref.close(true)">
        Leave
      </button>
    </mat-dialog-actions>
  `,
})
export class CardLeaveDialogComponent {
  protected readonly ref = inject<MatDialogRef<CardLeaveDialogComponent, boolean>>(MatDialogRef);
  protected readonly title = LEAVE_TITLE;
  protected readonly text = LEAVE_TEXT;
}
