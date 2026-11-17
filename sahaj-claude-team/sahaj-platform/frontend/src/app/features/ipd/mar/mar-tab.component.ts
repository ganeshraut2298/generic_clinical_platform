import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { IdentityBarComponent } from '../emr/identity-bar.component';
import { isOutsideScope } from '../ipd-refusal';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { DoseErrorPanelComponent } from './dose-error-panel.component';
import { UndoToast } from './undo-toast.component';
import { GIVE_TITLE_ID, GiveResult, GiveSheetComponent, GiveSheetData } from './give-sheet.component';
import { MarApi } from './mar.api';
import {
  HIGH_ALERT_HINT,
  MAR_CLOSING_LINE,
  MAR_HEADER_HINT,
  MEDICINES_LIST_LABEL,
  NOTHING_DUE,
  MAR_LOADING,
  MAR_LOAD_ERROR,
  MAR_LOCK_LINES,
  Mar,
  MarOrder,
  MarSlot,
  NO_MEDICINES,
  changedMarker,
  foldToggleLabel,
  highAlertPill,
  historyLine,
  marOrderLine,
  nothingDue,
  orderLockLine,
  orderPill,
  prnView,
  slotChip,
  visibleOrders,
} from './mar.model';

/**
 * The tab "Medicines (MAR)" of the Nursing sheet (SAH-32), mockup `sfMar` with `sfIdBar`,
 * `sfMedHead` and `sfSlotChip`: the identity bar, one block for each Pharmacist check or Active
 * order with its slots, the closing line. The subtitle and the footer "Done" belong to the sheet
 * around it, which learns the MAR through `marChange`.
 *
 * - The MAR is read when the tab is chosen and replaced by the answer of every dose (never polled).
 *   Every state, the count and `canGive` are the server's.
 * - A slot opens the Give sheet only when the server marks it `canGive` (state Due or Late, an
 *   Active order, and a user who may give). Other roles see the button disabled.
 * - After the Give sheet closes, the focus goes back to the slot that opened it (to the chip itself
 *   when it is no longer a button, for example once it shows Given). When the MAR is read again
 *   (a 409 closed the sheet), the focus moves only after that read has been drawn, because the chip
 *   is redrawn then; if the slot cannot take it, it goes to the list of medicines (tabindex -1), so
 *   it never falls back to the page body. A focus the user has already moved elsewhere is kept.
 * - SAH-76-4: the header counts "N late", "N due now", "N given"; blocks with nothing due or later are
 *   folded behind "Show N medicines with nothing due now" (the server sends the order and `folded`);
 *   a PRN order is a line with its own Give. A changed order shows "Changed · v2".
 * - SAH-76-5: a given dose the nurse may correct is a button that opens the recorded-in-error panel
 *   under the list; records marked in error stay under their slot, struck through. The toast Undo
 *   after Given reports back through `UndoToast.corrected`, which replaces the MAR here.
 * - Names, allergy text and medicines are on screen only: nothing is logged or stored.
 */
@Component({
  selector: 'app-mar-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, IdentityBarComponent, DoseErrorPanelComponent],
  styleUrl: './mar-tab.component.scss',
  templateUrl: './mar-tab.component.html',
})
export class MarTabComponent implements OnInit {
  private readonly api = inject(MarApi);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly scopeRefusals = inject(ScopeRefusals);
  private readonly undoToast = inject(UndoToast);

  /** The slot whose Give sheet just closed; the focus goes back to it once the MAR is drawn. */
  private pendingFocus: SlotRef | null = null;

  readonly admissionId = input.required<string>();
  /** The MAR was read, or replaced by the answer of a dose. */
  readonly marChange = output<Mar>();

  protected readonly mar = signal<Mar | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  /** The "Show N" button of the folded blocks. */
  protected readonly showFolded = signal(false);
  /** The given dose whose panel is open (recorded in error). */
  private readonly errorRef = signal<SlotRef | null>(null);

  protected readonly shown = computed(() => {
    const mar = this.mar();
    return mar ? visibleOrders(mar.orders, this.showFolded()) : [];
  });
  protected readonly nothingDueNow = computed(() => {
    const mar = this.mar();
    return mar ? nothingDue(mar, this.showFolded()) : false;
  });
  /** The panel's order, slot and dose, read from the MAR on screen; null once the dose is not given any more. */
  protected readonly errorView = computed(() => {
    const ref = this.errorRef();
    const order = ref ? this.mar()?.orders.find((o) => o.orderId === ref.orderId) : undefined;
    const slot = order?.slots.find((s) => s.slotAt === ref?.slotAt);
    return order && slot && slot.state === 'GIVEN' && slot.dose ? { order, slot, dose: slot.dose } : null;
  });

