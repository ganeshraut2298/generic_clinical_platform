import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { EmrFlag } from '../specialty.model';

/**
 * The "− value +" control of the mockup (vitals, ANC check, growth, labs). The value is typed or
 * stepped; an empty value starts from the drawn starting point on the first step. Every control has
 * a label, the note shows text as well as colour.
 */
@Component({
  selector: 'app-sp-stepper',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './sp-stepper.component.scss',
  template: `
    <div class="st" [class.bad]="note()?.alert">
      <span class="lbl">{{ label() }}</span>
      <div class="ctl">
        <button type="button" class="b" [attr.aria-label]="'Decrease ' + label()" [disabled]="disabled()" (click)="step(-1)">−</button>
        <input
          class="v"
          type="number"
          inputmode="decimal"
          [attr.aria-label]="label() + ' in ' + unit()"
          [value]="value() ?? ''"
          [step]="stepBy()"
          [disabled]="disabled()"
          (change)="typed($event)"
        />
        <button type="button" class="b" [attr.aria-label]="'Increase ' + label()" [disabled]="disabled()" (click)="step(1)">+</button>
      </div>
      <span class="u">{{ unit() }}</span>
      @if (note(); as n) {
        <span class="n" [class.alert]="n.alert" data-testid="stepper-note">{{ n.label }}</span>
      }
    </div>
  `,
})
export class SpStepperComponent {
  readonly label = input.required<string>();
  readonly unit = input('');
  readonly value = input<number | null | undefined>(null);
  readonly stepBy = input(1);
  readonly start = input(0);
  readonly min = input<number | null>(null);
  readonly max = input<number | null>(null);
  readonly note = input<EmrFlag | null>(null);
  readonly disabled = input(false);
  readonly valueChange = output<number | null>();

  private readonly decimals = computed(() => (String(this.stepBy()).split('.')[1]?.length ?? 0));

  protected step(direction: 1 | -1): void {
    const base = this.value() ?? this.start();
    this.emit(Math.round((base + direction * this.stepBy()) * 10 ** this.decimals()) / 10 ** this.decimals());
  }

  protected typed(event: Event): void {
    const raw = (event.target as HTMLInputElement).value.trim();
    this.emit(raw === '' ? null : Number(raw));
  }

  private emit(n: number | null): void {
    if (n === null) {
      this.valueChange.emit(null);
      return;
    }
    if (!Number.isFinite(n)) {
      return;
    }
    const lo = this.min();
    const hi = this.max();
    this.valueChange.emit(lo !== null && n < lo ? lo : hi !== null && n > hi ? hi : n);
  }
}
