import { ChangeDetectionStrategy, Component, ElementRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MOBILE_PATTERN } from '../../../core/auth/auth.model';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PatientApi } from '../patient.api';
import { LANGUAGE_LABELS, Patient, PatientLanguage, Sex } from '../patient.model';

export interface PatientFormDialogData {
  /** Pre-fills the mobile number when adding a family member from the record page. */
  mobile?: string;
  /** Label for the dialog heading; "Add family member" reuses the same form. */
  familyMember?: boolean;
  defaultLanguage?: PatientLanguage;
}

type FieldName = 'name' | 'age' | 'sex' | 'mobile' | 'area' | 'language' | 'allergy' | 'chronic';

/**
 * Plain registration form (SAH-8): name, age, sex, mobile, area, language, allergy, chronic,
 * ABHA toggle. This is not the mockup's combined walk-in/token modal (`openWalkin`) — that is
 * SAH-9's job, once Queue exists.
 */
@Component({
  selector: 'app-patient-form-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    IconComponent,
  ],
  styleUrl: './patient-form-dialog.component.scss',
  templateUrl: './patient-form-dialog.component.html',
})
export class PatientFormDialogComponent {
  private readonly api = inject(PatientApi);
  private readonly dialogRef = inject<MatDialogRef<PatientFormDialogComponent, Patient | null>>(MatDialogRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly data = inject<PatientFormDialogData>(MAT_DIALOG_DATA, { optional: true }) ?? {};

  protected readonly languageLabels = LANGUAGE_LABELS;
  protected readonly languages: readonly PatientLanguage[] = ['mr', 'hi', 'en'];
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [(c) => (c.value.trim() ? null : { required: true }), Validators.maxLength(120)],
    }),
    age: new FormControl<number | null>(null, {
      validators: [Validators.required, Validators.min(0), Validators.max(120)],
    }),
    sex: new FormControl<Sex>('F', { nonNullable: true, validators: [Validators.required] }),
    mobile: new FormControl(this.data.mobile ?? '', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(MOBILE_PATTERN)],
    }),
    area: new FormControl('', { nonNullable: true }),
    language: new FormControl<PatientLanguage>(this.data.defaultLanguage ?? 'mr', { nonNullable: true }),
    allergy: new FormControl('', { nonNullable: true }),
    chronic: new FormControl('', { nonNullable: true }),
    abhaLinked: new FormControl(false, { nonNullable: true }),
  });

  protected errorOf(field: FieldName): string | null {
    const control = this.form.controls[field];
    if (!control.touched || !control.errors) {
      return null;
    }
    if (typeof control.errors['server'] === 'string') {
      return control.errors['server'];
    }
    if (control.errors['required']) {
      return field === 'name'
        ? 'Enter the name'
        : field === 'age'
          ? 'Enter the age'
          : field === 'mobile'
            ? 'Enter a 10-digit mobile number'
            : 'This field is required';
    }
    if (control.errors['pattern']) {
      return 'Enter a 10-digit mobile number';
    }
    if (control.errors['min'] || control.errors['max']) {
      return 'Age must be between 0 and 120';
    }
    if (control.errors['maxlength']) {
      return 'Use 120 characters or fewer';
    }
    return null;
  }

  protected close(): void {
    this.dialogRef.close(null);
  }

  protected submit(): void {
    if (this.saving()) {
      return;
    }
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.focusFirstInvalid();
      return;
    }
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.serverError.set(null);
    this.api
      .create({
        name: value.name.trim(),
        age: value.age as number,
        sex: value.sex,
        mobile: value.mobile,
        area: value.area.trim() || null,
        language: value.language,
        allergy: value.allergy.trim() || null,
        chronic: value.chronic.trim() || null,
        abhaLinked: value.abhaLinked,
      })
      .subscribe({
        next: (patient) => {
          this.saving.set(false);
          this.dialogRef.close(patient);
        },
        error: (error: unknown) => this.fail(error),
      });
  }

  private fail(error: unknown): void {
    this.saving.set(false);
    const problem = error instanceof ApiError ? error.problem : null;
    const fieldErrors = problem?.fieldErrors ?? [];
    let shownOnField = false;
    for (const item of fieldErrors) {
      const control = this.form.get(item.field);
      if (control) {
        control.setErrors({ server: item.message });
        control.markAsTouched();
        shownOnField = true;
      }
    }
    if (shownOnField) {
      this.serverError.set('Some details need a change. Check the fields marked below and try again.');
      this.focusFirstInvalid();
    } else {
      this.serverError.set(
        problem?.detail ?? 'We could not register this patient. Try again.',
      );
    }
  }

  private focusFirstInvalid(): void {
    const order: FieldName[] = ['name', 'age', 'mobile'];
    const first = order.find((field) => this.form.controls[field].invalid);
    if (first) {
      this.host.nativeElement.querySelector<HTMLElement>(`[formControlName="${first}"]`)?.focus();
    }
  }
}
