import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { formatDisplayDate } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmrLayoutSection, FILLER_LABEL, FILLER_ORDER, StarterKey } from '../emr.model';
import { FormFillComponent } from '../forms/form-fill.component';
import { SpecialtyHostComponent } from '../specialty/specialty-host.component';
import { editorLoader } from '../specialty/specialty-registry';
import { FieldRendererComponent } from './field-renderer.component';
import { VitalsNoteComponent } from './vitals-note.component';
import { EmrRecordStore } from './emr-record.store';

/**
 * One section of the EMR as a card (SAH-47-3): name, red star when required, status tag (Done,
 * Required or Optional), a small "who can fill" label, and the fields. A section marked Collapsed by
 * default opens collapsed. A section pre-filled from the last signed visit says "Copied from <date>"
 * and is Done only after the doctor confirms or edits it (OQ-45-26).
 */
@Component({
  selector: 'app-section-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, FieldRendererComponent, FormFillComponent, SpecialtyHostComponent, VitalsNoteComponent],
  styleUrl: './section-card.component.scss',
  templateUrl: './section-card.component.html',
})
export class SectionCardComponent {
  protected readonly store = inject(EmrRecordStore);
  private readonly snackBar = inject(MatSnackBar);

  readonly section = input.required<EmrLayoutSection>();
  readonly specialty = input<StarterKey | null>(null);
  /** The card title of the design where it differs from the library name (profile chrome). */
  readonly title = input<string | null>(null);
  readonly area = input<'main' | 'tools' | null>(null);
  protected readonly heading = computed(() => this.title() ?? this.section().name);

  /** The second line of a chip card ("Shapes the treatment plan and advice"): the library description of a section drawn as chips. */
  protected readonly subLine = computed(() => {
    const section = this.section();
    const chipCard = section.fields.some((f) => f.presentation === 'CHIPS') || section.specialtyEditor?.component === 'DENTAL_MED_HISTORY';
    return chipCard && section.description.trim() !== '' ? section.description : null;
  });
  protected readonly collapsed = computed(() => this.store.isCollapsed(this.section().key));
  protected readonly state = computed(() => this.store.stateOf(this.section().key));
  protected readonly statusText = computed(() => {
    switch (this.state()?.status) {
      case 'DONE':
        return 'Done';
      case 'REQUIRED':
        return 'Required';
      default:
        return 'Optional';
    }
  });
  protected readonly values = computed(() => this.store.values()[this.section().key] ?? {});
  protected readonly problems = computed(() => this.store.problems()[this.section().key] ?? {});
  protected readonly copiedText = computed(() => {
    const copied = this.store.recordSection(this.section().key)?.copiedFrom;
    return copied ? `Copied from ${formatDisplayDate(copied.date)}` : null;
  });
  protected readonly pending = computed(() => this.store.copyPending(this.section().key));
  protected readonly fillers = computed(() =>
    FILLER_ORDER.filter((r) => this.section().whoCanFill.includes(r))
      .map((r) => FILLER_LABEL[r])
      .join(', '),
  );
  protected readonly highlighted = computed(() => this.store.highlight() === this.section().key);
  /** A specialty editor (ADR-023 D1) replaces the generic fields when the registry knows the component. */
  protected readonly hasEditor = computed(() => !!editorLoader(this.section().specialtyEditor?.component));
  /** Values saved before the section had its own screen: shown read only until the first specialty save replaces them. */
  protected readonly legacy = computed(() => {
    const entries = Object.entries(this.store.recordSection(this.section().key)?.legacyValues ?? {});
    return entries.length > 0 && !this.store.specialtyValue(this.section().key)
      ? entries.map(([key, value]) => ({ key, text: typeof value === 'string' ? value : JSON.stringify(value) }))
      : [];
  });
  /** "Save to patient record" for a clinic form: the values are saved with the visit (ADR-022 H9). */
  protected saveForm(formName: string): void {
    const section = this.section();
    const part = section.part === 'ASSESSMENT' ? 'Assessment' : 'Treatment';
    const version = section.formVersionNo ? ` (v${section.formVersionNo})` : '';
    this.store.save().subscribe((ok) => {
      if (ok) {
        this.snackBar.open(`Saved to the patient's EMR under ${part} as “${formName}”${version}.`, 'Close', { duration: 3500 });
      }
    });
  }

  protected readonly bodyId = computed(() => `sec-body-${this.section().key}`);
}
