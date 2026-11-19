import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { ActiveReason } from '../pharmacy-inventory.model';

/**
 * The reason chips of the indent screens (OQ-79-15): one chip may be chosen; "Other" (a key ending in `.other`) needs a
 * note, which the screen asks for. Large enough to use with one hand (BRD section 7).
 */
@Component({
  selector: 'app-reason-chips',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    button {
      min-height: 44px;
      min-width: 44px;
      padding: 0 14px;
      border: 1px solid var(--line);
      border-radius: 999px;
      background: var(--glass-hi);
      font: inherit;
      cursor: pointer;
    }
    button[aria-pressed='true'] {
      border-color: var(--pri-text);
      color: var(--pri-text);
      font-weight: 700;
      box-shadow: inset 0 0 0 1px var(--pri-text);
    }
  `,
  template: `
    <div class="chips" role="group" [attr.aria-label]="label()">
      @for (reason of reasons(); track reason.reasonKey) {
        <button
          type="button"
          [attr.aria-pressed]="reason.reasonKey === value()"
          [attr.data-testid]="testIdPrefix() + reason.reasonKey"
          (click)="chosen.emit(reason)"
        >
          {{ reason.label }}
        </button>
      }
    </div>
  `,
})
export class ReasonChipsComponent {
  readonly reasons = input.required<ActiveReason[]>();
  readonly value = input<string | null>(null);
  readonly label = input('Reason');
  readonly testIdPrefix = input('reason-');
  readonly chosen = output<ActiveReason>();
}
