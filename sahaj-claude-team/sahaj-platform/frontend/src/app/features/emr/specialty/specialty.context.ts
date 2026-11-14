import { Injectable, Signal, computed, effect, inject, signal, untracked } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { newClientKey } from '../../../shared/client-key';
import { EmrApi } from '../emr.api';
import { istToday } from '../../../shared/format/ist-date';
import { detailOr } from '../emr-errors';
import { EmrRecordStore } from '../record/emr-record.store';
import { EmrPrescriptionRow, EmrRxFrequency, EmrRxSource, EmrRxWhen } from '../emr-phase3.model';
import { SpecialtyApi } from './specialty.api';
import { pickOnco, specialtyOf } from './specialty.logic';
import { PrintSheet, contextSheet } from './print-sheet';
import { previewOnco } from './onco.logic';
import {
  EmrReferenceItem,
  EmrScreenMode,
  EmrSpecialtyHistory,
  EmrSpecialtyProfile,
  EmrSpecialtyReference,
  EmrSpecialtyValue,
  EmrOncoView,
  EmrSpecialtyView,
  SpOncEcog,
  SpOncExam,
  SpOncLabs,
  SpOncTox,
  REFERENCE_LIST_NAMES,
  ReferenceListName,
  SpVitalsLog,
  SurgeryBookingResult,
} from './specialty.model';

/**
 * Names of the reference lists the screens read from `EmrSpecialtyReference.lists`: the closed set of
 * 44 names of contract 0.5.1 (ADR-023 D13.1), each mapped to itself so a misspelt name does not compile.
 * Item keys are the strings the client stores (enum value, property name or reference table key), so
 * no mapping exists. Every screen falls back to the list drawn in the mockup when a list is absent.
 */
export const REFERENCE_LISTS = Object.fromEntries(REFERENCE_LIST_NAMES.map((n) => [n, n])) as { readonly [K in ReferenceListName]: K };

export interface SpecialtyRxLine {
  name: string;
  frequency: EmrRxFrequency | null;
  frequencyText?: string | null;
  /** Null: the medicine's own usual number of days applies (5 for a typed medicine). */
  days: number | null;
  when: EmrRxWhen;
  source: EmrRxSource;
  doseText?: string | null;
  note?: string | null;
  calculation?: EmrPrescriptionRow['calculation'];
}

/**
 * What every specialty editor needs from the open record (ADR-023 D1): its value, the computed view,
 * the reference lists, the history, and the shared actions. One instance per EMR page, next to the
 * record store. Nothing here is logged or kept in the browser.
 */
@Injectable()
export class SpecialtyContext {
  readonly store = inject(EmrRecordStore);
  private readonly api = inject(SpecialtyApi);
  private readonly emrApi = inject(EmrApi);

  readonly profile: Signal<EmrSpecialtyProfile | null> = this.store.profile;
  readonly view: Signal<EmrSpecialtyView | null> = this.store.specialtyView;
  readonly readOnly = this.store.readOnly;

  /** The visit mode: the doctor's pick, else the server's effective mode (ANC; session once one exists), else the same rule here. */
  readonly mode = computed<EmrScreenMode | null>(() => this.store.visitMode() as EmrScreenMode | null);

  /**
   * The oncology facts (BSA, response, lab flags, suggestion, alerts): the server's when nothing on
   * screen is unsaved, else the same rules applied to what is typed. The server's answer wins.
   */
  readonly onco = computed<EmrOncoView | null>(() => {
    if (this.profile() !== 'onco') {
      return null;
    }
    const at = <T extends EmrSpecialtyValue>(component: T['component']): T | null => {
      const key = this.sectionKeyOf(component);
      return key ? this.value<T>(key, component) : null;
    };
    const vitals = at<SpVitalsLog>('VITALS_LOG');
    const preview = previewOnco({
      tox: at<SpOncTox>('ONC_TOX'),
      exam: at<SpOncExam>('ONC_EXAM'),
      labs: at<SpOncLabs>('ONC_LABS'),
      ecog: at<SpOncEcog>('ONC_ECOG'),
      vitals: vitals?.entries[vitals.entries.length - 1] ?? null,
      decision: this.store.screen().oncDecision,
      server: this.view()?.onco ?? null,
      now: new Date(),
    });
    return pickOnco(this.view()?.onco, preview, !this.store.dirty());
  });

  constructor() {
    // The lists, thresholds and protocols of the profile are read once when the screen opens.
    effect(() => {
      if (this.profile()) {
        untracked(() => this.loadReference());
      }
    });
  }

  readonly reference = signal<EmrSpecialtyReference | null>(null);
  readonly referenceError = signal<string | null>(null);
  readonly history = signal<EmrSpecialtyHistory | null>(null);
  readonly historyError = signal<string | null>(null);
  private referenceFor: EmrSpecialtyProfile | null = null;

  /** The key of the section that carries a component (for example the dental plan), or null when the layout hides it. */
  sectionKeyOf(component: string): string | null {
    return this.store.layout().find((s) => s.specialtyEditor?.component === component)?.key ?? null;
  }

