import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../../core/http/problem-detail';
import { ShellStore } from '../../../../core/shell/shell.store';
import { AdmissionsApi } from '../../../ipd/admissions.api';
import { SAMPLE_AS_OF, SAMPLE_ID, SAMPLE_ROW, rowForGroups } from '../../../ipd/card/card-sample';
import { labelProblems } from '../../../ipd/card/card-label.rules';
import { CardSetupApi } from '../../../ipd/card/card-setup.api';
import {
  EditResult,
  abilitiesFor,
  actionMovedText,
  applyTemplate,
  canonicalSetup,
  changedParts,
  cloneSetup,
  fieldMovedText,
  groupsFor,
  hiddenCounts,
  hiddenText,
  labelLocked,
  moveAction,
  moveActionBy,
  moveField,
  moveFieldBy,
  moveSection,
  moveSectionBy,
  publishToast,
  resolveSetup,
  sameSetup,
  sectionMovedText,
  setCardsPerRow,
  setFieldLabel,
  setLayout,
  toggleAction,
  toggleField,
  toggleSection,
} from '../../../ipd/card/card-setup.logic';
import {
  CardActionKey,
  CardChangedPart,
  CardFieldKey,
  CardLayout,
  CardRole,
  CardSectionKey,
  CardSetup,
  CardSetupView,
  CardTemplateKey,
  CardsPerRow,
} from '../../../ipd/card/card-setup.model';
import { isModuleOff, isRefusal } from '../../../ipd/ipd-refusal';
import type { AdmittedPatientRow } from '../../../ipd/ipd.model';
import { formatWhen, problemMessage } from '../../config-format';
import { IpdAccess } from '../ipd-access';
import { IpdGate } from '../ipd-setup.store';

export const LOAD_ERROR = 'We could not load the card setup. Try again.';
export const SAVE_ERROR = 'We could not save the draft. Try again.';
export const PUBLISH_ERROR = 'We could not publish the card. Try again.';
export const DISCARD_ERROR = 'We could not discard the draft. Try again.';
export const CONFLICT_TEXT = 'The card setup was changed by someone else. Reload to see it.';
export const READONLY_TEXT = 'IPD is read-only. Nothing can be changed.';
export const PATIENTS_ERROR = 'Could not load patients. Showing an example.';

export type Busy = 'save' | 'discard' | 'publish' | null;
export type Device = 'desktop' | 'tablet';

/** A message that stays on screen until the next action (an error or a conflict with Reload). */
export interface Problem {
  text: string;
  conflict: boolean;
  /** Further server messages that belong to no label box (G-1); the banner lists them after `text`. */
  more?: string[];
}

/** A server field path that names a label box: `setup.sections[2].fields[0].label`. */
const LABEL_PATH = /^setup\.sections\[(\d+)\]\.fields\[(\d+)\]\.label$/;

/**
 * State of Clinic setup → Patient card (SAH-37, key `ipcard`). One instance per page. It holds the last
 * answer of the server (`view`), the owner's unsaved edits (`editor`) and the two concurrency tokens
 * that come with the answer (live version, draft revision). Edits change `editor` only; Save draft and
 * Publish send the whole setup with the tokens and the answer replaces everything. The role rule of the
 * preview is applied to the owner's own `cardFacts=all` answer using the catalogue the server sent; the
 * browser holds no role table. A 403 anywhere clears all data on the page (SAH-40).
 */
@Injectable()
export class CardSetupStore {
  private readonly api = inject(CardSetupApi);
  private readonly admissions = inject(AdmissionsApi);
  private readonly access = inject(IpdAccess);
  private readonly shell = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  readonly view = signal<CardSetupView | null>(null);
  readonly editor = signal<CardSetup | null>(null);
  readonly loading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly busy = signal<Busy>(null);
  readonly problem = signal<Problem | null>(null);
  /** The server answered 409 `ipd-readonly` while the page still thought IPD was writable. */
  private readonly readonlyNow = signal(false);
  /** Changed parts of the server's last answer; cleared by the first edit (the server's values win). */
  private readonly serverParts = signal<CardChangedPart[] | null>(null);
  /** Polite announcement of a move (AC24); empty until then. */
  readonly announcement = signal('');

