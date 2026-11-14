import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Subject, catchError, debounceTime, distinctUntilChanged, filter, of, switchMap, tap } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { formatInr, formatInrPaise } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import {
  PatientFormDialogComponent,
  PatientFormDialogData,
} from '../../patients/form/patient-form-dialog.component';
import { PatientApi } from '../../patients/patient.api';
import { Patient, PatientSummary } from '../../patients/patient.model';
import { AdmissionsApi } from '../admissions.api';
import { CASE_CLOSED, EmergencyHandoff } from '../emergency/emergency.model';
import {
  ADMISSION_TYPES,
  AdmissionBedOption,
  AdmissionDetail,
  AdmissionOptions,
  AdmissionType,
  CATEGORY_ORDER,
  MAX_DEPOSIT_RUPEES,
  MAX_REASON,
  PAYERS,
  PAY_MODES,
  PayMode,
  Payer,
  depositHint,
  minimumDepositPaise,
  parseWholeRupees,
} from '../ipd.model';

/**
 * Optional input: a bed to preselect (bed board tile or Admit here), or an emergency case being
 * admitted (SAH-28). For a case, `options` are the options the Emergency page already read with
 * `emergencyCaseId` (they carry the hand-off); `onCaseClosed` runs when the server answers
 * `case-closed`, so the queue behind the sheet can reload.
 */
export interface AdmitDialogData {
  bedId?: string;
  /** SAH-58-2. "Admit now" from a treatment course: the course's patient is picked and the sitting is linked. */
  patientId?: string;
  treatmentCourseSittingId?: string;
  /** SAH-67-3. The doctor's admission request this admission completes; pre-fills the type and reason. */
  admissionRequestId?: string;
  admissionType?: AdmissionType;
  reason?: string;
  emergencyCaseId?: string;
  options?: AdmissionOptions;
  onCaseClosed?: () => void;
}

interface PickedPatient {
  id: string;
  name: string;
  age: number;
  sex: string;
  uhid: string;
}

interface BedGroup {
  key: string;
  label: string;
  beds: AdmissionBedOption[];
}

const MIN_SEARCH = 2;
const SAVE_ERROR = 'We could not admit this patient. Try again.';

/** Empty means no deposit; otherwise whole rupees up to ₹10,00,000. */
function depositValidator(control: AbstractControl): ValidationErrors | null {
  const text = String(control.value ?? '').trim();
  return text === '' || parseWholeRupees(text) !== null ? null : { deposit: true };
}

/**
 * Admit-a-patient sheet (SAH-23), mockup `openAdmit`/`ipfRender`/`ipfgo`. The patient is picked
 * from Patients (or registered there), so no duplicate patient records are made. The minimum
 * deposit is worked out here for display only; the server checks everything again and its
 * messages are shown as they come.
 */
@Component({
  selector: 'app-admit-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    IconComponent,
  ],
  styleUrl: './admit-dialog.component.scss',
  templateUrl: './admit-dialog.component.html',
})
export class AdmitDialogComponent {
  private readonly api = inject(AdmissionsApi);
  private readonly patientApi = inject(PatientApi);
  private readonly dialog = inject(MatDialog);
  private readonly dialogRef =
    inject<MatDialogRef<AdmitDialogComponent, AdmissionDetail | null>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly data = inject<AdmitDialogData | null>(MAT_DIALOG_DATA, { optional: true });
  protected readonly emergencyCaseId = this.data?.emergencyCaseId ?? null;
  private readonly courseSittingId = this.data?.treatmentCourseSittingId ?? null;
  private readonly requestId = this.data?.admissionRequestId ?? null;

  protected readonly types = ADMISSION_TYPES;
  protected readonly payers = PAYERS;
  protected readonly modes = PAY_MODES;
  protected readonly maxReason = MAX_REASON;

