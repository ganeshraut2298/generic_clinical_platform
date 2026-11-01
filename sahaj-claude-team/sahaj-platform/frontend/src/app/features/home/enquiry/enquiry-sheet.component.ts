import { ChangeDetectionStrategy, Component, ElementRef, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { SalesEnquiryApi } from './sales-enquiry.api';
import {
  BEST_TIME_OPTIONS,
  BestTime,
  CLINIC_CITY_MAX_LENGTH,
  ENQUIRY_THANK_YOU,
  EnquirySource,
  MOBILE_PATTERN,
  NAME_MAX_LENGTH,
  SalesEnquiryRequest,
} from './sales-enquiry.model';

export interface EnquirySheetData {
  source: EnquirySource;
}

export type SubmitState = 'idle' | 'sending' | 'failed';
type FieldName = 'name' | 'clinicCity' | 'mobile' | 'bestTime';

const COPY: Record<EnquirySource, { title: string; lead: string }> = {
  BOOK_DEMO: {
    title: 'Book a demo',
    lead: 'A 15-minute walkthrough on a video call, or at your clinic in Pune.',
  },
  CONTACT_SALES: {
    title: 'Talk to sales',
    lead: 'Tell us about your clinics and we will call you.',
  },
};

const digitsOnly = (text: string): string => text.replace(/\D/g, '');

function nameRequired(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim().length === 0 ? { required: true } : null;
}

function mobileNumber(control: AbstractControl<string>): ValidationErrors | null {
  const digits = digitsOnly(control.value);
  if (digits.length !== 10) {
    return { mobileLength: true };
  }
  return MOBILE_PATTERN.test(digits) ? null : { mobileStart: true };
}

/** The enquiry sheet: Contact sales and Book a Demo. Posts to the platform API. */
@Component({
  selector: 'app-enquiry-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    IconComponent,
  ],
  styleUrl: './enquiry-sheet.component.scss',
  templateUrl: './enquiry-sheet.component.html',
})
export class EnquirySheetComponent {
  private readonly api = inject(SalesEnquiryApi);
  private readonly dialogRef = inject<MatDialogRef<EnquirySheetComponent, boolean>>(MatDialogRef);
  private readonly snackBar = inject(MatSnackBar);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly data = inject<EnquirySheetData>(MAT_DIALOG_DATA);

  protected readonly copy = COPY[this.data.source];
  protected readonly bestTimes = BEST_TIME_OPTIONS;
  protected readonly nameMax = NAME_MAX_LENGTH;
  protected readonly cityMax = CLINIC_CITY_MAX_LENGTH;

  protected readonly state = signal<SubmitState>('idle');
  /** A clear message when the request failed. The entered values are kept. */
  protected readonly failure = signal<string | null>(null);

  protected readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [nameRequired, Validators.maxLength(NAME_MAX_LENGTH)],
    }),
    clinicCity: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(CLINIC_CITY_MAX_LENGTH)],
    }),
    mobile: new FormControl('', { nonNullable: true, validators: [mobileNumber] }),
    bestTime: new FormControl<BestTime>('AFTER_OPD', { nonNullable: true }),
  });

  protected errorOf(field: FieldName): string | null {
    const errors = this.form.controls[field].errors;
    if (!errors) {
      return null;
    }
    if (typeof errors['server'] === 'string') {
      return errors['server'];
    }
    if (errors['required']) {
      return 'Enter your name';
    }
    if (errors['maxlength']) {
      return field === 'name'
        ? `Use ${NAME_MAX_LENGTH} characters or fewer`
        : `Use ${CLINIC_CITY_MAX_LENGTH} characters or fewer`;
    }
    if (errors['mobileLength']) {
      return 'Enter a 10-digit mobile number';
    }
    if (errors['mobileStart']) {
      return 'Mobile numbers start with 6, 7, 8 or 9';
    }
    return null;
  }

  protected close(): void {
    this.dialogRef.close(false);
  }

  protected submit(): void {
    if (this.state() === 'sending') {
      return;
    }
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.focusFirstInvalid();
      return;
    }
    const value = this.form.getRawValue();
    const city = value.clinicCity.trim();
    const request: SalesEnquiryRequest = {
      name: value.name.trim(),
      ...(city ? { clinicCity: city } : {}),
      mobile: digitsOnly(value.mobile),
      bestTime: value.bestTime,
      source: this.data.source,
    };

    this.state.set('sending');
    this.failure.set(null);
    this.api.create(request).subscribe({
      next: () => {
        this.snackBar.open(ENQUIRY_THANK_YOU, 'Close', { duration: 8000 });
        this.dialogRef.close(true);
      },
      error: (error: unknown) => this.fail(error),
    });
  }

  private fail(error: unknown): void {
    this.state.set('failed');
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
      this.failure.set('Some details need a change. Check the fields marked below and try again.');
      this.focusFirstInvalid();
    } else if (problem?.status === 429) {
      this.failure.set(
        'Too many requests were sent from this connection. Please try again in a while, or call us.',
      );
    } else if (problem?.status === 0) {
      this.failure.set(
        'We could not reach Sahaj. Check your internet connection and press Request a call again. Your details are still here.',
      );
    } else {
      this.failure.set(
        'We could not send your request. Your details are still here. Please try again in a few minutes.',
      );
    }
  }

  private focusFirstInvalid(): void {
    const order: FieldName[] = ['name', 'clinicCity', 'mobile'];
    const first = order.find((field) => this.form.controls[field].invalid);
    if (first) {
      this.host.nativeElement
        .querySelector<HTMLElement>(`[formControlName="${first}"]`)
        ?.focus();
    }
  }
}
