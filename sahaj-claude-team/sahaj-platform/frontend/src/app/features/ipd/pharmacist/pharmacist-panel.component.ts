import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  effect,
  inject,
  untracked,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { IconComponent } from '../../../shared/icon/icon.component';
import { focusIsLost } from '../ipd-refusal';
import { severityWord } from '../emr/emr.model';
import { staleBoardText } from '../safety/safety.model';
import {
  LOCK_HINT,
  NO_WAITING,
  PANEL_SUBTITLE,
  PANEL_TITLE,
  PharmacistActionResult,
  PharmacistCheckRow,
  SHOW_MORE,
  rejectedToast,
  rowLine,
  rowTitle,
  verifiedToast,
} from './pharmacist.model';
import { PharmacistStore } from './pharmacist.store';
import { RejectSheetComponent, RejectSheetData } from './reject-sheet.component';
import { VerifySheetComponent, VerifySheetData } from './verify-sheet.component';

/** The heading of the panel: focus goes here when the button that opened a sheet is gone. */
export const PHARMACIST_HEADING_ID = 'ph-h';

/**
 * The panel "Pharmacist check" (SAH-31), mockup `ipSafety` with `pendRow`: one row for each order
 * waiting (oldest first), with the server's findings, the doctor's reason, Reject and Verify.
 *
 * - Findings and flags are the server's, run again on every read; Verify is off with a visible hint
 *   when `canVerify` is false (hard stop, the person who ordered, IPD read-only).
 * - "Show more orders" loads the next page while the server sends a cursor.
 * - The list is read when the panel opens, after each verify or reject (their answers carry it),
 *   when the summary poll sees a change, and behind a sheet that met a conflict.
 * - Names, allergy text and findings are on screen only: nothing is logged or stored.
 */
@Component({
  selector: 'app-pharmacist-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './pharmacist-panel.component.scss',
  templateUrl: './pharmacist-panel.component.html',
})
export class PharmacistPanelComponent implements OnInit {
  protected readonly store = inject(PharmacistStore);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  private readonly doc = inject(DOCUMENT);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** The open Verify or Reject sheet: closed with this panel, so a refused board leaves no name on screen (SAH-40). */
  private sheet: { close(): void } | null = null;
  /** Try again was pressed from inside the panel: the focus may fall to the body when its button is replaced. */
  private retried = false;

  protected readonly title = PANEL_TITLE;
  protected readonly subtitle = PANEL_SUBTITLE;
  protected readonly empty = NO_WAITING;
  protected readonly showMore = SHOW_MORE;
  protected readonly headingId = PHARMACIST_HEADING_ID;
  protected readonly lockHint = LOCK_HINT;
  protected readonly titleOf = rowTitle;
  protected readonly severity = severityWord;

  protected readonly asOf = computed(() => this.store.meta()?.asOf ?? '');
  protected readonly staleText = computed(() => staleBoardText(this.store.meta()?.asOf));
  protected readonly line = (row: PharmacistCheckRow): string => rowLine(row, this.asOf());

  constructor() {
    // SAH-40 (AC 23): a refusal removes the rows; when focus was on one of them (it is still there
    // while this runs, before the view updates) or was lost to the body after Try again, it moves to
    // the notice and not to the top of the page. An open Verify or Reject sheet closes at once (it
    // shows a name, the UHID, the drug and the allergy), and a toast of an earlier success goes too.
    effect(() => {
      const refused = this.store.refused();
      if (!refused) {
        if (this.store.meta() !== null) {
          // A read was answered: the focus no longer waits for a refusal after Try again.
          this.retried = false;
        }
        return;
      }
      untracked(() => {
        const lost = focusIsLost(this.host.nativeElement, this.doc, this.retried) || this.sheet !== null;
        this.retried = false;
        this.closeSheet();
        this.snackBar.dismiss();
        if (lost) {
          afterNextRender(
            () => this.host.nativeElement.querySelector<HTMLElement>('.notice.err')?.focus(),
            { injector: this.injector },
          );
        }
      });
    });
  }

  ngOnInit(): void {
    this.store.panelShown.set(true);
    this.destroyRef.onDestroy(() => {
      this.store.panelShown.set(false);
      this.store.stop();
      this.closeSheet();
    });
    this.store.load();
  }

  protected reload(): void {
    this.retried = this.host.nativeElement.contains(this.doc.activeElement);
    this.store.load();
  }

  /** The store read again for a sheet that met a conflict; never while refused (only Try again reads). */
  private conflict(): void {
    if (!this.store.refused()) {
      this.store.load();
    }
  }

  private closeSheet(): void {
    const sheet = this.sheet;
    this.sheet = null;
    sheet?.close();
  }

  protected loadMore(): void {
    this.store.loadMore();
  }

  protected verify(row: PharmacistCheckRow): void {
    const meta = this.store.meta();
    if (!meta || !row.canVerify) {
      return;
    }
    const live = computed(() => this.store.items().find((r) => r.orderId === row.orderId) ?? null);
    const sheet = this.dialog.open<VerifySheetComponent, VerifySheetData, PharmacistActionResult | undefined>(
      VerifySheetComponent,
      {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        ariaLabelledBy: 'sf-vh',
        // Material would focus the close button once the sheet has opened. The sheet places the
        // focus itself (the PIN field, or the first action of the PIN notice); the focus trap stays.
        autoFocus: false,
        data: {
          row,
          verifierName: meta.verifierName,
          live,
          onConflict: () => this.conflict(),
        },
      },
    );
    this.sheet = sheet;
    sheet
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((answer) => {
        this.sheetClosed(sheet);
        this.afterAction(answer, (a) => verifiedToast(a.result.drugName, a.result.actorName));
      });
  }

  protected reject(row: PharmacistCheckRow): void {
    const meta = this.store.meta();
    if (!meta || !row.canReject) {
      return;
    }
    const sheet = this.dialog.open<RejectSheetComponent, RejectSheetData, PharmacistActionResult | undefined>(
      RejectSheetComponent,
      {
        panelClass: 'sheet-panel',
        width: '520px',
        maxWidth: '100vw',
        ariaLabelledBy: 'sf-rj-title',
        data: {
          row,
          reasons: meta.rejectReasons,
          onConflict: () => this.conflict(),
        },
      },
    );
    this.sheet = sheet;
    sheet
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((answer) => {
        this.sheetClosed(sheet);
        this.afterAction(answer, (a) => rejectedToast(a.result.rejectReason?.label ?? ''));
      });
  }

  private sheetClosed(sheet: { close(): void }): void {
    if (this.sheet === sheet) {
      this.sheet = null;
    }
  }

  /**
   * The sheet closed with an answer: the answer carries the queue now. The button that opened the
   * sheet is gone, so focus goes to the panel heading one tick later (the dialog gives focus back
   * to its opener while it closes, and that must not win).
   */
  private afterAction(
    answer: PharmacistActionResult | undefined,
    toast: (a: PharmacistActionResult) => string,
  ): void {
    if (!answer) {
      return;
    }
    this.store.apply(answer.checks);
    this.snackBar.open(toast(answer), 'Close', { duration: 6000 });
    setTimeout(() => this.doc.getElementById(PHARMACIST_HEADING_ID)?.focus(), 0);
  }
}
