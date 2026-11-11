import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogRef } from '@angular/material/dialog';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmergencyAccessApi } from './emergency-access.api';
import {
  CANCEL_LABEL,
  CONFIRM_LABEL,
  EmergencyAccessDraft,
  EmergencyAccessErrors,
  EmergencyAccessField,
  EmergencyAccessGrant,
  EmergencyAccessPicker,
  EmergencyAccessReasonKey,
  FIELD_ORDER,
  NOTHING_OUTSIDE,
  OPEN_ERROR,
  OPEN_LABEL,
  PATIENT_LABEL,
  PICKER_ERROR,
  PICKER_LOADING,
  REASON_LABEL,
  REASON_PLACEHOLDER,
  SHEET_SUBTITLE,
  SHEET_TITLE,
  SHEET_TITLE_ID,
  patientOption,
  validateDraft,
} from './emergency-access.model';

/** How the sheet closed: a grant was made; the patient was already in the list; or the page should read again. */
export type EmergencyAccessResult =
  | { kind: 'granted'; grant: EmergencyAccessGrant }
  | { kind: 'in-scope'; message: string }
  | { kind: 'reload' };

const FIELD_CONTROL: Record<EmergencyAccessField, string> = {
  admissionId: 'eg-patient',
  reasonKey: 'eg-reason',
  confirmed: 'eg-confirm',
};

/**
 * Sheet "Emergency access" (SAH-34, story 34E; mockup `sfbg`, `sfbgok`): the patients outside the nurse's
 * wards, the reason, the confirmation and "Open for 30 minutes".
 *
 * - The patients and the three reasons come from the server (`GET /ipd/emergency-access/patients`); the
 *   sheet draws them and never decides who is outside the scope. Nothing is stored or logged.
 * - Without a reason (or the ticked box) the sheet stays open with the message under the field and sends
 *   nothing. The server checks again (400).
 * - Open is off while the request runs, and so are Cancel, the close button and Esc: one tap, one grant.
 *   The sheet never says access was granted before the server answered. On a failure the choices stay.
 * - A 403 (the scope or the module changed) clears the patient names, shows the server's message and asks the
 *   page to read again when the sheet closes (SAH-40 pattern; the module-off lock comes from that read).
 * - Focus returns to the button that opened the sheet when it closes (Material restores it).
 */
@Component({
  selector: 'app-emergency-access-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent],
  styleUrl: './emergency-access-sheet.component.scss',
  templateUrl: './emergency-access-sheet.component.html',
})
export class EmergencyAccessSheetComponent {
  private readonly api = inject(EmergencyAccessApi);
  private readonly dialogRef =
    inject<MatDialogRef<EmergencyAccessSheetComponent, EmergencyAccessResult>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly titleId = SHEET_TITLE_ID;
  protected readonly title = SHEET_TITLE;
  protected readonly subtitle = SHEET_SUBTITLE;
  protected readonly patientLabel = PATIENT_LABEL;
  protected readonly reasonLabel = REASON_LABEL;
  protected readonly reasonPlaceholder = REASON_PLACEHOLDER;
  protected readonly confirmLabel = CONFIRM_LABEL;
  protected readonly cancelLabel = CANCEL_LABEL;
  protected readonly openLabel = OPEN_LABEL;
  protected readonly nothingOutside = NOTHING_OUTSIDE;
  protected readonly loadingText = PICKER_LOADING;
  protected readonly option = patientOption;

  protected readonly picker = signal<EmergencyAccessPicker | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** The server said no (403): the names are gone and only Cancel is left. */
  protected readonly refused = signal<string | null>(null);
  protected readonly running = signal(false);
  protected readonly errors = signal<EmergencyAccessErrors>({});
  /** A failure that is not about one field; the choices stay. */
  protected readonly failure = signal<string | null>(null);

  protected readonly nobodyOutside = computed(() => {
    const p = this.picker();
    return p !== null && p.items.length === 0;
  });

