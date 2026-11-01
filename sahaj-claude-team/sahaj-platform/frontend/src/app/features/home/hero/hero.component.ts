import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { InViewDirective } from '../../../core/motion/in-view.directive';
import { runScene } from '../../../core/motion/scene';
import { ScrollTo } from '../../../core/scroll/scroll-to.service';
import { CharacterComponent } from '../../../shared/character/character.component';
import { IconComponent } from '../../../shared/icon/icon.component';

/** The scene reaches this step when it is finished. All items show at this step. */
export const HERO_FINAL_STEP = 10;
const HERO_LOOP_END = 14;

/** Section 1: headline, Start Free, and the animated clinic setup assistant. */
@Component({
  selector: 'app-hero',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink, InViewDirective, CharacterComponent, IconComponent],
  styleUrl: './hero.component.scss',
  templateUrl: './hero.component.html',
})
export class HeroComponent {
  protected readonly scroll = inject(ScrollTo);

  protected readonly inView = signal(false);
  /** Animation step. Items with `at <= step` are visible. */
  readonly step = signal(HERO_FINAL_STEP);
  readonly playing = signal(false);

  protected readonly summary = [
    { label: 'Specialty', value: 'General practice' },
    { label: 'OPD workflow', value: '5 stages' },
    { label: 'Roles', value: 'Reception, Nurse, Doctor, Billing' },
  ];
  protected readonly flow = [
    { stage: 'Appointment', owner: 'Front desk' },
    { stage: 'Check-in', owner: 'Front desk' },
    { stage: 'Consultation', owner: 'Doctor' },
    { stage: 'Billing', owner: 'Billing' },
    { stage: 'Follow-up', owner: 'Doctor' },
  ];

  private timer: ReturnType<typeof setInterval> | undefined;

  constructor() {
    runScene(this.inView, {
      play: () => {
        let h = 0;
        this.playing.set(true);
        this.step.set(0);
        this.timer = setInterval(() => {
          h = h >= HERO_LOOP_END ? 0 : h + 1;
          this.step.set(Math.min(h, HERO_FINAL_STEP));
        }, 700);
      },
      settle: () => {
        this.playing.set(false);
        this.step.set(HERO_FINAL_STEP);
      },
      stop: () => clearInterval(this.timer),
    });
  }
}
