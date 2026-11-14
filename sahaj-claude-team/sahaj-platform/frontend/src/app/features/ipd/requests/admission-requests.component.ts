import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { AdmitDialogData } from '../admit/admit-dialog.component';
import { AdmissionsApi } from '../admissions.api';
import { AdmissionRequestList, AdmissionRequestRow, NO_REQUESTS, requestTypeText } from './admission-requests.model';

const LOAD_ERROR = 'We could not load the admission requests. Try again.';
const SEX_WORD = { F: 'F', M: 'M', O: 'O' } as const;

/**
 * "Admission requests" on Beds and admissions (SAH-67-3, ADR-025 H16; not drawn in the mockup): the
 * requests doctors made from the EMR and the planned treatment-course sittings waiting for a bed, newest
 * first. "Admit" opens the existing admission sheet pre-filled; bed, deposit and consent follow SAH-23.
 * Names, diagnoses and notes are for the screen only.
 */
@Component({
  selector: 'app-admission-requests',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styles: `
    .list {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .row {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px 14px;
      padding: 12px 14px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--surface);
    }
    .main {
      display: flex;
      flex-direction: column;
      gap: 2px;
      flex: 1 1 260px;
      min-width: 0;
      overflow-wrap: anywhere;
    }
    button {
      min-height: 44px;
    }
  `,
  template: `
    @if (loading()) {
      <p class="muted" aria-busy="true">Loading the admission requests</p>
    } @else if (error(); as message) {
      <div class="notice err" role="alert">
        <app-icon name="alert" [size]="16" />
        <span>{{ message }}</span>
        <button type="button" mat-stroked-button data-act="retry-requests" (click)="load()">Try again</button>
      </div>
    } @else if (list(); as l) {
      @if (l.items.length === 0) {
        <p class="muted" data-testid="no-requests">{{ none }}</p>
      } @else {
        <div class="list" data-testid="request-list">
          @for (r of l.items; track r.requestId ?? r.sittingId) {
            <div class="row" data-testid="request-row">
              <div class="main">
                <b>{{ r.patientName }} <span class="faint">{{ r.age !== null ? r.age + ' ' : '' }}{{ r.sex ? sexOf(r.sex) : '' }}</span></b>
                <span>{{ typeText(r) }} · {{ r.doctorName }} · {{ when(r.requestedAt) }}</span>
                @if (r.kind === 'COURSE_SITTING' && r.courseNo) {
                  <span class="faint t-xs">Treatment course {{ r.courseNo }}</span>
                }
                @if (r.diagnoses) {
                  <span>{{ r.diagnoses }}</span>
                }
                @if (r.note) {
                  <span class="faint">{{ r.note }}</span>
                }
              </div>
              @if (r.openAdmission; as a) {
                <span class="pill p-good" data-testid="already-admitted">Already admitted · {{ a.ipNumber }}</span>
              } @else if (l.canAdmit) {
                <button type="button" mat-flat-button data-act="admit-request" [attr.aria-label]="'Admit ' + r.patientName" (click)="admit(r)">Admit</button>
              }
            </div>
          }
        </div>
      }
    }
  `,
})
export class AdmissionRequestsComponent implements OnInit {
  private readonly api = inject(AdmissionsApi);
  private readonly destroyRef = inject(DestroyRef);

  /** The sheet to open, filled from the row. */
  readonly admitRequested = output<AdmitDialogData>();
  /** How many requests wait (for the tab). */
  readonly count = output<number>();

  protected readonly none = NO_REQUESTS;
  protected readonly typeText = requestTypeText;
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly list = signal<AdmissionRequestList | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .admissionRequests()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.list.set(list);
          this.loading.set(false);
          this.count.emit(list.items.filter((i) => !i.openAdmission).length);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.error.set(problemMessage(error, LOAD_ERROR));
        },
      });
  }

  protected sexOf(sex: 'F' | 'M' | 'O'): string {
    return SEX_WORD[sex];
  }

  protected when(instant: string): string {
    return formatDisplayDateTimeOfInstant(instant);
  }

  /** "Admit": the existing sheet with the patient, type and reason from the row; the request closes when the admission is made. */
  protected admit(row: AdmissionRequestRow): void {
    const data: AdmitDialogData = {
      admissionType: row.admissionType,
      ...(row.diagnoses ? { reason: row.diagnoses } : {}),
      ...(row.patientId ? { patientId: row.patientId } : {}),
      ...(row.emergencyCaseId ? { emergencyCaseId: row.emergencyCaseId } : {}),
      ...(row.kind === 'EMR' && row.requestId ? { admissionRequestId: row.requestId } : {}),
      ...(row.kind === 'COURSE_SITTING' && row.sittingId ? { treatmentCourseSittingId: row.sittingId } : {}),
    };
    this.admitRequested.emit(data);
  }
}
