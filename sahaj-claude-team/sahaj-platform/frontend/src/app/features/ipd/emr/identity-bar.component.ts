import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmrPatient, allergyChip, identityLine } from './emr.model';

/**
 * Identity bar (mockup `sfIdBar`), at the top of the EMR sheet and the Verify sheet: the name in
 * bold, "<uhid> · <age> · <sex> · bed <bed>", and the allergy chip. The chip says its meaning in
 * words and with an icon, so colour is never the only signal. Names and allergy text are on screen
 * only.
 */
@Component({
  selector: 'app-identity-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styles: `
    .sfid {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      padding: 10px 12px;
      border-radius: 12px;
      background: var(--glass-hi);
      border: 1px solid var(--line);
    }
    .sfidn {
      display: flex;
      flex-direction: column;
      min-width: 0;
      b {
        font-size: 0.98rem;
        overflow-wrap: anywhere;
      }
      small {
        color: var(--ink-3);
        font-size: 0.78rem;
      }
    }
    .sfalg {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: 0.8rem;
      font-weight: 600;
      padding: 4px 10px;
      border-radius: 99px;
      overflow-wrap: anywhere;
      &.bad {
        background: color-mix(in srgb, var(--crit) 12%, transparent);
        color: var(--crit);
      }
      &.ok {
        background: color-mix(in srgb, var(--good) 12%, transparent);
        color: var(--good);
      }
      &.nr {
        background: color-mix(in srgb, var(--warn) 14%, transparent);
        color: var(--warn);
      }
    }
  `,
  template: `
    <div class="sfid" data-testid="identity-bar">
      <span class="sfidn">
        <b data-testid="identity-name">{{ patient().name }}</b>
        <small data-testid="identity-line">{{ line() }}</small>
      </span>
      <span [class]="'sfalg ' + chip().tone" data-testid="allergy-chip" [attr.data-tone]="chip().tone">
        <app-icon [name]="chip().icon" [size]="13" /> {{ chip().text }}
      </span>
    </div>
  `,
})
export class IdentityBarComponent {
  readonly patient = input.required<EmrPatient>();
  protected readonly line = computed(() => identityLine(this.patient()));
  protected readonly chip = computed(() => allergyChip(this.patient()));
}
