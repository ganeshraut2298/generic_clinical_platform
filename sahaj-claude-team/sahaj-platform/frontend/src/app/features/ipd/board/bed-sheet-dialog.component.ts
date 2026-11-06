import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { formatInrPaise } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { AdmissionsApi } from '../admissions.api';
import { BED_STATUS, BedBoard, BoardBed } from '../ipd.model';

export interface BedSheetData {
  bed: BoardBed;
  wardName: string;
  canChange: boolean;
}

/** `board` on success; `failed` with the server message when the change was refused (409, 404 ...). */
export interface BedSheetResult {
  board?: BedBoard;
  message: string;
  failed?: boolean;
}

const CLEANING_TEXT =
  'Housekeeping is preparing this bed after a discharge or transfer. It is not offered for admission until it is marked ready.';
const BLOCKED_TEXT =
  'This bed is out of use, for example for repair or isolation. It is not offered for admission.';

/** Bed sheet (SAH-24), mockup `openBedSheet`, `ipclean` and `ipblock`. */
@Component({
  selector: 'app-bed-sheet-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './bed-sheet-dialog.component.scss',
  templateUrl: './bed-sheet-dialog.component.html',
})
export class BedSheetDialogComponent {
  private readonly api = inject(AdmissionsApi);
  private readonly dialogRef =
    inject<MatDialogRef<BedSheetDialogComponent, BedSheetResult | null>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly data = inject<BedSheetData>(MAT_DIALOG_DATA);

  protected readonly formatInrPaise = formatInrPaise;
  protected readonly status = BED_STATUS[this.data.bed.status];
  protected readonly text = this.data.bed.status === 'clean' ? CLEANING_TEXT : BLOCKED_TEXT;
  protected readonly busy = signal(false);

  protected get canBlock(): boolean {
    return this.data.canChange && this.data.bed.status === 'clean';
  }

  protected get canReady(): boolean {
    return this.data.canChange && (this.data.bed.status === 'clean' || this.data.bed.status === 'blk');
  }

  protected get readyLabel(): string {
    return this.data.bed.status === 'blk' ? 'Unblock · ready' : 'Cleaned · ready';
  }

  protected close(): void {
    this.dialogRef.close(null);
  }

  protected ready(): void {
    const verb = this.data.bed.status === 'blk' ? 'unblocked' : 'cleaned';
    this.run(
      this.api.ready(this.data.bed.id),
      `Bed ${this.data.bed.code} ${verb} and ready for admission`,
    );
  }

  protected block(): void {
    this.run(this.api.block(this.data.bed.id), `Bed ${this.data.bed.code} blocked`);
  }

  private run(call: Observable<BedBoard>, message: string): void {
    this.busy.set(true);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (board) => this.dialogRef.close({ board, message }),
      error: (error: unknown) =>
        this.dialogRef.close({
          failed: true,
          message: problemMessage(error, 'We could not change this bed. Try again.'),
        }),
    });
  }
}
