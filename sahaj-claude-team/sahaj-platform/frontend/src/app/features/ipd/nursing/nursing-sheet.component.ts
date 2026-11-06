import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { newClientKey } from '../../../shared/client-key';
import { sheetToast } from '../../../shared/ui/sheet-toast';
import { MarTabComponent } from '../mar/mar-tab.component';
import { Mar, NursingTab, TAB_MAR, TAB_VITALS, countBadge, marSubtitle, tabAfterKey } from '../mar/mar.model';
import { NursingApi } from './nursing.api';
import {
  FIELD_ORDER,
  KEY_ERROR,
  LOAD_ERROR,
  LOST_ANSWER,
  NOTE_PLACEHOLDER,
  NO_NOTES,
  NursingField,
  NursingSheet,
  ROLE_NOTICE,
  SAME_VALUES_NOTICE,
  SAVE_ERROR,
  VITAL_FIELDS,
  VitalsDraft,
  VitalsErrors,
  VitalsSaved,
  ewsNotice,
  noteAgoText,
  prefill,
  sameAsLast,
  savedToast,
  subtitleText,
  toVitalsInput,
  validateVitals,
} from './nursing.model';

export interface NursingSheetData {
  admissionId: string;
}

/** Status 0 (no connection), a server error or anything that is not an API answer: the save may have happened. */
function isLostAnswer(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status === 0 || error.status >= 500;
}

/**
 * Nursing sheet (SAH-30), mockup `pcNursing` and `pcvsave`: the score notice, five vitals prefilled
 * with the last values, a nursing note that is always empty on open, the last three notes and the
 * footer Close and Save vitals and note.
 *
 * - The score and every "ago" text come from the server (`asOf`); the device clock is never read.
 * - The role notice shows for a role that can view but not record (DOCTOR). Read-only IPD and a
 *   discharged patient only disable the fields (no notice, no save button), as the contract says.
 * - clientKey (ADR-006 D5): made when the sheet opens, kept for a retry of the same values, replaced
 *   after a success and whenever any field or the note changes. A 200 repeat counts as a success.
 * - Save is off while the request runs. Typed values are kept on every failure. When the answer is
 *   lost, the sheet says so and offers "Check the sheet" (reads the sheet again, keeps the typing).
 * - After a success Save stays off until the user types (an `input` event) in a field or the note, so
 *   one more tap cannot store the same reading twice (DEC-013, code review M4).
 * - A save whose five vitals equal the last recorded values (compared by value, not by typing; the
 *   note does not count) first shows a notice and saves on the second tap; any edit removes the
 *   notice and asks again. A retry of confirmed values does not ask again (DEC-013, security M3;
 *   ADR-006 D5).
 * - SAH-32: two tabs at the top (accessible tab pattern: roving tabindex, arrow keys, Home and End,
 *   `aria-selected`, a tabpanel): "Vitals and notes" (default) and "Medicines (MAR)" with the count
 *   of doses that are due or late, shown only above 0. Choosing "Vitals and notes" reads the sheet
 *   again and keeps what was typed. On the MAR tab the subtitle and the footer ("Done") change; the
 *   title stays "Nursing · <patient name>".
 */
@Component({
  selector: 'app-nursing-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent, MarTabComponent],
  styleUrl: './nursing-sheet.component.scss',
  templateUrl: './nursing-sheet.component.html',
})
export class NursingSheetComponent {
  private readonly api = inject(NursingApi);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject<MatDialogRef<NursingSheetComponent, boolean>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly data = inject<NursingSheetData>(MAT_DIALOG_DATA);

  protected readonly fields = VITAL_FIELDS;
  protected readonly notePlaceholder = NOTE_PLACEHOLDER;
  protected readonly noNotes = NO_NOTES;
  protected readonly roleNotice = ROLE_NOTICE;
  protected readonly sameValuesNotice = SAME_VALUES_NOTICE;
  protected readonly noteAgo = noteAgoText;