  // ---- Label messages (G-1, G-2) ----
  /** Boxes whose browser message may show: the owner left the box, or a submit found it in error. */
  private readonly labelShown = signal<ReadonlySet<CardFieldKey>>(new Set());
  /** Messages of the server's last 400, by field; they replace the browser's message and clear on a keystroke. */
  private readonly labelServer = signal<ReadonlyMap<CardFieldKey, string>>(new Map());
  private labelFocusCount = 0;
  /** Asks the page to put the keyboard focus in a label box (first box in error after a submit). */
  readonly labelFocus = signal<{ key: CardFieldKey; n: number } | null>(null);

  // ---- Preview ----
  readonly role = signal<CardRole>('NURSE');
  readonly patientId = signal<string | null>(null);
  readonly device = signal<Device>('desktop');
  private readonly patients = signal<AdmittedPatientRow[]>([]);
  private readonly patientsAsOf = signal<string>(SAMPLE_AS_OF);
  readonly patientsLoading = signal(false);
  readonly patientsFailed = signal(false);

  // ---- Who ----
  readonly isOwner = computed(() => this.shell.me()?.role === 'OWNER');
  readonly gate = computed<IpdGate>(() => {
    if (this.access.available()) {
      return 'ready';
    }
    if (this.access.state() === 'off') {
      return 'off';
    }
    return this.access.failed() ? 'error' : 'checking';
  });
  readonly readOnlyReason = computed(() => {
    const v = this.view();
    if (!v) {
      return null;
    }
    if (this.readonlyNow() || v.readOnlyReason === 'IPD_READONLY') {
      return 'IPD_READONLY' as const;
    }
    return this.isOwner() && v.canEdit ? null : ('ROLE' as const);
  });
  readonly canEdit = computed(() => this.view() !== null && this.readOnlyReason() === null);
  readonly locked = computed(() => !this.canEdit() || this.busy() !== null);

  // ---- Parts of the server's answer ----
  readonly catalogue = computed(() => this.view()?.catalogue ?? null);
  readonly live = computed(() => this.view()?.live ?? null);
  readonly serverDraft = computed(() => this.view()?.draft ?? null);
  readonly versions = computed(() => this.view()?.versions ?? null);
  readonly templates = computed(() => this.view()?.templates ?? []);

