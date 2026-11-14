import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, ValidationErrors, ValidatorFn } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ageText, completedYears, dateOfBirthProblem, workOutAge } from '../../../shared/format/patient-age';

/** Validator for the optional date of birth control: the refusal texts of SAH-73 (empty is fine). */
export const dateOfBirthValidator: ValidatorFn = (control): ValidationErrors | null => {
  const message = dateOfBirthProblem(control.value as string | null);
  return message ? { dob: message } : null;
};

/**
 * "Age" and the optional "Date of birth" (SAH-73-1, SAH-73-2), shared by the registration dialog and the
 * "Edit details" sheet. While a date is filled the age is worked out on screen as read-only text and
 * is not sent; clearing the date makes Age typeable again with no stale value (or, with
 * `prefillOnClear`, with the years worked out from the cleared date). The age rule is the one in
 * `shared/format/patient-age.ts` with today in India. The date is never stored, logged or put in a URL.
 */
@Component({
  selector: 'app-dob-age-fields',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule],
  styles: [
    `
      :host {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 12px;
        align-items: start;
      }
      @media (max-width: 560px) {
        :host {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
  template: `
    <mat-form-field appearance="outline" subscriptSizing="dynamic">
      <mat-label>Age</mat-label>
      @if (filled()) {
        <input
          matInput
          readonly
          class="num"
          data-testid="age-preview"
          placeholder="Worked out from the date"
          [value]="preview()"
        />
      } @else {
        <input
          matInput
          class="num"
          data-field="age"
          type="number"
          inputmode="numeric"
          placeholder="Years"
          min="0"
          max="120"
          [formControl]="age()"
        />
        @if (ageError(); as error) {
          <mat-error>{{ error }}</mat-error>
        }
      }
    </mat-form-field>

    <mat-form-field appearance="outline" subscriptSizing="dynamic">
      <mat-label>Date of birth</mat-label>
      <input matInput type="date" data-field="dateOfBirth" autocomplete="off" [formControl]="dateOfBirth()" />
      <mat-hint>Optional. If you know it, the age is worked out for you.</mat-hint>
      @if (dateError(); as error) {
        <mat-error>{{ error }}</mat-error>
      }
    </mat-form-field>
  `,
})
export class DobAgeFieldsComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);

  readonly age = input.required<FormControl<number | null>>();
  readonly dateOfBirth = input.required<FormControl<string>>();
  /** Edit sheet: clearing a date fills Age with the years worked out from the cleared date. */
  readonly prefillOnClear = input(false);

  private readonly value = signal('');
  private lastYears: number | null = null;

  protected readonly filled = computed(() => this.value() !== '');
  protected readonly preview = computed(() => {
    const worked = this.value() ? workOutAge(this.value()) : null;
    return worked && !dateOfBirthProblem(this.value()) ? ageText(worked, 'header') : '';
  });

  ngOnInit(): void {
    const dob = this.dateOfBirth();
    this.value.set(dob.value);
    this.remember(dob.value);
    this.sync();
    dob.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((next) => {
      const wasFilled = this.value() !== '';
      this.value.set(next);
      this.remember(next);
      this.sync(wasFilled);
    });
  }

  protected ageError(): string | null {
    const control = this.age();
    if (!control.touched || !control.errors) {
      return null;
    }
    if (typeof control.errors['server'] === 'string') {
      return control.errors['server'];
    }
    if (control.errors['required']) {
      return 'Enter the age';
    }
    return control.errors['min'] || control.errors['max'] ? 'Age must be between 0 and 120' : null;
  }

  protected dateError(): string | null {
    const control = this.dateOfBirth();
    if (!control.errors) {
      return null;
    }
    if (typeof control.errors['server'] === 'string') {
      return control.errors['server'];
    }
    return control.touched && typeof control.errors['dob'] === 'string' ? control.errors['dob'] : null;
  }

  private remember(value: string): void {
    const years = value && !dateOfBirthProblem(value) ? completedYears(value) : null;
    if (years !== null) {
      this.lastYears = years;
    }
  }

  private sync(wasFilled = false): void {
    const age = this.age();
    if (this.value() !== '') {
      if (age.enabled) {
        age.disable({ emitEvent: false });
      }
      if (age.value !== null) {
        age.setValue(null, { emitEvent: false });
      }
    } else if (age.disabled) {
      age.enable({ emitEvent: false });
      age.setValue(wasFilled && this.prefillOnClear() ? this.lastYears : null, { emitEvent: false });
      age.updateValueAndValidity();
    }
  }
}
