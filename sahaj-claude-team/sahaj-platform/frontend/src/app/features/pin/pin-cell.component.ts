import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { IconComponent } from '../../shared/icon/icon.component';
import { PIN_PILL, TeamMemberPin, colleagueTriesRowText, ownPinButton } from './pin.model';

/**
 * The PIN part of a Team list row (SAH-31, ADR-010 D12; mockup panel Staff `f-staff`): the pill
 * "PIN set" / "PIN locked" / "No PIN yet" (the server sends `pin` to the owner for every row and to
 * everyone for their own row only), the OWNER line "N wrong tries by colleagues in 7 days" (SAH-38, ADR-014 D9,
 * NEW; a link-style button that opens the history; shown only above 0, and never as a 0 when the server sent
 * none), the OWNER button "Reset PIN" on rows with a PIN set or locked,
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
    .tries {
      background: none;
      border: 0;
      padding: 0 8px;
      font: inherit;
      font-size: 0.84rem;
      color: var(--pri-text);
      text-decoration: underline;
      cursor: pointer;
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
    @if (owner() && triesText(); as tries) {
      <button
        type="button"
        class="tries"
        data-act="pin-tries"
        aria-haspopup="dialog"
        [attr.aria-label]="tries + ' for ' + memberName()"
        (click)="showTries.emit()"
      >
        {{ tries }}
      </button>
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
  /** OWNER: open the history of colleague tries on this PIN. */
  readonly showTries = output<void>();

  /** T4 / T4b; null at 0, and when the server sent no count (a non-owner, or no data). */
  protected readonly triesText = computed(() => {
    const count = this.pin()?.colleagueTries7d;
    return typeof count === 'number' && count > 0 ? colleagueTriesRowText(count) : null;
  });
  protected readonly pill = computed(() => PIN_PILL[this.pin()?.state ?? 'NOT_SET']);
  protected readonly ownLabel = computed(() => ownPinButton(this.pin()?.state ?? 'NOT_SET'));
}
