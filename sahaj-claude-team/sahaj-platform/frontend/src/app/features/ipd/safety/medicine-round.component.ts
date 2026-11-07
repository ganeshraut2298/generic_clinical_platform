import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { IconComponent } from '../../../shared/icon/icon.component';
import { sheetToast } from '../../../shared/ui/sheet-toast';
import { problemMessage } from '../../config/config-format';
import { GIVE_TITLE_ID, GiveResult, GiveSheetComponent, GiveSheetData } from '../mar/give-sheet.component';
import { MarApi } from '../mar/mar.api';
import {
  GIVE_LABEL,
  HIGH_ALERT_PILL,
  MedicineRound,
  MedicineRoundRow,
  NO_DOSES,
  ROUND_HEADING_ID,
  ROUND_MAR_ERROR,
  ROUND_READONLY_LINE,
  ROUND_TITLE,
  ROUND_TRUNCATED,
  checkGive,
  giveName,
  roundKey,
  roundRowLine,
  roundStatusPill,
  roundSubtitle,
} from './safety.model';

/**
 * The panel "Medicine round" of the Safety tab (SAH-35), mockup `ipSafety` panel and `doseRow`:
 * the medicine in bold (with the red pill "High-alert"), "<patient> · bed <code> · <time> dose", the
 * status pill ("2 h late" red, "Due now" amber) and the button Give. The rows, their order, the
 * 200-row limit and `canGive` are the server's; the device clock is never read.
 *
 * - Give is drawn only when the server says `canGive` (hidden, not disabled, for everybody else);
 *   the dose call checks role, state and the bedside checks again.
 * - Give reads that patient's MAR once (ADR-012 D4), checks that the slot can still be given and
 *   then opens the SAH-32 Give sheet unchanged (no "Scan wristband"). When the slot can no longer
 *   be given the sheet does not open: the existing SAH-32 or DEC-018 text is shown in a toast.
 * - After any answer of the server (a dose recorded, a refusal that closes the sheet, a stale
 *   slot) the panel asks the board to reload (`answered`); the board stays on the Safety tab and
 *   then calls `restoreFocus()`: focus goes to the Give button of the same row, or to the panel
 *   heading (tabindex -1) when the row is gone. A cancelled sheet gives the focus back at once.
 * - Names and medicines are on screen only: nothing is logged or stored.
 */
@Component({
  selector: 'app-medicine-round',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './medicine-round.component.scss',
  templateUrl: './medicine-round.component.html',
})
export class MedicineRoundComponent {
  private readonly api = inject(MarApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly round = input.required<MedicineRound>();
  /** `SafetyBoard.asOf`: the clock in the subtitle. */
  readonly asOf = input.required<string>();
  /** A server answer changed (or may have changed) the doses: the board reloads. */
  readonly answered = output<void>();

  /** Key of the row whose MAR is being read; every Give is off meanwhile (one sheet at a time). */
  protected readonly busyKey = signal<string | null>(null);
  /** The row that opened the sheet; the focus goes back to it. */
  private focusKey: string | null = null;

  protected readonly title = ROUND_TITLE;
  protected readonly headingId = ROUND_HEADING_ID;
  protected readonly empty = NO_DOSES;
  protected readonly truncatedText = ROUND_TRUNCATED;
  protected readonly readonlyText = ROUND_READONLY_LINE;
  protected readonly highAlertText = HIGH_ALERT_PILL;
  protected readonly giveLabel = GIVE_LABEL;
  protected readonly subtitle = computed(() => roundSubtitle(this.asOf()));
  protected readonly showReadonly = computed(() => this.round().giveLockReason === 'IPD_READONLY');
  protected readonly line = roundRowLine;
  protected readonly pill = roundStatusPill;
  protected readonly nameOf = giveName;
  protected readonly keyOf = roundKey;

  /** Give: one MAR read for that patient, the re-check, then the unchanged SAH-32 Give sheet. */
  protected give(row: MedicineRoundRow): void {
    if (this.busyKey() !== null || !this.round().canGive) {
      return;
    }
    const key = roundKey(row);
    this.focusKey = key;
    this.busyKey.set(key);
    this.api
      .mar(row.admissionId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (mar) => {
          this.busyKey.set(null);
          const check = checkGive(mar, row);
          if (!check.open) {
            this.toast(check.message);
            this.answered.emit();
            return;
          }
          this.dialog
            .open<GiveSheetComponent, GiveSheetData, GiveResult | undefined>(GiveSheetComponent, {
              panelClass: 'sheet-panel',
              width: '640px',
              maxWidth: '100vw',
              ariaLabelledBy: GIVE_TITLE_ID,
              // The sheet places the focus itself (the wristband box); the focus trap stays.
              autoFocus: false,
              data: { mar, order: check.order, slot: check.slot },
            })
            .afterClosed()
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((result) => {
              if (result) {
                // A dose was recorded (201 or 200), or the server refused with a state change.
                this.answered.emit();
              } else {
                setTimeout(() => this.restoreFocus(), 0);
              }
            });
        },
        error: (error: unknown) => {
          this.busyKey.set(null);
          this.toast(problemMessage(error, ROUND_MAR_ERROR));
          setTimeout(() => this.restoreFocus(), 0);
        },
      });
  }

  /** Called by the board once the reloaded rows are drawn (or the reload failed). */
  restoreFocus(): void {
    const key = this.focusKey;
    this.focusKey = null;
    if (key === null) {
      return;
    }
    const root = this.host.nativeElement;
    const button = Array.from(root.querySelectorAll<HTMLButtonElement>('[data-testid="round-give"]')).find(
      (el) => el.dataset['key'] === key,
    );
    const target = button && !button.disabled ? button : root.querySelector<HTMLElement>(`#${ROUND_HEADING_ID}`);
    target?.focus();
  }

  private toast(message: string): void {
    this.snackBar.open(message, 'Close', sheetToast(8000));
  }
}