  protected readonly form = new FormGroup({
    admissionId: new FormControl('', { nonNullable: true }),
    reasonKey: new FormControl('', { nonNullable: true }),
    confirmed: new FormControl(false, { nonNullable: true }),
  });

  constructor() {
    effect(() => {
      this.dialogRef.disableClose = this.running();
    });
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .picker()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (picker) => {
          this.loading.set(false);
          this.accept(picker);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.picker.set(null);
          if (error instanceof ApiError && error.status === 403) {
            this.refuse(error);
            return;
          }
          this.loadError.set(PICKER_ERROR);
        },
      });
  }

  /** Takes the list; the patient chosen stays when still on the list, else the first one (as the mockup). */
  private accept(picker: EmergencyAccessPicker): void {
    this.picker.set(picker);
    const keep = picker.items.find((p) => p.admissionId === this.form.controls.admissionId.value);
    this.form.controls.admissionId.setValue(keep?.admissionId ?? picker.items[0]?.admissionId ?? '');
  }

  private refuse(error: ApiError): void {
    this.picker.set(null);
    this.form.controls.admissionId.setValue('');
    this.refused.set(error.problem.detail ?? OPEN_ERROR);
  }

  protected dropError(field: EmergencyAccessField): void {
    if (this.errors()[field]) {
      const rest = { ...this.errors() };
      delete rest[field];
      this.errors.set(rest);
    }
  }

  protected submit(): void {
    if (this.running() || this.picker() === null || this.nobodyOutside() || this.refused() !== null) {
      return;
    }
    const draft: EmergencyAccessDraft = this.form.getRawValue();
    const errors = validateDraft(draft);
    this.errors.set(errors);
    this.failure.set(null);
    if (this.focusFirst(errors)) {
      return;
    }
    this.running.set(true);
    this.api
      .open({
        admissionId: draft.admissionId,
        reasonKey: draft.reasonKey as EmergencyAccessReasonKey,
        confirmed: true,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (grant) => {
          this.running.set(false);
          this.dialogRef.close({ kind: 'granted', grant });
        },
        error: (error: unknown) => {
          this.running.set(false);
          this.failed(error);
        },
      });
  }

  private failed(error: unknown): void {
    if (!(error instanceof ApiError) || error.status === 0 || error.status >= 500) {
      this.failure.set(OPEN_ERROR);
      return;
    }
    if (error.status === 400) {
      const shown: EmergencyAccessErrors = {};
      let other: string | null = null;
      for (const item of error.fieldErrors) {
        if (FIELD_ORDER.includes(item.field as EmergencyAccessField)) {
          shown[item.field as EmergencyAccessField] ??= item.message;
        } else {
          other ??= item.message;
        }
      }
      this.errors.set(shown);
      if (!this.focusFirst(shown) || other !== null) {
        this.failure.set(other ?? error.problem.detail ?? OPEN_ERROR);
      }
      return;
    }
    if (error.status === 403) {
      this.refuse(error);
      return;
    }
    if (error.status === 409 && error.problem.type.endsWith('already-in-scope')) {
      this.dialogRef.close({ kind: 'in-scope', message: error.problem.detail ?? '' });
      return;
    }
    this.failure.set(error.problem.detail ?? OPEN_ERROR);
    if (error.status === 404) {
      // The patient went home: the list is read again, the other choices stay.
      this.api
        .picker()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({ next: (picker) => this.accept(picker), error: () => undefined });
    }
  }

  /** Focuses the first field with an error in screen order; true when there was one. */
  private focusFirst(errors: EmergencyAccessErrors): boolean {
    const first = FIELD_ORDER.find((f) => errors[f]);
    if (!first) {
      return false;
    }
    this.host.nativeElement.querySelector<HTMLElement>(`#${FIELD_CONTROL[first]}`)?.focus();
    return true;
  }

  protected cancel(): void {
    if (this.running()) {
      return;
    }
    // After a 403 the page reads the list again, so the chip and the module lock follow the server.
    this.dialogRef.close(this.refused() !== null ? { kind: 'reload' } : undefined);
  }
}
