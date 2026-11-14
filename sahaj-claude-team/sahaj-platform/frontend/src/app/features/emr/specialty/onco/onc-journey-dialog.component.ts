import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { addDays, istToday } from '../../../../shared/format/ist-date';
import { detailOr, problemSlug } from '../../emr-errors';
import { SpecialtyApi } from '../specialty.api';
import { CancerJourney } from '../specialty.model';
import { INTENT_LABEL, JourneyForm, LINES, SETTING_LABEL, STAGE_GROUPS, formOf, formProblems, inputOf } from './onc-journey.logic';

export interface OncJourneyDialogData {
  recordId: string;
  /** The version the doctor sees; null when the patient has none yet (the save then sends version 0). */
  current: CancerJourney | null;
}

type Controls = { [K in keyof JourneyForm]: FormControl<string> };

export const JOURNEY_CHANGED = 'The journey was changed. Reload.';

/**
 * The Cancer journey edit form (SAH-75-7, OQ-75-7: a simple per-patient form; the layout and the labels are
 * ours, listed in the handoff). Every field is optional; an emptied field clears it in the new version. Values are
 * checked here against the contract limits and again by the server (400 validation with fieldErrors; 409
 * journey-changed when another save came first). Clinical data: kept on screen only, never logged.
 */
@Component({
  selector: 'app-onc-journey-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule],
  styles: `
    .grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 10px 12px;
    }
    label {
      display: flex;
      flex-direction: column;
      gap: 4px;
      font-weight: 600;
      font-size: 0.86rem;
    }
    .full {
      grid-column: 1 / -1;
    }
    .err {
      color: var(--crit);
      font-weight: 500;
      font-size: 0.8rem;
    }
    button {
      min-height: 44px;
    }
    @media (max-width: 560px) {
      .grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
  template: `
    <h2 mat-dialog-title id="journey-title">Cancer journey</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <div class="grid">
          <label>
            <span>Stage group</span>
            <select class="input" formControlName="stageGroup" data-field="stageGroup">
              <option value="">Not recorded</option>
              @for (s of stages; track s) {
                <option [value]="s">{{ s }}</option>
              }
            </select>
          </label>
          <label>
            <span>TNM</span>
            <input class="input" type="text" formControlName="tnmText" maxlength="40" placeholder="cT2 cN2 M0" data-field="tnmText" />
            @if (problems()['tnmText']; as p) {
              <span class="err" role="alert">{{ p }}</span>
            }
          </label>
          <label>
            <span>Markers</span>
            <input class="input" type="text" formControlName="markersText" maxlength="80" placeholder="ER+ PR+ HER2−" data-field="markersText" />
            @if (problems()['markersText']; as p) {
              <span class="err" role="alert">{{ p }}</span>
            }
          </label>
          <label>
            <span>Ki-67 (%)</span>
            <input class="input" type="text" inputmode="numeric" formControlName="ki67Pct" data-field="ki67Pct" />
            @if (problems()['ki67Pct']; as p) {
              <span class="err" role="alert" data-testid="ki67-error">{{ p }}</span>
            }
          </label>
          <label class="full">
            <span>Genetics</span>
            <input class="input" type="text" formControlName="geneticsText" maxlength="60" placeholder="BRCA pending" data-field="geneticsText" />
            @if (problems()['geneticsText']; as p) {
              <span class="err" role="alert">{{ p }}</span>
            }
          </label>
          <label>
            <span>Intent</span>
            <select class="input" formControlName="intent" data-field="intent">
              <option value="">Not recorded</option>
              @for (i of intents; track i.key) {
                <option [value]="i.key">{{ i.label }}</option>
              }
            </select>
          </label>
          <label>
            <span>Setting</span>
            <select class="input" formControlName="setting" data-field="setting">
              <option value="">Not recorded</option>
              @for (s of settings; track s.key) {
                <option [value]="s.key">{{ s.label }}</option>
              }
            </select>
          </label>
          <label>
            <span>Line of treatment</span>
            <select class="input" formControlName="lineNo" data-field="lineNo">
              <option value="">Not recorded</option>
              @for (n of lines; track n) {
                <option [value]="'' + n">{{ n }}</option>
              }
            </select>
          </label>
          <label>
            <span>Tumour board date</span>
            <input class="input" type="date" formControlName="boardDate" data-field="boardDate" />
            @if (problems()['boardDate']; as p) {
              <span class="err" role="alert" data-testid="board-error">{{ p }}</span>
            }
          </label>
        </div>
        @if (error(); as message) {
          <p class="err" role="alert" data-testid="journey-error">{{ message }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button type="button" mat-button data-act="cancel" (click)="ref.close(stale())">Cancel</button>
        <button type="submit" mat-flat-button data-act="save-journey" [disabled]="saving()">{{ saving() ? 'Saving' : 'Save' }}</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class OncJourneyDialogComponent {
  protected readonly ref = inject<MatDialogRef<OncJourneyDialogComponent, boolean>>(MatDialogRef);
  private readonly data = inject<OncJourneyDialogData>(MAT_DIALOG_DATA);
  private readonly api = inject(SpecialtyApi);

  protected readonly stages = STAGE_GROUPS;
  protected readonly lines = LINES;
  protected readonly intents = Object.entries(INTENT_LABEL).map(([key, label]) => ({ key, label }));
  protected readonly settings = Object.entries(SETTING_LABEL).map(([key, label]) => ({ key, label }));
  protected readonly saving = signal(false);
  /** Another save came first: closing then reloads the strip. */
  protected readonly stale = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly problems = signal<Partial<Record<keyof JourneyForm, string>>>({});

  protected readonly form: FormGroup<Controls>;

  constructor() {
    const f = formOf(this.data.current);
    const c = (v: string): FormControl<string> => new FormControl(v, { nonNullable: true });
    this.form = new FormGroup<Controls>({
      stageGroup: c(f.stageGroup),
      tnmText: c(f.tnmText),
      markersText: c(f.markersText),
      ki67Pct: c(f.ki67Pct),
      geneticsText: c(f.geneticsText),
      intent: c(f.intent),
      setting: c(f.setting),
      lineNo: c(f.lineNo),
      boardDate: c(f.boardDate),
    });
  }

  protected save(): void {
    if (this.saving()) {
      return;
    }
    const value = this.form.getRawValue() as JourneyForm;
    const found = formProblems(value, istToday(), addDays);
    this.problems.set(found);
    if (Object.keys(found).length > 0) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api.saveCancerJourney(this.data.recordId, inputOf(value, this.data.current?.version ?? 0)).subscribe({
      next: () => {
        this.saving.set(false);
        this.ref.close(true);
      },
      error: (e: unknown) => {
        this.saving.set(false);
        this.stale.set(problemSlug(e) === 'journey-changed');
        this.error.set(problemSlug(e) === 'journey-changed' ? JOURNEY_CHANGED : detailOr(e, 'We could not save the journey. Try again.'));
      },
    });
  }
}