  protected readonly sheet = signal<NursingSheet | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly loading = signal(false);
  /** A save went through and nothing has been typed since: Save stays off. */
  protected readonly held = signal(false);
  /** The "same values as the last reading" notice is showing; the next Save tap confirms. */
  protected readonly confirming = signal(false);
  protected readonly running = signal(false);
  protected readonly checking = signal(false);
  protected readonly errors = signal<VitalsErrors>({});
  /** A failure that is not about one field. `lost` shows "Check the sheet". */
  protected readonly failure = signal<{ message: string; lost: boolean } | null>(null);
  /** The server said the patient cannot be recorded for any more (409): Save stays off. */
  protected readonly locked = signal(false);

  protected readonly form = new FormGroup({
    bp: new FormControl('', { nonNullable: true }),
    pulse: new FormControl('', { nonNullable: true }),
    spo2: new FormControl('', { nonNullable: true }),
    temp: new FormControl('', { nonNullable: true }),
    rr: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  /** SAH-32: the tab shown. "Vitals and notes" when the sheet opens. */
  protected readonly tab = signal<NursingTab>('vit');
  /** The MAR the tab "Medicines (MAR)" last read; null on the other tab. */
  protected readonly mar = signal<Mar | null>(null);
  protected readonly tabVitals = TAB_VITALS;
  protected readonly tabMar = TAB_MAR;

  protected readonly subtitle = computed(() => {
    const s = this.sheet();
    if (!s) {
      return '';
    }
    if (this.tab() === 'mar') {
      const mar = this.mar();
      return mar ? marSubtitle(mar) : `Bed ${s.bedCode}`;
    }
    return subtitleText(s);
  });
  /** The count on the tab: the MAR's own count while the MAR is shown, else the sheet's. */
  protected readonly doseBadge = computed(() => {
    const mar = this.mar();
    return countBadge(this.tab() === 'mar' && mar ? mar.doseCount : this.sheet()?.doseCount);
  });
  protected readonly notice = computed(() => ewsNotice(this.sheet()?.latest ?? null));
  protected readonly viewOnlyRole = computed(() => this.sheet()?.readOnlyReason === 'ROLE');

  private key = newClientKey();
  private didSave = false;
  /** The user confirmed the same-values notice; stays until typing or a success. */
  private confirmed = false;

  constructor() {
    // Any edit of a field or the note starts a new reading: a new key (ADR-006 D5).
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
      this.key = newClientKey();
    });
    this.load();
  }

