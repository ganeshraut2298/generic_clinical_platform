import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, HostListener, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable, map, of, switchMap } from 'rxjs';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { printPaper } from '../../emr/paper/print-paper';
import { EmrFormField, EmrFormFieldType, PaperSize } from '../../emr/emr-phase3.model';
import { EmrLeaveChoice, EmrLeaveDialogComponent } from '../../emr/record/emr-leave-dialog.component';
import { FormFillComponent, FormFieldChange } from '../../emr/forms/form-fill.component';
import { PrintWord, printRows, word } from '../../emr/forms/form-print.logic';
import { bandOf, checkNumber, formProblems, isVisible, optionsOf, scoreTotal, statusLine } from '../../emr/forms/form.logic';
import { ClinicApi } from '../platform/clinic.api';
import { FieldSettingsComponent } from './field-settings.component';
import { FormSettingsComponent } from './form-settings.component';
import { PALETTE, TYPE_HINT, TYPE_ICON, TYPE_LABEL, hasScores } from './form-builder.logic';
import { BuilderMode, FormBuilderStore } from './form-builder.store';

type Drag = { kind: 'new'; type: EmrFormFieldType } | { kind: 'move'; key: string };

interface Letterhead {
  name: string;
  address: string;
  phone: string;
}

const MODES: readonly { key: BuilderMode; label: string }[] = [
  { key: 'build', label: 'Build' },
  { key: 'preview', label: 'Preview' },
  { key: 'print', label: 'Print' },
];

/**
 * The form builder (SAH-59; mockup #FormBuilder, under Clinic setup): form name, state chip, modes
 * Build, Preview and Print, a palette of 23 field types, the form canvas, and the Field and Form
 * settings panels. Only the owner changes anything; everyone else reads and previews. Preview uses
 * example data only (no patient) and its values are never saved. Photo, file, signature and
 * handwriting are shown disabled with "Later".
 */
@Component({
  selector: 'app-form-builder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink, IconComponent, FormFillComponent, FieldSettingsComponent, FormSettingsComponent],
  providers: [FormBuilderStore],
  styleUrl: './form-builder.page.scss',
  templateUrl: './form-builder.page.html',
})
export class FormBuilderPage implements OnInit {
  protected readonly store = inject(FormBuilderStore);
  private readonly route = inject(ActivatedRoute);
  private readonly dialog = inject(MatDialog);
  private readonly shell = inject(ShellStore);
  private readonly clinicApi = inject(ClinicApi);
  private readonly doc = inject(DOCUMENT);

  private readonly sheet = viewChild<ElementRef<HTMLElement>>('sheet');

  protected readonly modes = MODES;
  protected readonly palette = PALETTE;
  protected readonly typeLabel = TYPE_LABEL;
  protected readonly typeIcon = TYPE_ICON;
  protected readonly typeHint = TYPE_HINT;
  protected readonly dragging = signal<Drag | null>(null);
  protected readonly dropAt = signal<number | null>(null);
  protected readonly paper = signal<PaperSize>('A4');
  protected readonly printFailed = signal(false);
  protected readonly letterhead = signal<Letterhead>({ name: '', address: '', phone: '' });
  protected readonly nonEmpty = (v: string): boolean => !!v;
  protected readonly wordKeys: readonly PrintWord[] = ['patient', 'ageSex', 'uhid'];

  protected readonly def = this.store.definition;
  protected readonly previewProblems = computed(() => {
    const d = this.def();
    return d ? formProblems(d, this.store.previewValues()) : {};
  });
  protected readonly previewStatus = computed(() => {
    const d = this.def();
    return d ? statusLine(d, this.store.previewValues(), this.store.saveTried()) : '';
  });
  protected readonly previewBad = computed(
    () => (this.store.saveTried() && this.store.previewMissing().length > 0) || this.store.previewAbnormal() > 0,
  );
  protected readonly rows = computed(() => {
    const d = this.def();
    return d ? printRows(d, this.store.previewValues()) : [];
  });
  protected readonly language = computed(() => this.store.settings()?.printLanguage ?? 'EN');
  protected readonly clinicName = computed(() => this.letterhead().name || (this.shell.me()?.clinic?.name ?? ''));
  protected readonly versionText = computed(() => `v${this.store.view()?.chipVersionNo ?? 1}`);

  ngOnInit(): void {
    const key = this.route.snapshot.paramMap.get('formKey');
    if (key) {
      this.store.load(key);
    }
    this.clinicApi.getProfile().subscribe({
      next: (p) => this.letterhead.set({ name: p.name, address: [p.address, p.area, p.city].filter((x) => x).join(', '), phone: p.phone ?? '' }),
      error: () => undefined,
    });
  }

  protected w(key: PrintWord): string {
    return word(key, this.language());
  }

  // ---- canvas ----

  protected isVisibleNow(f: EmrFormField): boolean {
    const d = this.def();
    return !d || this.store.mode() === 'build' || isVisible(f, d.fields, this.store.previewValues());
  }

