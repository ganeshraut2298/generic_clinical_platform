import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { IconComponent } from '../../../shared/icon/icon.component';
import { SectionHeadComponent } from '../shared/section-head.component';

export type SpecialtyKey = 'gp' | 'dental' | 'physio' | 'poly';

export interface Specialty {
  name: string;
  description: string;
  flow: string[];
  paper: string[];
  bill: string;
  soon?: boolean;
}

/** SPX in the mockup. */
export const SPECIALTIES: Record<SpecialtyKey, Specialty> = {
  gp: {
    name: 'General physician',
    description: 'Walk-ins, short consults, repeat prescriptions',
    flow: ['Token', 'Queue', 'Vitals', 'Consultation', 'Case paper print', 'Billing', 'Follow-up'],
    paper: ['Complaints', 'Vitals', 'Diagnosis', 'Rx', 'Advice'],
    bill: 'Free follow-up within 6 days',
  },
  dental: {
    name: 'Dental clinic',
    description: 'Chair slots, treatment plans over several sittings',
    flow: [
      'Appointment',
      'Check-in',
      'Chair and X-ray',
      'Treatment plan per tooth',
      'Estimate and consent',
      'Payment per sitting',
      'Next sitting',
    ],
    paper: ['Tooth chart', 'X-ray images', 'Plan per tooth', 'Consent'],
    bill: 'Payment per sitting, balance tracked',
  },
  physio: {
    name: 'Physiotherapy',
    description: 'Daily sessions for 10 to 20 days',
    flow: ['Appointment', 'Assessment', 'Session', 'Pain score', 'Package balance', 'Next session'],
    paper: ['Assessment', 'Exercise plan', 'Pain-score trend'],
    bill: 'Prepaid session packages',
    soon: true,
  },
  poly: {
    name: 'Multi-doctor polyclinic',
    description: 'Several doctors, one front desk and one bill',
    flow: [
      'Token',
      'Doctor allocation',
      'Vitals',
      'Consultation',
      'Lab and pharmacy',
      'Single bill',
      'Follow-up',
    ],
    paper: ['Per-doctor templates', 'Shared history', 'Orders'],
    bill: 'One bill across doctors',
  },
};

/** Section 6: Specialties. Pick one to see how the workflow changes. */
@Component({
  selector: 'app-specialties-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, SectionHeadComponent],
  styleUrl: './specialties.component.scss',
  templateUrl: './specialties.component.html',
})
export class SpecialtiesSectionComponent {
  protected readonly entries = Object.entries(SPECIALTIES) as [SpecialtyKey, Specialty][];
  readonly selected = signal<SpecialtyKey>('gp');
  protected readonly specialty = computed(() => SPECIALTIES[this.selected()]);
  private readonly base = SPECIALTIES.gp.flow;

  /** A stage is highlighted when general practice does not have it. */
  protected differs(stage: string): boolean {
    return !this.base.includes(stage);
  }
}