  protected load(): void {
    if (this.loading()) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .sheet(this.data.admissionId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sheet) => {
          this.loading.set(false);
          this.accept(sheet, true);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(problemMessage(error, LOAD_ERROR));
        },
      });
  }

  /**
   * Runs on the `input` event of a field or the note (a real keystroke or paste, not a reset of the
   * form): Save comes back, the same-values step starts over and that field's error goes.
   */
  protected edited(field: NursingField): void {
    this.held.set(false);
    this.confirming.set(false);
    this.confirmed = false;
    if (this.errors()[field]) {
      const rest = { ...this.errors() };
      delete rest[field];
      this.errors.set(rest);
    }
  }

  /** Shows a sheet from the server. `fill` replaces the typing with the last values and an empty note. */
  private accept(sheet: NursingSheet, fill: boolean): void {
    this.sheet.set(sheet);
    if (fill) {
      this.form.reset(prefill(sheet.latest));
    }
    if (sheet.canRecord) {
      this.form.enable({ emitEvent: false });
    } else {
      this.form.disable({ emitEvent: false });
    }
  }

  protected save(): void {
    const sheet = this.sheet();
    if (!sheet || !sheet.canRecord || this.running() || this.locked() || this.held()) {
      return;
    }
    const draft: VitalsDraft = this.form.getRawValue();
    const errors = validateVitals(draft);
    this.errors.set(errors);
    this.failure.set(null);
    if (this.focusFirst(errors)) {
      this.confirming.set(false);
      return;
    }
    if (!this.confirmed && sameAsLast(draft, sheet.latest)) {
      if (!this.confirming()) {
        this.confirming.set(true);
        return;
      }
      this.confirmed = true;
    }
    this.confirming.set(false);
    this.running.set(true);
    this.api
      .record(sheet.admissionId, toVitalsInput(draft, this.key))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (answer) => this.saved(answer),
        error: (error: unknown) => this.failed(error),
      });
  }

  /** 201, or 200 for a repeat of the clientKey: the sheet is shown as the server answered. */
  private saved(answer: VitalsSaved): void {
    this.running.set(false);
    this.didSave = true;
    this.held.set(true);
    this.confirmed = false;
    this.confirming.set(false);
    this.errors.set({});
    this.failure.set(null);
    this.accept(answer, true);
    this.snackBar.open(savedToast(answer), 'Close', sheetToast(6000));
  }

  private failed(error: unknown): void {
    this.running.set(false);
    if (isLostAnswer(error)) {
      this.show(LOST_ANSWER, true);
      return;
    }
    const api = error as ApiError;
    if (api.status === 400) {
      this.badRequest(api);
      return;
    }
    if (api.status === 409 && api.problem.type.endsWith('client-key-reused')) {
      // The key was used for other values: the typing is kept, the next try is a new reading.
      this.key = newClientKey();
    } else if (api.status === 409) {
      this.locked.set(true);
    }
    this.show(problemMessage(api, SAVE_ERROR), false);
  }

  private badRequest(error: ApiError): void {
    const shown: VitalsErrors = {};
    let other: string | null = null;
    for (const item of error.fieldErrors) {
      if (FIELD_ORDER.includes(item.field as NursingField)) {
        shown[item.field as NursingField] ??= item.message;
      } else {
        other ??= item.field === 'clientKey' ? KEY_ERROR : item.message;
      }
    }
    this.errors.set(shown);
    if (Object.keys(shown).length > 0) {
      this.focusFirst(shown);
    }
    if (other !== null || Object.keys(shown).length === 0) {
      this.show(other ?? problemMessage(error, SAVE_ERROR), false);
    }
  }

  private show(message: string, lost: boolean): void {
    this.failure.set({ message, lost });
    this.snackBar.open(message, 'Close', sheetToast(8000));
  }

  /** Focuses the first invalid field in screen order; true when there was one. */
  private focusFirst(errors: VitalsErrors): boolean {
    const first = FIELD_ORDER.find((field) => errors[field]);
    if (!first) {
      return false;
    }
    this.host.nativeElement.querySelector<HTMLElement>(`#nv-${first}`)?.focus();
    return true;
  }

  /** After a lost answer: reads the sheet again without touching what was typed. */
  protected check(): void {
    if (this.checking()) {
      return;
    }
    this.checking.set(true);
    this.api
      .sheet(this.data.admissionId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sheet) => {
          this.checking.set(false);
          const before = this.sheet()?.latest?.id ?? null;
          if (sheet.latest && sheet.latest.id !== before) {
            // The lost save did reach the server (or another reading came in): the list needs a refresh.
            this.didSave = true;
          }
          this.accept(sheet, false);
          if (!sheet.canRecord) {
            this.locked.set(true);
          }
        },
        error: (error: unknown) => {
          this.checking.set(false);
          this.snackBar.open(problemMessage(error, LOAD_ERROR), 'Close', sheetToast(8000));
        },
      });
  }

  // ---- Tabs (SAH-32) ----

  protected selectTab(next: NursingTab): void {
    if (next === this.tab()) {
      return;
    }
    this.tab.set(next);
    if (next === 'vit') {
      this.mar.set(null);
      this.refresh();
    }
  }

  /** Arrow keys move to the other tab and focus it, Home and End jump to the first and last. */
  protected tabKey(event: KeyboardEvent): void {
    const next = tabAfterKey(event.key, this.tab());
    if (next === null) {
      return;
    }
    event.preventDefault();
    this.selectTab(next);
    this.host.nativeElement.querySelector<HTMLElement>(`#nv-tab-${next}`)?.focus();
  }

  /** "Vitals and notes" again: the sheet is read once more; what was typed stays. */
  private refresh(): void {
    this.api
      .sheet(this.data.admissionId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sheet) => this.accept(sheet, false),
        error: (error: unknown) =>
          this.snackBar.open(problemMessage(error, LOAD_ERROR), 'Close', sheetToast(8000)),
      });
  }

  protected marChanged(mar: Mar): void {
    this.mar.set(mar);
  }

  protected close(): void {
    this.dialogRef.close(this.didSave);
  }
}
