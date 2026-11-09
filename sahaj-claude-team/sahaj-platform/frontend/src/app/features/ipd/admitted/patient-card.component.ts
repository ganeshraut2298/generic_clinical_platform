import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { IconComponent } from '../../../shared/icon/icon.component';
import { CardActionsComponent } from '../card/card-actions.component';
import { CardVitalsComponent } from '../card/card-vitals.component';
import { CardView, buildCard } from '../card/card-view';
import { STANDARD_RESOLVED, ResolvedCardSetup } from '../card/card-setup.model';
import { AdmittedCardAbilities, AdmittedPatientRow } from '../ipd.model';
import { CardAction, NAME_TOOLTIP, foldedText } from './admitted.model';

/**
 * The one patient card (SAH-36 Standard card, SAH-37 Compact, Standard and Expanded; mockup `pcCard`).
 * A presentational component: every fact, flag and action comes from the server's list answer (`row`,
 * `card`, `asOf`) and the published setup (`setup`, a `ResolvedCardSetup`: layout, sections, fields,
 * labels, actions). The Admitted page and the live preview of Clinic setup both draw it, so what the
 * owner approves is what staff see. It asks the parent to open the sheets and makes no request of its
 * own. With `preview` it is inert: the name is plain text and no button can be tabbed to or used.
 */
@Component({
  selector: 'app-patient-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet, IconComponent, CardActionsComponent, CardVitalsComponent],
  styleUrl: './patient-card.component.scss',
  templateUrl: './patient-card.component.html',
})
export class PatientCardComponent {
  readonly row = input.required<AdmittedPatientRow>();
  readonly card = input.required<AdmittedCardAbilities>();
  readonly asOf = input.required<string>();
  /** The published setup; the Sahaj standard card when none is given. */
  readonly setup = input<ResolvedCardSetup>(STANDARD_RESOLVED);
  /** Inert drawing for the live preview (AC47). */
  readonly preview = input(false);

  /** The patient's name was chosen: the admission sheet opens on its main view. */
  readonly openName = output<void>();
  /** An action button was chosen. */
  readonly act = output<CardAction>();

  protected readonly nameTooltip = NAME_TOOLTIP;
  protected readonly view = computed<CardView>(() =>
    buildCard(this.row(), this.card(), this.setup(), this.asOf()),
  );
  protected readonly layout = computed(() => this.setup().layout);
  protected readonly cardClass = computed(
    () => `pc lay-${this.layout()}${this.view().critical ? ' pc-crit' : ''}`,
  );
  protected readonly foldedLabel = foldedText;

  protected press(action: CardAction): void {
    if (!this.preview()) {
      this.act.emit(action);
    }
  }

  protected openDetails(): void {
    if (!this.preview()) {
      this.openName.emit();
    }
  }
}