  protected readonly noMedicines = NO_MEDICINES;
  protected readonly nothingDueText = NOTHING_DUE;
  protected readonly headerHint = MAR_HEADER_HINT;
  protected readonly listLabel = MEDICINES_LIST_LABEL;
  protected readonly foldLabel = foldToggleLabel;
  protected readonly lockLine = orderLockLine;
  protected readonly marker = changedMarker;
  protected readonly prnOf = prnView;
  protected readonly history = historyLine;
  protected readonly closingLine = MAR_CLOSING_LINE;
  protected readonly highAlertHint = HIGH_ALERT_HINT;
  protected readonly loadingText = MAR_LOADING;
  protected readonly lockLines = MAR_LOCK_LINES;
  protected readonly line = marOrderLine;
  protected readonly highAlertText = highAlertPill;
  protected readonly pill = orderPill;
  protected readonly chip = slotChip;

  ngOnInit(): void {
    this.undoToast.corrected.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((done) => {
      if (done.admissionId !== this.admissionId()) {
        return;
      }
      this.errorRef.set(null);
      if (done.mar) {
        this.accept(done.mar);
      } else {
        this.load();
      }
    });
    this.load();
  }

  protected load(): void {
    if (this.loading()) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .mar(this.admissionId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (mar) => {
          this.loading.set(false);
          this.accept(mar);
          this.focusAfterDraw();
        },
        error: (error: unknown) => {
          this.loading.set(false);
          if (isOutsideScope(error)) {
            // SAH-34 AC 38: the sheet around this tab clears and closes; the page says so.
            this.mar.set(null);
            this.scopeRefusals.report(this.admissionId(), false);
            return;
          }
          this.loadError.set(problemMessage(error, MAR_LOAD_ERROR));
          this.focusAfterDraw();
        },
      });
  }

  private accept(mar: Mar): void {
    this.mar.set(mar);
    this.marChange.emit(mar);
  }

  /** A slot button, or the Give of a PRN line (slot null): opens the Give sheet (only when the server allows). */
  protected give(order: MarOrder, slot: MarSlot | null): void {
    const mar = this.mar();
    const allowed = slot ? slot.canGive : !!order.prn && prnView(order.prn, order).canGive;
    if (!mar || !mar.canGive || !allowed) {
      return;
    }
    this.dialog
      .open<GiveSheetComponent, GiveSheetData, GiveResult | undefined>(GiveSheetComponent, {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        ariaLabelledBy: GIVE_TITLE_ID,
        // Material would focus the close button once the sheet has opened. The sheet places the focus
        // itself (the wristband box); the focus trap stays.
        autoFocus: false,
        data: { mar, order, slot },
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => this.afterGive(result, order, slot));
  }

  /** A given dose the nurse may correct: opens the panel under the list. */
  protected openError(order: MarOrder, slot: MarSlot): void {
    if (slot.dose?.canMarkInError) {
      this.errorRef.set({ orderId: order.orderId, slotAt: slot.slotAt });
    }
  }

  protected closeError(): void {
    this.errorRef.set(null);
  }

  protected errorCorrected(mar: Mar): void {
    this.errorRef.set(null);
    this.accept(mar);
  }

  protected errorReload(): void {
    this.errorRef.set(null);
    this.load();
  }

  private afterGive(result: GiveResult | undefined, order: MarOrder, slot: MarSlot | null): void {
    this.pendingFocus = { orderId: order.orderId, slotAt: slot ? slot.slotAt : null };
    if (result && 'mar' in result) {
      this.accept(result.mar);
      this.focusAfterDraw();
    } else if (result && 'reload' in result) {
      // The focus moves when this second read has been drawn (focusAfterDraw, called from load).
      this.load();
    } else {
      // Closed without a result: the dialog has given the focus back to the slot; check it one tick later.
      setTimeout(() => this.focusPending(), 0);
    }
  }

  /** Waits until the MAR just stored has been drawn, then moves the focus. */
  private focusAfterDraw(): void {
    if (this.pendingFocus) {
      afterNextRender(() => this.focusPending(), { injector: this.injector });
    }
  }

  private focusPending(): void {
    const target = this.pendingFocus;
    this.pendingFocus = null;
    if (!target) {
      return;
    }
    const root = this.host.nativeElement;
    const doc = root.ownerDocument;
    const chips = Array.from(
      root.querySelectorAll<HTMLElement>(target.slotAt === null ? '[data-testid="mar-prn-give"]' : '[data-testid="mar-slot"]'),
    );
    const chip = chips.find(
      (el) => el.dataset['order'] === target.orderId && (target.slotAt === null || el.dataset['slot'] === target.slotAt),
    );
    const active = doc.activeElement;
    if (active && active !== doc.body && active.isConnected && active !== chip) {
      return; // the user has already moved on: do not take the focus away
    }
    // A chip that is a disabled button can not take the focus; the list of medicines can.
    const usable = chip && !(chip instanceof HTMLButtonElement && chip.disabled);
    (usable ? chip : root.querySelector<HTMLElement>('[data-testid="mar-orders"]'))?.focus();
  }
}

interface SlotRef {
  orderId: string;
  /** Null for the PRN line of an order. */
  slotAt: string | null;
}
