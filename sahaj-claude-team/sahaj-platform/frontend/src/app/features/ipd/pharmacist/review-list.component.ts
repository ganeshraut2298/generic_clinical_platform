import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subscription } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { severityWord } from '../emr/emr.model';
import { detailsLine } from '../orders/order.model';
import { PharmacistApi } from './pharmacist.api';
import {
  PharmacistActionResult,
  PharmacistReviewPage,
  PharmacistReviewRow,
  REVIEW_EMPTY,
  REVIEW_ERROR,
  REVIEW_LOAD_ERROR,
  STAT_HARD_STOP_HINT,
  allergyLabel,
  givenBeforeLine,
  queriedToast,
  reviewHeading,
  reviewedToast,
} from './pharmacist.model';
import { QuerySheetComponent, QuerySheetData } from './query-sheet.component';

const FIRST_PAGE = 50;

/**
 * "Review after dose" (SAH-76-2, B2; heading NEW): the STAT doses the nurse gave before the pharmacist
 * check, above the waiting orders. Each row shows who gave it and when, the checks run now, and the buttons
 * Mark reviewed and Query doctor. A hard stop found now replaces Mark reviewed with Query doctor only (the
 * doctor must act) until the order is stopped; the server sends `canMarkReviewed` and `canQuery`. A row
 * leaves only through an action: it never disappears on a timer.
 * The list is read when it opens and whenever the count the panel holds (`open`) changes.
 */
@Component({
  selector: 'app-review-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './pharmacist-panel.component.scss',
  template: `
    <section class="rv" aria-labelledby="ph-rv-h" data-testid="review-list">
      <h3 id="ph-rv-h" class="rvh">{{ heading() }}</h3>
      @if (loadError(); as message) {
        <div class="notice err" role="alert" data-testid="review-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ message }}</span>
          <button type="button" mat-stroked-button data-act="rv-retry" (click)="load()">Try again</button>
        </div>
      } @else if (page(); as pg) {
        @if (pg.items.length === 0) {
          <div class="empty" data-testid="review-empty">{{ empty }}</div>
        } @else {
          <div role="list" aria-label="Doses to review">
            @for (r of pg.items; track r.id) {
              <div class="sfrow" role="listitem" [attr.data-review]="r.id">
                <span class="sfmn">
                  <b data-testid="rv-title">{{ r.drugName }} · {{ line(r) }}</b>
                  <small data-testid="rv-patient">
                    {{ r.patient.name }} · bed {{ r.patient.bedCode }} · {{ allergy(r) }}
                  </small>
                  <small data-testid="rv-given">{{ given(r) }}</small>
                  @for (f of r.check.hardStops; track $index) {
                    <small class="sfbad" data-testid="rv-hardstop">
                      <app-icon name="alert" [size]="12" /> {{ f.text }}
                    </small>
                  }
                  @for (w of r.check.warnings; track $index) {
                    <small class="sfwarn" data-testid="rv-warning">
                      <app-icon name="alert" [size]="12" /> {{ severity(w.severity) }}: {{ w.text }}
                    </small>
                  }
                </span>
                <span class="sfacts">
                  @if (r.canQuery) {
                    <button
                      type="button"
                      mat-stroked-button
                      data-act="rv-query"
                      [disabled]="busy()"
                      [attr.aria-label]="'Query doctor about ' + r.drugName + ' for ' + r.patient.name"
                      (click)="query(r)"
                    >
                      Query doctor
                    </button>
                  }
                  @if (r.canMarkReviewed) {
                    <button
                      type="button"
                      mat-flat-button
                      data-act="rv-done"
                      [disabled]="busy()"
                      [attr.aria-busy]="busy()"
                      [attr.aria-label]="'Mark ' + r.drugName + ' reviewed for ' + r.patient.name"
                      (click)="markReviewed(r)"
                    >
                      Mark reviewed
                    </button>
                  } @else if (r.check.hardStops.length > 0) {
                    <small class="faint sfhint" data-testid="rv-hint">{{ hardStopHint }}</small>
                  }
                </span>
              </div>
            }
          </div>
          @if (pg.nextCursor !== null) {
            <div class="more">
              <button
                type="button"
                mat-stroked-button
                data-act="rv-more"
                [disabled]="busy()"
                (click)="loadMore()"
              >
                Show more doses
              </button>
            </div>
          }
        }
      } @else {
        <p class="muted" role="status" aria-busy="true">Loading the doses to review</p>
      }
    </section>
  `,
})
export class ReviewListComponent {
  private readonly api = inject(PharmacistApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  /** The count the panel holds (`PharmacistChecks.reviewsOpen`): a different number reads the list again. */
  readonly open = input.required<number>();
  /** A review was closed or a question sent: the panel reads its own counts again. */
  readonly changed = output<void>();
  /** A question was sent: the answer carries the queue now. */
  readonly queried = output<PharmacistActionResult>();

  protected readonly page = signal<PharmacistReviewPage | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly empty = REVIEW_EMPTY;
  protected readonly hardStopHint = STAT_HARD_STOP_HINT;
  protected readonly heading = computed(() => reviewHeading(this.page()?.open ?? this.open()));
  protected readonly severity = severityWord;
  protected readonly allergy = (r: PharmacistReviewRow) => allergyLabel(r.patient);
  protected readonly given = givenBeforeLine;
  protected readonly line = (r: PharmacistReviewRow) => detailsLine(r.details);

  private sub: Subscription | null = null;

  constructor() {
    effect(() => {
      this.open();
      untracked(() => this.load());
    });
    this.destroyRef.onDestroy(() => this.sub?.unsubscribe());
  }

  protected load(): void {
    this.sub?.unsubscribe();
    this.loadError.set(null);
    this.sub = this.api.reviews(FIRST_PAGE).subscribe({
      next: (page) => this.page.set(page),
      error: (error: unknown) => this.loadError.set(problemMessage(error, REVIEW_LOAD_ERROR)),
    });
  }

  protected loadMore(): void {
    const cursor = this.page()?.nextCursor;
    if (!cursor || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api
      .reviews(FIRST_PAGE, cursor)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (next) => {
          this.busy.set(false);
          const current = this.page();
          const known = new Set((current?.items ?? []).map((r) => r.id));
          this.page.set({ ...next, items: [...(current?.items ?? []), ...next.items.filter((r) => !known.has(r.id))] });
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.toast(problemMessage(error, REVIEW_LOAD_ERROR));
        },
      });
  }

