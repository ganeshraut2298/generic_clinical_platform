import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { EmrLayoutSection, fieldTypeLabel } from '../../emr/emr.model';
import { sectionTag } from './emr-config.logic';
import { EmrConfigStore } from './emr-config.store';

/** Fields drawn on a card: six for Vitals, four for the others (mockup #Main preview). */
function fieldLimit(section: EmrLayoutSection): number {
  return section.key === 'vitals' ? 6 : 4;
}

/** Sections that need their own editor in a later phase: the preview draws a placeholder. */
const SPECIAL_EDITORS: ReadonlySet<string> = new Set(['dental_chart', 'anc']);

/**
 * Tab "Doctor screen preview" (mockup #Main, SAH-45-6): a small doctor screen drawn from the draft.
 * It reads the working copy and never writes. The patient is example text, not patient data.
 */
@Component({
  selector: 'app-doctor-preview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet],
  styleUrl: './doctor-preview.component.scss',
  templateUrl: './doctor-preview.component.html',
})
export class DoctorPreviewComponent {
  protected readonly store = inject(EmrConfigStore);
  protected readonly typeLabel = fieldTypeLabel;
  protected readonly departmentName = computed(() => this.store.selected()?.name ?? '');
  protected readonly initials = computed(() => this.store.selected()?.initials ?? '');

  protected readonly assessment = computed(() => this.shown('ASSESSMENT'));
  protected readonly treatment = computed(() => this.shown('TREATMENT'));

  private shown(part: EmrLayoutSection['part']): EmrLayoutSection[] {
    return this.store.sections().filter((s) => s.shown && s.part === part);
  }

  protected tag(section: EmrLayoutSection): string {
    return sectionTag(section, this.initials());
  }

  protected fieldsOf(section: EmrLayoutSection) {
    return section.fields.slice(0, fieldLimit(section));
  }

  protected isSpecialEditor(section: EmrLayoutSection): boolean {
    return SPECIAL_EDITORS.has(section.key);
  }
}
