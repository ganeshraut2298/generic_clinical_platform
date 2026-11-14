import { Injectable, computed, inject, signal } from '@angular/core';
import { MatSnackBar, MatSnackBarRef, TextOnlySnackBar } from '@angular/material/snack-bar';
import { EmrApi } from '../../emr/emr.api';
import { SETUP_CHANGED_TEXT, detailOr, isOffline, problemSlug } from '../../emr/emr-errors';
import {
  EmrDepartmentList,
  EmrDepartmentSummary,
  EmrDepartmentView,
  EmrFillerRole,
  EmrLayoutSection,
  EmrLayoutSettingsInput,
  EmrLayoutSettingsSection,
} from '../../emr/emr.model';
import {
  ConfigVisitType,
  ToggleFlag,
  applyTarget,
  atEdge,
  countChanges,
  counts,
  initialSelection,
  movePart,
  moveWithin,
  sameLayout,
  searchDepartments,
  toSettings,
  toggleFiller,
  toggleFlag,
  toggleRequired,
  toggleShown,
} from './emr-config.logic';

export type ConfigTab = 'sections' | 'preview';
type Origin = 'EDIT' | 'RESET';
type Busy = 'save' | 'publish' | 'reset' | null;

const LOAD_ERROR = 'We could not load the departments. Try again.';
const VIEW_ERROR = 'We could not load this department. Try again.';
const SAVE_ERROR = 'We could not reach the server. Your changes are still here, unsaved.';
const TOAST_MS = 4500;

interface UndoPoint {
  readonly key: string;
  readonly sections: EmrLayoutSection[];
  readonly origin: Origin;
}

/**
 * State of "EMR configuration by department" (SAH-45). One working copy per department is kept in
 * memory (the owner can look at another department without losing edits); the baseline of the
 * "unsaved changes" count is the layout the server sent (ADR-020 D6). Nothing is stored in the
 * browser. Provided by the page, so it is gone when the page is left.
 */
@Injectable()
export class EmrConfigStore {
  private readonly api = inject(EmrApi);
  private readonly snackBar = inject(MatSnackBar);

  readonly list = signal<EmrDepartmentList | null>(null);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  readonly query = signal('');
  readonly selectedKey = signal<string | null>(null);
  readonly selectedSectionKey = signal<string | null>(null);
  readonly tab = signal<ConfigTab>('sections');
  readonly visitType = signal<ConfigVisitType>('New consult');

  readonly viewLoading = signal(false);
  readonly viewError = signal<string | null>(null);
  readonly busy = signal<Busy>(null);
  /** Shown above the sections after a failed save or publish; the changes stay on screen. */
  readonly saveError = signal<string | null>(null);
  /** Another owner changed the department: nothing is applied until the owner reloads it. */
  readonly conflict = signal(false);

  private readonly views = signal<Record<string, EmrDepartmentView>>({});
  private readonly drafts = signal<Record<string, EmrLayoutSection[]>>({});
  private readonly origins = signal<Record<string, Origin>>({});
  private undoPoint: UndoPoint | null = null;
  private toastRef: MatSnackBarRef<TextOnlySnackBar> | null = null;

  readonly canEdit = computed(() => this.list()?.canEdit ?? false);
  readonly departments = computed(() => this.list()?.departments ?? []);
  readonly filtered = computed(() => searchDepartments(this.departments(), this.query()));
  readonly selected = computed<EmrDepartmentSummary | null>(
    () => this.departments().find((d) => d.departmentKey === this.selectedKey()) ?? null,
  );
  readonly view = computed(() => {
    const key = this.selectedKey();
    return key ? (this.views()[key] ?? null) : null;
  });
  readonly sections = computed<EmrLayoutSection[]>(() => {
    const key = this.selectedKey();
    return key ? (this.drafts()[key] ?? []) : [];
  });
  readonly selectedSection = computed(
    () => this.sections().find((s) => s.key === this.selectedSectionKey()) ?? null,
  );
  readonly selectedCounts = computed(() => counts(this.sections()));