  protected markReviewed(row: PharmacistReviewRow): void {
    if (this.busy() || !row.canMarkReviewed) {
      return;
    }
    this.busy.set(true);
    this.api
      .markReviewed(row.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.busy.set(false);
          this.page.set(page);
          this.toast(reviewedToast(row.drugName));
          this.changed.emit();
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.toast(problemMessage(error, REVIEW_ERROR));
          if (error instanceof ApiError && (error.status === 409 || error.status === 404)) {
            this.load();
            this.changed.emit();
          }
        },
      });
  }

  protected query(row: PharmacistReviewRow): void {
    if (this.busy() || !row.canQuery) {
      return;
    }
    const sheet = this.dialog.open<QuerySheetComponent, QuerySheetData, PharmacistActionResult | undefined>(
      QuerySheetComponent,
      {
        panelClass: 'sheet-panel',
        width: '520px',
        maxWidth: '100vw',
        ariaLabelledBy: 'sf-qd-title',
        data: {
          orderId: row.orderId,
          orderVersion: row.orderVersion,
          drugName: row.drugName,
          subtitle: `${row.patient.name} · ${row.patient.uhid}`,
          onConflict: () => {
            this.load();
            this.changed.emit();
          },
        },
      },
    );
    sheet
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((answer) => {
        if (answer) {
          this.toast(queriedToast(answer.result.drugName));
          this.queried.emit(answer);
          this.load();
        }
      });
  }

  private toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 6000 });
  }
}