  protected readonly options = signal<AdmissionOptions | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly query = signal('');
  protected readonly searching = signal(false);
  protected readonly searched = signal(false);
  protected readonly searchError = signal<string | null>(null);
  protected readonly results = signal<PatientSummary[]>([]);
  protected readonly patient = signal<PickedPatient | null>(null);
  /** SAH-28. The emergency case being admitted: shows "From Emergency" and prefills the sheet. */
  protected readonly handoff = signal<EmergencyHandoff | null>(null);

  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = new FormGroup({
    doctor: new FormControl('', { nonNullable: true }),
    type: new FormControl<AdmissionType>('planned', { nonNullable: true }),
    bed: new FormControl('', { nonNullable: true }),
    reason: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(MAX_REASON)] }),
    payer: new FormControl<Payer>('Self', { nonNullable: true }),
    deposit: new FormControl('', { nonNullable: true, validators: [depositValidator] }),
    mode: new FormControl<PayMode>('UPI', { nonNullable: true }),
    consent: new FormControl(false, { nonNullable: true }),
  });
  private readonly value = signal(this.form.getRawValue());
  private depositEdited = false;
  private handoffApplied = false;
  private readonly searchTerms = new Subject<string>();

  /** Groups by category; inside a group the beds keep the order the server sends (room, then code). */
  protected readonly bedGroups = computed<BedGroup[]>(() => {
    const beds = this.options()?.beds ?? [];
    return CATEGORY_ORDER.map((key) => {
      const inCategory = beds.filter((b) => b.categoryKey === key);
      const first = inCategory[0];
      return {
        key,
        label: first ? `${first.categoryName} · ${formatInrPaise(first.ratePaise)} a day` : '',
        beds: inCategory,
      };
    }).filter((g) => g.beds.length > 0);
  });

  protected readonly bed = computed(
    () => this.options()?.beds.find((b) => b.id === this.value().bed) ?? null,
  );

  protected readonly minimumPaise = computed(() => {
    const options = this.options();
    return options
      ? minimumDepositPaise(options.rules, this.value().type, this.bed()?.categoryKey)
      : 0;
  });

  protected readonly hint = computed(() => {
    const options = this.options();
    if (!options) {
      return '';
    }
    return depositHint({
      minimumPaise: this.minimumPaise(),
      type: this.value().type,
      categoryName: this.bed()?.categoryName ?? '',
      emergencyWithoutDeposit: options.rules.emergencyWithoutDeposit,
      payer: this.value().payer,
      format: formatInrPaise,
    });
  });

  protected readonly canSubmit = computed(
    () => !!this.patient() && !!this.bed() && !!this.value().doctor && !this.saving(),
  );

  constructor() {
    this.form.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.value.set(this.form.getRawValue()));
    this.form.controls.type.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.syncDeposit());
    this.form.controls.bed.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.syncDeposit());

    this.searchTerms
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        filter((term) => {
          const ok = term.length >= MIN_SEARCH;
          if (!ok) {
            this.results.set([]);
            this.searched.set(false);
          }
          return ok;
        }),
        tap(() => {
          this.searching.set(true);
          this.searchError.set(null);
        }),
        switchMap((term) =>
          this.patientApi.list({ q: term }).pipe(
            catchError(() => {
              this.searchError.set('We could not search patients. Try again.');
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((page) => {
        this.searching.set(false);
        this.searched.set(true);
        this.results.set(page?.items ?? []);
      });

    if (this.data?.bedId) {
      this.form.controls.bed.setValue(this.data.bedId);
    }
    if (this.data?.admissionType) {
      this.form.controls.type.setValue(this.data.admissionType);
    }
    if (this.data?.reason) {
      this.form.controls.reason.setValue(this.data.reason.slice(0, MAX_REASON));
    }
    if (this.data?.patientId) {
      this.patientApi
        .get(this.data.patientId)
        .pipe(takeUntilDestroyed())
        .subscribe({ next: (p) => this.pick(p), error: () => undefined });
    }
    if (this.data?.options) {
      this.options.set(this.data.options);
      this.loading.set(false);
      this.applyDefaults(this.data.options);
    } else {
      this.loadOptions();
    }
  }

  protected loadOptions(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .options(this.emergencyCaseId ?? undefined)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (options) => {
          this.options.set(options);
          this.loading.set(false);
          this.applyDefaults(options);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(problemMessage(error, 'We could not load the admission options. Try again.'));
        },
      });
  }

  /** Keeps what the user chose where it is still valid; otherwise the first doctor and bed. */
  private applyDefaults(options: AdmissionOptions): void {
    const { doctor, bed } = this.form.controls;
    const handoff = options.emergencyCase ?? null;
    if (handoff && !this.handoffApplied) {
      this.applyHandoff(options, handoff);
    }
    if (!options.doctors.some((d) => d.membershipId === doctor.value)) {
      doctor.setValue(options.doctors[0]?.membershipId ?? '');
    }
    if (!options.beds.some((b) => b.id === bed.value)) {
      bed.setValue(this.bedGroups()[0]?.beds[0]?.id ?? '');
    }
    this.syncDeposit();
  }

  /**
   * Admission type Emergency, the reason = the complaint, the suggested bed, and the patient:
   * a known name opens the search with the name; an unknown patient opens the registration dialog
   * with the placeholder name, age and sex (OQ-28-1).
   */
  private applyHandoff(options: AdmissionOptions, handoff: EmergencyHandoff): void {
    this.handoffApplied = true;
    this.handoff.set(handoff);
    this.form.controls.type.setValue('emergency');
    this.form.controls.reason.setValue(handoff.complaint.slice(0, MAX_REASON));
    if (handoff.suggestedBedId && options.beds.some((b) => b.id === handoff.suggestedBedId)) {
      this.form.controls.bed.setValue(handoff.suggestedBedId);
    }
    if (handoff.identityPending) {
      queueMicrotask(() => this.register());
    } else {
      this.query.set(handoff.displayName);
      this.searchTerms.next(handoff.displayName.trim());
    }
  }

  private syncDeposit(): void {
    if (this.depositEdited || !this.options()) {
      return;
    }
    // The type and bed controls have just changed, so read the minimum from them directly.
    this.value.set(this.form.getRawValue());
    this.form.controls.deposit.setValue(String(this.minimumPaise() / 100));
  }

  protected onDepositInput(): void {
    this.depositEdited = true;
  }

  protected setType(type: AdmissionType): void {
    this.form.controls.type.setValue(type);
  }

  protected setPayer(payer: Payer): void {
    this.form.controls.payer.setValue(payer);
  }

  protected setMode(mode: PayMode): void {
    this.form.controls.mode.setValue(mode);
  }

  protected onSearchInput(text: string): void {
    this.query.set(text);
    this.searchTerms.next(text.trim());
  }

  protected pick(summary: PatientSummary): void {
    this.patient.set({
      id: summary.id,
      name: summary.name,
      age: summary.age,
      sex: summary.sex,
      uhid: summary.uhid,
    });
    this.serverError.set(null);
  }

  protected changePatient(): void {
    this.patient.set(null);
  }

  protected register(): void {
    const digits = this.query().replace(/\D/g, '');
    const handoff = this.handoff();
    const data: PatientFormDialogData = handoff
      ? {
          name: handoff.displayName,
          age: handoff.age,
          sex: handoff.sex,
          ...(handoff.mobile ? { mobile: handoff.mobile } : {}),
        }
      : digits.length === 10
        ? { mobile: digits }
        : {};
    this.dialog
      .open<PatientFormDialogComponent, PatientFormDialogData, Patient | null>(PatientFormDialogComponent, {
        panelClass: 'sheet-panel',
        width: '560px',
        maxWidth: '100vw',
        data,
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((created) => {
        if (created) {
          this.pick(created);
        }
      });
  }

  protected depositError(): string | null {
    return this.form.controls.deposit.errors
      ? `Enter whole rupees up to ${formatInr(MAX_DEPOSIT_RUPEES)}`
      : null;
  }

  protected submit(): void {
    const patient = this.patient();
    const bed = this.bed();
    this.form.markAllAsTouched();
    if (!patient || !bed || this.saving() || this.form.invalid) {
      return;
    }
    const v = this.form.getRawValue();
    const rupees = parseWholeRupees(v.deposit) ?? 0;
    const reason = v.reason.trim();
    this.saving.set(true);
    this.serverError.set(null);
    this.api
      .admit({
        patientId: patient.id,
        doctorMembershipId: v.doctor,
        admissionType: v.type,
        bedId: bed.id,
        reason: reason || null,
        payer: v.payer,
        depositPaise: rupees * 100,
        depositMode: rupees > 0 ? v.mode : null,
        consentSigned: v.consent,
        ...(this.emergencyCaseId ? { emergencyCaseId: this.emergencyCaseId } : {}),
        ...(this.courseSittingId ? { treatmentCourseSittingId: this.courseSittingId } : {}),
        ...(this.requestId ? { admissionRequestId: this.requestId } : {}),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (admission) => {
          this.saving.set(false);
          this.dialogRef.close(admission);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          const caseClosed = error instanceof ApiError && error.problem.type.endsWith('case-closed');
          this.serverError.set(problemMessage(error, caseClosed ? CASE_CLOSED : SAVE_ERROR));
          if (error instanceof ApiError && error.problem.type.endsWith('bed-not-free')) {
            this.loadOptions();
          }
          if (caseClosed) {
            this.data?.onCaseClosed?.();
          }
        },
      });
  }

  protected close(): void {
    this.dialogRef.close(null);
  }
}
