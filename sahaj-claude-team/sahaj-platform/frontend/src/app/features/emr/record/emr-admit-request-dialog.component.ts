import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { newClientKey } from '../../../shared/client-key';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import {
  EmrAdmissionPrefill,
  EmrAdmissionRequestInput,
  EmrAdmissionRequestType,
  EmrAdmissionRequestView,
} from '../emr-finish.model';
import { EmrRecordStore } from './emr-record.store';

export interface AdmitRequestDialogData {
  prefill: EmrAdmissionPrefill;
}

export interface AdmitRequestDialogResult {
  request: EmrAdmissionRequestView;
  prefill: EmrAdmissionPrefill;
  /** The owner goes on to the existing admission sheet (bed, deposit, consent), pre-filled. */
  openSheet: boolean;
}

const TYPES: readonly { key: EmrAdmissionRequestType; label: string }[] = [
  { key: 'planned', label: 'Planned' },
  { key: 'emergency', label: 'Emergency' },
  { key: 'daycare', label: 'Day care' },
];
const SEX_WORD = { F: 'Female', M: 'Male', O: 'Other' } as const;

export function requestTypeWord(type: EmrAdmissionRequestType, days: number | null): string {
  if (type === 'planned') {
    return days ? `IPD ${days} days` : 'Planned';
  }
  return type === 'emergency' ? 'Emergency' : 'Day care';
}

/**
 * "Admit to IPD" (SAH-67-3; mockup "Admission request" of #TreatmentCourse and #DoctorSurgery): the
 * patient's facts and the diagnoses are filled in; the doctor picks the type (Planned with "IPD n days",
 * Emergency, Day care) and may add a note. A doctor who is not the owner sends the request ("Send
 * admission request"); the owner goes on to the existing admission sheet for the bed, deposit and consent
 * (SAH-23). An open request is shown, never made twice. The facts are for the screen only: never logged.
 */
