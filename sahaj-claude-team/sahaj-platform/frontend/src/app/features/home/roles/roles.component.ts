import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { InViewDirective } from '../../../core/motion/in-view.directive';
import { runScene } from '../../../core/motion/scene';
import { CHARACTERS, CharacterKey, CharacterPose } from '../../../shared/character/characters';
import { CharacterComponent } from '../../../shared/character/character.component';
import { IconComponent } from '../../../shared/icon/icon.component';
import { SectionHeadComponent } from '../shared/section-head.component';

export type MemberKey = Exclude<CharacterKey, 'sunita'>;

export const MEMBERS: readonly MemberKey[] = ['pooja', 'anjali', 'meera', 'kiran'];

export const PERMISSIONS: Record<MemberKey, [string, boolean][]> = {
  pooja: [['Appointments', true], ['Check-in', true], ['Clinical notes', false]],
  anjali: [['Vitals', true], ['View notes', true], ['Billing', false]],
  meera: [['Clinical notes', true], ['Prescriptions', true], ['Approvals', true]],
  kiran: [['Invoices', true], ['Discounts up to 10%', true], ['Clinical notes', false]],
};

export const TASKS: readonly { task: string; owner: MemberKey }[] = [
  { task: 'Book appointment', owner: 'pooja' },
  { task: 'Check in · token T-19', owner: 'pooja' },
  { task: 'Record vitals', owner: 'anjali' },
  { task: 'Write consultation', owner: 'meera' },
  { task: 'Generate invoice', owner: 'kiran' },
  { task: 'Schedule follow-up', owner: 'meera' },
];

export const VIEW_TEXT: Record<MemberKey, string> = {
  pooja: 'Pooja books, checks in and collects payment. Clinical notes are locked for her.',
  anjali: 'Anjali records vitals and can read notes. Billing is locked for her.',
  meera: 'Dr. Meera writes notes and prescriptions, and approves discounts above the limit.',
  kiran: 'Kiran creates invoices and gives discounts up to 10%. Clinical notes are locked for him.',
};

const POSES: Record<MemberKey, CharacterPose> = {
  pooja: 'idle',
  anjali: 'clip',
  meera: 'tablet',
  kiran: 'idle',
};

type ViewChoice = 'all' | MemberKey;

/** Section 4: Roles and responsibilities. Each step of a visit goes to the right person. */
@Component({
  selector: 'app-roles-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonToggleModule,
    InViewDirective,
    CharacterComponent,
    IconComponent,
    SectionHeadComponent,
  ],
  styleUrl: './roles.component.scss',
  templateUrl: './roles.component.html',
})
export class RolesSectionComponent {
  protected readonly inView = signal(false);

  protected readonly members = MEMBERS;
  protected readonly characters = CHARACTERS;
  protected readonly permissions = PERMISSIONS;
  protected readonly tasks = TASKS;
  protected readonly viewText = VIEW_TEXT;
  protected readonly poses = POSES;
  protected readonly total = TASKS.length;

  /** How many steps of the visit are finished. The final state is all of them. */
  readonly step = signal(TASKS.length);
  readonly view = signal<ViewChoice>('all');

  protected readonly viewOptions: { value: ViewChoice; label: string }[] = [
    { value: 'all', label: 'Everyone' },
    ...MEMBERS.map((m) => ({ value: m, label: CHARACTERS[m].role.split(' ')[0] })),
  ];

  protected readonly focused = computed(() => {
    const view = this.view();
    return view === 'all' ? null : view;
  });

  protected readonly status = computed(() => {
    const step = this.step();
    const current = TASKS[step];
    return current
      ? `Step ${step + 1} of ${TASKS.length}: ${current.task} → ${CHARACTERS[current.owner].name}`
      : `All ${TASKS.length} steps done. Visit closed with nothing missed.`;
  });

  private seen = false;
  private timer: ReturnType<typeof setInterval> | undefined;

  constructor() {
    runScene(this.inView, {
      play: () => {
        if (this.seen) {
          return;
        }
        this.seen = true;
        this.step.set(0);
        this.timer = setInterval(() => {
          this.step.update((n) => n + 1);
          if (this.step() >= TASKS.length) {
            clearInterval(this.timer);
          }
        }, 1400);
      },
      settle: () => this.step.set(TASKS.length),
      stop: () => clearInterval(this.timer),
    });
  }

  /** The tasks that have started for one person. */
  protected tasksOf(member: MemberKey): { task: string; index: number }[] {
    const step = this.step();
    return TASKS.map((t, index) => ({ task: t.task, owner: t.owner, index }))
      .filter((t) => t.owner === member && t.index <= step)
      .map(({ task, index }) => ({ task, index }));
  }
}
