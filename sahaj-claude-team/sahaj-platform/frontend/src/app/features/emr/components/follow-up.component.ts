import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { formatDisplayDate } from '../../../shared/format/ist-date';
import { EmrFollowUpInterval, EmrFollowUpValue } from '../emr-finish.model';

/** The five choices of the mockup's Follow-up section (SO-45-13), with the contract's interval keys. */
export const FOLLOW_UP_CHOICES: readonly { key: EmrFollowUpInterval; label: string }[] = [
  { key: 'D3', label: '3 days' },
  { key: 'D7', label: '7 days' },
  { key: 'D15', label: '15 days' },
  { key: 'M1', label: '1 month' },
  { key: 'M3', label: '3 months' },
];

export const FOLLOW_UP_NOTE = 'Books an appointment on that day.';
export const SMS_NOTE = 'SMS reminders: later';

export function followUpLabel(interval: EmrFollowUpInterval | null | undefined): string | null {
  return FOLLOW_UP_CHOICES.find((c) => c.key === interval)?.label ?? null;
}

export function followUpOf(value: unknown): EmrFollowUpValue {
  const v = value as Partial<EmrFollowUpValue> | null;
  return v && v.component === 'FOLLOW_UP'
    ? { component: 'FOLLOW_UP', schemaVersion: 1, interval: v.interval ?? null, dueDate: v.dueDate ?? null }
    : { component: 'FOLLOW_UP', schemaVersion: 1, interval: null, dueDate: null };
}

/**
 * The Follow-up choice (SAH-51-6; mockup #GeneralTreatment "Follow-up"): one of five chips, or none.
 * Tapping the chosen chip takes it back. The reason is always "Review after treatment" and never a
 * diagnosis (OQ-43-32), so it is not asked here. The SMS reminder is drawn switched off (OQ-45-27).
 * Nothing is typed, so nothing needs a log guard.
 */
@Component({
  selector: 'app-follow-up',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    h3 {
      margin: 0 0 8px;
      font-size: 1rem;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .chip {
      min-height: 44px;
      padding: 0 16px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--surface);
      color: var(--ink-2);
      font-weight: 600;
      cursor: pointer;
    }
    .chip[aria-pressed='true'] {
      border-color: var(--pri);
      background: var(--pri-soft);
      color: var(--pri-text);
    }
    .chip:disabled {
      opacity: 0.55;
      cursor: default;
    }
    .notes {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 16px;
      margin-top: 8px;
    }
    .sms {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
  `,
  template: `
    <section [attr.aria-labelledby]="inputId() + '-t'">
      <h3 [id]="inputId() + '-t'">{{ title() }}</h3>
      <div class="chips" role="group" [attr.aria-labelledby]="inputId() + '-t'">
        @for (c of choices; track c.key) {
          <button
            type="button"
            class="chip"
            [attr.data-interval]="c.key"
            [attr.aria-pressed]="interval() === c.key"
            [disabled]="disabled()"
            (click)="pick(c.key)"
          >
            {{ c.label }}
          </button>
        }
      </div>
      <div class="notes faint t-xs">
        <span data-testid="follow-note">{{ note }}</span>
        @if (due(); as date) {
          <span data-testid="follow-due">Due {{ date }}</span>
        }
        <label class="sms">
          <input type="checkbox" disabled data-testid="sms-off" />
          {{ sms }}
        </label>
      </div>
    </section>
  `,
})
export class FollowUpComponent {
  readonly title = input('Follow-up');
  readonly value = input<unknown>(null);
  readonly disabled = input(false);
  readonly inputId = input.required<string>();

  readonly valueChange = output<EmrFollowUpValue>();

  protected readonly choices = FOLLOW_UP_CHOICES;
  protected readonly note = FOLLOW_UP_NOTE;
  protected readonly sms = SMS_NOTE;

  protected readonly current = computed(() => followUpOf(this.value()));
  protected readonly interval = computed(() => this.current().interval);
  protected readonly due = computed(() => {
    const d = this.current().dueDate;
    return d ? formatDisplayDate(d) : null;
  });

  protected pick(key: EmrFollowUpInterval): void {
    if (this.disabled()) {
      return;
    }
    this.valueChange.emit({ component: 'FOLLOW_UP', schemaVersion: 1, interval: this.interval() === key ? null : key });
  }
}
