import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
} from '@angular/core';
import { OrderNoticesStore } from '../orders/order-notices.store';
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
  /** SAH-46-1. The signed-in user may open the EMR of this admission (doctor, visiting doctor, owner with doctor details). */
  readonly canOpenEmr = input(false);

  /** The patient's name was chosen: the admission sheet opens on its main view. */
  readonly openName = output<void>();
  /** An action button was chosen. */
  readonly act = output<CardAction>();
  /** "Open EMR" was chosen (the consultation screen of SAH-46, not the IPD EMR sheet). */
  readonly openEmrRecord = output<void>();

  private readonly notices = inject(OrderNoticesStore);
  private readonly destroyRef = inject(DestroyRef);
  private attached = false;

  protected readonly nameTooltip = NAME_TOOLTIP;
  /**
   * The row with the badge of the 30-second counts poll (SAH-76-3) in place of the one the list carried,
   * once a poll has answered. A card without the badge field (front desk) or the preview never changes.
   */
  private readonly liveRow = computed<AdmittedPatientRow>(() => {
    const row = this.row();
    const clinical = row.clinical;
    if (this.preview() || !clinical || clinical.ordersToAcknowledge === undefined) {
      return row;
    }
    const live = this.notices.countFor(row.id);
    return live === null ? row : { ...row, clinical: { ...clinical, ordersToAcknowledge: live } };
  });
  protected readonly view = computed<CardView>(() =>
    buildCard(this.liveRow(), this.card(), this.setup(), this.asOf()),
  );
  protected readonly layout = computed(() => this.setup().layout);
  protected readonly cardClass = computed(
    () => `pc lay-${this.layout()}${this.view().critical ? ' pc-crit' : ''}`,
  );
  protected readonly foldedLabel = foldedText;

  constructor() {
    // The poll runs while a card that carries the badge field is on screen (the Admitted list).
    effect(() => {
      if (!this.attached && !this.preview() && this.row().clinical?.ordersToAcknowledge !== undefined) {
        this.attached = true;
        this.notices.attach(this.destroyRef);
      }
    });
  }

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
