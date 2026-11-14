import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { EmrApi } from '../../emr/emr.api';
import { EmrFormApi } from '../../emr/emr-form.api';
import { detailOr, isOffline, problemSlug } from '../../emr/emr-errors';
import {
  EmrFormField,
  EmrFormFieldType,
  EmrFormSettings,
  EmrFormView,
} from '../../emr/emr-phase3.model';
import { fieldCountLine, missingRequired, abnormalCount } from '../../emr/forms/form.logic';
import {
  NO_DEPARTMENT_TEXT,
  WHO_CHOICES,
  chipText,
  deleteBlockedMessage,
  definitionOf,
  duplicate,
  fieldProblems,
  insertAt,
  insertionIndex,
  isDirty,
  move,
  moveTo,
  newField,
  publishProblem,
  removeField,
  updateField,
} from './form-builder.logic';

export type BuilderMode = 'build' | 'preview' | 'print';
export type BuilderTab = 'field' | 'form';

export interface DepartmentChoice {
  key: string;
  name: string;
}

const LOAD_ERROR = 'We could not open this form. Try again.';
const SAVE_ERROR = 'We could not save. Your changes are still on screen.';
const CHANGED_TEXT = 'Someone else changed this form. Reload and try again.';
const TOAST_MS = 3500;

/**
 * State of the form builder (SAH-59): the working draft the owner edits (name, settings, fields),
 * the saved view with its revision and versions, Preview values, and the save, approval and publish
 * steps. Typed values of Preview are example data only and are never saved. Nothing here is stored
 * in the browser or logged.
 */
@Injectable()
export class FormBuilderStore {
  private readonly api = inject(EmrFormApi);
  private readonly emrApi = inject(EmrApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly view = signal<EmrFormView | null>(null);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);
  readonly departments = signal<DepartmentChoice[]>([]);

  readonly name = signal('');
  readonly settings = signal<EmrFormSettings | null>(null);
  readonly fields = signal<EmrFormField[]>([]);

  readonly selectedKey = signal<string | null>(null);
  readonly mode = signal<BuilderMode>('build');
  readonly tab = signal<BuilderTab>('field');
  readonly previewValues = signal<Record<string, unknown>>({});
  readonly saveTried = signal(false);

  readonly busy = signal<'save' | 'submit' | 'publish' | 'approve' | 'back' | 'retire' | null>(null);
  readonly toast = signal<string | null>(null);
  readonly undoDelete = signal<{ fields: EmrFormField[]; selected: string | null } | null>(null);
  readonly saveError = signal<string | null>(null);
  readonly conflict = signal(false);

