import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { Subscription } from 'rxjs';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { isRefusal } from '../ipd-refusal';
import { NursingSheetComponent, NursingSheetData } from '../nursing/nursing-sheet.component';
import { OrderNoticesApi } from '../orders/order-notices.api';
import { OrderNoticesStore } from '../orders/order-notices.store';
import {
  NOTICE_KIND_LABEL,
  OrderNoticePage,
  OrderNoticePageRow,
  clock,
  detailsLine,
} from '../orders/order.model';
import { statText, unacknowledgedText } from '../orders/stat-countdown';
import { staleBoardText } from './safety.model';

export const WARD_ORDERS_TITLE = 'Orders to acknowledge';
export const WARD_ORDERS_EMPTY = 'No orders waiting for you.';
export const WARD_ORDERS_ERROR = 'We could not load the orders. Try again.';
const PAGE = 50;
const TICK_MS = 15_000;

/**
 * The panel "Orders to acknowledge" of the Safety tab, after the Medicine round (SAH-76-3; placement NEW,
 * OQ-76-4). Open notices of the nurse's patients across patients, STAT first, then the ones not acknowledged for
 * too long, then oldest. Each row opens that patient's Nursing sheet on the tab Orders. A notice overdue by the
 * clinic's setting also shows as a red strip at the top. No text says that anyone was alerted. The count is not
 * added to the Safety tab number (SAH-35).
 *
 * - NURSE and OWNER only: any other role is refused (403) and the panel then draws nothing.
 * - It reads again when the 30-second counts poll sees another change token; a failed refresh keeps the rows and
 *   shows "Could not refresh. Showing data from 11:05 AM.".
 * - Names and medicines are on screen only: nothing is logged or stored.
 */
@Component({
  selector: 'app-orders-to-acknowledge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './orders-to-acknowledge.component.scss',
  templateUrl: './orders-to-acknowledge.component.html',
})
export class OrdersToAcknowledgeComponent {
  private readonly api = inject(OrderNoticesApi);
  protected readonly store = inject(OrderNoticesStore);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly page = signal<OrderNoticePage | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly refreshFailed = signal(false);
  /** 403: the role has no such list. The panel draws nothing. */
  protected readonly refused = signal(false);
  protected readonly moreLoading = signal(false);
  protected readonly tick = signal(0);

  protected readonly title = WARD_ORDERS_TITLE;
  protected readonly empty = WARD_ORDERS_EMPTY;
  protected readonly kindLabel = NOTICE_KIND_LABEL;
  protected readonly line = (r: OrderNoticePageRow): string => detailsLine(r.notice.details);
  protected readonly timeText = clock;
  protected readonly waitingText = unacknowledgedText;
  protected readonly staleText = computed(() => staleBoardText(this.page()?.asOf));
  /** The rows that have waited longer than the setting: the red strips at the top. */
  protected readonly overdueRows = computed(() => (this.page()?.items ?? []).filter((r) => r.notice.overdue));

  private receivedAt = 0;
  private sub: Subscription | null = null;
  private lastToken: string | null = null;

  constructor() {
    const timer = setInterval(() => this.tick.update((n) => n + 1), TICK_MS);
    this.destroyRef.onDestroy(() => {
      clearInterval(timer);
      this.sub?.unsubscribe();
    });
    this.store.attach(this.destroyRef);
    this.load(true);
    effect(() => {
      const token = this.store.changeToken();
      untracked(() => {
        if (token !== null && this.lastToken !== null && token !== this.lastToken) {
          this.load(false);
        }
        this.lastToken = token ?? this.lastToken;
      });
    });
  }

  protected load(first = true): void {
    this.sub?.unsubscribe();
    if (first) {
      this.loading.set(true);
      this.loadError.set(null);
    }
    this.sub = this.api.page(PAGE).subscribe({
      next: (page) => {
        this.page.set(page);
        this.receivedAt = performance.now();
        this.loading.set(false);
        this.loadError.set(null);
        this.refreshFailed.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        if (isRefusal(error)) {
          this.refused.set(true);
          this.page.set(null);
        } else if (this.page() === null || first) {
          this.loadError.set(problemMessage(error, WARD_ORDERS_ERROR));
        } else {
          this.refreshFailed.set(true);
        }
      },
    });
  }

  protected loadMore(): void {
    const cursor = this.page()?.nextCursor;
    if (!cursor || this.moreLoading()) {
      return;
    }
    this.moreLoading.set(true);
    this.api
      .page(PAGE, cursor)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (next) => {
          this.moreLoading.set(false);
          const current = this.page();
          const known = new Set((current?.items ?? []).map((r) => r.notice.id));
          this.page.set({
            ...next,
            items: [...(current?.items ?? []), ...next.items.filter((r) => !known.has(r.notice.id))],
          });
        },
        error: () => {
          this.moreLoading.set(false);
          this.refreshFailed.set(true);
        },
      });
  }

  /** The STAT line counted on from the answer's `asOf` (never removed by a timer). */
  protected stat(row: OrderNoticePageRow): { text: string; overdue: boolean } | null {
    this.tick();
    const page = this.page();
    if (!row.notice.stat || !page) {
      return null;
    }
    return statText(row.notice.stat, page.asOf, performance.now() - this.receivedAt);
  }

  /** Opens that patient's Nursing sheet on the tab Orders; the panel reads again when it closes. */
  protected open(row: OrderNoticePageRow): void {
    this.dialog
      .open<NursingSheetComponent, NursingSheetData, boolean>(NursingSheetComponent, {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        ariaLabelledBy: 'nv-title',
        data: { admissionId: row.notice.admissionId, tab: 'ord' },
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.load(false);
        this.store.refresh();
      });
  }
}
