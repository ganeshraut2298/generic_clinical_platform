import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ApiError } from '../../../core/http/problem-detail';
import { formatClockLabel, formatDisplayDate } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { PageHeadComponent } from '../../../shared/ui/page-head.component';
import { WHATSAPP_NOT_IN_PHASE } from '../../../shared/whatsapp';
import {
  FOLLOW_UP_TABS,
  doctorShortName,
  followUpPillClass,
  followUpPillText,
  followUpSeverity,
} from '../followups-display.util';
import { FollowUpsApi } from '../followups.api';
import { FollowUp, FollowUpCounts, FollowUpStatus } from '../followups.model';

const LOAD_ERROR = 'We could not load follow-ups. Try again.';
const EMPTY_COUNTS: FollowUpCounts = { due: 0, missed: 0, upcoming: 0, returned: 0 };

/** Follow-ups (SAH-14), mockup `vFollowups`: four tabs with counts and a list per tab.
 * WhatsApp reminders are not in this phase, so the Remind buttons are shown disabled. */
@Component({
  selector: 'app-followups',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, PageHeadComponent, EmptyStateComponent],
  styleUrl: './followups.page.scss',
  templateUrl: './followups.page.html',
})
export class FollowUpsPage implements OnInit {
  private readonly api = inject(FollowUpsApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly tabs = FOLLOW_UP_TABS;
  protected readonly notInPhase = WHATSAPP_NOT_IN_PHASE;
  protected readonly pillText = followUpPillText;
  protected readonly pillClass = followUpPillClass;
  protected readonly severity = followUpSeverity;
  protected readonly doctorShortName = doctorShortName;

  /** "5 Nov 2026, 5:00 PM" of the appointment the doctor's sign booked (SAH-51-6). */
  protected appointmentText(date: string, time: string | null | undefined): string {
    return time ? `${formatDisplayDate(date)}, ${formatClockLabel(time)}` : formatDisplayDate(date);
  }

  protected readonly status = signal<FollowUpStatus>('DUE');
  protected readonly counts = signal<FollowUpCounts>(EMPTY_COUNTS);
  protected readonly items = signal<FollowUp[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected severityText(status: FollowUpStatus): string {
    return status === 'MISSED'
      ? 'Missed'
      : status === 'DUE'
        ? 'Due today'
        : status === 'UPCOMING'
          ? 'Upcoming'
          : 'Returned';
  }

  protected onTabKeydown(event: KeyboardEvent): void {
    const order = this.tabs.map((t) => t.status);
    const at = order.indexOf(this.status());
    let next: number;
    switch (event.key) {
      case 'ArrowRight':
        next = (at + 1) % order.length;
        break;
      case 'ArrowLeft':
        next = (at - 1 + order.length) % order.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = order.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    this.selectTab(order[next]);
    document.getElementById('fu-tab-' + order[next])?.focus();
  }

  protected countFor(status: FollowUpStatus): number {
    return this.counts()[status.toLowerCase() as keyof FollowUpCounts];
  }

  protected selectTab(status: FollowUpStatus): void {
    this.status.set(status);
    this.load();
  }

  protected load(): void {
    const requested = this.status();
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .list(requested)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          if (requested !== this.status()) {
            return;
          }
          this.counts.set(list.counts);
          this.items.set(list.items);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          if (requested !== this.status()) {
            return;
          }
          this.items.set([]);
          this.loadError.set(
            error instanceof ApiError ? (error.problem.detail ?? LOAD_ERROR) : LOAD_ERROR,
          );
          this.loading.set(false);
        },
      });
  }
}
