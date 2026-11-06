import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { ApiError } from '../../../core/http/problem-detail';
import { formatDisplayDate, istToday } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PageHeadComponent } from '../../../shared/ui/page-head.component';
import { WHATSAPP_NOT_IN_PHASE } from '../../../shared/whatsapp';
import {
  CHART_HEIGHT,
  CHART_WIDTH,
  EXPORT_NOT_IN_PHASE,
  NON_OWNER_MESSAGE,
  REVENUE_RANGES,
  changeText,
  dayBars,
  doctorRows,
  modeRows,
  revenueLead,
  rupees,
  serviceRows,
} from '../revenue-display.util';
import { RevenueApi } from '../revenue.api';
import { RevenueRange, RevenueReport } from '../revenue.model';

const LOAD_ERROR = 'We could not load the revenue report. Try again.';

/** Revenue (SAH-16), mockup `vRevenue`. Read-only; the API is owner-only and the page shows a
 * message when it answers 403. WhatsApp reminders and Excel export are not in this phase. */
@Component({
  selector: 'app-revenue',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink, IconComponent, PageHeadComponent],
  styleUrl: './revenue.page.scss',
  templateUrl: './revenue.page.html',
})
export class RevenuePage implements OnInit {
  private readonly api = inject(RevenueApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly ranges = REVENUE_RANGES;
  protected readonly whatsappNote = WHATSAPP_NOT_IN_PHASE;
  protected readonly exportNote = EXPORT_NOT_IN_PHASE;
  protected readonly nonOwnerMessage = NON_OWNER_MESSAGE;
  protected readonly rupees = rupees;
  protected readonly formatDate = formatDisplayDate;
  protected readonly chartWidth = CHART_WIDTH;
  protected readonly chartHeight = CHART_HEIGHT;
  protected readonly skeletonTiles = [1, 2, 3, 4];

  protected readonly range = signal<RevenueRange>('TODAY');
  protected readonly report = signal<RevenueReport | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly forbidden = signal(false);

  protected readonly lead = computed(() => revenueLead(this.range()));
  protected readonly change = computed(() => changeText(this.report()?.collectedChangePercent));
  protected readonly changeIsDown = computed(
    () => (this.report()?.collectedChangePercent ?? 0) < 0,
  );
  protected readonly bars = computed(() => dayBars(this.report()?.days ?? [], istToday()));
  protected readonly modes = computed(() => modeRows(this.report()?.modes ?? []));
  protected readonly doctors = computed(() => doctorRows(this.report()?.doctors ?? []));
  protected readonly services = computed(() => serviceRows(this.report()?.topServices ?? []));
  protected readonly dueTotal = computed(() =>
    (this.report()?.dues ?? []).reduce((sum, d) => sum + d.balancePaise, 0),
  );

  ngOnInit(): void {
    this.load();
  }

  protected selectRange(range: RevenueRange): void {
    if (range === this.range()) {
      return;
    }
    this.range.set(range);
    this.load();
  }

  protected load(): void {
    const requested = this.range();
    this.loading.set(true);
    this.loadError.set(null);
    this.forbidden.set(false);
    this.api
      .report(requested)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (report) => {
          if (requested !== this.range()) {
            return;
          }
          this.report.set(report);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          if (requested !== this.range()) {
            return;
          }
          this.report.set(null);
          if (error instanceof ApiError && error.status === 403) {
            this.forbidden.set(true);
          } else {
            this.loadError.set(LOAD_ERROR);
          }
          this.loading.set(false);
        },
      });
  }
}
