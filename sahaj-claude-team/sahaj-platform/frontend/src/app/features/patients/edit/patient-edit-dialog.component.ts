import { ChangeDetectionStrategy, Component, ElementRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { DobAgeFieldsComponent, dateOfBirthValidator } from '../form/dob-age-fields.component';
import { PatientApi } from '../patient.api';
import { Patient, PatientUpdate } from '../patient.model';

export interface PatientEditDialogData {
  patient: Patient;
}

/**
 * "Edit details" sheet (SAH-73-2): only "Date of birth" and "Age", same behaviour as the registration
 * dialog. A date can be added, corrected or cleared (PATCH with `dateOfBirth: null`); on clear the Age
 * box is pre-filled with the years worked out from the cleared date and can be changed. No other
 * patient field is editable here. The sheet stays open on a failed save, with the typed values.
 */
@Component({
  selector: 'app-patient-edit-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, IconComponent, DobAgeFieldsComponent],
  styles: [
    `
      :host {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
    `,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="pe-title">Edit details</h2>
        <p class="muted">{{ data.patient.name }}</p>
      </div>
      <button mat-icon-button type="button" (click)="close()" aria-label="Close">
        <app-icon name="x" />
      </button>
    </div>

    <form class="sheet-b" [formGroup]="form" (ngSubmit)="submit()" novalidate aria-labelledby="pe-title">
      <app-dob-age-fields [age]="form.controls.age" [dateOfBirth]="form.controls.dateOfBirth" [prefillOnClear]="true" />

      @if (serverError(); as message) {
        <div class="notice err" role="alert">
          <app-icon name="alert" [size]="16" />
          <span>{{ message }}</span>
        </div>
      }

      <div class="sheet-f">
        <button mat-stroked-button type="button" (click)="close()">Cancel</button>
        <button mat-flat-button type="submit" [disabled]="saving()">
          {{ serverError() ? 'Retry' : 'Save' }}
        </button>
      </div>
    </form>
  `,
})
export class PatientEditDialogComponent {
  private readonly api = inject(PatientApi);
  private readonly dialogRef = inject<MatDialogRef<PatientEditDialogComponent, Patient | null>>(MatDialogRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly data = inject<PatientEditDialogData>(MAT_DIALOG_DATA);

  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  private readonly originalDate = this.data.patient.dateOfBirth ?? '';

  protected readonly form = new FormGroup({
    age: new FormControl<number | null>(this.data.patient.age, {
      validators: [Validators.required, Validators.min(0), Validators.max(120)],
    }),
    dateOfBirth: new FormControl(this.originalDate, { nonNullable: true, validators: [dateOfBirthValidator] }),
  });

  protected close(): void {
    this.dialogRef.close(null);
  }

  protected submit(): void {
    if (this.saving()) {
      return;
    }
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.host.nativeElement.querySelector<HTMLElement>('input[data-field]:not([readonly])')?.focus();
      return;
    }
    const body = this.body();
    if (!body) {
      this.dialogRef.close(null);
      return;
    }
    this.saving.set(true);
    this.serverError.set(null);
    this.api.update(this.data.patient.id, body).subscribe({
      next: (patient) => {
        this.saving.set(false);
        this.dialogRef.close(patient);
      },
      error: (error: unknown) => this.fail(error),
    });
  }

  /** Only what changed: a date (set or correct), `null` (clear, with the age to keep), or a changed age. */
  private body(): PatientUpdate | null {
    const date = this.form.controls.dateOfBirth.value;
    const age = this.form.controls.age.value;
    if (date) {
      return date === this.originalDate ? null : { dateOfBirth: date };
    }
    if (this.originalDate) {
      return { dateOfBirth: null, ...(age !== null ? { age } : {}) };
    }
    return age !== null && age !== this.data.patient.age ? { age } : null;
  }

  private fail(error: unknown): void {
    this.saving.set(false);
    const problem = error instanceof ApiError ? error.problem : null;
    let shown = false;
    for (const item of problem?.fieldErrors ?? []) {
      const control = item.field === 'dateOfBirth' || item.field === 'age' ? this.form.controls[item.field] : null;
      if (control) {
        control.setErrors({ server: item.message });
        control.markAsTouched();
        shown = true;
      }
    }
    this.serverError.set(
      shown
        ? 'Some details need a change. Check the fields marked below and try again.'
        : (problem?.detail ?? 'We could not save the changes. Try again.'),
    );
  }
}
