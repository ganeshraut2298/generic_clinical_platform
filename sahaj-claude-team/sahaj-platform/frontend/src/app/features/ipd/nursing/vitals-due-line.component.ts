import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { IconComponent } from '../../../shared/icon/icon.component';
import { clockWithMinutes } from '../mar/mar.model';

/** What the line says and how it looks: `null` when there is nothing to say (a discharged patient). */
export interface VitalsDueView {
  text: string;
  tone: 'ok' | 'warn' | 'crit';
}

/**
 * SAH-76-6 (F1): the line under the score notice of the tab "Vitals and notes".
 * - before the due time: "Next vitals due 12:00 PM";
 * - within the first minute after it: "Vitals due now";
 * - after that: "Vitals overdue by 25 min" (whole minutes from the server's `asOf`).
 * A patient with no vitals yet has no due time (`dueAt` null): the vitals are due at once, so the line
 * reads "Vitals due now" (text NEW for this case; the contract gives no wording). Nothing is shown for a
 * discharged patient. The device clock is never read: both instants come from the server.
 */
export function vitalsDueView(dueAt: string | null, asOf: string, discharged = false): VitalsDueView | null {
  if (discharged) {
    return null;
  }
  if (dueAt === null) {
    return { text: 'Vitals due now', tone: 'warn' };
  }
  const due = Date.parse(dueAt);
  const now = Date.parse(asOf);
  if (Number.isNaN(due) || Number.isNaN(now)) {
    return null;
  }
  const minutes = Math.floor((now - due) / 60_000);
  if (minutes < 0) {
    return { text: `Next vitals due ${clockWithMinutes(dueAt)}`, tone: 'ok' };
  }
  if (minutes < 1) {
    return { text: 'Vitals due now', tone: 'warn' };
  }
  return { text: `Vitals overdue by ${minutes} min`, tone: 'crit' };
}

/** "1 hour", "4 hours" for the notice "Routine vitals every <interval>." (`NursingSheet.vitalsIntervalMinutes`). */
export function vitalsIntervalText(minutes: number): string {
  if (minutes % 60 === 0) {
    const h = minutes / 60;
    return h === 1 ? '1 hour' : `${h} hours`;
  }
  return `${minutes} minutes`;
}

@Component({
  selector: 'app-vitals-due-line',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  template: `
    @if (view(); as v) {
      <p class="vdue" [class.warn]="v.tone === 'warn'" [class.crit]="v.tone === 'crit'" data-testid="vitals-due">
        <app-icon [name]="v.tone === 'ok' ? 'clock' : 'alert'" [size]="14" />
        <span>{{ v.text }}</span>
      </p>
    }
  `,
  styles: `
    .vdue {
      display: flex;
      align-items: center;
      gap: 6px;
      margin: 0;
      font-size: 0.84rem;
      font-weight: 600;
      color: var(--ink-2);
    }
    .vdue.warn {
      color: var(--warn);
    }
    .vdue.crit {
      color: var(--crit);
    }
  `,
})
export class VitalsDueLineComponent {
  /** `NursingSheet.vitalsDueAt`. */
  readonly dueAt = input.required<string | null>();
  /** `NursingSheet.asOf`. */
  readonly asOf = input.required<string>();
  readonly discharged = input(false);

  protected readonly view = computed(() => vitalsDueView(this.dueAt(), this.asOf(), this.discharged()));
}
