import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MOBILE_PATTERN } from '../../../core/auth/auth.model';
import { formatClockLabel } from '../../../shared/format/ist-date';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PatientFormDialogComponent } from '../../patients/form/patient-form-dialog.component';
import { PatientApi } from '../../patients/patient.api';
import { PatientSummary, Sex } from '../../patients/patient.model';
import { AppointmentsApi } from '../appointments.api';
import {
  APPOINTMENT_SOURCES,
  APPOINTMENT_VISIT_TYPE_LABELS,
  Appointment,
  AppointmentSource,
  AppointmentVisitType,
} from '../appointments.model';

interface SelectedPatient {
  id: string;
  name: string;
  age: number;
  sex: Sex;
}

export interface BookAppointmentDialogData {
  doctorMembershipId: string;
  doctorName: string;
  apptDate: string;
  apptTime: string;
}

const VISIT_TYPES: readonly AppointmentVisitType[] = ['NEW', 'FOLLOW_UP'];

/**
 * Book dialog (SAH-11), mockup `apbook`/`bookslot`: pick-or-register a patient (the same flow
 * as SAH-9's "New token" dialog) for the free slot the front desk clicked, then choose visit
 * type and source. The doctor and slot are fixed by the column and row clicked.
 */
@Component({
  selector: 'app-book-appointment-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatFormFieldModule, MatInputModule, IconComponent],
  styleUrl: './book-appointment-dialog.component.scss',
  templateUrl: './book-appointment-dialog.component.html',
})
export class BookAppointmentDialogComponent {
  protected readonly data = inject<BookAppointmentDialogData>(MAT_DIALOG_DATA);
  private readonly patientApi = inject(PatientApi);
  private readonly appointmentsApi = inject(AppointmentsApi);
  private readonly dialog = inject(MatDialog);
  private readonly dialogRef = inject<MatDialogRef<BookAppointmentDialogComponent, Appointment | null>>(MatDialogRef);

  protected readonly visitTypes = VISIT_TYPES;
  protected readonly visitTypeLabels = APPOINTMENT_VISIT_TYPE_LABELS;
  protected readonly sources = APPOINTMENT_SOURCES;
  protected readonly formatClockLabel = formatClockLabel;

  protected readonly mobile = signal('');
  protected readonly searched = signal(false);
  protected readonly searching = signal(false);
  protected readonly searchError = signal<string | null>(null);
  protected readonly results = signal<PatientSummary[]>([]);
  protected readonly selectedPatient = signal<SelectedPatient | null>(null);

  protected readonly visitType = signal<AppointmentVisitType>('NEW');
  protected readonly source = signal<AppointmentSource>('FRONT_DESK');
  protected readonly note = signal('');

  protected readonly booking = signal(false);
  protected readonly bookError = signal<string | null>(null);
  protected readonly booked = signal<Appointment | null>(null);

  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  protected readonly mobileValid = computed(() => MOBILE_PATTERN.test(this.mobile()));

  protected readonly canBook = computed(() => !!this.selectedPatient() && !this.booking());

  protected onMobileInput(value: string): void {
    this.mobile.set(value.replace(/\D/g, '').slice(0, 10));
    this.selectedPatient.set(null);
    this.results.set([]);
    this.searched.set(false);
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }
    if (MOBILE_PATTERN.test(this.mobile())) {
      this.searchTimer = setTimeout(() => this.search(), 300);
    }
  }

  protected search(): void {
    if (!this.mobileValid()) {
      return;
    }
    this.searching.set(true);
    this.searchError.set(null);
    this.patientApi.list({ q: this.mobile() }).subscribe({
      next: (page) => {
        this.results.set(page.items);
        this.searching.set(false);
        this.searched.set(true);
      },
      error: () => {
        this.searching.set(false);
        this.searched.set(true);
        this.searchError.set('We could not search patients. Try again.');
      },
    });
  }

  protected selectPatient(patient: PatientSummary): void {
    this.selectedPatient.set({ id: patient.id, name: patient.name, age: patient.age, sex: patient.sex });
  }

  protected addPatient(): void {
    const ref = this.dialog.open(PatientFormDialogComponent, {
      panelClass: 'sheet-panel',
      width: '560px',
      maxWidth: '100vw',
      data: { mobile: this.mobile(), familyMember: this.results().length > 0 },
    });
    ref.afterClosed().subscribe((patient) => {
      if (!patient) {
        return;
      }
      this.selectedPatient.set({ id: patient.id, name: patient.name, age: patient.age, sex: patient.sex });
      this.results.update((items) => [
        { id: patient.id, uhid: patient.uhid, name: patient.name, age: patient.age, ageDetail: patient.ageDetail, sex: patient.sex, mobile: patient.mobile },
        ...items,
      ]);
    });
  }

  protected setVisitType(value: AppointmentVisitType): void {
    this.visitType.set(value);
  }

  protected setSource(value: AppointmentSource): void {
    this.source.set(value);
  }

  protected onNoteInput(value: string): void {
    this.note.set(value);
  }

  protected book(): void {
    const patient = this.selectedPatient();
    if (!patient || this.booking()) {
      return;
    }
    this.booking.set(true);
    this.bookError.set(null);
    this.appointmentsApi
      .book({
        patientId: patient.id,
        doctorMembershipId: this.data.doctorMembershipId,
        apptDate: this.data.apptDate,
        apptTime: this.data.apptTime,
        visitType: this.visitType(),
        source: this.source(),
        note: this.note().trim() || null,
      })
      .subscribe({
        next: (appointment) => {
          this.booking.set(false);
          this.booked.set(appointment);
        },
        error: (error: unknown) => {
          this.booking.set(false);
          this.bookError.set(
            error instanceof ApiError
              ? error.problem.detail ?? 'We could not book this slot. Try again.'
              : 'We could not book this slot. Try again.',
          );
        },
      });
  }

  protected close(): void {
    this.dialogRef.close(this.booked());
  }
}
