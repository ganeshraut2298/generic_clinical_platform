import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { InViewDirective } from '../../../core/motion/in-view.directive';
import { ReducedMotion } from '../../../core/motion/reduced-motion';
import { runScene } from '../../../core/motion/scene';
import { CharacterComponent } from '../../../shared/character/character.component';
import { IconComponent } from '../../../shared/icon/icon.component';
import { SectionHeadComponent } from '../shared/section-head.component';
import {
  DEFAULT_CAPTION,
  INITIAL_WORKFLOW,
  STAGES,
  STAGE_KEYS,
  StageKey,
} from './workflow.data';

const FINISHED_CAPTION = 'The sample patient passed every stage.';

/** Section 3: Workflow builder. Add, remove and reorder stages, then preview a sample patient. */
@Component({
  selector: 'app-workflow-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, InViewDirective, CharacterComponent, IconComponent, SectionHeadComponent],
  styleUrl: './workflow.component.scss',
  templateUrl: './workflow.component.html',
})
export class WorkflowSectionComponent {
  private readonly motion = inject(ReducedMotion);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly stages = STAGES;
  protected readonly palette = STAGE_KEYS;
  protected readonly inView = signal(false);

  readonly workflow = signal<StageKey[]>([...INITIAL_WORKFLOW]);
  /** The stage that was just added, for its pop-in effect. */
  protected readonly justAdded = signal<StageKey | null>(null);
  readonly caption = signal(DEFAULT_CAPTION);
  /** Index of the stage the sample patient is at, or -1. */
  protected readonly activeIndex = signal(-1);

  private seen = false;
  private addTimer: ReturnType<typeof setTimeout> | undefined;
  private previewStartTimer: ReturnType<typeof setTimeout> | undefined;
  private previewTimer: ReturnType<typeof setInterval> | undefined;

  constructor() {
    runScene(this.inView, {
      play: () => {
        if (this.seen) {
          return;
        }
        this.seen = true;
        this.workflow.update((list) => list.filter((k) => k !== 'vitals'));
        this.caption.set('Kiran is adding a Vitals step for new patients…');
        this.addTimer = setTimeout(() => {
          this.insertBeforeConsult('vitals');
          this.justAdded.set('vitals');
          this.caption.set('Vitals added before Consultation. Owner: Nurse.');
          this.previewStartTimer = setTimeout(() => this.preview(), 1200);
        }, 1400);
      },
      settle: () => this.ensureVitals(),
      stop: () => {
        clearTimeout(this.addTimer);
        clearTimeout(this.previewStartTimer);
        clearInterval(this.previewTimer);
      },
    });
  }

  protected has(key: StageKey): boolean {
    return this.workflow().includes(key);
  }

  protected add(key: StageKey): void {
    const list = [...this.workflow()];
    const at = list.indexOf('followup');
    list.splice(at < 0 ? list.length : at, 0, key);
    this.workflow.set(list);
    this.justAdded.set(key);
    this.caption.set(`${STAGES[key].name} added. Owner: ${STAGES[key].owner}.`);
  }

  protected remove(key: StageKey): void {
    if (key === 'consult') {
      return;
    }
    this.workflow.update((list) => list.filter((k) => k !== key));
    this.caption.set(`${STAGES[key].name} removed from the workflow.`);
  }

  protected move(key: StageKey, direction: -1 | 1): void {
    const list = [...this.workflow()];
    const i = list.indexOf(key);
    const j = i + direction;
    if (i < 0 || j < 0 || j >= list.length) {
      return;
    }
    [list[i], list[j]] = [list[j], list[i]];
    this.workflow.set(list);
    this.caption.set(`${STAGES[key].name} moved.`);
    this.keepFocus(`move-${key}-${direction}`);
  }

  /** Walks a sample patient through every stage. With reduced motion it shows the end at once. */
  protected preview(): void {
    clearInterval(this.previewTimer);
    this.justAdded.set(null);
    if (this.motion.reduced()) {
      this.activeIndex.set(-1);
      this.caption.set(FINISHED_CAPTION);
      return;
    }
    let at = 0;
    const step = () => {
      const list = this.workflow();
      if (at >= list.length) {
        clearInterval(this.previewTimer);
        this.activeIndex.set(-1);
        this.caption.set(FINISHED_CAPTION);
        return;
      }
      this.activeIndex.set(at);
      const stage = STAGES[list[at]];
      this.caption.set(`Sunita is at ${stage.name} · owner: ${stage.owner}`);
      at++;
    };
    step();
    this.previewTimer = setInterval(step, 700);
  }

  private insertBeforeConsult(key: StageKey): void {
    this.workflow.update((list) => {
      if (list.includes(key)) {
        return list;
      }
      const next = [...list];
      const at = next.indexOf('consult');
      next.splice(at < 0 ? next.length : at, 0, key);
      return next;
    });
  }

  private ensureVitals(): void {
    if (!this.has('vitals')) {
      this.insertBeforeConsult('vitals');
    }
    this.activeIndex.set(-1);
  }

  /** After the list is redrawn, put keyboard focus back on the button that was used. */
  private keepFocus(id: string): void {
    afterNextRender(
      () => {
        const button = this.host.nativeElement.querySelector<HTMLButtonElement>(
          `[data-focus="${id}"]:not([disabled])`,
        );
        button?.focus();
      },
      { injector: this.injector },
    );
  }
}
