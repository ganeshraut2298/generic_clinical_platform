import { ChangeDetectionStrategy, Component, OnDestroy, computed, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Observable } from 'rxjs';
import { MOBILE_PATTERN } from '../../../core/auth/auth.model';
import { ApiError } from '../../../core/http/problem-detail';
import { ConsentBlockComponent, buildConsents } from '../../privacy/consent-block.component';
import { PrivacyApi } from '../../privacy/privacy.api';
import { PrivacySettings } from '../../privacy/privacy.model';
import { DobAgeFieldsComponent, dateOfBirthValidator } from '../../patients/form/dob-age-fields.component';
import { PatientApi } from '../../patients/patient.api';
import { PatientSummary, Sex } from '../../patients/patient.model';
import { DeskApi } from './desk.api';
import {
  BOOK_NETWORK_MESSAGE,
  DeskAppointment,
  DeskBookedSummary,
  DeskDoctor,
  DeskFollowUpResult,
  DeskPanelMode,
  DeskProblem,
  confirmBookingLabel,
  deskProblem,
  inrOrFree,
  shortDoctorName,
  slotSummary,
  visitFeePaise,
} from './desk.model';

type BookMode = Extract<DeskPanelMode, { kind: 'book' }>;
type VisitChoice = 'NEW' | 'FOLLOW_UP';

interface ChosenPatient {
  id: string;
  name: string;
  mobile: string | null;
}

const SEARCH_DELAY_MS = 250;
const SEARCH_MIN_CHARS = 2;
const RESULT_LIMIT = 6;

/**
 * Confirm booking (SAH-91-1, SAH-91-3, SAH-93-2): the patient (search, minimal new patient, or fixed when a
 * moved appointment or a follow-up brought the user here), the visit type with its fee, and the confirm button.
 * The server checks everything; this panel only shows the answer in plain English.
 */
