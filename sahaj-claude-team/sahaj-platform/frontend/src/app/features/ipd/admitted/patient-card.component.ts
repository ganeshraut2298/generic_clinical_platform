import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { IconComponent } from '../../../shared/icon/icon.component';
import { AdmittedCardAbilities, AdmittedPatientRow } from '../ipd.model';
import {
  CardAction,
  NAME_TOOLTIP,
  actionLabel,
  admittedOnText,
  cardFlags,
  cardLabel,
  foldFlags,
  foldedText,
  identityLine,
  initials,
  isCritical,
  lengthOfStay,
  roomText,
  visibleActions,
} from './admitted.model';

/**
 * The Standard patient card of the Admitted tab (SAH-36; mockup `pcCard`, layout standard, uniform
 * grid): round initials, the name as a button, UHID, age and sex, the bed, the flags line, Admitted
 * on, Length of stay, Ward, Room, Doctor and the action row. A presentational component: every
 * fact, flag and action comes from the server's list answer (`row`, `card`, `asOf`); it asks the
 * parent to open the sheets and makes no request of its own.
 */
@Component({
  selector: 'app-patient-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrl: './patient-card.component.scss',
  templateUrl: './patient-card.component.html',
})
export class PatientCardComponent {
  readonly row = input.required<AdmittedPatientRow>();
  readonly card = input.required<AdmittedCardAbilities>();
  readonly asOf = input.required<string>();

  /** The patient's name was chosen: the admission sheet opens on its main view. */
  readonly openName = output<void>();
  /** An action button was chosen. */
  readonly act = output<CardAction>();

  protected readonly nameTooltip = NAME_TOOLTIP;
  protected readonly label = computed(() => cardLabel(this.row()));
  protected readonly initials = computed(() => initials(this.row().patientName));
  protected readonly female = computed(() => (this.row().sex ?? '').trim().toLowerCase().startsWith('f'));
  protected readonly identity = computed(() => identityLine(this.row()));
  protected readonly admittedOn = computed(() => admittedOnText(this.row().admittedAt));
  protected readonly stay = computed(() =>
    lengthOfStay(this.row().admittedAt, this.row().dayNumber, this.asOf()),
  );
  protected readonly room = computed(() => roomText(this.row()));
  protected readonly critical = computed(() => isCritical(this.row()));
  protected readonly flags = computed(() => foldFlags(cardFlags(this.row(), this.card())));
  protected readonly foldedLabel = computed(() => foldedText(this.flags().folded));
  protected readonly actions = computed(() => visibleActions(this.card()));
  protected readonly actionName = (action: { label: string }): string => actionLabel(action, this.row());
}