  protected badges(f: EmrFormField): string[] {
    const out: string[] = [];
    const src = f.showIf ? this.store.fields().find((x) => x.key === f.showIf!.fieldKey) : null;
    if (f.showIf) {
      const opt = src ? optionsOf(src).find((o) => o.key === f.showIf!.optionKey)?.label : null;
      out.push(`Shows if ${src?.label ?? '?'} = ${opt ?? f.showIf.optionKey}`);
    }
    if (hasScores(f)) {
      out.push('Scored');
    }
    if (f.standardCode) {
      out.push(f.standardCode.split(' ')[0]);
    }
    if (!f.print) {
      out.push('Not printed');
    }
    return out;
  }

  protected sample(f: EmrFormField): string {
    switch (f.type) {
      case 'NUMBER':
        return f.min != null || f.max != null ? `${f.min ?? ''}–${f.max ?? ''}` : '';
      case 'SCORE':
        return `Adds ${f.score?.sourceFieldKeys.length ?? 0} field(s)${f.score?.rules.length ? ` + ${f.score.rules.length} rule(s)` : ''}`;
      case 'SCORE_BAND':
        return `From ${this.store.fields().find((x) => x.key === f.band?.sourceFieldKey)?.label ?? '—'}`;
      default:
        return '';
    }
  }

  protected kindOf(f: EmrFormField): 'input' | 'long' | 'chips' | 'select' | 'calc' | 'block' | 'section' | 'note' {
    switch (f.type) {
      case 'SHORT_TEXT':
      case 'NUMBER':
      case 'DATE':
      case 'TIME':
        return 'input';
      case 'LONG_TEXT':
        return 'long';
      case 'YES_NO':
      case 'SINGLE_CHOICE':
      case 'MULTI_CHOICE':
        return 'chips';
      case 'DROPDOWN':
        return 'select';
      case 'SCORE':
      case 'SCORE_BAND':
        return 'calc';
      case 'SECTION_HEADING':
        return 'section';
      case 'INFO_NOTE':
        return 'note';
      default:
        return 'block';
    }
  }

  protected options(f: EmrFormField): ReturnType<typeof optionsOf> {
    return optionsOf(f);
  }

  protected inputType(f: EmrFormField): string {
    return f.type === 'DATE' ? 'date' : f.type === 'TIME' ? 'time' : 'text';
  }

  protected calcValue(f: EmrFormField): string {
    const d = this.def();
    if (!d) {
      return '—';
    }
    return f.type === 'SCORE' ? String(scoreTotal(f, d.fields, this.store.previewValues()) ?? '—') : (bandOf(f, d.fields, this.store.previewValues())?.label ?? '—');
  }

  protected flagOf(f: EmrFormField): ReturnType<typeof checkNumber> {
    return checkNumber(f, this.store.previewValues()[f.key]);
  }

  // ---- drag and drop (and the keyboard way: tap or Enter adds, arrows move) ----

  protected startNew(type: EmrFormFieldType, event: DragEvent): void {
    this.dragging.set({ kind: 'new', type });
    event.dataTransfer?.setData('text/plain', type);
  }

  protected startMove(key: string, event: DragEvent): void {
    if (!this.store.canEdit() || this.store.mode() !== 'build') {
      return;
    }
    this.dragging.set({ kind: 'move', key });
    event.dataTransfer?.setData('text/plain', key);
  }

  protected over(index: number, event: DragEvent): void {
    if (this.dragging()) {
      event.preventDefault();
      this.dropAt.set(index);
    }
  }

  protected drop(index: number, event: DragEvent): void {
    event.preventDefault();
    const d = this.dragging();
    this.endDrag();
    if (!d) {
      return;
    }
    if (d.kind === 'new') {
      this.store.addField(d.type, index);
    } else {
      this.store.dropField(d.key, index);
    }
  }

  protected endDrag(): void {
    this.dragging.set(null);
    this.dropAt.set(null);
  }

  // ---- Preview ----

  protected onPreviewChange(change: FormFieldChange): void {
    this.store.setPreviewValue(change.key, change.value);
  }

  protected tryPreviewSave(): void {
    this.store.saveTried.set(true);
  }

  // ---- Print ----

  protected print(): void {
    const el = this.sheet()?.nativeElement;
    if (!el) {
      return;
    }
    this.printFailed.set(!printPaper(this.doc, this.doc.defaultView ?? window, el, this.paper()));
  }

  // ---- leaving with unsaved changes ----

  confirmLeave(): Observable<boolean> | boolean {
    if (!this.store.dirty() || !this.store.canEdit()) {
      return true;
    }
    return this.dialog
      .open<EmrLeaveDialogComponent, void, EmrLeaveChoice>(EmrLeaveDialogComponent, {
        panelClass: 'sheet-panel',
        width: '440px',
        maxWidth: '100vw',
      })
      .afterClosed()
      .pipe(
        switchMap((choice) => {
          if (choice === 'leave') {
            return of(true);
          }
          if (choice === 'save') {
            return this.store.saveDraft().pipe(map((saved) => saved !== null));
          }
          return of(false);
        }),
      );
  }

  @HostListener('window:beforeunload', ['$event'])
  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.store.dirty() && this.store.canEdit()) {
      event.preventDefault();
    }
  }

  protected onNameInput(event: Event): void {
    this.store.setName((event.target as HTMLInputElement).value);
  }
}
