import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { IconComponent } from '../../../shared/icon/icon.component';
import { SHEET_COLUMN_STYLES } from './sheet-column.styles';

export interface ConfirmSheetData {
  title: string;
  /** Plain sentences that say what changes; nothing changes until the person confirms. */
  body: string;
  confirmLabel: string;
  cancelLabel?: string;
}

export const CONFIRM_SHEET_CONFIG = {
  panelClass: 'sheet-panel',
  width: '480px',
  maxWidth: '100vw',
  ariaLabelledBy: 'confirm-title',
} as const;

/** A confirmation sheet used for switching off, removing and changing Integration mode (SAH-78). */
@Component({
  selector: 'app-confirm-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, IconComponent],
  styles: [SHEET_COLUMN_STYLES],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="confirm-title">{{ data.title }}</h2>
      </div>
      <button mat-icon-button type="button" (click)="close(false)" aria-label="Close">
        <app-icon name="x" />
      </button>
    </div>
    <div class="sheet-b">
      <p data-testid="confirm-body">{{ data.body }}</p>
    </div>
    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close(false)">
        {{ data.cancelLabel ?? 'Cancel' }}
      </button>
      <button mat-flat-button type="button" data-testid="confirm-yes" (click)="close(true)">
        {{ data.confirmLabel }}
      </button>
    </div>
  `,
})
export class ConfirmSheetComponent {
  protected readonly data = inject<ConfirmSheetData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ConfirmSheetComponent, boolean>>(MatDialogRef);

  protected close(result: boolean): void {
    this.ref.close(result);
  }
}