  /** Total unsaved changes over every department the owner has opened. */
  readonly unsaved = computed(() => {
    const views = this.views();
    const drafts = this.drafts();
    let total = 0;
    for (const key of Object.keys(drafts)) {
      const view = views[key];
      if (view) {
        total += countChanges(toSettings(view.sections), toSettings(drafts[key]));
      }
    }
    return total;
  });
  readonly selectedUnsaved = computed(() => {
    const view = this.view();
    return view ? countChanges(toSettings(view.sections), toSettings(this.sections())) : 0;
  });
  /** A saved draft that doctors do not see yet. */
  readonly hasSavedDraft = computed(() => this.departments().some((d) => d.hasDraft));

  readonly chip = computed<{ text: string; tone: 'good' | 'warn' | 'info' }>(() => {
    const n = this.unsaved();
    if (n > 0) {
      return { text: `${n} unsaved change${n > 1 ? 's' : ''}`, tone: 'warn' };
    }
    if (this.hasSavedDraft()) {
      return { text: 'Draft saved, not published', tone: 'info' };
    }
    return { text: 'All changes published', tone: 'good' };
  });

  /** Wizard step 2: shows a department that does not exist yet with the same columns (SAH-54-2). */
  seedLocal(view: EmrDepartmentView): void {
    const key = view.departmentKey;
    this.list.set({ canEdit: true, departments: [summaryOf(view)] });
    this.loading.set(false);
    this.views.set({ [key]: view });
    this.drafts.set({ [key]: view.sections.map((s) => ({ ...s })) });
    this.origins.set({ [key]: 'EDIT' });
    this.selectedKey.set(key);
    this.selectedSectionKey.set(initialSelection(view.sections));
  }

  /** Step 2: the name typed in step 1 changed after the review was drawn. */
  renameLocal(name: string): void {
    const list = this.list();
    if (list) {
      this.list.set({ ...list, departments: list.departments.map((d) => ({ ...d, name })) });
    }
  }

  /** The settings on screen, in order, as the create call takes them. */
  workingSettings(): EmrLayoutSettingsSection[] {
    return toSettings(this.sections());
  }

