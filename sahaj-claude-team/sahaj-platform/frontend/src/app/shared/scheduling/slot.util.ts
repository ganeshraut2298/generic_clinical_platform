import { isoWeekday } from '../format/ist-date';

/** A doctor's one or two OPD sessions, start and end as `HH:mm`. */
export interface SlotSession {
  startTime: string;
  endTime: string;
}

/** The subset of `DoctorSlotSettings` (`docs/api/opd.yaml`) needed to compute a day's slots.
 * Kept here, rather than importing the Appointments feature's model, so this pure function has
 * no dependency on a feature folder. */
export interface SlotTemplate {
  slotMinutes: number;
  workingDays: readonly number[];
  walkInEveryNth: number;
  sessions: readonly SlotSession[];
}

export interface ComputedSlot {
  /** `HH:mm`, 24-hour. */
  time: string;
  sessionIndex: number;
  /** True when this slot is kept free for walk-ins (story acceptance criterion 1). */
  walkIn: boolean;
}

/** `09:05` -> 545. Exported so other pure logic (such as the "Due"/"Late" display status) can
 * compare clock times without re-implementing this. */
export function toMinutes(hhmm: string): number {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
}

function toHHMM(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/**
 * Port of the mockup's `slotsFor`: the day's bookable slots from a doctor's slot template
 * (story acceptance criterion 1). Returns `null` when the doctor does not work that day.
 */
export function computeSlots(template: SlotTemplate, isoDate: string): ComputedSlot[] | null {
  if (!template.workingDays.includes(isoWeekday(isoDate))) {
    return null;
  }
  const out: ComputedSlot[] = [];
  template.sessions.forEach((session, sessionIndex) => {
    const start = toMinutes(session.startTime);
    const end = toMinutes(session.endTime);
    let i = 0;
    for (let m = start; m + template.slotMinutes <= end; m += template.slotMinutes, i += 1) {
      out.push({
        time: toHHMM(m),
        sessionIndex,
        walkIn: template.walkInEveryNth > 0 && (i + 1) % template.walkInEveryNth === 0,
      });
    }
  });
  return out;
}
