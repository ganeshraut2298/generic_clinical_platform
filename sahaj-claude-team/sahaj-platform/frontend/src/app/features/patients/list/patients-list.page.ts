import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Router } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PatientFormDialogComponent } from '../form/patient-form-dialog.component';
import { MergeConfirmDialogComponent } from '../merge/merge-confirm-dialog.component';
import { PatientApi } from '../patient.api';
import {
  PATIENT_FILTERS,
  Patient,
  PatientFilter,
  PatientPage,
  PatientSummary,
  patientInitials,
} from '../patient.model';

const FORM_DIALOG_CONFIG = {
  panelClass: 'sheet-panel',
  width: '560px',
  maxWidth: '100vw',
} as const;

/**
 * Patients (SAH-8), mockup `vPatients` list tab. The doctor filter, sort and Analytics tab
 * from the mockup need visit data that does not exist until SAH-9 (Queue/consultation); they
 * are left out here rather than built against data that cannot be real yet.
 */
@Component({
  selector: 'app-patients-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatFormFieldModule, MatInputModule, IconComponent],
  styleUrl: './patients-list.page.scss',
  templateUrl: './patients-list.page.html',
})
export class PatientsListPage implements OnInit {
  private readonly api = inject(PatientApi);
  private readonly store = inject(ShellStore);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  protected readonly filters = PATIENT_FILTERS;
  protected readonly initials = patientInitials;

  protected readonly canWrite = computed(() => {
    const role = this.store.me()?.role;
    return role === 'OWNER' || role === 'FRONT_DESK';
  });

  protected readonly query = signal('');
  protected readonly activeFilter = signal<PatientFilter>('all');
  protected readonly page = signal(0);

  protected readonly items = signal<PatientSummary[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly counts = signal<Partial<Record<PatientFilter, number>>>({});
  /** Registered patients, for the KPI row. The other three KPIs (seen in the last 30 days,
   * follow-ups pending, long-term care) stay at zero until SAH-9 gives real visit data, per the
   * story's instruction, even though the "Long-term care" chip below already has a real count. */
  protected readonly registeredCount = computed(() => this.counts().all ?? 0);

  protected readonly duplicatePair = signal<{ keep: Patient; mergeInto: Patient } | null>(null);

  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  ngOnInit(): void {
    this.loadCounts();
    this.loadList();
  }

  protected onSearchInput(value: string): void {
    this.query.set(value);
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => {
      this.page.set(0);
      this.loadList();
    }, 300);
  }

  protected selectFilter(filter: PatientFilter): void {
    if (filter === this.activeFilter()) {
      return;
    }
    this.activeFilter.set(filter);
    this.page.set(0);
    this.loadList();
  }

  protected changePage(delta: number): void {
    const next = this.page() + delta;
    if (next < 0) {
      return;
    }
    this.page.set(next);
    this.loadList();
  }

  protected openRecord(patient: PatientSummary): void {
    this.router.navigate(['/app/patients', patient.id]);
  }

  protected openNewPatient(): void {
    if (!this.canWrite()) {
      return;
    }
    const ref = this.dialog.open(PatientFormDialogComponent, FORM_DIALOG_CONFIG);
    ref.afterClosed().subscribe((patient: Patient | null) => {
      if (!patient) {
        return;
      }
      this.loadCounts();
      this.loadList();
      if (patient.possibleDuplicateOfId) {
        this.offerMerge(patient);
      }
    });
  }

  protected reviewAndMerge(): void {
    const pair = this.duplicatePair();
    if (!pair) {
      return;
    }
    const ref = this.dialog.open(MergeConfirmDialogComponent, { ...FORM_DIALOG_CONFIG, data: pair });
    ref.afterClosed().subscribe((merged: Patient | null) => {
      if (merged) {
        this.duplicatePair.set(null);
        this.loadCounts();
        this.loadList();
      }
    });
  }

  protected dismissDuplicate(): void {
    this.duplicatePair.set(null);
  }

  private offerMerge(created: Patient): void {
    const keepId = created.possibleDuplicateOfId;
    if (!keepId) {
      return;
    }
    this.api.get(keepId).subscribe({
      next: (keep) => this.duplicatePair.set({ keep, mergeInto: created }),
      error: () => undefined,
    });
  }

  private loadList(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const q = this.query().trim();
    this.api.list({ ...(q ? { q } : {}), filter: this.activeFilter(), page: this.page() }).subscribe({
      next: (result) => {
        this.items.set(result.items);
        this.total.set(result.total);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(this.messageOf(error, 'We could not load patients. Try again.'));
      },
    });
  }

  private loadCounts(): void {
    const sources = Object.fromEntries(
      this.filters.map((f) => [f.key, this.api.list({ filter: f.key, page: 0 })] as const),
    ) as Record<PatientFilter, Observable<PatientPage>>;
    forkJoin(sources).subscribe({
      next: (result) => {
        const counts: Partial<Record<PatientFilter, number>> = {};
        (Object.keys(result) as PatientFilter[]).forEach((key) => {
          counts[key] = result[key].total;
        });
        this.counts.set(counts);
      },
      // Counts are a nicety for the chips; the list still works without them.
      error: () => undefined,
    });
  }

  private messageOf(error: unknown, fallback: string): string {
    return error instanceof ApiError ? (error.problem.detail ?? fallback) : fallback;
  }
}