  // ---- What the editor holds ----
  /** The canonical form of the browser needs the standard names (G-3). */
  private readonly names = computed(() => this.catalogue() ?? undefined);
  /** Every label problem of the editor now, by field, in body order (all rules of the design note 4.2). */
  readonly labelProblems = computed(() => {
    const e = this.editor();
    const c = this.catalogue();
    return e && c ? labelProblems(e, c) : new Map<CardFieldKey, string>();
  });
  /** The message to draw under each label box: the server's, else the browser's for boxes already left. */
  readonly labelMessages = computed<ReadonlyMap<CardFieldKey, string>>(() => {
    const out = new Map(this.labelServer());
    const found = this.labelProblems();
    for (const key of this.labelShown()) {
      const message = found.get(key);
      if (message !== undefined && !out.has(key)) {
        out.set(key, message);
      }
    }
    return out;
  });
  readonly dirty = computed(() => {
    const e = this.editor();
    const l = this.live();
    return !!e && !!l && !sameSetup(e, l.setup, this.names());
  });
  /** Edits made since the last answer of the server (not yet saved as a draft). */
  readonly unsaved = computed(() => {
    const e = this.editor();
    const l = this.live();
    if (!e || !l) {
      return false;
    }
    return !sameSetup(e, this.serverDraft()?.setup ?? l.setup, this.names());
  });
  /**
   * Save draft and Discard draft are offered while there are unsaved edits or a draft is stored on the
   * server. A stored draft stays reachable even when the editor was edited back to equal the live setup
   * (code review 37 #3): Save then sends the live setup, which makes the server remove the draft.
   */
  readonly draftActions = computed(() => this.unsaved() || this.serverDraft() !== null);
  readonly parts = computed<CardChangedPart[]>(() => {
    const e = this.editor();
    const l = this.live();
    if (!e || !l) {
      return [];
    }
    return this.serverParts() ?? changedParts(e, l.setup, this.names());
  });
  readonly isDefault = computed(() => {
    const e = this.editor();
    const v = this.view();
    return !!e && !!v && sameSetup(e, v.standard, this.names());
  });
  /** "11:05 AM" while the editor is exactly the saved draft. */
  readonly savedAt = computed(() => {
    const d = this.serverDraft();
    const e = this.editor();
    return d && e && sameSetup(e, d.setup, this.names())
      ? (formatWhen(d.savedAt).split(', ').pop() ?? null)
      : null;
  });
  readonly resolved = computed(() => {
    const e = this.editor();
    const c = this.catalogue();
    const l = this.live();
    return e && c ? resolveSetup(e, c, { version: l?.version ?? 0, isStandard: false }) : null;
  });
  readonly showingDraft = computed(() => this.dirty());

  // ---- Preview data ----
  readonly previewRows = computed<AdmittedPatientRow[]>(() => {
    const rows = this.patients();
    return this.isOwner() && !this.patientsFailed() && rows.length > 0 ? rows : [SAMPLE_ROW];
  });
  readonly previewRow = computed<AdmittedPatientRow>(() => {
    const rows = this.previewRows();
    return rows.find((r) => r.id === this.patientId()) ?? rows[0];
  });
  readonly previewAsOf = computed(() =>
    this.previewRow().id === SAMPLE_ID ? SAMPLE_AS_OF : this.patientsAsOf(),
  );
  readonly previewGroups = computed(() => {
    const c = this.catalogue();
    return c ? groupsFor(this.role(), c) : [];
  });
  readonly previewCard = computed(() => {
    const c = this.catalogue();
    const v = this.view();
    return c && v ? abilitiesFor(this.role(), c, v.state) : null;
  });
  readonly previewDrawnRow = computed(() => rowForGroups(this.previewRow(), this.previewGroups()));
  readonly hiddenLine = computed(() => {
    const c = this.catalogue();
    const e = this.editor();
    const v = this.view();
    return c && e && v ? hiddenText(this.role(), hiddenCounts(e, c, this.role(), v.state)) : null;
  });

  constructor() {
    this.access.ensureLoaded();
    effect(() => {
      if (this.access.available() && this.view() === null && !this.loading() && !this.loadError()) {
        this.load();
      }
    });
  }

