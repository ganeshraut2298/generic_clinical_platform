import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { IconComponent } from '../../../shared/icon/icon.component';
import type { CardLayout } from './card-setup.model';
import type { VitalsSectionView } from './card-view';

/**
 * The Vitals section of a patient card (SAH-37; mockup `vts`, `vt`, `vmini`, `pcnov`): the five vital
 * tiles with "Check" or "Critical" words as well as colour, the Compact mini line (BP, SpO₂, Pulse and
 * the locked Early warning), "No vitals recorded yet", and the foot with Early warning and Last recorded.
 * Every value and mark is the server's.
 */
@Component({
  selector: 'app-card-vitals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet, IconComponent],
  styleUrl: './card-vitals.component.scss',
  templateUrl: './card-vitals.component.html',
})
export class CardVitalsComponent {
  readonly section = input.required<VitalsSectionView>();
  readonly layout = input.required<CardLayout>();
}
