import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { InViewDirective } from '../../../core/motion/in-view.directive';
import { ReducedMotion } from '../../../core/motion/reduced-motion';
import { runScene } from '../../../core/motion/scene';
import { CharacterComponent } from '../../../shared/character/character.component';
import { IconComponent } from '../../../shared/icon/icon.component';
import { SectionHeadComponent } from '../shared/section-head.component';

export const SETUP_STEPS = [
  'Select clinic type',
  'Add doctors and staff',
  'Choose an OPD template',
  'Assign roles',
  'Set required fields and rules',
  'Preview the workflow',
  'Activate the clinic',
] as const;

/** Step number that means "finished": every step done and the clinic is ready. */
export const SETUP_DONE = SETUP_STEPS.length;

/** Section 2: Clinic setup, a short walk through the seven steps. */
@Component({
  selector: 'app-setup-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, InViewDirective, CharacterComponent, IconComponent, SectionHeadComponent],
  styleUrl: './setup.component.scss',
  templateUrl: './setup.component.html',
})
export class SetupSectionComponent {
  private readonly motion = inject(ReducedMotion);

  protected readonly steps = SETUP_STEPS;
  protected readonly done: number = SETUP_DONE;
  protected readonly inView = signal(false);

  protected readonly clinicTypes = ['General practice', 'Dental', 'Paediatrics', 'Homeopathy'];
  protected readonly team = [
    { name: 'Dr. Meera Kulkarni', detail: 'Doctor · owner · ₹400' },
    { name: 'Pooja Gaikwad', detail: 'Reception' },
    { name: 'Anjali Pawar', detail: 'Nurse' },
    { name: 'Kiran Shinde', detail: 'Clinic manager, billing' },
  ];
  protected readonly stages = [
    'Appointment',
    'Check-in',
    'Vitals',
    'Consultation',
    'Billing',
    'Follow-up',
  ];
  protected readonly owners = [
    { task: 'Check-in', who: 'Pooja · Reception' },
    { task: 'Vitals', who: 'Anjali · Nurse' },
    { task: 'Consultation', who: 'Dr. Meera · Doctor' },
    { task: 'Billing', who: 'Kiran · Billing' },
  ];
  protected readonly rules = [
    'Vitals required for new patients',
    'Free follow-up within 6 days',
    'Discount above 10% needs owner approval',
  ];

  /** The step being shown, 0 to 6, or 7 when the clinic is ready. */
  readonly current = signal<number>(SETUP_DONE);
  protected readonly percent = computed(() =>
    Math.round((Math.min(this.current(), SETUP_DONE) / SETUP_DONE) * 100),
  );

  private started = false;
  private timer: ReturnType<typeof setInterval> | undefined;

  constructor() {
    runScene(this.inView, {
      play: () => {
        if (!this.started) {
          this.started = true;
          this.run();
        }
      },
      settle: () => this.current.set(SETUP_DONE),
      stop: () => clearInterval(this.timer),
    });
  }

  /** Clicking a step stops the animation and shows that step. */
  protected go(index: number): void {
    clearInterval(this.timer);
    this.current.set(index);
  }

  /** Replay. With reduced motion it only goes back to step 1, without animation. */
  protected replay(): void {
    if (this.motion.reduced()) {
      clearInterval(this.timer);
      this.current.set(0);
    } else {
      this.run();
    }
  }

  private run(): void {
    clearInterval(this.timer);
    this.current.set(0);
    this.timer = setInterval(() => {
      this.current.update((n) => n + 1);
      if (this.current() >= SETUP_DONE) {
        clearInterval(this.timer);
      }
    }, 1300);
  }
}
