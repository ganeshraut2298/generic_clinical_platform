import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { Subject, catchError, debounceTime, distinctUntilChanged, filter, of, switchMap, tap } from 'rxjs';
import { PatientApi } from '../../patients/patient.api';
import { PatientSummary } from '../../patients/patient.model';
import { EmrAdmissionRequestType, EmrErOutcome, EmrErOutcomeKind } from '../emr-finish.model';
import { ER_OUTCOME_LABEL } from './finish.logic';

export interface ErOutcomeDialogData {
  current: EmrErOutcome | null;
  admissionAdvised: boolean;
}

const KINDS: readonly EmrErOutcomeKind[] = ['DISCHARGE', 'ADMIT', 'REFER_OUT', 'SEND_TO_OPD'];
const TYPE_WORDS: readonly { key: EmrAdmissionRequestType; label: string }[] = [
  { key: 'emergency', label: 'Emergency' },
  { key: 'planned', label: 'Planned' },
  { key: 'daycare', label: 'Day care' },
];
const MIN_SEARCH = 2;

/**
 * "ER outcome" (SAH-67-2; BRD FR-05, not drawn in the mockup): the question the doctor answers before
 * "Sign emergency assessment". Four choices, none chosen at start. Refer out asks for the place and a
 * note, Admit to IPD for the request (Emergency preselected), Send to OPD for the patient (an
 * Emergency case has none until admitted, so the doctor picks an existing patient, ADR-025 H2). The
 * text typed here is for the paper and the request only: never logged.
 */
