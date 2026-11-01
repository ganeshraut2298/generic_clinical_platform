import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { InViewDirective } from '../../../core/motion/in-view.directive';
import { runScene } from '../../../core/motion/scene';
import { CharacterKey, CharacterPose } from '../../../shared/character/characters';
import { CharacterComponent } from '../../../shared/character/character.component';
import { IconComponent } from '../../../shared/icon/icon.component';
import { QrSampleComponent } from '../../../shared/qr-sample/qr-sample.component';
import { SectionHeadComponent } from '../shared/section-head.component';

export const JOURNEY_STAGES = [
  'Appointment',
  'Check-in',
  'Queue',
  'Consultation',
  'Diagnosis and Rx',
  'Billing and payment',
  'Follow-up',
] as const;

interface JourneyNarration {
  person: CharacterKey;
  pose: CharacterPose;
  text: string;
}

export const NARRATION: readonly JourneyNarration[] = [
  { person: 'pooja', pose: 'point', text: 'Pooja books Sunita for 11:30. She gets a WhatsApp confirmation.' },
  {
    person: 'sunita',
    pose: 'file',
    text: 'Sunita arrives with her file. One mobile number brings up her whole family.',
  },
  {
    person: 'pooja',
    pose: 'point',
    text: 'Pooja sees the live queue. If Dr. Meera is free, she sends Sunita straight in.',
  },
  {
    person: 'meera',
    pose: 'tablet',
    text: 'Dr. Meera writes on the tablet. The case paper fills itself, and she confirms it.',
  },
  {
    person: 'meera',
    pose: 'point',
    text: 'The diagnosis and prescription print on her own letterhead, with a QR code for next time.',
  },
  {
    person: 'kiran',
    pose: 'point',
    text: 'Kiran bills the visit. Sunita pays by UPI and gets the receipt on WhatsApp.',
  },
  {
    person: 'sunita',
    pose: 'idle',
    text: 'Sunita is due back on 30 Oct. The reminder goes out the day before, and the visit is free.',
  },
];

const LAST = JOURNEY_STAGES.length - 1;

/** Section 5: Patient journey, from appointment to follow-up. */
@Component({
  selector: 'app-journey-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [InViewDirective, CharacterComponent, IconComponent, QrSampleComponent, SectionHeadComponent],
  styleUrl: './journey.component.scss',
  templateUrl: './journey.component.html',
})
export class JourneySectionComponent {
  protected readonly inView = signal(false);
  protected readonly stages = JOURNEY_STAGES;
  protected readonly narration = NARRATION;
  protected readonly slots = ['11:00', '11:15', '11:30', '11:45'];
  protected readonly queue = [
    { token: 'T-17', name: 'Sneha Kale', state: 'With doctor', pill: 'p-consult' },
    { token: 'T-18', name: 'Ganpat Waghmare', state: 'Vitals', pill: 'p-vitals' },
    { token: 'T-19', name: 'Sunita Patil', state: 'Next · about 8 min', pill: 'p-teal' },
  ];

  /** Index of the stage being shown. */
  readonly current = signal(3);
  /** True until the visitor picks a stage; then the stages stop changing by themselves. */
  readonly auto = signal(true);

  protected readonly last = LAST;

  private seen = false;
  private timer: ReturnType<typeof setInterval> | undefined;

  constructor() {
    runScene(this.inView, {
      play: () => {
        if (!this.auto()) {
          return;
        }
        if (!this.seen) {
          this.seen = true;
          this.current.set(0);
        }
        this.timer = setInterval(() => this.current.update((n) => (n + 1) % this.stages.length), 3200);
      },
      settle: () => undefined,
      stop: () => clearInterval(this.timer),
    });
  }

  /** The visitor picks a stage: stop the automatic tour. */
  protected go(index: number): void {
    this.auto.set(false);
    clearInterval(this.timer);
    this.current.set(index);
  }
}
