import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, switchMap } from 'rxjs';
import { Visit } from '../../queue/queue.model';
import { ApiError } from '../../../core/http/problem-detail';
import { AdviceTextService } from '../components/advice-text.service';
import { isComponentValue } from '../components/component-values.logic';
import { ALERT_BLOCK_TEXT, isAllergyClassKey, rowReviewed, rowsOf } from '../components/prescription.logic';
import { EmrComponentContext, LiveAllergyAlert } from '../emr-context';
import { EmrApi } from '../emr.api';
import { EmrAddendum, EmrAdmissionPrefill, EmrAdmissionRequestInput, EmrAdmissionRequestView, EmrEncounterVitals, EmrTriageScale, EmrVitalsReading } from '../emr-finish.model';
import { VITALS_SECTION, vitalsFieldValues } from './vitals-prefill.logic';
import {
  EmrAllergyClassKey,
  EmrCasePaperContext,
  EmrCopyItemsInput,
  EmrCopyItemsResult,
  EmrPrintPreferences,
  PrintLanguage,
} from '../emr-phase3.model';
import { LANGUAGE_NAME, PaperModel, composePaper, subtitle } from '../paper/case-paper.logic';
import { dentalAllergyFooter, readyFooterText } from './frame.logic';
import { istToday } from '../../../shared/format/ist-date';
import {
  DoneContext,
  FooterInput,
  footerItems,
  footerSentence,
  hiddenSectionKeys,
  isSpecialtyDone,
  profileOfLayout,
  specialtyOf,
} from '../specialty/specialty.logic';
import { EmrScreenMode, EmrSpecialtyProfile, EmrSpecialtyReference, EmrStillNeededItem, OncDecisionKey, OncDeviationReason } from '../specialty/specialty.model';
import { EmrPrescriptionRow } from '../emr-phase3.model';
import { emptyListValue, hasText, itemsOf, newListItem, withItems } from '../components/emr-list.logic';
import { hasMedicine, withRows } from '../components/prescription.logic';
import { RECORD_CHANGED_TEXT, detailOr, isOffline, problemSlug } from '../emr-errors';
import { SaveState } from './emr-save-status';
import {
  EmrAdmissionAdviceInput,
  EmrLayoutSection,
  EmrListKind,
  EmrPart,
  EmrRecordDraftInput,
  EmrRecordSectionInput,
  EmrRecordView,
  EmrVisitType,
} from '../emr.model';
import {
  SectionState,
  ValuesBySection,
  compact,
  footerText,
  partProgress,
  sameValue,
  sectionProblems,
  sectionStates,
  statesFromServer,
  stillNeeded,
} from './emr-record.logic';

export type RightTab = 'history' | 'paper' | 'tools';

const LOAD_ERROR = 'We could not open this EMR. Try again.';
const SAVE_ERROR = 'We could not save. Your entries are still on screen.';
const FIX_ERROR = 'Fix the fields marked in red, then save again.';
export const ALREADY_BILLED_TEXT = 'Already billed. Ask billing to adjust.';
const SIGNED_TEXT = 'This visit is already signed. It is now read-only.';

/**
 * State of one open EMR record (SAH-47). The doctor's typed values live here and nowhere else:
 * nothing goes to local storage, the URL or a log. Done, the part badges and "Still needed"
 * come from the server while nothing on screen is unsaved, and from the same rules applied to the
 * values on screen while the doctor is typing.
 */
