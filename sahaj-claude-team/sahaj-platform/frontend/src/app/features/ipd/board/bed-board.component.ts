import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { formatInrPaise } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { AdmissionsApi } from '../admissions.api';
import {
  BED_STATUS,
  BED_STATUS_ORDER,
  BedBoard,
  BoardBed,
  BoardRoom,
  BoardWard,
  bedAriaLabel,
} from '../ipd.model';
import { BedSheetData, BedSheetDialogComponent, BedSheetResult } from './bed-sheet-dialog.component';

const LOAD_ERROR = 'We could not load the bed board. Try again.';

/**
 * Bed board tab (SAH-24), mockup `ipBoard` and `bedTile`: legend, one panel per ward, bed tiles.
 * Opening an admission or the Admit sheet is left to the page (it owns those dialogs); the Bed
 * sheet is opened here. `changed` tells the page that bed counts may have moved.
 */
@Component({
  selector: 'app-bed-board',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './bed-board.component.scss',
  templateUrl: './bed-board.component.html',
})
export class BedBoardComponent implements OnInit {
  private readonly api = inject(AdmissionsApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  readonly openAdmission = output<string>();
  readonly admit = output<string | undefined>();
  readonly changed = output<void>();

  protected readonly statuses = BED_STATUS_ORDER;
  protected readonly meta = BED_STATUS;
  protected readonly label = bedAriaLabel;
  protected readonly formatInrPaise = formatInrPaise;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly board = signal<BedBoard | null>(null);

  ngOnInit(): void {
    this.reload();
  }

  /** Loads the board; keeps what is on screen while refreshing. */
  reload(): void {
    this.loadError.set(null);
    if (!this.board()) {
      this.loading.set(true);
    }
    this.api
      .board()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (board) => {
          this.board.set(board);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loadError.set(problemMessage(error, LOAD_ERROR));
          this.loading.set(false);
        },
      });
  }

  protected roomKey(room: BoardRoom): string {
    return room.id ?? 'none';
  }

  protected percent(ward: BoardWard): number {
    return ward.total > 0 ? (ward.occupied / ward.total) * 100 : 0;
  }

  /** Whether clicking the tile does anything for this user; the others are marked aria-disabled. */
  protected actionable(bed: BoardBed, board: BedBoard): boolean {
    switch (bed.status) {
      case 'occ':
        // SAH-34: an occupied bed outside the scope has no admission id; the tile shows the status only.
        return !bed.outsideScope && !!bed.admissionId;
      case 'free':
        return board.canAdmit;
      case 'clean':
      case 'blk':
        return true;
      default:
        return false;
    }
  }

  protected tileClick(bed: BoardBed, ward: BoardWard): void {
    const board = this.board();
    if (!board || !this.actionable(bed, board)) {
      return;
    }
    if (bed.status === 'occ' && bed.admissionId) {
      this.openAdmission.emit(bed.admissionId);
    } else if (bed.status === 'free') {
      this.admit.emit(bed.id);
    } else {
      this.openSheet(bed, ward, board);
    }
  }

  protected admitHere(ward: BoardWard): void {
    if (ward.firstFreeBedId) {
      this.admit.emit(ward.firstFreeBedId);
    }
  }

  private openSheet(bed: BoardBed, ward: BoardWard, board: BedBoard): void {
    this.dialog
      .open<BedSheetDialogComponent, BedSheetData, BedSheetResult | null>(BedSheetDialogComponent, {
        panelClass: 'sheet-panel',
        width: '520px',
        maxWidth: '100vw',
        data: { bed, wardName: ward.name, canChange: board.canChangeBeds },
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        if (!result) {
          return;
        }
        this.snackBar.open(result.message, 'Close', { duration: result.failed ? 8000 : 5000 });
        if (result.board) {
          this.board.set(result.board);
        } else {
          this.reload();
        }
        this.changed.emit();
      });
  }
}
