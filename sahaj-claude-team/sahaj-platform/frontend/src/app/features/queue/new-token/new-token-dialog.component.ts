import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MOBILE_PATTERN } from '../../../core/auth/auth.model';
import { ApiError } from '../../../core/http/problem-detail';
import { formatInr } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PatientFormDialogComponent } from '../../patients/form/patient-form-dialog.component';
import { PatientApi } from '../../patients/patient.api';
import { PatientSummary, Sex } from '../../patients/patient.model';
import { TeamApi } from '../../config/team/team.api';
import { TeamMember, isDoctorRole } from '../../config/team/team.model';
import { QueueApi } from '../queue.api';
import { Visit, visitTypeLabel } from '../queue.model';

interface SelectedPatient {
  id: string;
  name: string;
  age: number;
  sex: Sex;
}

/**
 * "New token" dialog (SAH-9), mockup `openWalkin` in walk-in mode: mobile search → pick a
 * family member or register new (reusing the SAH-8 form) → choose doctor → issue. The fee shown
 * before issuing is the doctor's plain new-visit fee; the real fee (which may be a follow-up
 * rate) only exists once the server computes it, so it is shown after the token is issued.
 */
@Component({
  selector: 'app-new-token-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatFormFieldModule, MatInputModule, IconComponent],
  styleUrl: './new-token-dialog.component.scss',
  templateUrl: './new-token-dialog.component.html',
})
export class NewTokenDialogComponent implements OnInit {
  private readonly patientApi = inject(PatientApi);
  private readonly teamApi = inject(TeamApi);
  private readonly queueApi = inject(QueueApi);
  private readonly dialog = inject(MatDialog);
  private readonly dialogRef = inject<MatDialogRef<NewTokenDialogComponent, Visit | null>>(MatDialogRef);

  protected readonly formatInr = formatInr;
  protected readonly visitTypeLabel = visitTypeLabel;

  protected readonly mobile = signal('');
  protected readonly searched = signal(false);
  protected readonly searching = signal(false);
  protected readonly searchError = signal<string | null>(null);
  protected readonly results = signal<PatientSummary[]>([]);
  protected readonly selectedPatient = signal<SelectedPatient | null>(null);

  protected readonly doctorsLoading = signal(true);
  protected readonly doctors = signal<TeamMember[]>([]);
  protected readonly selectedDoctorId = signal<string | null>(null);

  protected readonly issuing = signal(false);
  protected readonly issueError = signal<string | null>(null);
  protected readonly issuedVisit = signal<Visit | null>(null);

  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  protected readonly mobileValid = computed(() => MOBILE_PATTERN.test(this.mobile()));

  protected readonly selectedDoctor = computed(() =>
    this.doctors().find((doctor) => doctor.id === this.selectedDoctorId()) ?? null,
  );

  protected readonly canIssue = computed(
    () => !!this.selectedPatient() && !!this.selectedDoctorId() && !this.issuing(),
  );

  ngOnInit(): void {
    this.teamApi.list().subscribe({
      next: (members) => {
        this.doctors.set(members.filter((m) => isDoctorRole(m.role) && m.status === 'ACTIVE'));
        this.doctorsLoading.set(false);
      },
      error: () => this.doctorsLoading.set(false),
    });
  }

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
        { id: patient.id, uhid: patient.uhid, name: patient.name, age: patient.age, sex: patient.sex, mobile: patient.mobile },
        ...items,
      ]);
    });
  }

  protected selectDoctor(id: string): void {
    this.selectedDoctorId.set(id);
  }

  protected issue(): void {
    const patient = this.selectedPatient();
    const doctorId = this.selectedDoctorId();
    if (!patient || !doctorId || this.issuing()) {
      return;
    }
    this.issuing.set(true);
    this.issueError.set(null);
    this.queueApi.create({ patientId: patient.id, doctorMembershipId: doctorId }).subscribe({
      next: (visit) => {
        this.issuing.set(false);
        this.issuedVisit.set(visit);
      },
      error: (error: unknown) => {
        this.issuing.set(false);
        this.issueError.set(
          error instanceof ApiError ? error.problem.detail ?? 'We could not issue this token. Try again.' : 'We could not issue this token. Try again.',
        );
      },
    });
  }

  protected close(): void {
    this.dialogRef.close(this.issuedVisit());
  }

  protected issueAnother(): void {
    this.dialogRef.close(this.issuedVisit());
  }
}
