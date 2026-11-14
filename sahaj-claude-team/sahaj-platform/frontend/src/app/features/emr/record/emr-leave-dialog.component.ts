import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';

export type EmrLeaveChoice = 'save' | 'leave' | 'stay';

/** SAH-47-5: going back to the Queue with unsaved entries asks whether to save the draft first. */
@Component({
  selector: 'app-emr-leave-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  styles: `
    button {
      min-height: 44px;
    }
  `,
  template: `
    <h2 mat-dialog-title>Save the draft first?</h2>
    <mat-dialog-content>
      <p>You have entries that are not saved yet. Leaving now would lose them.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button data-act="leave-stay" cdkFocusInitial (click)="ref.close('stay')">
        Stay on this page
      </button>
      <button type="button" mat-stroked-button data-act="leave-go" (click)="ref.close('leave')">
        Leave without saving
      </button>
      <button type="button" mat-flat-button data-act="leave-save" (click)="ref.close('save')">
        Save draft and leave
      </button>
    </mat-dialog-actions>
  `,
})
export class EmrLeaveDialogComponent {
  protected readonly ref = inject<MatDialogRef<EmrLeaveDialogComponent, EmrLeaveChoice>>(MatDialogRef);
}
