import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { IconComponent } from '../../shared/icon/icon.component';
import { PIN_PILL, TeamMemberPin, ownPinButton } from './pin.model';

/**
 * The PIN part of a Team list row (SAH-31, ADR-010 D12; mockup panel Staff `f-staff`): the pill
 * "PIN set" / "PIN locked" / "No PIN yet" (the server sends `pin` to the owner for every row and to
 * everyone for their own row only), the OWNER button "Reset PIN" on rows with a PIN set or locked,
 * and the own-row button "Set my PIN" / "Change my PIN". The words carry the state, so colour is
 * never the only signal. The parent runs the sheets; this component only draws and emits.
 */
@Component({
  selector: 'app-pin-cell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 6px 8px;
    }
    button {
      min-height: 44px;
    }
  `,
  template: `
    @if (pin(); as p) {
      <span
        [class]="'pill ' + pill().css"
        [id]="'pin-pill-' + memberId()"
        tabindex="-1"
        data-testid="pin-pill"
      >
        <app-icon name="key" [size]="12" /> {{ pill().text }}
      </span>
    }
    @if (owner() && pin() && pin()?.state !== 'NOT_SET') {
      <button
        type="button"
        mat-stroked-button
        data-act="pin-reset"
        [attr.aria-label]="'Reset PIN for ' + memberName()"
        (click)="resetPin.emit()"
      >
        <app-icon name="key" [size]="13" /> Reset PIN
      </button>
    }
    @if (self()) {
      <button type="button" mat-stroked-button data-act="pin-own" (click)="ownPin.emit()">
        <app-icon name="key" [size]="13" /> {{ ownLabel() }}
      </button>
    }
  `,
})
export class PinCellComponent {
  readonly pin = input<TeamMemberPin | null>(null);
  readonly memberId = input.required<string>();
  readonly memberName = input.required<string>();
  /** The signed-in person is the OWNER. */
  readonly owner = input(false);
  /** This row is the signed-in person's own. */
  readonly self = input(false);

  readonly resetPin = output<void>();
  readonly ownPin = output<void>();

  protected readonly pill = computed(() => PIN_PILL[this.pin()?.state ?? 'NOT_SET']);
  protected readonly ownLabel = computed(() => ownPinButton(this.pin()?.state ?? 'NOT_SET'));
}