  load(preselect: string | null = null): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.listDepartments().subscribe({
      next: (list) => {
        this.list.set(list);
        this.loading.set(false);
        const first = list.departments.find((d) => d.departmentKey === preselect) ?? list.departments[0];
        if (first && !this.selectedKey()) {
          this.select(first.departmentKey);
        }
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(LOAD_ERROR);
      },
    });
  }

  setQuery(text: string): void {
    this.query.set(text);
  }

  select(key: string): void {
    this.selectedKey.set(key);
    this.saveError.set(null);
    this.conflict.set(false);
    this.viewError.set(null);
    this.dismissToast();
    const known = this.views()[key];
    if (known) {
      this.selectedSectionKey.set(initialSelection(this.drafts()[key] ?? known.sections));
      return;
    }
    this.selectedSectionKey.set(null);
    this.fetchView(key, true);
  }

  retryView(): void {
    const key = this.selectedKey();
    if (key) {
      this.fetchView(key, true);
    }
  }

  /** Reload after a conflict: the working copy of this department is replaced by the server's. */
  reloadSelected(): void {
    const key = this.selectedKey();
    if (!key) {
      return;
    }
    this.conflict.set(false);
    this.saveError.set(null);
    this.fetchView(key, true);
  }

  pickSection(key: string): void {
    this.selectedSectionKey.set(key);
  }

  setTab(tab: ConfigTab): void {
    this.tab.set(tab);
  }

  setVisitType(type: ConfigVisitType): void {
    this.visitType.set(type);
  }

  // ---- edits (draft only; nothing is written until Save draft or Publish) ----

  toggleShown(key: string): void {
    this.edit((s) => toggleShown(s, key));
  }

  toggleRequired(key: string): void {
    this.edit((s) => toggleRequired(s, key));
  }

  toggleFlag(key: string, flag: ToggleFlag): void {
    this.edit((s) => toggleFlag(s, key, flag));
  }

  toggleFiller(key: string, role: EmrFillerRole): void {
    this.edit((s) => toggleFiller(s, key, role));
  }

  movePart(key: string): void {
    const section = this.sections().find((s) => s.key === key);
    if (!section || !this.canEdit()) {
      return;
    }
    this.edit((s) => movePart(s, key));
    const now = section.part === 'ASSESSMENT' ? 'Treatment' : 'Assessment';
    this.say(`${section.name} moved to ${now}`);
  }

  move(key: string, direction: -1 | 1): void {
    this.edit((s) => moveWithin(s, key, direction));
  }

  isAtEdge(key: string, direction: -1 | 1): boolean {
    return atEdge(this.sections(), key, direction);
  }

  // ---- save, publish, reset ----

  saveDraft(): void {
    const key = this.selectedKey();
    const view = this.view();
    if (!key || !view || !this.canEdit() || this.busy() || this.conflict()) {
      return;
    }
    if (this.selectedUnsaved() === 0 && (this.origins()[key] ?? 'EDIT') === 'EDIT') {
      this.say('Nothing new to save');
      return;
    }
    this.busy.set('save');
    this.saveError.set(null);
    this.api.saveDraft(key, this.body(key, view)).subscribe({
      next: (result) => {
        this.busy.set(null);
        this.applyView(result.department);
        this.say(
          result.outcome === 'SAVED'
            ? 'Draft saved. Doctors still see the published layout.'
            : 'Nothing new to save',
        );
      },
      error: (error: unknown) => this.failed(error),
    });
  }

  publish(): void {
    const key = this.selectedKey();
    const view = this.view();
    if (!key || !view || !this.canEdit() || this.busy() || this.conflict()) {
      return;
    }
    if (this.selectedUnsaved() === 0 && !view.hasDraft && (this.origins()[key] ?? 'EDIT') === 'EDIT') {
      this.say('Nothing to publish');
      return;
    }
    this.busy.set('publish');
    this.saveError.set(null);
    this.api.publish(key, this.body(key, view)).subscribe({
      next: (result) => {
        this.busy.set(null);
        this.applyView(result.department);
        this.say(
          result.outcome === 'PUBLISHED'
            ? `${result.department.name} published as v${result.publishedVersion}. New visits use it from now.`
            : 'Nothing to publish',
        );
      },
      error: (error: unknown) => this.failed(error),
    });
  }

  resetToTemplate(): void {
    const key = this.selectedKey();
    const view = this.view();
    if (!key || !view || !this.canEdit() || this.busy()) {
      return;
    }
    this.busy.set('reset');
    this.api.getResetTarget(key).subscribe({
      next: (target) => {
        this.busy.set(null);
        const before = this.drafts()[key] ?? view.sections;
        const after = applyTarget(before, target.sections);
        if (sameLayout(toSettings(before), toSettings(after))) {
          this.say('Nothing changed');
          return;
        }
        this.dismissToast();
        this.drafts.update((d) => ({ ...d, [key]: after }));
        const previousOrigin = this.origins()[key] ?? 'EDIT';
        this.origins.update((o) => ({ ...o, [key]: 'RESET' }));
        this.undoPoint = { key, sections: before, origin: previousOrigin };
        this.toastRef = this.snackBar.open(`${view.name} reset to template`, 'Undo', { duration: TOAST_MS });
        this.toastRef.onAction().subscribe(() => this.undo());
      },
      error: (error: unknown) => {
        this.busy.set(null);
        this.saveError.set(detailOr(error, 'We could not reset this department. Try again.'));
      },
    });
  }

  /** Puts the working copy back exactly as it was before the reset (OQ-45-8). */
  undo(): void {
    const point = this.undoPoint;
    this.undoPoint = null;
    if (!point) {
      return;
    }
    this.drafts.update((d) => ({ ...d, [point.key]: point.sections }));
    this.origins.update((o) => ({ ...o, [point.key]: point.origin }));
  }

  // ---- internals ----

  private edit(change: (sections: EmrLayoutSection[]) => EmrLayoutSection[]): void {
    const key = this.selectedKey();
    if (!key || !this.canEdit() || this.conflict()) {
      return;
    }
    const before = this.drafts()[key];
    if (!before) {
      return;
    }
    const after = change(before);
    if (sameLayout(toSettings(before), toSettings(after)) && sameOrder(before, after)) {
      return;
    }
    if (this.undoPoint) {
      this.undoPoint = null;
      this.dismissToast();
    }
    this.drafts.update((d) => ({ ...d, [key]: after }));
  }

  private body(key: string, view: EmrDepartmentView): EmrLayoutSettingsInput {
    return {
      revision: view.revision,
      origin: this.origins()[key] ?? 'EDIT',
      sections: toSettings(this.drafts()[key] ?? view.sections),
    };
  }

  private fetchView(key: string, selectFirst: boolean): void {
    this.viewLoading.set(true);
    this.viewError.set(null);
    this.api.getDepartment(key).subscribe({
      next: (view) => {
        this.viewLoading.set(false);
        this.applyView(view);
        if (selectFirst && this.selectedKey() === key) {
          this.selectedSectionKey.set(initialSelection(view.sections));
        }
      },
      error: () => {
        this.viewLoading.set(false);
        this.viewError.set(VIEW_ERROR);
      },
    });
  }

  /** The server's layout becomes both the baseline and the working copy of the department. */
  private applyView(view: EmrDepartmentView): void {
    const key = view.departmentKey;
    this.views.update((v) => ({ ...v, [key]: view }));
    this.drafts.update((d) => ({ ...d, [key]: view.sections.map((s) => ({ ...s })) }));
    this.origins.update((o) => ({ ...o, [key]: 'EDIT' }));
    this.undoPoint = null;
    const list = this.list();
    if (list) {
      this.list.set({
        ...list,
        departments: list.departments.map((d) => (d.departmentKey === key ? summaryOf(view) : d)),
      });
    }
  }

  private failed(error: unknown): void {
    this.busy.set(null);
    if (problemSlug(error) === 'emr-setup-changed') {
      this.conflict.set(true);
      this.saveError.set(SETUP_CHANGED_TEXT);
      return;
    }
    this.saveError.set(isOffline(error) ? SAVE_ERROR : detailOr(error, SAVE_ERROR));
  }

  private say(message: string): void {
    this.dismissToast();
    this.snackBar.open(message, 'Close', { duration: TOAST_MS });
  }

  private dismissToast(): void {
    this.toastRef?.dismiss();
    this.toastRef = null;
  }
}

function sameOrder(a: readonly EmrLayoutSection[], b: readonly EmrLayoutSection[]): boolean {
  return a.length === b.length && a.every((s, i) => s.key === b[i].key);
}

function summaryOf(view: EmrDepartmentView): EmrDepartmentSummary {
  return {
    departmentKey: view.departmentKey,
    name: view.name,
    initials: view.initials,
    colour: view.colour,
    starterKey: view.starterKey,
    sourceKind: view.sourceKind,
    copiedFromKey: view.copiedFromKey,
    status: view.status,
    publishedVersion: view.publishedVersion,
    hasDraft: view.hasDraft,
    sectionsTotal: view.sectionsTotal,
    sectionsOn: view.sectionsOn,
    requiredOn: view.requiredOn,
    doctorCount: view.doctorCount,
    rooms: view.rooms,
  };
}