  /** The clinic header and the patient line of a printed sheet, from the case paper context (screen and print only). */
  sheet(title: string, sections: PrintSheet['sections'], footer?: string): PrintSheet {
    return contextSheet(this.store.paperContext(), this.profile() === 'paeds', title, sections, footer);
  }

  today(): string {
    return istToday();
  }

  /** Lists, thresholds and protocols of the profile; read once per screen. */
  loadReference(): void {
    const profile = this.profile();
    if (!profile || this.referenceFor === profile) {
      return;
    }
    this.referenceFor = profile;
    this.api.getReference(profile).subscribe({
      next: (r) => {
        this.reference.set(r);
        this.store.paperSpecialtyReference.set(r);
      },
      error: (e: unknown) => {
        this.referenceFor = null;
        this.referenceError.set(detailOr(e, 'We could not load the reference lists. The lists drawn in the design are shown.'));
      },
    });
  }

  list(name: ReferenceListName): EmrReferenceItem[] | undefined {
    return this.reference()?.lists?.[name];
  }

  /** Earlier values (vitals history, ANC visits, growth points, earlier cycles, physio progress). */
  loadHistory(): void {
    const id = this.store.recordId();
    if (!id) {
      return;
    }
    this.historyError.set(null);
    this.api.getHistory(id).subscribe({
      next: (h) => this.history.set(h),
      error: (e: unknown) => this.historyError.set(detailOr(e, 'We could not load the earlier readings. Try again.')),
    });
  }

  /** The value of an anchor section as it is on screen. */
  value<T extends EmrSpecialtyValue>(sectionKey: string, component: T['component']): T | null {
    const v = specialtyOf(this.store.values()[sectionKey]);
    return v && v.component === component ? (v as T) : null;
  }

  set(sectionKey: string, value: EmrSpecialtyValue): void {
    this.store.setSpecialty(sectionKey, value);
  }

  /** Nothing unsaved in this section: the server's computed answer is the one to show. */
  fresh(sectionKey: string): boolean {
    return !this.store.isSectionDirty(sectionKey);
  }

  setMode(mode: EmrScreenMode): void {
    this.store.setScreenMode(mode);
  }

  addRx(rows: EmrPrescriptionRow[]): boolean {
    return this.store.addPrescriptionRows(rows);
  }

  /**
   * Adds medicines a specialty proposes (supplements, take-home, dose by weight). Each name is looked
   * up in the EMR medicine list first so the drug-class allergy check can apply; a name that is not
   * found goes in as a typed medicine (never allergy-checked, ADR-022 H6). The same name twice is skipped.
   */
  addSpecialtyRx(lines: readonly SpecialtyRxLine[]): Observable<boolean> {
    if (lines.length === 0) {
      return of(true);
    }
    return forkJoin(
      lines.map((line) =>
        this.emrApi.searchEmrMedicines(line.name).pipe(
          map((page) => page.items.find((m) => m.name.trim().toLowerCase() === line.name.trim().toLowerCase()) ?? null),
          catchError(() => of(null)),
          map((found): EmrPrescriptionRow => {
            if (found?.allergyClass) {
              this.store.noteMedicineClass(found.medicineId, found.allergyClass);
            }
            return {
              id: newClientKey(),
              medicineId: found?.medicineId ?? null,
              name: found?.name ?? line.name,
              form: found?.form ?? null,
              frequency: line.frequency,
              days: line.days ?? found?.defaultDays ?? 5,
              when: line.when,
              source: line.source,
              doseText: line.doseText ?? null,
              frequencyText: line.frequencyText ?? null,
              note: line.note ?? null,
              calculation: line.calculation ?? null,
            };
          }),
        ),
      ),
    ).pipe(map((rows) => this.store.addPrescriptionRows(rows)));
  }

  removeRx(names: readonly string[]): void {
    this.store.removePrescriptionRows(names);
  }

  addInvestigations(texts: readonly string[]): void {
    this.store.addInvestigations(texts);
  }

  removeInvestigation(text: string): void {
    this.store.removeInvestigation(text);
  }

  /** Saves what is on screen first, then books (the booking needs the saved record revision). */
  book(idempotencyKey: string): Observable<SurgeryBookingResult | null> {
    return new Observable((subscriber) => {
      const run = (): void => {
        const record = this.store.record();
        if (!record) {
          subscriber.next(null);
          subscriber.complete();
          return;
        }
        this.api.bookSurgery(record.id, record.revision, idempotencyKey).subscribe({
          next: (result) => {
            this.store.adopt(result.record);
            subscriber.next(result);
            subscriber.complete();
          },
          error: (e: unknown) => subscriber.error(e),
        });
      };
      if (this.store.dirty()) {
        this.store.save().subscribe((ok) => {
          if (ok) {
            run();
          } else {
            subscriber.next(null);
            subscriber.complete();
          }
        });
      } else {
        run();
      }
    });
  }
}
