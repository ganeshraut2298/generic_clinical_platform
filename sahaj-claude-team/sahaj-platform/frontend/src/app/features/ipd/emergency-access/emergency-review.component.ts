import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
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
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { isRefusal } from '../ipd-refusal';
import { EmergencyAccessApi } from './emergency-access.api';
import {
  ALREADY_REVIEWED,
  EmergencyAccessReviewPage,
  EmergencyAccessReviewRow,
  MARK_REVIEWED,
  MORE_ERROR,
  PANEL_SUBTITLE,
  PANEL_TITLE,
  REVIEWED_TOAST,
  REVIEW_ERROR,
  SHOW_MORE,
  markReviewedName,
  reviewHeadline,
  reviewSmallLine,
} from './emergency-access.model';

export const EMERGENCY_REVIEW_HEADING_ID = 'sf-eg-h';
const AUDIT_HEADING_ID = 'sf-aud-h';

/**
 * The panel "Emergency access to review" of the owner's Safety tab (SAH-34, story 34F; mockup `ipSafety`, `bgRow`,
 * `sfbgrev`): one row per grant not yet reviewed, "<staff> opened <patient> · bed <code>", the small line
 * "<reason> · <time> · outside their wards" and the button "Mark reviewed".
 *
 * - The rows, the order (oldest first), the names and the times are the server's (`page`, read in the board's
 *   transaction); the panel draws them and counts nothing. It is not drawn when `page.waiting` is 0.
 * - Mark reviewed is one write; the button is off while it runs. A row leaves only after the server said so
 *   (the board then reads again, so the tile and the tab number follow). 409 `already-reviewed` shows "Already reviewed."
 *   and the board reads again; another failure keeps the row and says so. A 403 clears the board (SAH-40).
 * - "Show 25 more" asks for the next page with the cursor; a failure keeps the rows already drawn.
 * - A new board answer replaces what the panel holds (it follows the server). No comment, outcome, history
 *   or filter exists (the mockup shows none, AC 77). Names are on screen only: never logged or stored.
 */
@Component({
  selector: 'app-emergency-review',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './emergency-review.component.scss',
  templateUrl: './emergency-review.component.html',
})
export class EmergencyReviewComponent {
  private readonly api = inject(EmergencyAccessApi);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** The first page of the board (`SafetyBoard.emergencyAccess`). */
  readonly page = input.required<EmergencyAccessReviewPage>();
  /** A review changed the data (done, or already done by someone else): the board reads again. */
  readonly changed = output<void>();
  /** A 403: the board is refused (SAH-40); the board clears and shows the notice. */
  readonly refused = output<ApiError>();

  protected readonly title = PANEL_TITLE;
  protected readonly headingId = EMERGENCY_REVIEW_HEADING_ID;
  protected readonly subtitle = PANEL_SUBTITLE;
  protected readonly markLabel = MARK_REVIEWED;
  protected readonly moreLabel = SHOW_MORE;
  protected readonly headline = reviewHeadline;
  protected readonly markName = markReviewedName;
  protected readonly small = (row: EmergencyAccessReviewRow): string => reviewSmallLine(row, this.asOf());

  /** Pages read with "Show 25 more", after the first page. */
  private readonly extra = signal<EmergencyAccessReviewRow[]>([]);
  /** The cursor of the last extra page: undefined until "Show 25 more" has answered, null at the end of the list. */
  private readonly cursorMore = signal<string | null | undefined>(undefined);
  /** Rows the server has reviewed that the board has not yet dropped. */
  private readonly done = signal<ReadonlySet<string>>(new Set());
  private readonly asOfMore = signal<string | null>(null);

  protected readonly busy = signal(false);
  protected readonly loadingMore = signal(false);
  /** A message above the rows: "Already reviewed." or a failure. */
  protected readonly notice = signal<string | null>(null);

  protected readonly asOf = computed(() => this.asOfMore() ?? this.page().asOf);
  protected readonly rows = computed(() =>
    [...this.page().items, ...this.extra()].filter((r) => !this.done().has(r.grantId)),
  );
  protected readonly cursor = computed(() => {
    const more = this.cursorMore();
    return more === undefined ? this.page().nextCursor : more;
  });

  constructor() {
    // A new board answer replaces everything the panel holds: it follows the server.
    effect(() => {
      this.page();
      untracked(() => {
        this.extra.set([]);
        this.cursorMore.set(undefined);
        this.asOfMore.set(null);
        this.done.set(new Set());
      });
    });
  }

  protected markReviewed(row: EmergencyAccessReviewRow): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.notice.set(null);
    this.api
      .review(row.grantId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.busy.set(false);
          const index = this.rows().findIndex((r) => r.grantId === row.grantId);
          this.done.update((d) => new Set(d).add(row.grantId));
          this.snackBar.open(REVIEWED_TOAST, 'Close', { duration: 6000 });
          this.placeFocus(index);
          this.changed.emit();
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.failed(error);
        },
      });
  }

  private failed(error: unknown): void {
    if (isRefusal(error)) {
      this.refused.emit(error as ApiError);
      return;
    }
    if (error instanceof ApiError && error.status === 409 && error.problem.type.endsWith('already-reviewed')) {
      // The panel may disappear with the reload (it was the last row), so this is a toast, not a line in the panel.
      this.snackBar.open(error.problem.detail ?? ALREADY_REVIEWED, 'Close', { duration: 6000 });
      this.changed.emit();
      return;
    }
    if (error instanceof ApiError && error.status === 404) {
      this.notice.set(error.problem.detail ?? REVIEW_ERROR);
      this.changed.emit();
      return;
    }
    this.notice.set(
      error instanceof ApiError && error.status > 0 && error.status < 500
        ? (error.problem.detail ?? REVIEW_ERROR)
        : REVIEW_ERROR,
    );
  }

  protected showMore(): void {
    const cursor = this.cursor();
    if (cursor === null || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    this.notice.set(null);
    this.api
      .reviewPage(cursor)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (next) => {
          this.loadingMore.set(false);
          const known = new Set([...this.page().items, ...this.extra()].map((r) => r.grantId));
          this.extra.update((rows) => [...rows, ...next.items.filter((r) => !known.has(r.grantId))]);
          this.cursorMore.set(next.nextCursor);
          this.asOfMore.set(next.asOf);
        },
        error: (error: unknown) => {
          this.loadingMore.set(false);
          if (isRefusal(error)) {
            this.refused.emit(error as ApiError);
            return;
          }
          this.notice.set(MORE_ERROR);
        },
      });
  }

  /** The reviewed row is gone: focus goes to the next row's button, or to the audit trail heading when none is left. */
  private placeFocus(index: number): void {
    afterNextRender(
      () => {
        const buttons = Array.from(
          this.host.nativeElement.querySelectorAll<HTMLElement>('[data-testid="mark-reviewed"]'),
        );
        const target = buttons[Math.min(Math.max(index, 0), buttons.length - 1)];
        (target ?? this.host.nativeElement.ownerDocument.getElementById(AUDIT_HEADING_ID))?.focus();
      },
      { injector: this.injector },
    );
  }
}