@Component({
  selector: 'app-er-outcome-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  styles: `
    fieldset {
      border: 0;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    legend {
      font-weight: 700;
      margin-bottom: 8px;
    }
    .opt {
      display: flex;
      align-items: center;
      gap: 10px;
      min-height: 44px;
      padding: 0 12px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      cursor: pointer;
    }
    .opt input {
      width: 20px;
      height: 20px;
    }
    .opt.on {
      border-color: var(--pri);
      background: var(--pri-soft);
    }
    .more {
      display: flex;
      flex-direction: column;
      gap: 10px;
      margin: 8px 0 4px 8px;
    }
    .more label {
      display: flex;
      flex-direction: column;
      gap: 4px;
      font-size: 0.82rem;
      font-weight: 600;
      color: var(--ink-2);
    }
    .results {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .results button {
      width: 100%;
      min-height: 44px;
      text-align: left;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--surface);
      padding: 0 12px;
      cursor: pointer;
    }
    button {
      min-height: 44px;
    }
  `,
  template: `
    <h2 mat-dialog-title id="er-outcome-title">ER outcome</h2>
    <mat-dialog-content>
      <fieldset>
        <legend class="sr-only">ER outcome</legend>
        @for (k of kinds; track k) {
          <label class="opt" [class.on]="kind() === k">
            <input type="radio" name="er-outcome" [value]="k" [checked]="kind() === k" [attr.data-kind]="k" (change)="kind.set(k)" />
            {{ labels[k] }}
          </label>
        }
      </fieldset>

      @if (kind() === 'REFER_OUT') {
        <div class="more">
          <label>
            <span>Refer to (place)</span>
            <input class="input" data-field="refer-to" maxlength="120" [value]="referTo()" (input)="referTo.set(val($event))" />
          </label>
          <label>
            <span>Note (optional)</span>
            <textarea class="input" data-field="refer-note" rows="2" maxlength="300" [value]="referNote()" (input)="referNote.set(val($event))"></textarea>
          </label>
        </div>
      }

      @if (kind() === 'ADMIT') {
        <div class="more">
          <label>
            <span>Admission type</span>
            <select class="input" data-field="admit-type" [value]="admitType()" (change)="admitType.set(typeVal($event))">
              @for (t of types; track t.key) {
                <option [value]="t.key">{{ t.label }}</option>
              }
            </select>
          </label>
          @if (admitType() === 'planned') {
            <label>
              <span>Planned stay (days, optional)</span>
              <input class="input" data-field="admit-stay" inputmode="numeric" maxlength="2" [value]="stay()" (input)="stay.set(val($event))" />
            </label>
          }
          <label>
            <span>Note for the ward (optional)</span>
            <textarea class="input" data-field="admit-note" rows="2" maxlength="300" [value]="admitNote()" (input)="admitNote.set(val($event))"></textarea>
          </label>
          <p class="faint t-xs">The admission request goes to the ward. The patient stays in the Emergency queue until the bed is given.</p>
        </div>
      }

      @if (kind() === 'SEND_TO_OPD') {
        <div class="more">
          @if (patient(); as p) {
            <p data-testid="opd-patient">
              <b>{{ p.name }}</b> · {{ p.age }} y · UHID {{ p.uhid }}
              <button type="button" mat-button data-act="change-patient" (click)="patient.set(null)">Change</button>
            </p>
          } @else {
            <label>
              <span>Find the patient (name, mobile or UHID)</span>
              <input class="input" type="search" data-field="opd-search" autocomplete="off" [value]="query()" (input)="onQuery($event)" />
            </label>
            @if (searchError(); as message) {
              <p class="err-text" role="alert">{{ message }}</p>
            }
            <ul class="results" aria-label="Patients found">
              @for (r of results(); track r.id) {
                <li>
                  <button type="button" data-act="pick-patient" (click)="patient.set(r)">{{ r.name }} · {{ r.age }} y · UHID {{ r.uhid }}</button>
                </li>
              }
            </ul>
            @if (searched() && results().length === 0) {
              <p class="faint t-xs">No patient found. Ask the front desk to register the patient first.</p>
            }
          }
          <p class="faint t-xs">A new OPD token is issued for this patient with you as the doctor.</p>
        </div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button data-act="outcome-cancel" (click)="ref.close(null)">Cancel</button>
      <button type="button" mat-flat-button data-act="outcome-continue" [disabled]="!valid()" (click)="done()">Continue</button>
    </mat-dialog-actions>
  `,
})
export class ErOutcomeDialogComponent {
  protected readonly ref = inject<MatDialogRef<ErOutcomeDialogComponent, EmrErOutcome | null>>(MatDialogRef);
  private readonly data = inject<ErOutcomeDialogData>(MAT_DIALOG_DATA);
  private readonly patientApi = inject(PatientApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly kinds = KINDS;
  protected readonly labels = ER_OUTCOME_LABEL;
  protected readonly types = TYPE_WORDS;

  protected readonly kind = signal<EmrErOutcomeKind | null>(this.data.current?.kind ?? null);
  protected readonly referTo = signal(this.data.current?.referTo ?? '');
  protected readonly referNote = signal(this.data.current?.referNote ?? '');
  protected readonly admitType = signal<EmrAdmissionRequestType>(this.data.current?.admission?.admissionType ?? 'emergency');
  protected readonly stay = signal(this.data.current?.admission?.plannedStayDays ? String(this.data.current.admission.plannedStayDays) : '');
  protected readonly admitNote = signal(this.data.current?.admission?.note ?? '');
  protected readonly query = signal('');
  protected readonly results = signal<PatientSummary[]>([]);
  protected readonly searched = signal(false);
  protected readonly searchError = signal<string | null>(null);
  protected readonly patient = signal<PatientSummary | null>(null);
  private readonly terms = new Subject<string>();

  protected readonly stayDays = computed(() => {
    const raw = this.stay().trim();
    if (raw === '') {
      return null;
    }
    const n = Number(raw);
    return Number.isInteger(n) && n >= 1 && n <= 60 ? n : NaN;
  });

  protected readonly valid = computed(() => {
    switch (this.kind()) {
      case 'DISCHARGE':
        return true;
      case 'REFER_OUT':
        return this.referTo().trim().length > 0;
      case 'ADMIT':
        return !Number.isNaN(this.stayDays());
      case 'SEND_TO_OPD':
        return this.patient() !== null;
      default:
        return false;
    }
  });

  constructor() {
    this.terms
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
        tap(() => this.searchError.set(null)),
        switchMap((term) =>
          this.patientApi.list({ q: term }).pipe(
            catchError(() => {
              this.searchError.set('We could not search patients. Try again.');
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((page) => {
        this.searched.set(true);
        this.results.set(page?.items ?? []);
      });
  }

  protected val(event: Event): string {
    return (event.target as HTMLInputElement | HTMLTextAreaElement).value;
  }

  protected typeVal(event: Event): EmrAdmissionRequestType {
    return (event.target as HTMLSelectElement).value as EmrAdmissionRequestType;
  }

  protected onQuery(event: Event): void {
    const text = this.val(event);
    this.query.set(text);
    this.terms.next(text.trim());
  }

  protected done(): void {
    const kind = this.kind();
    if (!kind || !this.valid()) {
      return;
    }
    switch (kind) {
      case 'REFER_OUT': {
        const note = this.referNote().trim();
        this.ref.close({ kind, referTo: this.referTo().trim(), ...(note ? { referNote: note } : {}) });
        return;
      }
      case 'ADMIT': {
        const days = this.admitType() === 'planned' ? this.stayDays() : null;
        const note = this.admitNote().trim();
        this.ref.close({
          kind,
          admission: {
            admissionType: this.admitType(),
            ...(typeof days === 'number' ? { plannedStayDays: days } : {}),
            ...(note ? { note } : {}),
          },
        });
        return;
      }
      case 'SEND_TO_OPD':
        this.ref.close({ kind, opdPatientId: this.patient()?.id ?? null });
        return;
      default:
        this.ref.close({ kind });
    }
  }
}