@Component({
  selector: 'app-desk-book-panel',
  imports: [ReactiveFormsModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatInputModule, DobAgeFieldsComponent, ConsentBlockComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './desk-book-panel.component.scss',
  templateUrl: './desk-book-panel.component.html',
})
export class DeskBookPanelComponent implements OnDestroy {
  private readonly api = inject(DeskApi);
  private readonly patients = inject(PatientApi);
  private readonly privacyApi = inject(PrivacyApi);

  readonly mode = input.required<BookMode>();
  readonly doctor = input<DeskDoctor | null>(null);
  /** Another write is running on the page: one write at a time (SAH-92). */
  readonly busy = input(false);

  readonly closePanel = output<void>();
  readonly booked = output<DeskBookedSummary>();
  /** A write failed; the page refreshes the finder or the board when the problem asks for it. */
  readonly failed = output<DeskProblem>();
  /** This panel starts or ends a write; the page keeps the one shared busy state. */
  readonly busyChange = output<boolean>();

  protected readonly shortName = shortDoctorName;
  protected readonly inr = inrOrFree;
  protected readonly confirmLabel = confirmBookingLabel;

  protected readonly query = signal('');
  protected readonly results = signal<PatientSummary[]>([]);
  protected readonly searching = signal(false);
  protected readonly searched = signal(false);
  protected readonly searchError = signal<string | null>(null);
  protected readonly chosen = signal<ChosenPatient | null>(null);
  protected readonly addingPatient = signal(false);
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly typeChoice = signal<VisitChoice | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly privacy = signal<PrivacySettings | null>(null);
  protected readonly privacyError = signal(false);
  protected readonly consentRecords = new FormControl(false, { nonNullable: true });
  protected readonly consentPromo = new FormControl(false, { nonNullable: true });

  protected readonly newForm = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [(c) => (c.value.trim() ? null : { required: true }), Validators.maxLength(120)],
    }),
    age: new FormControl<number | null>(null, [Validators.required, Validators.min(0), Validators.max(120)]),
    dateOfBirth: new FormControl('', { nonNullable: true, validators: [dateOfBirthValidator] }),
    sex: new FormControl<Sex>('F', { nonNullable: true, validators: [Validators.required] }),
    mobile: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(MOBILE_PATTERN)] }),
  });

  /** The patient is fixed when a moved appointment or a follow-up brought the user here. */
  protected readonly fixedPatient = computed<ChosenPatient | null>(() => {
    const m = this.mode();
    if (m.reschedule) {
      return { id: m.reschedule.patientId, name: m.reschedule.patientName, mobile: null };
    }
    if (m.followUp) {
      return { id: m.followUp.patientId, name: m.followUp.patientName, mobile: null };
    }
    return null;
  });
  protected readonly patient = computed(() => this.fixedPatient() ?? this.chosen());

  /** A moved appointment keeps its visit type; everything else lets the desk choose. */
  protected readonly typeLocked = computed(() => this.mode().reschedule !== null);
  protected readonly visitType = computed<VisitChoice>(() => {
    const m = this.mode();
    if (m.reschedule) {
      return m.reschedule.visitType === 'FOLLOW_UP' ? 'FOLLOW_UP' : 'NEW';
    }
    return this.typeChoice() ?? (m.followUp ? 'FOLLOW_UP' : 'NEW');
  });

  protected readonly fee = computed(() => {
    const doctor = this.doctor();
    return doctor ? visitFeePaise(doctor, this.visitType()) : 0;
  });
  protected readonly newFee = computed(() => {
    const doctor = this.doctor();
    return doctor ? visitFeePaise(doctor, 'NEW') : 0;
  });
  protected readonly followFee = computed(() => {
    const doctor = this.doctor();
    return doctor ? visitFeePaise(doctor, 'FOLLOW_UP') : 0;
  });
  protected readonly when = computed(() => {
    const m = this.mode();
    return slotSummary(m.date, m.time, this.doctor()?.slotMinutes ?? 0);
  });
  protected readonly canConfirm = computed(() => this.patient() !== null && this.doctor() !== null && !this.saving() && !this.busy());
  /** The new patient is saved only once the clinic's consent setting is known. */
  protected readonly canSavePatient = computed(() => !this.creating() && !this.busy() && this.privacy() !== null);

  private timer: ReturnType<typeof setTimeout> | null = null;
  private seq = 0;

  ngOnDestroy(): void {
    this.clearTimer();
  }

  protected setQuery(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.query.set(value);
    this.clearTimer();
    this.error.set(null);
    if (value.trim().length < SEARCH_MIN_CHARS) {
      this.seq++;
      this.results.set([]);
      this.searched.set(false);
      this.searching.set(false);
      return;
    }
    this.searching.set(true);
    this.timer = setTimeout(() => this.search(value.trim()), SEARCH_DELAY_MS);
  }

  protected choose(item: PatientSummary): void {
    this.chosen.set({ id: item.id, name: item.name, mobile: item.mobile });
    this.reset();
  }

  protected changePatient(): void {
    this.chosen.set(null);
    this.error.set(null);
  }

  protected chooseType(type: VisitChoice): void {
    this.typeChoice.set(type);
  }

  protected openNewPatient(): void {
    const q = this.query().trim();
    const digits = q.replace(/\D/g, '');
    this.newForm.reset({
      name: /\d/.test(q) ? '' : q,
      age: null,
      dateOfBirth: '',
      sex: 'F',
      mobile: digits.length >= 6 ? digits.slice(-10) : '',
    });
    this.createError.set(null);
    this.consentRecords.reset(false);
    this.consentPromo.reset(false);
    this.addingPatient.set(true);
    this.loadPrivacy();
  }

  protected loadPrivacy(): void {
    if (this.privacy() !== null) {
      return;
    }
    this.privacyError.set(false);
    this.privacyApi.get().subscribe({
      next: (settings) => this.privacy.set(settings),
      error: () => this.privacyError.set(true),
    });
  }

  protected cancelNewPatient(): void {
    this.addingPatient.set(false);
    this.createError.set(null);
  }

  protected newPatientError(field: 'name' | 'mobile'): string | null {
    const control = this.newForm.controls[field];
    if (!control.touched || !control.errors) {
      return null;
    }
    if (typeof control.errors['server'] === 'string') {
      return control.errors['server'];
    }
    if (control.errors['required']) {
      return field === 'name' ? 'Enter the name' : 'Enter a 10-digit mobile number';
    }
    if (control.errors['pattern']) {
      return 'Enter a 10-digit mobile number';
    }
    return control.errors['maxlength'] ? 'Use 120 characters or fewer' : null;
  }

  protected createPatient(): void {
    if (this.creating() || this.busy() || this.privacy() === null) {
      return;
    }
    this.newForm.markAllAsTouched();
    if (this.newForm.invalid) {
      return;
    }
    const value = this.newForm.getRawValue();
    const consents = buildConsents(this.privacy(), this.consentRecords, this.consentPromo);
    this.creating.set(true);
    this.busyChange.emit(true);
    this.createError.set(null);
    this.patients
      .create({
        name: value.name.trim(),
        // The date and the age are never sent together (SAH-73-1).
        ...(value.dateOfBirth ? { dateOfBirth: value.dateOfBirth } : { age: value.age as number }),
        sex: value.sex,
        mobile: value.mobile,
        ...(consents.length ? { consents } : {}),
      })
      .subscribe({
        next: (created) => {
          this.creating.set(false);
          this.busyChange.emit(false);
          this.chosen.set({ id: created.id, name: created.name, mobile: created.mobile });
          this.addingPatient.set(false);
          this.reset();
        },
        error: (error: unknown) => {
          this.creating.set(false);
          this.busyChange.emit(false);
          this.showCreateError(error);
        },
      });
  }

  protected confirm(): void {
    const patient = this.patient();
    const doctor = this.doctor();
    if (!patient || !doctor || this.saving() || this.busy()) {
      return;
    }
    const m = this.mode();
    const type = this.visitType();
    const where = { doctorMembershipId: m.doctorMembershipId, apptDate: m.date, apptTime: m.time };
    let call: Observable<DeskAppointment | DeskFollowUpResult>;
    if (m.reschedule) {
      const version = m.reschedule.appointmentVersion;
      if (version === null || version === undefined || !m.reschedule.appointmentId) {
        // A missing version is an error: never send a guessed one.
        const message = 'This appointment could not be checked. The board now shows the latest.';
        this.error.set(message);
        this.failed.emit({ message, slug: 'appointment-version-missing', refreshBoard: true, slotTaken: false });
        return;
      }
      call = this.api.reschedule(m.reschedule.appointmentId, { version, ...where });
    } else if (m.followUp) {
      call = this.api.bookFollowUp(m.followUp.followUpId, { ...where, visitType: type });
    } else {
      call = this.api.book({ patientId: patient.id, ...where, visitType: type, source: 'FRONT_DESK' });
    }
    this.saving.set(true);
    this.busyChange.emit(true);
    this.error.set(null);
    const summary: DeskBookedSummary = { patientName: patient.name, doctorName: doctor.name, date: m.date, time: m.time };
    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.busyChange.emit(false);
        this.booked.emit(summary);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.busyChange.emit(false);
        // A retry after a lost answer finds the saved booking: treat it as booked, never as a second one.
        if (!m.reschedule && !m.followUp && error instanceof ApiError && error.problem.type.endsWith('appointment-duplicate')) {
          this.booked.emit(summary);
          return;
        }
        const problem = deskProblem(error, BOOK_NETWORK_MESSAGE);
        this.error.set(problem.message);
        this.failed.emit(problem);
      },
    });
  }

  private search(q: string): void {
    this.timer = null;
    const seq = ++this.seq;
    this.searchError.set(null);
    this.patients.list({ q }).subscribe({
      next: (page) => {
        if (seq !== this.seq) {
          return;
        }
        this.results.set(page.items.slice(0, RESULT_LIMIT));
        this.searched.set(true);
        this.searching.set(false);
      },
      error: () => {
        if (seq !== this.seq) {
          return;
        }
        this.searching.set(false);
        this.searched.set(false);
        this.searchError.set('Could not search. Try again.');
      },
    });
  }

  private showCreateError(error: unknown): void {
    const problem = error instanceof ApiError ? error.problem : null;
    let onField = false;
    for (const item of problem?.fieldErrors ?? []) {
      const control = this.newForm.get(item.field);
      if (control) {
        control.setErrors({ server: item.message });
        control.markAsTouched();
        onField = true;
      }
    }
    this.createError.set(
      onField
        ? 'Some details need a change. Check the fields marked below and try again.'
        : (problem?.detail ?? 'Could not save the patient. Check the connection and try again.'),
    );
  }

  private reset(): void {
    this.clearTimer();
    this.seq++;
    this.query.set('');
    this.results.set([]);
    this.searched.set(false);
    this.searching.set(false);
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
