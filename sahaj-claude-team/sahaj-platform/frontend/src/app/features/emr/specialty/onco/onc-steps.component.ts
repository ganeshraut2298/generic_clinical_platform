import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { ScrollTo } from '../../../../core/scroll/scroll-to.service';
import { SpecialtyContext } from '../specialty.context';

/** The Assessment steps of the oncology screen (mockup #DoctorOncology): label, the card it jumps to. The four cards are listed in the order of the layout (ADR-026 D14); Decision always comes last. */
export const ONC_STEPS: readonly { key: string; label: string; section: string | null }[] = [
  { key: 'tox', label: 'Side effects', section: 'onc_tox' },
  { key: 'exam', label: 'Examination', section: 'onc_bsa' },
  { key: 'labs', label: 'Labs', section: 'onc_labs' },
  { key: 'ecog', label: 'ECOG', section: 'onc_ecog' },
  { key: 'decision', label: 'Decision', section: null },
];

/** The steps whose card is in the layout, in the layout's order, then Decision (the decision card follows the last card). */
export function orderedSteps(layoutKeys: readonly string[]): typeof ONC_STEPS {
  const cards = ONC_STEPS.filter((s) => s.section !== null && layoutKeys.includes(s.section)).sort(
    (a, b) => layoutKeys.indexOf(a.section!) - layoutKeys.indexOf(b.section!),
  );
  return [...cards, ...ONC_STEPS.filter((s) => s.section === null)];
}

/**
 * "Assessment steps": a bar that stays in view while the doctor scrolls the oncology Assessment (SAH-75-11
 * O-4). Each step shows its number, or a tick once its card is done; tapping a step moves to that card. It
 * reads the screen only and changes nothing.
 */
@Component({
  selector: 'app-onc-steps',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .bar {
      position: sticky;
      top: 150px;
      z-index: 2;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      padding: 8px 12px;
      border-radius: var(--r);
      border: 1px solid var(--glass-edge);
      background: var(--glass-hi);
      box-shadow: var(--shadow-sm);
    }
    .t {
      font-size: 0.8125rem;
      font-weight: 700;
      color: var(--ink-2);
    }
    .step {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      min-height: 44px;
      padding: 0 12px;
      border: 1px solid var(--line);
      border-radius: var(--r-pill);
      background: var(--surface);
      color: var(--ink);
      font: inherit;
      font-size: 0.8125rem;
      font-weight: 600;
      cursor: pointer;
    }
    .step:focus-visible {
      outline: 2px solid var(--pri);
    }
    .n {
      display: inline-grid;
      place-items: center;
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background: var(--muted-soft);
      font-size: 0.75rem;
    }
    .step.done .n {
      background: var(--good);
      color: var(--surface);
    }
  `,
  template: `
    <nav class="bar" aria-label="Assessment steps" data-testid="onc-steps">
      <span class="t">Assessment steps</span>
      @for (s of steps(); track s.key; let i = $index) {
        <button type="button" class="step" [class.done]="s.done" [attr.data-step]="s.key" (click)="go(s.target)">
          <span class="n" aria-hidden="true">{{ s.done ? '✓' : i + 1 }}</span>
          {{ s.label }}<span class="sr-only">{{ s.done ? ' (done)' : '' }}</span>
        </button>
      }
    </nav>
  `,
})
export class OncStepsComponent {
  private readonly ctx = inject(SpecialtyContext);
  private readonly scroll = inject(ScrollTo);

  protected readonly steps = computed(() =>
    orderedSteps(this.ctx.store.layout().map((s) => s.key)).map((s) => ({
      key: s.key,
      label: s.label,
      target: s.section ? 'sec-' + s.section : 'onc-decision',
      done: s.section ? this.ctx.store.stateOf(s.section)?.done === true : !this.ctx.store.dirty() && !!this.ctx.onco()?.decision?.choice,
    })),
  );

  protected go(id: string): void {
    this.scroll.section(id);
  }
}