  // ---- Loading ----

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.problem.set(null);
    this.api
      .get()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => {
          this.readonlyNow.set(false);
          this.apply(view);
          this.loading.set(false);
          if (this.isOwner()) {
            this.loadPatients();
          }
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.fail(error, LOAD_ERROR);
        },
      });
  }

  retry(): void {
    if (this.access.failed()) {
      this.access.ensureLoaded();
    } else {
      this.load();
    }
  }

  /** Reload after a conflict: the owner's unsaved edits are replaced by the server's answer. */
  reload(): void {
    this.load();
  }

  /** Once per page open and on Try again; never polled (ADR-015 D6). */
  loadPatients(): void {
    this.patientsLoading.set(true);
    this.patientsFailed.set(false);
    this.admissions
      .list({ cardFacts: 'all' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.patients.set(list.items);
          this.patientsAsOf.set(list.asOf);
          this.patientsLoading.set(false);
        },
        error: (error: unknown) => {
          this.patientsLoading.set(false);
          if (isRefusal(error)) {
            this.fail(error, LOAD_ERROR);
            return;
          }
          this.patients.set([]);
          this.patientsFailed.set(true);
        },
      });
  }

  private apply(view: CardSetupView): void {
    this.view.set(view);
    this.editor.set(cloneSetup(view.draft?.setup ?? view.live.setup));
    this.serverParts.set(view.draft?.changedParts ?? null);
    this.clearLabelMessages();
  }

  private clearLabelMessages(): void {
    this.labelShown.set(new Set());
    this.labelServer.set(new Map());
    this.labelFocus.set(null);
  }

  /** A 403 clears everything this page holds (SAH-40); other failures keep the owner's edits. */
  private fail(error: unknown, fallback: string): void {
    if (isRefusal(error)) {
      this.view.set(null);
      this.editor.set(null);
      this.patients.set([]);
      this.patientsFailed.set(false);
      this.serverParts.set(null);
      this.problem.set(null);
      this.clearLabelMessages();
      this.snackBar.dismiss();
      this.loadError.set(isModuleOff(error) ? fallback : problemMessage(error, fallback));
      return;
    }
    // A failed read or a server fault shows the fixed text; the server's own words are for refusals only.
    this.loadError.set(fallback);
  }

  // ---- Editing (draft only) ----

  private commit(result: EditResult): boolean {
    if (!result.ok) {
      this.toast(result.message);
      return false;
    }
    this.editor.set(result.setup);
    this.serverParts.set(null);
    this.problem.set(null);
    return true;
  }

  private change(next: CardSetup): void {
    this.commit({ ok: true, setup: next });
  }

  private editable(): CardSetup | null {
    return this.canEdit() && this.busy() === null ? this.editor() : null;
  }

  applyTemplateByKey(key: CardTemplateKey): void {
    const e = this.editable();
    const template = this.templates().find((t) => t.key === key);
    if (e && template) {
      this.change(applyTemplate(e, template));
      this.toast(`${template.name} template applied to your draft. Publish to use it.`);
    }
  }

  setLayout(layout: CardLayout): void {
    const e = this.editable();
    if (e) {
      this.change(setLayout(e, layout));
    }
  }

  setCols(cols: CardsPerRow): void {
    const e = this.editable();
    if (e) {
      this.change(setCardsPerRow(e, cols));
    }
  }

  toggleSection(key: CardSectionKey): void {
    const e = this.editable();
    const c = this.catalogue();
    if (e && c) {
      this.commit(toggleSection(e, c, key));
    }
  }

  moveSection(key: CardSectionKey, toIndex: number): void {
    const e = this.editable();
    const c = this.catalogue();
    if (e && c) {
      const next = moveSection(e, key, toIndex);
      this.change(next);
      this.announcement.set(sectionMovedText(c, next, key));
    }
  }

  moveSectionBy(key: CardSectionKey, direction: -1 | 1): void {
    const e = this.editable();
    const c = this.catalogue();
    if (e && c) {
      const next = moveSectionBy(e, key, direction);
      this.change(next);
      this.announcement.set(sectionMovedText(c, next, key));
    }
  }

  toggleField(key: CardFieldKey): void {
    const e = this.editable();
    const c = this.catalogue();
    if (e && c) {
      this.commit(toggleField(e, c, key));
    }
  }

  /** A keystroke in a label box: its message (the browser's or the server's) goes away. */
  setLabel(key: CardFieldKey, label: string): void {
    const e = this.editable();
    const c = this.catalogue();
    if (e && c) {
      this.hideLabelMessage(key);
      this.change(setFieldLabel(e, c, key, label));
    }
  }

  /** The owner left a label box: its browser message may show now. */
  labelLeft(key: CardFieldKey): void {
    if (this.editable()) {
      this.labelShown.update((shown) => new Set(shown).add(key));
    }
  }

  private hideLabelMessage(key: CardFieldKey): void {
    if (this.labelShown().has(key)) {
      this.labelShown.update((shown) => {
        const next = new Set(shown);
        next.delete(key);
        return next;
      });
    }
    if (this.labelServer().has(key)) {
      this.labelServer.update((errors) => {
        const next = new Map(errors);
        next.delete(key);
        return next;
      });
    }
  }

  /**
   * Save draft and Publish check every label first (G-2). With any label in error nothing is sent: every
   * message shows and the focus goes to the first box in error.
   */
  private labelsAllowSubmit(): boolean {
    const found = this.labelProblems();
    if (found.size === 0) {
      return true;
    }
    this.labelServer.set(new Map());
    this.labelShown.update((shown) => new Set([...shown, ...found.keys()]));
    this.focusLabel(found.keys().next().value!);
    return false;
  }

  private focusLabel(key: CardFieldKey): void {
    this.labelFocusCount += 1;
    this.labelFocus.set({ key, n: this.labelFocusCount });
  }

  moveField(key: CardFieldKey, toSection: CardSectionKey, toIndex: number): void {
    const e = this.editable();
    const c = this.catalogue();
    if (e && c && this.commit(moveField(e, c, key, toSection, toIndex))) {
      this.announce(() => fieldMovedText(c, this.editor()!, key));
    }
  }

  moveFieldBy(key: CardFieldKey, direction: -1 | 1): void {
    const e = this.editable();
    const c = this.catalogue();
    if (e && c && this.commit(moveFieldBy(e, c, key, direction))) {
      this.announce(() => fieldMovedText(c, this.editor()!, key));
    }
  }

  toggleAction(key: CardActionKey): void {
    const e = this.editable();
    if (e) {
      this.change(toggleAction(e, key));
    }
  }

  moveAction(key: CardActionKey, toIndex: number): void {
    const e = this.editable();
    const c = this.catalogue();
    if (e && c) {
      const next = moveAction(e, key, toIndex);
      this.change(next);
      this.announcement.set(actionMovedText(c, next, key));
    }
  }

  moveActionBy(key: CardActionKey, direction: -1 | 1): void {
    const e = this.editable();
    const c = this.catalogue();
    if (e && c) {
      const next = moveActionBy(e, key, direction);
      this.change(next);
      this.announcement.set(actionMovedText(c, next, key));
    }
  }

  private announce(text: () => string): void {
    this.announcement.set(text());
  }

  /** Reset to default (AC6): the editor becomes the server's standard setup; nothing is stored yet. */
  reset(): void {
    const e = this.editable();
    const v = this.view();
    if (e && v && !this.isDefault()) {
      this.change(cloneSetup(v.standard));
      this.toast('Draft reset to the Sahaj standard card. Publish to apply it.');
    }
  }

  // ---- Server writes ----

  private body() {
    const v = this.view()!;
    return {
      baseVersion: v.live.version,
      draftRevision: v.draft?.revision ?? null,
      setup: canonicalSetup(this.editor()!, this.catalogue() ?? undefined),
    };
  }

  /** Sends when the editor differs from what the server holds as draft (or as live, with no draft). */
  saveDraft(): void {
    if (!this.canEdit() || this.busy() !== null || !this.unsaved() || !this.labelsAllowSubmit()) {
      return;
    }
    this.busy.set('save');
    this.problem.set(null);
    this.labelServer.set(new Map());
    const body = this.body();
    this.api
      .saveDraft(body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => {
          this.apply(view);
          this.busy.set(null);
          this.toast('Draft saved. Staff still see the live card until you publish.');
        },
        error: (error: unknown) => this.writeFailed(error, SAVE_ERROR, body.setup),
      });
  }

  /** Discard draft: removes the saved draft; with none saved, the unsaved edits are simply dropped. */
  discard(): void {
    if (!this.canEdit() || this.busy() !== null) {
      return;
    }
    const draft = this.serverDraft();
    const live = this.live();
    if (!draft) {
      if (live) {
        this.editor.set(cloneSetup(live.setup));
        this.serverParts.set(null);
        this.toast('Draft discarded. The live card is unchanged.');
      }
      return;
    }
    this.busy.set('discard');
    this.problem.set(null);
    this.api
      .discardDraft(draft.revision)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => {
          this.apply(view);
          this.busy.set(null);
          this.toast('Draft discarded. The live card is unchanged.');
        },
        error: (error: unknown) => this.writeFailed(error, DISCARD_ERROR, null),
      });
  }

  publish(): void {
    if (!this.canEdit() || this.busy() !== null || !this.dirty() || !this.labelsAllowSubmit()) {
      return;
    }
    this.busy.set('publish');
    this.problem.set(null);
    this.labelServer.set(new Map());
    const body = this.body();
    this.api
      .publish(body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => {
          this.apply(view);
          this.busy.set(null);
          const newest = view.versions?.[0];
          this.toast(publishToast(view.live.version, newest?.changedParts ?? []));
        },
        error: (error: unknown) => this.writeFailed(error, PUBLISH_ERROR, body.setup),
      });
  }

  private writeFailed(error: unknown, fallback: string, sent: CardSetup | null): void {
    this.busy.set(null);
    if (isRefusal(error)) {
      this.fail(error, LOAD_ERROR);
      return;
    }
    if (error instanceof ApiError) {
      const slug = error.problem.type;
      if (error.status === 409 && slug.endsWith('card-setup-changed')) {
        this.problem.set({ text: error.problem.detail ?? CONFLICT_TEXT, conflict: true });
        return;
      }
      if (error.status === 409 && slug.endsWith('ipd-readonly')) {
        this.readonlyNow.set(true);
        this.problem.set({ text: error.problem.detail ?? READONLY_TEXT, conflict: false });
        return;
      }
      if (error.status === 409 && slug.endsWith('card-setup-unchanged')) {
        this.problem.set({
          text: error.problem.detail ?? 'There are no changes to publish.',
          conflict: true,
        });
        return;
      }
      if (error.status === 400) {
        this.validationFailed(error, fallback, sent);
        return;
      }
    }
    this.problem.set({ text: fallback, conflict: false });
  }

  /**
   * A 400 with field errors (G-1): each label path is resolved to a field through the setup that was sent
   * (not the editor, which may have moved) and shown under its own box; everything else, and any path that
   * cannot be resolved, is listed in the banner. Focus goes to the first box in error.
   */
  private validationFailed(error: ApiError, fallback: string, sent: CardSetup | null): void {
    if (error.fieldErrors.length === 0) {
      this.problem.set({ text: problemMessage(error, fallback), conflict: false });
      return;
    }
    const catalogue = this.catalogue();
    const boxes = new Map<CardFieldKey, string>();
    const rest: string[] = [];
    for (const item of error.fieldErrors) {
      const match = LABEL_PATH.exec(item.field);
      const key = match
        ? sent?.sections[Number(match[1])]?.fields[Number(match[2])]?.key
        : undefined;
      if (key !== undefined && catalogue && !labelLocked(catalogue, key)) {
        if (!boxes.has(key)) {
          boxes.set(key, item.message);
        }
      } else if (!rest.includes(item.message)) {
        rest.push(item.message);
      }
    }
    this.labelServer.set(boxes);
    this.problem.set(
      rest.length === 0 ? null : { text: rest[0], conflict: false, more: rest.slice(1) },
    );
    const first = sent?.sections.flatMap((s) => s.fields).find((f) => boxes.has(f.key));
    if (first) {
      this.focusLabel(first.key);
    }
  }

  toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }

  // ---- Preview controls ----

  setRole(role: CardRole): void {
    this.role.set(role);
  }

  setPatient(id: string): void {
    this.patientId.set(id);
  }

  setDevice(device: Device): void {
    this.device.set(device);
  }
}
