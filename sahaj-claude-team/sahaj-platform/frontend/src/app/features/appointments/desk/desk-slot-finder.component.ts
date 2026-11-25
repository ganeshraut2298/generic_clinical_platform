import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { formatClockLabel } from '../../../shared/format/ist-date';
import {
  DeskDaySlots,
  DeskDoctor,
  DeskSlotGroupLabel,
  doctorFeeLine,
  doctorInitials,
  doctorStatusText,
  doctorStatusTone,
  freeCountText,
  noSlotsMessage,
  stripDayLabel,
} from './desk.model';

/** Slot finder (SAH-90-3): doctor card, 7-day strip, Morning and Evening slots. */
@Component({
  selector: 'app-desk-slot-finder',
  imports: [MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './desk-slot-finder.component.scss',
  templateUrl: './desk-slot-finder.component.html',
})
export class DeskSlotFinderComponent {
  readonly doctor = input<DeskDoctor | null>(null);
  readonly today = input.required<string>();
  readonly date = input.required<string>();
  readonly slots = input<DeskDaySlots | null>(null);
  readonly loading = input(false);
  readonly error = input<string | null>(null);
  /** OWNER and FRONT_DESK only; others see slots but clicking does not open booking. */
  readonly canBook = input(false);
  readonly pickedTime = input<string | null>(null);

  readonly dayChange = output<string>();
  readonly slotPick = output<string>();
  readonly retry = output<void>();

  protected readonly initials = doctorInitials;
  protected readonly statusText = doctorStatusText;
  protected readonly statusTone = doctorStatusTone;
  protected readonly feeLine = doctorFeeLine;
  protected readonly dayLabel = stripDayLabel;
  protected readonly freeText = freeCountText;
  protected readonly clock = formatClockLabel;

  protected readonly groupTitles: Record<DeskSlotGroupLabel, string> = { MORNING: 'Morning', EVENING: 'Evening' };

  /** The next day with slots: the server's, else the first later day of the rail's 7 days. */
  protected readonly emptyMessage = computed(() => {
    const doctor = this.doctor();
    const slots = this.slots();
    if (!doctor || !slots || slots.freeCount > 0) {
      return null;
    }
    const fromStrip = doctor.days.find((d) => d.date > slots.date && d.freeCount > 0)?.date ?? null;
    return noSlotsMessage(slots.date, slots.nextFreeDate ?? fromStrip);
  });
}
