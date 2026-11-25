import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { formatClockLabel, formatDisplayDate } from '../../../shared/format/ist-date';
import {
  DeskBookedSummary,
  DeskDoctor,
  DeskFollowUpToBook,
  DeskPanelMode,
  DeskProblem,
  dayLongLabel,
  followUpHint,
  rescheduleHint,
  shortDoctorName,
} from './desk.model';
import { DeskBookPanelComponent } from './desk-book-panel.component';
import { DeskFollowPanelComponent } from './desk-follow-panel.component';
import { DeskTokenPanelComponent } from './desk-token-panel.component';

/**
 * The action panel. One component, one mode at a time (`DeskPanelMode`): Idle with the follow-ups to book,
 * Pick a slot, Confirm booking, Booked, Token and Follow-up. Writes happen in the mode components; the page
 * refreshes the console after each one.
 */
@Component({
  selector: 'app-desk-action-panel',
  imports: [MatButtonModule, DeskBookPanelComponent, DeskFollowPanelComponent, DeskTokenPanelComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './desk-action-panel.component.scss',
  templateUrl: './desk-action-panel.component.html',
})
export class DeskActionPanelComponent {
  readonly mode = input.required<DeskPanelMode>();
  readonly followUps = input.required<readonly DeskFollowUpToBook[]>();
  readonly followUpTotal = input(0);
  /** The doctor selected in the rail (Confirm booking). */
  readonly doctor = input<DeskDoctor | null>(null);
  /** The doctor of the visit in the Follow-up mode (fee and department). */
  readonly followDoctor = input<DeskDoctor | null>(null);
  readonly today = input('');
  readonly canAct = input(false);
  /** A write is running anywhere on the page. */
  readonly busy = input(false);

  readonly closePanel = output<void>();
  readonly findSlot = output<DeskFollowUpToBook>();
  readonly booked = output<DeskBookedSummary>();
  readonly followSaved = output<void>();
  readonly failed = output<DeskProblem>();
  readonly busyChange = output<boolean>();

  protected readonly shortName = shortDoctorName;
  protected readonly clock = formatClockLabel;
  protected readonly dayLabel = dayLongLabel;
  protected readonly fullDate = formatDisplayDate;
  protected readonly rescheduleHint = rescheduleHint;
  protected readonly followUpHint = followUpHint;

  protected statusText(item: DeskFollowUpToBook): string {
    return item.status === 'MISSED' ? 'Overdue' : 'Due today';
  }

  /** "Added to Booked in today's flow." for today, else "It will appear in the flow on that day.". */
  protected bookedNote(date: string): string {
    return date === this.today() ? "Added to Booked in today's flow." : 'It will appear in the flow on that day.';
  }
}