@Component({
  selector: 'app-emr-admit-request-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  styles: `
    .facts {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 10px 12px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--surface-2);
      margin-bottom: 12px;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      border: 0;
      padding: 0;
      margin: 0 0 12px;
    }
    .chip {
      min-height: 44px;
      padding: 0 16px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--surface);
      color: var(--ink-2);
      font-weight: 600;
      cursor: pointer;
    }
    .chip[aria-pressed='true'] {
      border-color: var(--pri);
      background: var(--pri-soft);
      color: var(--pri-text);
    }
    label {
      display: flex;
      flex-direction: column;
      gap: 4px;
      font-weight: 600;
      color: var(--ink-2);
      margin-bottom: 12px;
    }
    button {
      min-height: 44px;
    }
  `,
  template: `
    <h2 mat-dialog-title id="admit-request-title">Admit to IPD</h2>
    <mat-dialog-content>
      <div class="facts" data-testid="admit-facts">
        <b>{{ prefill.patient.name }}</b>
        <span class="faint">
          {{ prefill.patient.age !== null ? prefill.patient.age + ' y · ' : '' }}{{ sex }}{{ prefill.patient.mobile ? ' · ' + prefill.patient.mobile : '' }}
        </span>
        @if (prefill.diagnoses.length > 0) {
          <span data-testid="admit-diagnoses">{{ prefill.diagnoses.join(', ') }}</span>
        }
        @if (prefill.procedure; as p) {
          <span data-testid="admit-procedure">Procedure: {{ p }}</span>
        }
      </div>

      @if (prefill.openAdmission; as a) {
        <p data-testid="admit-already">Already admitted · {{ a.ipNumber }}</p>
      } @else if (prefill.openRequest; as r) {
        <p data-testid="admit-open">
          Admission requested · {{ typeWord(r.admissionType, r.plannedStayDays) }} · {{ when(r.createdAt) }}
        </p>
        @if (prefill.canAdmitDirectly) {
          <p class="faint t-xs">Choose the bed, deposit and consent to admit the patient.</p>
        } @else {
          <p class="faint t-xs">The owner, front desk or nurse will complete the admission.</p>
        }
      } @else {
        <fieldset class="chips" aria-label="Admission type">
          @for (t of types; track t.key) {
            <button type="button" class="chip" [attr.data-type]="t.key" [attr.aria-pressed]="type() === t.key" (click)="type.set(t.key)">
              {{ t.label }}
            </button>
          }
        </fieldset>
        @if (type() === 'planned') {
          <label>
            <span>Planned stay (days)</span>
            <input class="input" data-field="stay" inputmode="numeric" maxlength="2" [value]="stay()" (input)="stay.set(val($event))" [attr.aria-invalid]="stayBad() ? true : null" />
            @if (stayBad()) {
              <span class="err-text" role="alert">Enter 1 to 60 days</span>
            }
          </label>
        }
        <label>
          <span>Note for the ward (optional)</span>
          <textarea class="input" data-field="note" rows="3" maxlength="300" [value]="note()" (input)="note.set(val($event))"></textarea>
        </label>
      }
      @if (error(); as message) {
        <p class="err-text" role="alert" data-testid="admit-error">{{ message }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button data-act="admit-cancel" (click)="ref.close(null)">Close</button>
      @if (prefill.openAdmission) {
        <button type="button" mat-flat-button data-act="admit-view" (click)="ref.close(null)">OK</button>
      } @else if (prefill.openRequest) {
        @if (prefill.canAdmitDirectly) {
          <button type="button" mat-flat-button data-act="admit-sheet" (click)="ref.close({ request: prefill.openRequest, prefill, openSheet: true })">
            Choose bed and admit
          </button>
        }
      } @else {
        <button type="button" mat-flat-button data-act="admit-send" [disabled]="busy() || stayBad()" (click)="send()">
          {{ prefill.canAdmitDirectly ? 'Request and choose bed' : 'Send admission request' }}
        </button>
      }
    </mat-dialog-actions>
  `,
})
export class EmrAdmitRequestDialogComponent {
  protected readonly ref = inject<MatDialogRef<EmrAdmitRequestDialogComponent, AdmitRequestDialogResult | null>>(MatDialogRef);
  protected readonly prefill = inject<AdmitRequestDialogData>(MAT_DIALOG_DATA).prefill;
  private readonly store = inject(EmrRecordStore);

  protected readonly types = TYPES;
  protected readonly typeWord = requestTypeWord;
  protected readonly sex = SEX_WORD[this.prefill.patient.sex];
  protected readonly type = signal<EmrAdmissionRequestType>(this.prefill.suggestedType);
  protected readonly stay = signal(this.prefill.plannedStayDays ? String(this.prefill.plannedStayDays) : '');
  protected readonly note = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private key: string | null = null;
  private keyedBody: string | null = null;

  protected readonly stayDays = computed(() => {
    const raw = this.stay().trim();
    if (this.type() !== 'planned' || raw === '') {
      return null;
    }
    const n = Number(raw);
    return Number.isInteger(n) && n >= 1 && n <= 60 ? n : NaN;
  });
  protected readonly stayBad = computed(() => Number.isNaN(this.stayDays()));

  protected val(event: Event): string {
    return (event.target as HTMLInputElement | HTMLTextAreaElement).value;
  }

  protected when(instant: string): string {
    return formatDisplayDateTimeOfInstant(instant);
  }

  protected send(): void {
    if (this.busy() || this.stayBad()) {
      return;
    }
    const days = this.stayDays();
    const note = this.note().trim();
    const input: EmrAdmissionRequestInput = {
      admissionType: this.type(),
      ...(typeof days === 'number' ? { plannedStayDays: days } : {}),
      ...(note ? { note } : {}),
    };
    const hash = JSON.stringify(input);
    if (!this.key || this.keyedBody !== hash) {
      this.key = newClientKey();
      this.keyedBody = hash;
    }
    this.busy.set(true);
    this.error.set(null);
    this.store.requestAdmission(input, this.key).subscribe((view) => {
      this.busy.set(false);
      if (view) {
        this.ref.close({ request: view, prefill: this.prefill, openSheet: this.prefill.canAdmitDirectly });
      } else {
        this.error.set(this.store.notice() ?? 'We could not send the admission request. Try again.');
        this.store.notice.set(null);
      }
    });
  }
}
