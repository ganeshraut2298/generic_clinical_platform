import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { EmrFlag } from '../specialty.model';

export interface BpValue {
  systolic: number | null;
  diastolic: number | null;
}

const SYSTOLIC = { step: 2, start: 120, min: 40, max: 300 } as const;
const DIASTOLIC = { step: 1, start: 80, min: 20, max: 200 } as const;

/** "120/80" typed as one value; null when it is not two numbers in range. */
export function parseBp(text: string): BpValue | null {
  const match = /^\s*(\d{2,3})\s*\/\s*(\d{2,3})\s*$/.exec(text);
  if (!match) {
    return null;
  }
  const systolic = Number(match[1]);
  const diastolic = Number(match[2]);
  const ok = systolic >= SYSTOLIC.min && systolic <= SYSTOLIC.max && diastolic >= DIASTOLIC.min && diastolic <= DIASTOLIC.max;
  return ok ? { systolic, diastolic } : null;
}

/**
 * Blood pressure as one control (mockup #DoctorGeneral): "−  120/80  +". One press moves the upper value by 2 and
 * the lower by 1. The contract stores the two values separately, so the control reads and writes both.
 */
@Component({
  selector: 'app-sp-bp-stepper',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './sp-stepper.component.scss',
  template: `
    <div class="st" [class.bad]="note()?.alert">
      <span class="lbl">BP</span>
      <div class="ctl">
        <button type="button" class="b" aria-label="Decrease BP" [disabled]="disabled()" (click)="step(-1)">−</button>
        <input
          class="v"
          type="text"
          inputmode="numeric"
          aria-label="BP in mmHg, upper value then lower value, for example 120/80"
          placeholder="—"
          [value]="text()"
          [disabled]="disabled()"
          (change)="typed($event)"
        />
        <button type="button" class="b" aria-label="Increase BP" [disabled]="disabled()" (click)="step(1)">+</button>
      </div>
      <span class="u">mmHg</span>
      @if (note(); as n) {
        <span class="n" [class.alert]="n.alert" data-testid="stepper-note">{{ n.label }}</span>
      }
    </div>
  `,
})
export class SpBpStepperComponent {
  readonly systolic = input<number | null | undefined>(null);
  readonly diastolic = input<number | null | undefined>(null);
  readonly note = input<EmrFlag | null>(null);
  readonly disabled = input(false);
  readonly valueChange = output<BpValue>();

  protected text(): string {
    const s = this.systolic();
    const d = this.diastolic();
    return s != null && d != null ? `${s}/${d}` : '';
  }

  protected step(direction: 1 | -1): void {
    const both = this.systolic() != null && this.diastolic() != null;
    const s = both ? (this.systolic() as number) : SYSTOLIC.start;
    const d = both ? (this.diastolic() as number) : DIASTOLIC.start;
    this.valueChange.emit({
      systolic: Math.min(SYSTOLIC.max, Math.max(SYSTOLIC.min, s + direction * SYSTOLIC.step)),
      diastolic: Math.min(DIASTOLIC.max, Math.max(DIASTOLIC.min, d + direction * DIASTOLIC.step)),
    });
  }

  protected typed(event: Event): void {
    const input = event.target as HTMLInputElement;
    const raw = input.value.trim();
    if (raw === '') {
      this.valueChange.emit({ systolic: null, diastolic: null });
      return;
    }
    const parsed = parseBp(raw);
    if (parsed) {
      this.valueChange.emit(parsed);
    } else {
      input.value = this.text();
    }
  }
}