/** The entries whose text is not on the list yet (ignoring case) and not repeated within the entries. */
function uniqueNew<T extends { text: string }>(items: readonly { text: string }[], entries: readonly T[]): T[] {
  const seen = new Set(items.map((i) => i.text.trim().toLowerCase()));
  return entries.filter((e) => {
    const key = e.text.trim().toLowerCase();
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

@Injectable()
export class EmrRecordStore implements EmrComponentContext {
  private readonly api = inject(EmrApi);
  private readonly adviceTexts = inject(AdviceTextService);

  readonly record = signal<EmrRecordView | null>(null);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  readonly values = signal<ValuesBySection>({});
  private readonly dirtySet = signal<ReadonlySet<string>>(new Set());
  private readonly confirmedSet = signal<ReadonlySet<string>>(new Set());
  private readonly serverErrors = signal<Record<string, Record<string, string>>>({});

  readonly part = signal<EmrPart>('ASSESSMENT');
  readonly activeSection = signal<string | null>(null);
  readonly highlight = signal<string | null>(null);
  readonly collapsedMap = signal<Record<string, boolean>>({});
  readonly rightTab = signal<RightTab>('history');

  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  /** The footer status text beside Save draft (SAH-51-3): Saving, Saved, Offline, Not saved. */
  readonly saveState = signal<SaveState>({ kind: 'idle' });
  /**
   * Set when the server says another doctor now holds the record (409 emr-record-held) or it was signed
   * elsewhere (409 emr-record-signed) while this screen was open: the screen turns read-only (SAH-51-4).
   */
  readonly lostEditing = signal<{ reason: 'HELD' | 'SIGNED'; holderName: string | null } | null>(null);
  /**
   * True while the sign request is in flight (set by the finish flow). The 30-second tick, the part-switch
   * save and a "held" answer are ignored meanwhile, so a record that is being signed never shows
   * "another doctor is editing" (code review 5).
   */
  readonly signInFlight = signal(false);
  /** Another place changed the record: nothing is applied until the doctor reloads. */
  readonly conflict = signal(false);
  readonly notice = signal<string | null>(null);
  /** What the nurse recorded for this encounter (SAH-68-1): read for an IPD round only; OPD and Emergency capture none yet. */
  readonly vitalsRead = signal<EmrEncounterVitals | null>(null);
  private readonly prefillMarks = signal<Readonly<Record<string, { source: 'IPD_NURSING_CHART'; sourceEntryId: string }>>>({});
  /** The reading the Vitals section was last filled from, by the doctor's tap or the server's record. */
  readonly vitalsUsedEntry = computed<string | null>(
    () => this.prefillMarks()[VITALS_SECTION]?.sourceEntryId ?? this.recordSection(VITALS_SECTION)?.prefilledFrom?.sourceEntryId ?? null,
  );
  /** "New vitals recorded. Use them?": a reading the Vitals section does not hold yet. */
  readonly vitalsOffer = computed<EmrVitalsReading | null>(() => {
    const current = this.vitalsRead()?.current ?? null;
    return current && current.sourceEntryId !== this.vitalsUsedEntry() ? current : null;
  });
  /** A plain note that is not an error (for example "Already billed. Ask billing to adjust."). */
  readonly infoNote = signal<string | null>(null);

  // ---- phase 4: specialty screen facts (ADR-023 D2, D5) ----

  /** Visit mode and the oncology decision; saved with the draft as "screen". */
  readonly screen = signal<{
    mode: EmrScreenMode | null;
    oncDecision: { choice: OncDecisionKey; reasonKey: OncDeviationReason | null } | null;
  }>({ mode: null, oncDecision: null });
  private readonly screenDirty = signal(false);
  /** The order status the order sheet holds; it wins over the record's last read. */
  readonly orderStatusOverride = signal<'NONE' | 'DRAFT' | 'SENT' | 'HELD' | null>(null);

  readonly dirty = computed(() => this.dirtySet().size > 0 || this.screenDirty());
  readonly layout = computed<EmrLayoutSection[]>(() => this.record()?.layout.sections ?? []);
  readonly readOnly = computed(() => (this.record()?.readOnly ?? true) || this.lostEditing() !== null);
  /** The specialty profile of this record, null for the generic renderer (IPD rounds, Emergency, other starters). */
  readonly profile = computed<EmrSpecialtyProfile | null>(() => this.record()?.specialty?.profile ?? profileOfLayout(this.layout()));
  readonly specialtyView = computed(() => this.record()?.specialty ?? null);
  /** The visit mode on screen (Obstetrics: ANC or Gynae; Physiotherapy: first visit or session). */
  readonly visitMode = computed<string | null>(() => {
    const profile = this.profile();
    const picked = this.screen().mode ?? this.record()?.specialty?.mode ?? null;
    if (picked || (profile !== 'og' && profile !== 'physio')) {
      return picked;
    }
    return profile === 'og' ? 'ANC' : (this.record()?.specialty?.physio?.sessionNo ?? 1) > 1 ? 'SESSION' : 'FIRST_VISIT';
  });
/**
   * The sections the visit mode hides: the server's list while the mode on screen is the saved one
   * (contract 0.5.1, ADR-023 D13.3), else the same rule worked out here (the mode was just switched, or
   * the server sent no list). Gynae keeps "pcpndt" while Form F is required (H21, a legal duty).
   */
  readonly hiddenKeys = computed<ReadonlySet<string>>(() => {
    const server = this.record()?.specialty?.hiddenSectionKeys;
    if (server && !this.screenDirty()) {
      return new Set(server);
    }
    return hiddenSectionKeys(this.profile(), this.visitMode(), this.record()?.specialty?.og?.formFRequired === true);
  });
  /** The layout without the sections the visit mode hides. */
  readonly visibleLayout = computed<EmrLayoutSection[]>(() => {
    const hidden = this.hiddenKeys();
    return hidden.size === 0 ? this.layout() : this.layout().filter((s) => !hidden.has(s.key));
  });

  /** Server flag, minus the copies the doctor confirmed on screen. */
  private readonly serverPending = computed(() => {
    const out = new Set<string>();
    for (const s of this.record()?.sections ?? []) {
      if (s.copyPendingConfirmation) {
        out.add(s.key);
      }
    }
    return out;
  });

  readonly states = computed<SectionState[]>(() => {
    const record = this.record();
    if (!record) {
      return [];
    }
    if (!this.dirty() && this.confirmedSet().size === 0 && this.orderStatusOverride() === null) {
      return statesFromServer(this.visibleLayout(), record.sections);
    }
    return sectionStates(
      this.visibleLayout(),
      this.values(),
      (key) => this.copyPending(key),
      (section, values) => this.specialtyDone(section, values),
    );
  });
  readonly progress = computed(() => partProgress(this.states()));
  readonly needed = computed(() => stillNeeded(this.states()));
  /** Complete is blocked while a drug-class allergy alert is neither removed nor reviewed (SAH-48-2). */
  readonly alertBlocked = computed(() => this.allergyAlerts().some((a) => !a.reviewed));
  /** The footer list of a specialty screen (the server's while nothing is unsaved, else the same rules on screen). */
  readonly footerItems = computed<EmrStillNeededItem[]>(() => {
    const record = this.record();
    const profile = this.profile();
    if (!record || !profile) {
      return [];
    }
    if (!this.dirty() && this.orderStatusOverride() === null && record.stillNeededItems) {
      return record.stillNeededItems;
    }
    const doneByKey: Record<string, boolean> = {};
    for (const s of this.states()) {
      doneByKey[s.key] = s.done;
    }
    const choice = this.screen().oncDecision?.choice ?? record.specialty?.onco?.decision?.choice ?? null;
    const input: FooterInput = {
      profile,
      mode: this.screen().mode ?? record.specialty?.mode ?? null,
      layout: this.visibleLayout(),
      doneByKey,
      values: this.values(),
      decisionRecorded: choice !== null,
      holdMode: choice === 'DELAY_1_WEEK' || choice === 'STOP',
    };
    return footerItems(input);
  });
  readonly ready = computed(() => {
    const open = this.profile() ? this.footerItems().length : this.needed().length;
    return open === 0 && !this.alertBlocked();
  });
  /** Medicine rows on screen (the Paediatrics footer "m medicine(s) prescribed"). */
  private readonly medicineCount = computed(() => {
    let n = 0;
    for (const section of Object.values(this.values())) {
      for (const value of Object.values(section)) {
        if (isComponentValue(value) && value.component === 'PRESCRIPTION') {
          n += rowsOf(value).filter((r) => r.name.trim() !== '').length;
        }
      }
    }
    return n;
  });
  readonly footer = computed(() => {
    const profile = this.profile();
    if (this.alertBlocked()) {
      const first = this.allergyAlerts().find((a) => !a.reviewed);
      return profile === 'dental' && first?.allergyClass === 'PENICILLIN' ? dentalAllergyFooter(first.medicineName) : ALERT_BLOCK_TEXT;
    }
    const ready = (): string => {
      const immunization = specialtyOf(this.values()['immunization']);
      const anc = specialtyOf(this.values()['anc']);
      return readyFooterText({
        profile,
        mode: this.visitMode(),
        languageName: this.paperContext() ? LANGUAGE_NAME[this.printLanguage()] : null,
        view: this.specialtyView(),
        nextAncWeeks: anc?.component === 'ANC_CHECK' ? (anc.nextAncWeeks ?? null) : null,
        vaccinesToday: immunization?.component === 'IMMUNIZATION' ? immunization.givenToday.length : 0,
        medicines: this.medicineCount(),
      });
    };
    if (profile) {
      const items = this.footerItems();
      return items.length === 0 ? ready() : footerSentence(items);
    }
    const needed = this.needed();
    if (needed.length === 0) {
      return ready();
    }
    return footerText(needed, (key) => this.nameOf(key));
  });

  // ---- phase 3: print choices, case paper context, live allergy check ----

  readonly paperContext = signal<EmrCasePaperContext | null>(null);
  readonly paperError = signal<string | null>(null);
  readonly printPrefs = signal<EmrPrintPreferences>({ language: null, paperSize: null });
  private readonly medicineClasses = signal<Readonly<Record<string, EmrAllergyClassKey>>>({});

  readonly recordId: Signal<string | null> = computed(() => this.record()?.id ?? null);
  readonly patientSex: Signal<'F' | 'M' | 'O' | null> = computed(() => this.record()?.header.sex ?? null);
  readonly lastSignedPrescription = computed(() => this.record()?.lastSignedPrescription ?? null);
  /** The visit's language, else the clinic's Case paper and Rx language (SAH-55-2). */
  readonly printLanguage: Signal<PrintLanguage> = computed(
    () => this.printPrefs().language ?? this.paperContext()?.clinicDefaults.language ?? 'EN',
  );
  readonly paperSize = computed(() => this.printPrefs().paperSize ?? this.paperContext()?.clinicDefaults.paperSize ?? 'A4');

  /** Allergy classes picked in this visit's Allergies section (free text never triggers the check). */
  private readonly pickedClasses = computed(() => {
    const out = new Set<EmrAllergyClassKey>();
    for (const value of Object.values(this.values()['allergy'] ?? {})) {
      if (Array.isArray(value)) {
        for (const entry of value) {
          const key = (entry as { itemKey?: unknown } | null)?.itemKey;
          if (typeof key === 'string' && isAllergyClassKey(key)) {
            out.add(key);
          }
        }
      }
    }
    // The Penicillin chip of the Dental card "Medical history and alerts" joins the visit's allergy classes (SAH-75-6, ADR-026 D6).
    const med = specialtyOf(this.values()['dental_medhx']);
    if (med?.component === 'DENTAL_MED_HISTORY' && med.allergies.includes('PENICILLIN')) {
      out.add('PENICILLIN');
    }
    return out;
  });

  /** The alert of every prescription row whose drug class is one the patient is allergic to. */
  readonly allergyAlerts: Signal<readonly LiveAllergyAlert[]> = computed(() => {
    const picked = this.pickedClasses();
    if (picked.size === 0) {
      return [];
    }
    const fromServer = new Map<string, EmrAllergyClassKey>();
    for (const a of this.record()?.allergyAlerts ?? []) {
      fromServer.set(a.rowId, a.allergyClass);
    }
    const alerts: LiveAllergyAlert[] = [];
    for (const section of Object.values(this.values())) {
      for (const value of Object.values(section)) {
        if (!isComponentValue(value) || value.component !== 'PRESCRIPTION') {
          continue;
        }
        for (const row of rowsOf(value)) {
          const cls = (row.medicineId ? this.medicineClasses()[row.medicineId] : undefined) ?? fromServer.get(row.id);
          if (cls && picked.has(cls)) {
            alerts.push({ rowId: row.id, medicineName: row.name, allergyClass: cls, reviewed: rowReviewed(row, cls) });
          }
        }
      }
    }
    return alerts;
  });

  /** The header tag "MLC" (SAH-57-3): any pin on screen, or the server's flag, is a medico-legal case. */
  readonly mlc = computed(() => {
    for (const section of Object.values(this.values())) {
      for (const value of Object.values(section)) {
        if (isComponentValue(value) && value.component === 'BODY_MAP' && value.pins.some((p) => p.mlc)) {
          return true;
        }
      }
    }
    return false;
  });

  /** The Allergies line the history facts follow while the doctor edits this visit's allergies. */
  readonly allergyLine = computed<string | null>(() => {
    const section = this.values()['allergy'];
    if (!section || !this.dirtySet().has('allergy')) {
      return null;
    }
    const names: string[] = [];
    for (const value of Object.values(section)) {
      if (Array.isArray(value)) {
        names.push(...value.map((e) => String((e as { text?: unknown })?.text ?? '')).filter((t) => t));
      } else if (typeof value === 'string' && value.trim()) {
        names.push(value.trim());
      }
    }
    return names.length > 0 ? names.join(', ') : null;
  });

  /** The profile's reference lists as the specialty context read them; the live case paper prints choices as words with them. */
  readonly paperSpecialtyReference = signal<EmrSpecialtyReference | null>(null);

  readonly paperModel = computed<PaperModel | null>(() => {
    const context = this.paperContext();
    const record = this.record();
    if (!context || !record) {
      return null;
    }
    return composePaper({
      context,
      sections: record.layout.sections,
      values: this.values(),
      language: this.printLanguage(),
      capitals: context.clinicDefaults.capitals,
      adviceText: (item, language) => this.adviceTexts.textFor(item, language),
      specialty: record.specialty ?? null,
      specialtyReference: this.paperSpecialtyReference(),
    });
  });
  readonly paperSubtitle = computed(() => subtitle(this.paperModel()));
  /** Print is disabled with "Resolve the allergy alert first" while an alert is unresolved. */
  readonly printBlocked = computed(() => this.alertBlocked() || this.paperContext()?.printBlocked === 'ALLERGY_ALERT');

  readonly problems = computed<Record<string, Record<string, string>>>(() => {
    const out: Record<string, Record<string, string>> = {};
    const server = this.serverErrors();
    for (const section of this.layout()) {
      const local = sectionProblems(section, this.values()[section.key] ?? {});
      const merged = { ...(server[section.key] ?? {}), ...local };
      if (Object.keys(merged).length > 0) {
        out[section.key] = merged;
      }
    }
    return out;
  });

  /** An earlier signed visit opens read-only through Past visits (pastRecordId). */
  load(recordId: string, pastRecordId?: string | null): void {
    this.loading.set(true);
    this.loadError.set(null);
    const request = pastRecordId ? this.api.getPastRecord(recordId, pastRecordId) : this.api.getRecord(recordId);
    request.subscribe({
      next: (record) => {
        this.loading.set(false);
        this.initialise(record);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(detailOr(error, LOAD_ERROR));
      },
    });
  }

  /** Reload after a conflict: what is on screen is replaced by the server's record. */
  reload(): void {
    const id = this.record()?.id;
    if (!id) {
      return;
    }
    this.conflict.set(false);
    this.saveError.set(null);
    this.load(id);
  }

  private initialise(record: EmrRecordView): void {
    this.record.set(record);
    const values: ValuesBySection = {};
    for (const section of record.sections) {
      values[section.key] = { ...section.values };
    }
    this.values.set(values);
    this.dirtySet.set(new Set());
    this.confirmedSet.set(new Set());
    this.serverErrors.set({});
    this.screenDirty.set(false);
    this.orderStatusOverride.set(null);
    this.lostEditing.set(null);
    this.saveState.set({ kind: 'idle' });
    this.vitalsRead.set(null);
    this.prefillMarks.set({});
    this.adoptScreen(record);
    this.part.set(record.uiState.part);
    this.activeSection.set(record.uiState.sectionKey);
    const collapsed: Record<string, boolean> = {};
    for (const section of record.layout.sections) {
      collapsed[section.key] = section.collapsed;
    }
    this.collapsedMap.set(collapsed);
    this.printPrefs.set(record.printPreferences ?? { language: null, paperSize: null });
    this.loadPaperContext(record.id);
  }

  /** The letterhead, doctor, patient and the ten headings; the paper itself is composed on screen. */
  loadPaperContext(recordId: string | null = this.record()?.id ?? null): void {
    if (!recordId) {
      return;
    }
    this.paperError.set(null);
    this.api.getCasePaperContext(recordId).subscribe({
      next: (context) => this.paperContext.set(context),
      error: (error: unknown) => this.paperError.set(detailOr(error, 'We could not load the case paper. Try again.')),
    });
  }

  /** This visit's paper size or language. Not clinical; last write wins; the clinic setting never changes. */
  setPrint(patch: Partial<EmrPrintPreferences>): void {
    const record = this.record();
    const next = { ...this.printPrefs(), ...patch };
    this.printPrefs.set(next);
    if (record) {
      this.api.setPrintPreferences(record.id, next).subscribe({ error: () => undefined });
    }
  }

  setPrintLanguage(language: PrintLanguage): void {
    this.setPrint({ language });
  }

  noteMedicineClass(medicineId: string, allergyClass: EmrAllergyClassKey | null): void {
    this.medicineClasses.update((all) => {
      const { [medicineId]: removed, ...rest } = all;
      void removed;
      return allergyClass ? { ...rest, [medicineId]: allergyClass } : rest;
    });
  }

  /**
   * Repeat last Rx, Copy medicines to today, Repeat these tests (SAH-48-1, SAH-50-4): unsaved entries
   * are saved first so the server copies into what the doctor sees; the answer replaces the values
   * of the sections that are not being edited. Emits null when it could not be done.
   */
  copyItems(input: EmrCopyItemsInput): Observable<EmrCopyItemsResult | null> {
    const record = this.record();
    if (!record || this.readOnly()) {
      return of(null);
    }
    const run = (): Observable<EmrCopyItemsResult | null> => {
      const current = this.record();
      if (!current) {
        return of(null);
      }
      return this.api.copyItems(current.id, input).pipe(
        map((result) => {
          this.adoptRecord(result.record);
          return result;
        }),
        catchError((error: unknown) => {
          this.headerFailed(error, 'We could not copy. Try again.');
          return of(null);
        }),
      );
    };
    if (!this.dirty()) {
      return run();
    }
    return this.save().pipe(switchMap((ok) => (ok ? run() : of(null))));
  }

  /** The server's record after an action of its own: values of sections not being edited are replaced. */
  private adoptRecord(next: EmrRecordView): void {
    this.record.set(next);
    const dirty = this.dirtySet();
    this.values.update((all) => {
      const out = { ...all };
      for (const section of next.sections) {
        if (!dirty.has(section.key)) {
          out[section.key] = { ...section.values };
        }
      }
      return out;
    });
  }

  // ---- reading ----

  nameOf(key: string): string {
    return this.layout().find((s) => s.key === key)?.name ?? key;
  }

  sectionsOf(part: EmrPart): EmrLayoutSection[] {
    return this.visibleLayout().filter((s) => s.part === part);
  }

  stateOf(key: string): SectionState | undefined {
    return this.states().find((s) => s.key === key);
  }

  recordSection(key: string) {
    return this.record()?.sections.find((s) => s.key === key) ?? null;
  }

  copyPending(key: string): boolean {
    return this.serverPending().has(key) && !this.confirmedSet().has(key);
  }

  isCollapsed(key: string): boolean {
    return this.collapsedMap()[key] ?? false;
  }

  // ---- editing ----

  setValue(sectionKey: string, fieldKey: string, value: unknown): void {
    if (this.readOnly()) {
      return;
    }
    this.values.update((all) => ({ ...all, [sectionKey]: { ...(all[sectionKey] ?? {}), [fieldKey]: value } }));
    this.markDirty(sectionKey);
    if (this.serverPending().has(sectionKey)) {
      this.confirmedSet.update((set) => new Set(set).add(sectionKey));
    }
    this.clearServerError(sectionKey, fieldKey);
  }

  /** "Keep these values" on a section pre-filled from the last signed visit (OQ-45-26). */
  confirmCopy(sectionKey: string): void {
    if (this.readOnly() || !this.serverPending().has(sectionKey)) {
      return;
    }
    this.confirmedSet.update((set) => new Set(set).add(sectionKey));
    this.markDirty(sectionKey);
  }

  private markDirty(sectionKey: string): void {
    this.dirtySet.update((set) => new Set(set).add(sectionKey));
  }

  private clearServerError(sectionKey: string, fieldKey: string): void {
    const current = this.serverErrors()[sectionKey];
    if (current && fieldKey in current) {
      const { [fieldKey]: _removed, ...rest } = current;
      void _removed;
      this.serverErrors.update((all) => ({ ...all, [sectionKey]: rest }));
    }
  }

  toggleCollapsed(key: string): void {
    this.collapsedMap.update((m) => ({ ...m, [key]: !(m[key] ?? false) }));
  }

  setPart(part: EmrPart): void {
    this.part.set(part);
  }

  /** The left list or a missing section: show its part, open it and mark it for a moment. */
  goToSection(key: string): void {
    const section = this.layout().find((s) => s.key === key);
    if (!section) {
      return;
    }
    this.part.set(section.part);
    this.activeSection.set(key);
    this.collapsedMap.update((m) => ({ ...m, [key]: false }));
    this.highlight.set(key);
  }

  clearHighlight(): void {
    this.highlight.set(null);
  }

  /** Complete is blocked while something is missing: open the first missing section (SAH-47-4). */
  firstMissing(): string | null {
    if (this.profile()) {
      const first = this.footerItems()[0];
      return first ? (first.sectionKey ?? this.layout().find((l) => l.part === 'ASSESSMENT')?.key ?? null) : null;
    }
    return this.needed()[0] ?? null;
  }

  // ---- saving ----

  /**
   * Save draft: sends the position and only the sections changed since the last save, with all their
   * values; no completeness check. Emits true when saved (or nothing to save), false when it failed;
   * the entries stay on screen either way.
   */
  save(kind: 'MANUAL' | 'AUTO' = 'MANUAL'): Observable<boolean> {
    const record = this.record();
    if (!record || this.readOnly() || this.saving() || this.conflict()) {
      return of(false);
    }
    const dirtyKeys = [...this.dirtySet()];
    const invalid = dirtyKeys.filter((key) => Object.keys(this.problems()[key] ?? {}).length > 0);
    if (invalid.length > 0) {
      this.saveError.set(FIX_ERROR);
      if (kind === 'AUTO') {
        this.saveState.set({ kind: 'failed', reason: 'fix the fields marked in red' });
      }
      return of(false);
    }
    const sent: Record<string, ReturnType<typeof compact>> = {};
    const sections: EmrRecordSectionInput[] = dirtyKeys.map((key) => {
      sent[key] = compact(this.values()[key] ?? {});
      const confirm = this.confirmedSet().has(key);
      const prefill = this.prefillMarks()[key];
      return { key, values: sent[key], ...(confirm ? { confirmCopy: true } : {}), ...(prefill ? { prefill } : {}) };
    });
    const confirmedSent = new Set([...this.confirmedSet()].filter((key) => dirtyKeys.includes(key)));
    const body: EmrRecordDraftInput = {
      revision: record.revision,
      uiState: { part: this.part(), sectionKey: this.activeSection() },
      sections,
      ...(kind === 'AUTO' ? { saveKind: 'AUTO' as const } : {}),
    };
    const screenSent = this.screenDirty() ? this.screenBody() : null;
    if (screenSent) {
      body.screen = screenSent;
    }
    this.saving.set(true);
    this.saveError.set(null);
    this.saveState.set({ kind: 'saving' });
    return this.api.saveRecordDraft(record.id, body).pipe(
      map((saved) => {
        this.saving.set(false);
        this.saveState.set({ kind: 'saved', at: new Date().toISOString() });
        this.record.set(saved);
        if (screenSent) {
          this.screenDirty.set(false);
          this.adoptScreen(saved);
        }
        this.adoptComponentValues(saved, dirtyKeys, sent);
        this.dirtySet.update((set) => {
          const next = new Set(set);
          for (const key of dirtyKeys) {
            if (sameValue(compact(this.values()[key] ?? {}), sent[key])) {
              next.delete(key);
            }
          }
          return next;
        });
        this.confirmedSet.update((set) => {
          const next = new Set(set);
          for (const key of confirmedSent) {
            if (!this.dirtySet().has(key)) {
              next.delete(key);
            }
          }
          return next;
        });
        this.serverErrors.set({});
        return true;
      }),
      catchError((error: unknown) => {
        this.saving.set(false);
        this.saveFailed(error, dirtyKeys);
        return of(false);
      }),
    );
  }

  // ---- autosave, edit hold, signed (SAH-51-3, SAH-51-4) ----

  /**
   * The 30-second tick: unsaved entries are saved as AUTO (no audit entry); with nothing to save the
   * edit hold is renewed so "has not saved for 2 minutes" means four missed ticks. Also runs again
   * after a lost connection, so the entries are saved at once when the network is back. Not on a
   * signed or read-only record.
   */
  autosaveTick(): void {
    const record = this.record();
    if (!record || this.readOnly() || this.saving() || this.conflict() || this.signInFlight()) {
      return;
    }
    this.loadVitals();
    if (this.dirty() || this.saveState().kind === 'offline') {
      this.save('AUTO').subscribe();
      return;
    }
    this.renewHold();
  }

  /**
   * Reads the nursing chart's vitals for this round (IPD only; ADR-025 D12). Pre-fills the empty Vitals fields
   * with the nurse's reading; the nurse's entry is never changed. Read again on each tick, so a reading
   * recorded after the EMR opened is offered as "New vitals recorded. Use them?".
   */
  loadVitals(): void {
    const record = this.record();
    if (!record || record.encounterType !== 'IPD_ROUND' || this.readOnly()) {
      return;
    }
    this.api.getEncounterVitals(record.id).subscribe({
      next: (vitals) => {
        this.vitalsRead.set(vitals);
        const current = vitals.current;
        if (current && this.vitalsUsedEntry() === null && this.vitalsFieldsEmpty()) {
          this.useVitals(current);
        }
      },
      error: () => undefined,
    });
  }

  private vitalsFieldsEmpty(): boolean {
    const section = this.layout().find((s) => s.key === VITALS_SECTION);
    if (!section) {
      return false;
    }
    const values = this.values()[VITALS_SECTION] ?? {};
    return section.fields.every((f) => values[f.key] === undefined || values[f.key] === null || values[f.key] === '');
  }

  /** "Use them" (or the first pre-fill): the nurse's numbers go into the fields they fit; the rest stay for the doctor. */
  useVitals(reading: EmrVitalsReading): void {
    const section = this.layout().find((s) => s.key === VITALS_SECTION);
    if (!section || this.readOnly()) {
      return;
    }
    const fields = new Set(section.fields.map((f) => f.key));
    for (const [fieldKey, value] of Object.entries(vitalsFieldValues(reading))) {
      if (fields.has(fieldKey)) {
        this.setValue(VITALS_SECTION, fieldKey, value);
      }
    }
    this.prefillMarks.update((marks) => ({ ...marks, [VITALS_SECTION]: { source: reading.source, sourceEntryId: reading.sourceEntryId } }));
  }

  /** Switching part saves at once when something changed, without checking required sections. */
  autosaveNow(): void {
    if (this.dirty() && !this.readOnly() && !this.signInFlight()) {
      this.save('AUTO').subscribe();
    }
  }

  /** Renews the caller's hold (PUT edit-hold). A 409 turns the screen read-only. */
  private renewHold(): void {
    const record = this.record();
    if (!record) {
      return;
    }
    this.api.takeEditHold(record.id, false).subscribe({
      next: () => {
        if (this.saveState().kind === 'offline') {
          this.saveState.set({ kind: 'saved', at: new Date().toISOString() });
        }
      },
      error: (error: unknown) => {
        const slug = problemSlug(error);
        if (slug === 'emr-record-held') {
          this.noteLost(error);
        } else if (slug === 'emr-record-signed') {
          this.lostEditing.set({ reason: 'SIGNED', holderName: null });
          this.saveError.set(SIGNED_TEXT);
        } else if (isOffline(error)) {
          this.saveState.set({ kind: 'offline' });
        }
      },
    });
  }

  /** "Edit this visit" once the other doctor's hold has expired, or "Take over" (offered when the server's editHold.canTakeOver is true: the owner, or the admitting doctor on an IPD round; H35): then reload. */
  takeEditHold(takeOver: boolean): void {
    const record = this.record();
    if (!record) {
      return;
    }
    this.api.takeEditHold(record.id, takeOver).subscribe({
      next: () => this.reload(),
      error: (error: unknown) => {
        if (problemSlug(error) === 'emr-record-held') {
          this.noteLost(error);
          this.notice.set(`${detailOr(error, 'Another doctor is editing this visit.')}`);
        } else {
          this.notice.set(detailOr(error, 'We could not take this visit. Try again.'));
        }
      },
    });
  }

  /** The doctor leaves the EMR: the hold is given up (best effort; expiry covers a closed tab). */
  releaseHold(): void {
    const record = this.record();
    if (!record || record.readOnly || this.lostEditing()) {
      return;
    }
    this.api.releaseEditHold(record.id).subscribe({ error: () => undefined });
  }

  /**
   * 409 ipd-read-only on a sign or save: IPD went Read-only for this clinic. The screen turns read-only with
   * the existing IPD wording; what the doctor entered stays on screen.
   */
  markIpdReadOnly(): void {
    this.record.update((r) => (r ? { ...r, readOnly: true, readOnlyReason: 'IPD_READ_ONLY' } : r));
  }

  /** The record after the sign: shown read-only; the paper reloads so its signature area is filled. */
  adoptSigned(next: EmrRecordView): void {
    this.initialise(next);
  }

  /**
   * The server stamps "Added on", "Added by", the diagnosis code and the pin time (ADR-022 D3, D8):
   * after a save, a component value that has not been edited since comes back from the server.
   */
  private adoptComponentValues(
    saved: EmrRecordView,
    sentKeys: readonly string[],
    sent: Record<string, ReturnType<typeof compact>>,
  ): void {
    this.values.update((all) => {
      let changed = false;
      const out = { ...all };
      for (const key of sentKeys) {
        const stored = saved.sections.find((s) => s.key === key);
        if (!stored || !sameValue(compact(all[key] ?? {}), sent[key])) {
          continue;
        }
        const merged = { ...(all[key] ?? {}) };
        for (const [fieldKey, value] of Object.entries(stored.values)) {
          if (isComponentValue(value)) {
            merged[fieldKey] = value;
            changed = true;
          }
        }
        out[key] = merged;
      }
      return changed ? out : all;
    });
  }

  private saveFailed(error: unknown, sentKeys: readonly string[]): void {
    const slug = problemSlug(error);
    if (slug === 'emr-record-changed') {
      this.conflict.set(true);
      this.saveError.set(RECORD_CHANGED_TEXT);
      this.saveState.set({ kind: 'failed', reason: 'this EMR was changed elsewhere' });
    } else if (slug === 'emr-record-held') {
      this.noteLost(error);
    } else if (slug === 'emr-record-signed') {
      this.lostEditing.set({ reason: 'SIGNED', holderName: null });
      this.saveError.set(SIGNED_TEXT);
      this.saveState.set({ kind: 'failed', reason: 'this visit is signed' });
    } else if (error instanceof ApiError && error.status === 400 && error.fieldErrors.length > 0) {
      this.saveError.set(FIX_ERROR);
      this.serverErrors.set(this.mapFieldErrors(error, sentKeys));
      this.saveState.set({ kind: 'failed', reason: 'fix the fields marked in red' });
    } else if (isOffline(error)) {
      this.saveError.set(SAVE_ERROR);
      this.saveState.set({ kind: 'offline' });
    } else {
      const reason = detailOr(error, SAVE_ERROR);
      this.saveError.set(reason);
      this.saveState.set({ kind: 'failed', reason: reason.replace(/.$/, '') });
    }
  }

  /** 409 emr-record-held: another doctor has the record now. Entries stay on screen; the screen is read-only. */
  private noteLost(error: unknown): void {
    if (this.signInFlight() || this.record()?.status === 'SIGNED') {
      return;
    }
    const name = error instanceof ApiError ? (error.problem.holderName ?? null) : null;
    this.lostEditing.set({ reason: 'HELD', holderName: name });
    this.saveError.set(null);
    this.saveState.set({ kind: 'failed', reason: 'another doctor is editing this visit' });
  }

  /** Server names `sections[i].values.<fieldKey>`; i is the index in the body we sent. */
  private mapFieldErrors(error: ApiError, sentKeys: readonly string[]): Record<string, Record<string, string>> {
    const out: Record<string, Record<string, string>> = {};
    for (const fe of error.fieldErrors) {
      const m = /^sections\[(\d+)\]\.values\.(.+)$/.exec(fe.field);
      if (!m) {
        continue;
      }
      const section = sentKeys[Number(m[1])];
      if (section) {
        out[section] = { ...(out[section] ?? {}), [m[2]]: fe.message };
      }
    }
    return out;
  }

  // ---- phase 4: specialty values, screen facts and rows added by a specialty ----

  isSectionDirty(sectionKey: string): boolean {
    return this.dirtySet().has(sectionKey);
  }

  /** The specialty value of a section, as it is on screen. */
  specialtyValue(sectionKey: string) {
    return specialtyOf(this.values()[sectionKey]);
  }

  setSpecialty(sectionKey: string, value: unknown): void {
    this.setValue(sectionKey, 'specialty', value);
  }

  setScreenMode(mode: EmrScreenMode): void {
    if (this.readOnly()) {
      return;
    }
    this.screen.update((s) => ({ ...s, mode }));
    this.screenDirty.set(true);
  }

  setOncDecision(choice: OncDecisionKey, reasonKey: OncDeviationReason | null): void {
    if (this.readOnly()) {
      return;
    }
    this.screen.update((s) => ({ ...s, oncDecision: { choice, reasonKey } }));
    this.screenDirty.set(true);
  }

  private screenBody(): { mode?: EmrScreenMode | null; oncDecision?: { choice: OncDecisionKey; reasonKey: OncDeviationReason | null } | null } {
    const screen = this.screen();
    if (this.profile() === 'onco') {
      return screen.oncDecision ? { oncDecision: screen.oncDecision } : {};
    }
    return screen.mode ? { mode: screen.mode } : {};
  }

  private adoptScreen(record: EmrRecordView): void {
    const view = record.specialty;
    const decision = view?.onco?.decision;
    this.screen.set({
      mode: view?.mode ?? null,
      oncDecision: decision ? { choice: decision.choice, reasonKey: decision.reasonKey } : null,
    });
  }

  private specialtyDone(section: EmrLayoutSection, values: Record<string, unknown>): boolean | null {
    const editor = section.specialtyEditor;
    if (!editor) {
      return null;
    }
    const context: DoneContext = {
      values: this.values(),
      view: this.record()?.specialty ?? null,
      today: istToday(),
      orderStatus: this.orderStatusOverride() ?? this.record()?.specialty?.onco?.orderStatus ?? null,
      sectionDirty: (key) => this.dirtySet().has(key),
    };
    return isSpecialtyDone(editor.component, specialtyOf(values), context, section.key);
  }

  private rxTarget(): { sectionKey: string; fieldKey: string } | null {
    for (const section of this.layout()) {
      const field = section.fields.find((f) => f.component?.type === 'PRESCRIPTION');
      if (field) {
        return { sectionKey: section.key, fieldKey: field.key };
      }
    }
    return null;
  }

  private listTarget(kind: EmrListKind): { sectionKey: string; fieldKey: string } | null {
    for (const section of this.layout()) {
      const field = section.fields.find((f) => f.component?.type === 'EMR_LIST' && f.component.listKind === kind);
      if (field) {
        return { sectionKey: section.key, fieldKey: field.key };
      }
    }
    return null;
  }

  /** Prescription rows added by a specialty (supplements, take-home, dose by weight); the same name twice is skipped. */
  addPrescriptionRows(rows: EmrPrescriptionRow[]): boolean {
    const target = this.rxTarget();
    if (!target) {
      this.notice.set('This visit has no prescription section, so the medicines were not added.');
      return false;
    }
    const current = this.values()[target.sectionKey]?.[target.fieldKey] as { rows?: EmrPrescriptionRow[] } | undefined;
    const existing = current?.rows ?? [];
    const fresh = rows.filter((r) => !hasMedicine(existing, r.name));
    if (fresh.length > 0) {
      this.setValue(target.sectionKey, target.fieldKey, withRows((current as never) ?? null, [...existing, ...fresh]));
    }
    return true;
  }

  /** Removes the medicines a specialty added when the doctor takes the tick back. */
  removePrescriptionRows(names: readonly string[]): void {
    const target = this.rxTarget();
    if (!target) {
      return;
    }
    const lower = new Set(names.map((n) => n.toLowerCase()));
    const current = this.values()[target.sectionKey]?.[target.fieldKey] as { rows?: EmrPrescriptionRow[] } | undefined;
    const existing = current?.rows ?? [];
    if (existing.some((r) => lower.has(r.name.toLowerCase()))) {
      this.setValue(target.sectionKey, target.fieldKey, withRows(current as never, existing.filter((r) => !lower.has(r.name.toLowerCase()))));
    }
  }

  /** Tests added by an ANC order or a quick order go to Investigations (no lab order is sent, OQ-45-28). */
  addInvestigations(texts: readonly string[]): void {
    const target = this.listTarget('INVESTIGATIONS');
    if (!target) {
      return;
    }
    const current = this.values()[target.sectionKey]?.[target.fieldKey];
    const items = itemsOf(current);
    const fresh = uniqueNew(items, texts.map((text) => ({ text })));
    if (fresh.length > 0) {
      const added = fresh.map(({ text }) => newListItem('INVESTIGATIONS', { itemKey: null, text, newToList: true }, this.printLanguage()));
      this.setValue(
        target.sectionKey,
        target.fieldKey,
        withItems((current as never) ?? emptyListValue('INVESTIGATIONS'), 'INVESTIGATIONS', [...items, ...added]),
      );
    }
  }

  /** Takes an ordered test back out of Investigations. */
  removeInvestigation(text: string): void {
    const target = this.listTarget('INVESTIGATIONS');
    if (!target) {
      return;
    }
    const current = this.values()[target.sectionKey]?.[target.fieldKey];
    const items = itemsOf(current);
    if (hasText(items, text)) {
      const t = text.trim().toLowerCase();
      this.setValue(target.sectionKey, target.fieldKey, withItems(current as never, 'INVESTIGATIONS', items.filter((i) => i.text.trim().toLowerCase() !== t)));
    }
  }

  /**
   * Adds entries to the list of a kind (a visit template, SAH-60-4): the same text twice is skipped.
   * Returns the ids of the items it added, so Undo can take exactly those out.
   */
  addListEntries(kind: EmrListKind, entries: readonly { itemKey: string | null; text: string; newToList: boolean }[]): string[] {
    const target = this.listTarget(kind);
    if (!target) {
      return [];
    }
    const current = this.values()[target.sectionKey]?.[target.fieldKey];
    const items = itemsOf(current);
    const fresh = uniqueNew(items, entries);
    const added = fresh.map((e) => newListItem(kind, e, this.printLanguage()));
    if (added.length > 0) {
      this.setValue(target.sectionKey, target.fieldKey, withItems((current as never) ?? emptyListValue(kind), kind, [...items, ...added]));
    }
    return added.map((i) => i.id);
  }

  removeListItems(kind: EmrListKind, ids: readonly string[]): void {
    const target = this.listTarget(kind);
    if (!target || ids.length === 0) {
      return;
    }
    const current = this.values()[target.sectionKey]?.[target.fieldKey];
    this.setValue(target.sectionKey, target.fieldKey, withItems(current as never, kind, itemsOf(current).filter((i) => !ids.includes(i.id))));
  }

  /** The active items of a list as plain entries (for "Save as visit template"). */
  listEntries(kind: EmrListKind): { itemKey: string | null; text: string; newToList: boolean }[] {
    const target = this.listTarget(kind);
    return target
      ? itemsOf(this.values()[target.sectionKey]?.[target.fieldKey])
          .filter((i) => i.active)
          .map((i) => ({ itemKey: i.itemKey, text: i.text, newToList: i.newToList }))
      : [];
  }

  prescriptionRows(): EmrPrescriptionRow[] {
    const target = this.rxTarget();
    return target ? ((this.values()[target.sectionKey]?.[target.fieldKey] as { rows?: EmrPrescriptionRow[] } | undefined)?.rows ?? []) : [];
  }

  /** True when this visit has at least one active diagnosis item (the surgery booking rule). */
  hasDiagnosis(): boolean {
    const target = this.listTarget('DIAGNOSIS');
    return target ? itemsOf(this.values()[target.sectionKey]?.[target.fieldKey]).some((i) => i.active) : false;
  }

  /** Takes the server's record after an action of its own (booking, order sent): sections being edited keep their entries. */
  adopt(next: EmrRecordView): void {
    this.adoptRecord(next);
  }

  /** Reads the record again (after the chemotherapy order changed) without losing what is being typed. */
  refreshRecord(keepRevision = false): void {
    const id = this.record()?.id;
    if (!id) {
      return;
    }
    this.api.getRecord(id).subscribe({
      next: (next) => {
        const expected = this.record()?.revision;
        this.adoptRecord(keepRevision && expected !== undefined ? { ...next, revision: expected } : next);
        this.orderStatusOverride.set(null);
      },
      error: () => undefined,
    });
  }

  // ---- header actions ----

  switchDepartment(departmentKey: string): void {
    const record = this.record();
    if (!record || this.readOnly() || this.saving()) {
      return;
    }
    const run = (): void => {
      const current = this.record();
      if (!current) {
        return;
      }
      this.api.switchDepartment(current.id, { departmentKey, revision: current.revision }).subscribe({
        next: (next) => this.initialise(next),
        error: (error: unknown) => this.headerFailed(error, 'We could not switch the department. Try again.'),
      });
    };
    if (this.dirty()) {
      this.save().subscribe((ok) => (ok ? run() : undefined));
    } else {
      run();
    }
  }

  /** The visit type (SAH-52-2). EMERGENCY needs a triage level (SAH-67-4); the answer is the whole visit. */
  changeVisitType(type: EmrVisitType, triageLevel?: number): void {
    const record = this.record();
    if (!record || this.readOnly() || record.encounterType !== 'OPD_VISIT') {
      return;
    }
    this.api.changeVisitType(record.encounterId, type, triageLevel).subscribe({
      next: (visit: Visit | null) => {
        this.record.update((r) =>
          r
            ? {
                ...r,
                header: {
                  ...r.header,
                  visitType: visit?.visitType ?? type,
                  triageLevel: visit?.triageLevel ?? (type === 'EMERGENCY' ? (triageLevel ?? null) : null),
                  triageLevelName: visit?.triageLevelName ?? null,
                },
              }
            : r,
        );
        if (visit?.feeChange?.outcome === 'ALREADY_BILLED') {
          this.infoNote.set(ALREADY_BILLED_TEXT);
        }
      },
      error: (error: unknown) => this.headerFailed(error, 'We could not change the visit type. Try again.'),
    });
  }

  /** Facts for the admission sheet (SAH-67-3). Null when they could not be read; the screen then says so. */
  admissionPrefill(): Observable<EmrAdmissionPrefill | null> {
    const record = this.record();
    if (!record) {
      return of(null);
    }
    return this.api.getAdmissionPrefill(record.id).pipe(
      catchError((error: unknown) => {
        this.headerFailed(error, 'We could not read the admission details. Try again.');
        return of(null);
      }),
    );
  }

  /** The addendum just saved joins the list under the signature (newest last). */
  noteAddendum(addendum: EmrAddendum): void {
    this.record.update((r) => (r ? { ...r, addenda: [...(r.addenda ?? []), addendum], canAddAddendum: (r.addenda?.length ?? 0) + 1 < 50 } : r));
    this.loadPaperContext();
  }

  /** The clinic's triage levels for the Emergency visit type. */
  triageLevels(): Observable<EmrTriageScale | null> {
    return this.api.listTriageLevels().pipe(catchError(() => of(null)));
  }

  /** "Send admission request" (SAH-67-3): the doctor's request; the header and the button follow the answer. */
  requestAdmission(input: EmrAdmissionRequestInput, key: string): Observable<EmrAdmissionRequestView | null> {
    const record = this.record();
    if (!record) {
      return of(null);
    }
    return this.api.requestAdmission(record.id, input, key).pipe(
      map((view) => {
        // The request does not change the record's revision on the server (ADR-025): the expected revision stays.
        this.refreshRecord(true);
        return view;
      }),
      catchError((error: unknown) => {
        this.headerFailed(error, 'We could not send the admission request. Try again.');
        return of(null);
      }),
    );
  }

  /** Marks "Admission advised" (and the referral line when IPD is off). Emits true when done. */
  adviseAdmission(referralLine: string | null): Observable<boolean> {
    const record = this.record();
    if (!record) {
      return of(false);
    }
    const body: EmrAdmissionAdviceInput = referralLine
      ? { revision: record.revision, referralLine }
      : { revision: record.revision };
    return this.api.adviseAdmission(record.id, body).pipe(
      map((next) => {
        this.record.update((r) =>
          r ? { ...r, revision: next.revision, admissionAdvised: next.admissionAdvised, referralLine: next.referralLine } : r,
        );
        return true;
      }),
      catchError((error: unknown) => {
        this.headerFailed(error, 'We could not mark the admission advice. Try again.');
        return of(false);
      }),
    );
  }

  private headerFailed(error: unknown, fallback: string): void {
    if (problemSlug(error) === 'emr-record-changed') {
      this.conflict.set(true);
      this.saveError.set(RECORD_CHANGED_TEXT);
      return;
    }
    this.notice.set(detailOr(error, fallback));
  }
}