  readonly canEdit = computed(() => this.view()?.canEdit ?? false);
  readonly canApprove = computed(() => this.view()?.canApprove ?? false);
  readonly selected = computed(() => this.fields().find((f) => f.key === this.selectedKey()) ?? null);
  readonly selectedIndex = computed(() => this.fields().findIndex((f) => f.key === this.selectedKey()));
  readonly dirty = computed(() => {
    const v = this.view();
    const s = this.settings();
    return !!v && !!s && isDirty(v, this.name(), s, this.fields());
  });
  readonly chip = computed(() => {
    const v = this.view();
    return v ? chipText(v.state, v.chipVersionNo) : '';
  });
  readonly countLine = computed(() => fieldCountLine(this.fields()));
  readonly approvalRequired = computed(() => this.settings()?.approvalRequired ?? true);
  readonly publishLabel = computed(() => (this.approvalRequired() ? 'Send for approval' : 'Publish'));
  readonly waiting = computed(() => this.view()?.state === 'WAITING_APPROVAL');
  readonly retired = computed(() => this.view()?.state === 'RETIRED');
  readonly problems = computed(() => fieldProblems(this.fields()));
  readonly definition = computed(() => {
    const s = this.settings();
    return s ? definitionOf(this.name(), s, this.fields()) : null;
  });
  readonly metaLine = computed(() => {
    const s = this.settings();
    if (!s) {
      return '';
    }
    const names = new Map(this.departments().map((d) => [d.key, d.name]));
    const where = [
      ...s.useIn.departmentKeys.map((k) => names.get(k) ?? k),
      ...s.useIn.places.map((p) => (p === 'EMERGENCY' ? 'Emergency' : 'IPD wards')),
    ];
    const part = s.emrPart === 'ASSESSMENT' ? 'Assessment' : 'Treatment';
    const who = WHO_CHOICES.filter((w) => s.whoCanFill.includes(w.key)).map((w) => w.label.toLowerCase());
    return `${where.join(', ') || 'No department yet'} · ${part} · filled by ${who.join(', ')}`;
  });
  /** Preview line: required fields missing, values outside the normal range, or how many are filled. */
  readonly previewMissing = computed(() => {
    const d = this.definition();
    return d ? missingRequired(d, this.previewValues()).map((f) => f.key) : [];
  });
  readonly previewAbnormal = computed(() => {
    const d = this.definition();
    return d ? abnormalCount(d, this.previewValues()) : 0;
  });

  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => {
      if (this.toastTimer) {
        clearTimeout(this.toastTimer);
      }
    });
  }

  load(formKey: string): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.get(formKey).subscribe({
      next: (view) => {
        this.loading.set(false);
        this.adopt(view);
        const first = view.definition.fields[0];
        this.selectedKey.set(first?.key ?? null);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(detailOr(error, LOAD_ERROR));
      },
    });
    this.emrApi.listDepartments().subscribe({
      next: (list) =>
        this.departments.set(
          list.departments
            .filter((d) => d.status !== 'DRAFT' || d.publishedVersion > 0)
            .map((d) => ({ key: d.departmentKey, name: d.name })),
        ),
      error: () => this.departments.set([]),
    });
  }

  reload(): void {
    const key = this.view()?.formKey;
    if (key) {
      this.conflict.set(false);
      this.saveError.set(null);
      this.load(key);
    }
  }

  private adopt(view: EmrFormView): void {
    this.view.set(view);
    this.name.set(view.definition.name);
    this.settings.set(structuredClone(view.definition.settings));
    this.fields.set(structuredClone(view.definition.fields));
  }

  // ---- screen choices ----

  setMode(mode: BuilderMode): void {
    this.mode.set(mode);
    this.saveTried.set(false);
  }

  setTab(tab: BuilderTab): void {
    this.tab.set(tab);
  }

  select(key: string | null): void {
    this.selectedKey.set(key);
    if (key) {
      this.tab.set('field');
    }
  }

  clearPreview(): void {
    this.previewValues.set({});
    this.saveTried.set(false);
  }

  setPreviewValue(key: string, value: unknown): void {
    this.previewValues.update((all) => ({ ...all, [key]: value }));
  }

  // ---- editing (owner only) ----

  setName(name: string): void {
    if (this.canEdit()) {
      this.name.set(name);
    }
  }

  patchSettings(patch: Partial<EmrFormSettings>): void {
    const s = this.settings();
    if (s && this.canEdit()) {
      this.settings.set({ ...s, ...patch });
    }
  }

  addField(type: EmrFormFieldType, index?: number): void {
    if (!this.canEdit()) {
      return;
    }
    const list = this.fields();
    const field = newField(type, list);
    const at = index ?? insertionIndex(list, this.selectedKey());
    this.fields.set(insertAt(list, at, field));
    this.selectedKey.set(field.key);
    this.tab.set('field');
    this.say(`${field.type === 'INFO_NOTE' ? 'Info note' : field.label} added. Set it up on the right.`);
  }

  patchField(key: string, patch: Partial<EmrFormField>): void {
    if (this.canEdit()) {
      this.fields.update((list) => updateField(list, key, patch));
    }
  }

  duplicateField(key: string): void {
    const result = duplicate(this.fields(), key);
    if (result && this.canEdit()) {
      this.fields.set(result.fields);
      this.selectedKey.set(result.copy.key);
    }
  }

  deleteField(key: string): void {
    if (!this.canEdit()) {
      return;
    }
    const blocked = deleteBlockedMessage(this.fields(), key);
    if (blocked) {
      this.say(blocked);
      return;
    }
    const list = this.fields();
    const index = list.findIndex((f) => f.key === key);
    const label = list[index]?.label ?? '';
    this.undoDelete.set({ fields: list, selected: this.selectedKey() });
    const next = removeField(list, key);
    this.fields.set(next);
    if (this.selectedKey() === key) {
      this.selectedKey.set(next[index]?.key ?? next[index - 1]?.key ?? null);
    }
    this.say(`“${label}” deleted`, true);
  }

  undo(): void {
    const snapshot = this.undoDelete();
    if (snapshot) {
      this.fields.set(snapshot.fields);
      this.selectedKey.set(snapshot.selected);
      this.undoDelete.set(null);
      this.toast.set(null);
    }
  }

  moveField(key: string, direction: -1 | 1): void {
    if (!this.canEdit()) {
      return;
    }
    const result = move(this.fields(), key, direction);
    this.fields.set(result.fields);
    if (result.message) {
      this.say(result.message);
    }
  }

  dropField(key: string, toIndex: number): void {
    if (!this.canEdit()) {
      return;
    }
    const result = moveTo(this.fields(), key, toIndex);
    this.fields.set(result.fields);
    this.selectedKey.set(key);
    if (result.message) {
      this.say(result.message);
    }
  }

  // ---- saving and the approval steps ----

  private fail(error: unknown): void {
    const slug = problemSlug(error);
    if (slug === 'form-changed') {
      this.conflict.set(true);
      this.saveError.set(CHANGED_TEXT);
      return;
    }
    if (error instanceof ApiError && error.fieldErrors.length > 0 && (slug === 'form-invalid' || slug === 'form-not-publishable' || slug === 'validation')) {
      this.saveError.set(error.fieldErrors.map((f) => f.message).join(' '));
      return;
    }
    this.saveError.set(isOffline(error) ? SAVE_ERROR : detailOr(error, SAVE_ERROR));
  }

  /** Save draft: always allowed (even with no field). Emits the saved view, or null when it failed. */
  saveDraft(): Observable<EmrFormView | null> {
    const view = this.view();
    const settings = this.settings();
    if (!view || !settings || !this.canEdit() || this.busy() || this.conflict()) {
      return of(null);
    }
    this.busy.set('save');
    this.saveError.set(null);
    return this.api
      .saveDraft(view.formKey, { revision: view.revision, name: this.name().trim() || view.definition.name, settings, fields: this.fields() })
      .pipe(
        map((saved) => {
          this.busy.set(null);
          this.view.set(saved);
          this.name.set(saved.definition.name);
          return saved;
        }),
        catchError((error: unknown) => {
          this.busy.set(null);
          this.fail(error);
          return of(null);
        }),
      );
  }

  save(): void {
    if (!this.dirty() && !this.busy()) {
      this.say('Nothing new to save');
      return;
    }
    this.saveDraft().subscribe((saved) => {
      if (saved) {
        this.say('Draft saved');
      }
    });
  }

  /** "Send for approval" (approval On) or "Publish" (approval Off). */
  publishOrSubmit(): void {
    const settings = this.settings();
    if (!settings || !this.canEdit() || this.busy()) {
      return;
    }
    const problem = publishProblem(this.name(), settings, this.fields());
    if (problem) {
      if (problem === NO_DEPARTMENT_TEXT) {
        this.tab.set('form');
      }
      this.say(problem);
      return;
    }
    const step = (): void => {
      const view = this.view();
      if (!view) {
        return;
      }
      const submit = settings.approvalRequired;
      this.busy.set(submit ? 'submit' : 'publish');
      const call = submit ? this.api.submit(view.formKey, view.revision) : this.api.publish(view.formKey, view.revision);
      call.subscribe({
        next: (next) => {
          this.busy.set(null);
          this.view.set(next);
          this.say(this.publishedMessage(next, submit));
        },
        error: (error: unknown) => {
          this.busy.set(null);
          this.fail(error);
        },
      });
    };
    if (this.dirty()) {
      this.saveDraft().subscribe((saved) => (saved ? step() : undefined));
    } else {
      step();
    }
  }

  private publishedMessage(view: EmrFormView, submitted: boolean): string {
    const names = new Map(this.departments().map((d) => [d.key, d.name]));
    const where = [
      ...view.useIn.departmentKeys.map((k) => names.get(k) ?? k),
      ...view.useIn.places.map((p) => (p === 'EMERGENCY' ? 'Emergency' : 'IPD wards')),
    ].join(', ');
    const part = view.emrPart === 'ASSESSMENT' ? 'Assessment' : 'Treatment';
    const v = `v${view.chipVersionNo}`;
    return submitted
      ? `${v} sent for approval. After approval it appears in ${where} (${part}).`
      : `${v} is live in ${where} under ${part}.`;
  }

  approve(): void {
    const view = this.view();
    if (!view || !this.canApprove() || this.busy()) {
      return;
    }
    this.busy.set('approve');
    this.api.approve(view.formKey, view.revision).subscribe({
      next: (next) => {
        this.busy.set(null);
        this.adopt(next);
        this.say(`v${next.liveVersionNo} is live. New visits get it at once.`);
      },
      error: (error: unknown) => {
        this.busy.set(null);
        this.fail(error);
      },
    });
  }

  sendBack(reason: string): void {
    const view = this.view();
    const text = reason.trim();
    if (!view || !this.canApprove() || this.busy() || text === '') {
      return;
    }
    this.busy.set('back');
    this.api.sendBack(view.formKey, view.revision, text).subscribe({
      next: (next) => {
        this.busy.set(null);
        this.view.set(next);
        this.say('Sent back to the editor with your reason.');
      },
      error: (error: unknown) => {
        this.busy.set(null);
        this.fail(error);
      },
    });
  }

  retire(): void {
    const view = this.view();
    if (!view || !this.canEdit() || this.busy()) {
      return;
    }
    this.busy.set('retire');
    this.api.retire(view.formKey, view.revision).subscribe({
      next: (next) => {
        this.busy.set(null);
        this.view.set(next);
        this.say('Form retired. Records already filled keep it.');
      },
      error: (error: unknown) => {
        this.busy.set(null);
        this.fail(error);
      },
    });
  }

  say(message: string, undoable = false): void {
    this.toast.set(message);
    if (!undoable) {
      this.undoDelete.set(null);
    }
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
    this.toastTimer = setTimeout(() => {
      this.toast.set(null);
      this.undoDelete.set(null);
    }, TOAST_MS);
  }
}
