import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiError } from '../../../core/http/problem-detail';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PatientFormDialogComponent } from '../form/patient-form-dialog.component';
import { PatientApi } from '../patient.api';
import { LANGUAGE_LABELS, Patient, PatientSummary, patientInitials } from '../patient.model';

export type RecordTab = 'visits' | 'bills';

/**
 * Patient record (SAH-8), mockup `ptRecord`. Visits, bills, vitals trend and the money column
 * need SAH-9 (Queue/consultation) and SAH-10 (Billing); they show "No visits yet" here rather
 * than faked data. "Print history" and "Edit details" are "coming soon" in the mockup itself
 * (`data-act="soon"`) so they stay that way here too.
 */
@Component({
  selector: 'app-patient-record',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './patient-record.page.scss',
  templateUrl: './patient-record.page.html',
})
export class PatientRecordPage implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(PatientApi);
  private readonly store = inject(ShellStore);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly languageLabels = LANGUAGE_LABELS;
  protected readonly initials = patientInitials;

  protected readonly canWrite = computed(() => {
    const role = this.store.me()?.role;
    return role === 'OWNER' || role === 'FRONT_DESK';
  });

  protected readonly patient = signal<Patient | null>(null);
  protected readonly family = signal<PatientSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly tab = signal<RecordTab>('visits');

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.loadError.set('Patient not found.');
      this.loading.set(false);
      return;
    }
    this.api.get(id).subscribe({
      next: (patient) => {
        this.patient.set(patient);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(
          error instanceof ApiError && error.status === 404
            ? 'Patient not found.'
            : this.messageOf(error, 'We could not load this patient. Try again.'),
        );
      },
    });
    this.api.family(id).subscribe({
      next: (family) => this.family.set(family),
      // The record still shows without the family list; it just stays empty.
      error: () => undefined,
    });
  }

  protected setTab(tab: RecordTab): void {
    this.tab.set(tab);
  }

  protected back(): void {
    this.router.navigate(['/app/patients']);
  }

  protected openFamilyMember(mobile: string): void {
    if (!this.canWrite()) {
      return;
    }
    const ref = this.dialog.open(PatientFormDialogComponent, {
      panelClass: 'sheet-panel',
      width: '560px',
      maxWidth: '100vw',
      data: { familyMember: true, mobile },
    });
    ref.afterClosed().subscribe((member: Patient | null) => {
      if (member) {
        this.family.update((list) => [...list, { ...member }]);
      }
    });
  }

  protected openFamilyMemberRecord(member: PatientSummary): void {
    this.router.navigate(['/app/patients', member.id]);
  }

  protected editComingSoon(): void {
    this.snackBar.open('Editing patient details is coming soon.', 'Close', { duration: 4000 });
  }

  protected printComingSoon(): void {
    this.snackBar.open('Printing the patient history is coming soon.', 'Close', { duration: 4000 });
  }

  private messageOf(error: unknown, fallback: string): string {
    return error instanceof ApiError ? (error.problem.detail ?? fallback) : fallback;
  }
}
