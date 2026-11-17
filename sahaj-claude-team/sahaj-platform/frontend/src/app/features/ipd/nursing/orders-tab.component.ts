import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subscription } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { sheetToast } from '../../../shared/ui/sheet-toast';
import { problemMessage } from '../../config/config-format';
import { isOutsideScope } from '../ipd-refusal';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { OrderNoticesApi } from '../orders/order-notices.api';
import { OrderNoticesStore } from '../orders/order-notices.store';
import {
  NOTICE_KIND_LABEL,
  NoticeCount,
  OrderNotice,
  OrderNoticeList,
  changeLineText,
  clock,
  detailsLine,
} from '../orders/order.model';
import { statText, unacknowledgedText } from '../orders/stat-countdown';
import { staleBoardText } from '../safety/safety.model';

export const ORDERS_EMPTY = 'No new orders.';
export const ORDERS_LOAD_ERROR = 'We could not load the orders. Try again.';
export const ACK_ERROR = 'We could not acknowledge that order. Try again.';
const TICK_MS = 15_000;

/**
 * The tab "Orders" of the Nursing sheet (SAH-76-3; reference panel "Nursing orders", "n to acknowledge"):
 * the patient's order notices, unacknowledged first (STAT first, then oldest), then acknowledged ones, each
 * with its kind (New, Changed, On hold, Resumed, Stopped), the order, who and when, for a change the
 * "<field>: <old> → <new>" lines, and the doctor's reason (clinical text, on screen only).
 *
 * - Acknowledge is one tap; the server answers with the list now. A second nurse who was first gets
 *   "<name> already acknowledged this." (409 `order-state`) and the list reloads. A stop or a hold takes effect
 *   whether or not it was acknowledged; acknowledging a Stopped notice only marks it read.
 * - STAT: "STAT · give within 15 min · 9 min left" counting on from the server's `asOf`, or red "STAT · overdue
 *   by 6 min". "Not acknowledged for 35 min" is red when the server says the notice is overdue. No text says
 *   that anyone was alerted: it is a list and a badge.
 * - The list reloads when the counts poll sees another `changeToken`; a failed refresh keeps the list and shows
 *   "Could not refresh. Showing data from 11:05 AM.". A failed first load shows "We could not load the orders.
 *   Try again." with a Try again button; the other tabs still work.
 * - DOCTOR reads (no Acknowledge button); a read-only IPD or a discharged patient reads the list too.
 */
@Component({
  selector: 'app-orders-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './orders-tab.component.scss',
  templateUrl: './orders-tab.component.html',
})
export class OrdersTabComponent implements OnInit {
  private readonly api = inject(OrderNoticesApi);
  protected readonly store = inject(OrderNoticesStore);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  private readonly scopeRefusals = inject(ScopeRefusals);

  readonly admissionId = input.required<string>();
  /** The count of this list after every answer: the tab badge follows it. */
  readonly counted = output<NoticeCount>();

  protected readonly list = signal<OrderNoticeList | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly refreshFailed = signal(false);
  /** The id of the notice being acknowledged: its button is off, and so are the others (one request at a time). */
  protected readonly acking = signal<string | null>(null);
  /** Moves every 15 seconds so the STAT countdown counts on between answers. */
  protected readonly tick = signal(0);

  protected readonly empty = ORDERS_EMPTY;
  protected readonly kindLabel = NOTICE_KIND_LABEL;
  protected readonly summaryLine = (n: OrderNotice): string => `${detailsLine(n.details)}`;
  protected readonly changeText = changeLineText;
  protected readonly timeText = clock;
  protected readonly waitingText = unacknowledgedText;
  protected readonly staleText = () => staleBoardText(this.list()?.asOf);

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
    // The counts poll saw something change (a new order, a stop, an acknowledgement elsewhere): read again.
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

  ngOnInit(): void {
    this.load(true);
  }

  /** `first`: show "loading" and the load error; later reads keep the list and only note a failed refresh. */
  protected load(first = true): void {
    this.sub?.unsubscribe();
    if (first) {
      this.loading.set(true);
      this.loadError.set(null);
    }
    this.sub = this.api.forAdmission(this.admissionId()).subscribe({
      next: (list) => this.accept(list),
      error: (error: unknown) => {
        this.loading.set(false);
        if (isOutsideScope(error)) {
          this.scopeRefusals.report(this.admissionId(), false);
          return;
        }
        if (this.list() === null || first) {
          this.loadError.set(problemMessage(error, ORDERS_LOAD_ERROR));
        } else {
          this.refreshFailed.set(true);
        }
      },
    });
  }

  private accept(list: OrderNoticeList): void {
    this.list.set(list);
    this.loading.set(false);
    this.loadError.set(null);
    this.refreshFailed.set(false);
    this.receivedAt = performance.now();
    this.counted.emit(list.open);
    this.store.setCount(list.admissionId, list.open);
  }

  /** The line "STAT · give within 15 min · 9 min left" or "STAT · overdue by 6 min", counted on from `asOf`. */
  protected stat(n: OrderNotice): { text: string; overdue: boolean } | null {
    this.tick();
    const list = this.list();
    if (!n.stat || !list) {
      return null;
    }
    return statText(n.stat, list.asOf, performance.now() - this.receivedAt);
  }

  protected isOpen(n: OrderNotice): boolean {
    return n.acknowledgedAt === null;
  }

  protected acknowledge(n: OrderNotice): void {
    if (this.acking() !== null || !n.canAcknowledge) {
      return;
    }
    this.acking.set(n.id);
    this.api
      .acknowledge(n.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.acking.set(null);
          this.accept(list);
        },
        error: (error: unknown) => {
          this.acking.set(null);
          if (isOutsideScope(error)) {
            this.scopeRefusals.report(this.admissionId(), true);
            return;
          }
          this.snackBar.open(problemMessage(error, ACK_ERROR), 'Close', sheetToast(6000));
          if (error instanceof ApiError && (error.status === 409 || error.status === 404)) {
            this.load(false);
          }
        },
      });
  }
}
