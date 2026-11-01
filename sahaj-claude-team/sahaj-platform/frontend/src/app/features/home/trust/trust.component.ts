import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { InViewDirective } from '../../../core/motion/in-view.directive';
import { CharacterComponent } from '../../../shared/character/character.component';
import { IconComponent, IconName } from '../../../shared/icon/icon.component';
import { SectionHeadComponent } from '../shared/section-head.component';

/** What a role can do: no access, view only, full access, or "up to 10%" (limit). */
export type Access = 'none' | 'view' | 'full' | 'limit';

export const ROLE_COLUMNS = ['Reception', 'Nurse', 'Doctor', 'Billing'] as const;

export const ACCESS_ROWS: readonly { name: string; access: readonly Access[] }[] = [
  { name: 'Clinical notes', access: ['none', 'view', 'full', 'none'] },
  { name: 'Prescriptions', access: ['none', 'none', 'full', 'none'] },
  { name: 'Billing', access: ['full', 'none', 'full', 'full'] },
  { name: 'Discount approval', access: ['none', 'none', 'full', 'limit'] },
  { name: 'Reports', access: ['none', 'none', 'full', 'full'] },
];

export const AUDIT: readonly { time: string; who: string; what: string }[] = [
  { time: '11:42', who: 'Dr. Meera', what: 'signed the case paper for T-19' },
  { time: '11:44', who: 'Kiran', what: 'created bill SSC/26-27/0419' },
  { time: '11:45', who: 'Pooja', what: 'sent the receipt on WhatsApp' },
  { time: '11:51', who: 'Kiran', what: 'requested a 15% discount' },
  { time: '11:52', who: 'Dr. Meera', what: 'approved the discount' },
];

export const TRUST_POINTS: readonly { icon: IconName; text: string }[] = [
  { icon: 'shield', text: 'Patient data hosted in India' },
  { icon: 'lock', text: 'Encrypted in transit and at rest' },
  { icon: 'clock', text: 'Daily backups, kept for 30 days' },
  { icon: 'receipt', text: 'Export your data any time' },
  { icon: 'users', text: 'Access set by role, per clinic' },
  { icon: 'link', text: 'Built to connect with ABDM' },
];

/** Section 9: Trust and control. */
@Component({
  selector: 'app-trust-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [InViewDirective, CharacterComponent, IconComponent, SectionHeadComponent],
  styleUrl: './trust.component.scss',
  templateUrl: './trust.component.html',
})
export class TrustSectionComponent {
  protected readonly inView = signal(false);
  protected readonly roles = ROLE_COLUMNS;
  protected readonly rows = ACCESS_ROWS;
  protected readonly audit = AUDIT;
  protected readonly points = TRUST_POINTS;
}
