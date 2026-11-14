import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { EmrLayoutSection, fieldTypeLabel } from '../../emr/emr.model';
import { sectionTag } from './emr-config.logic';
import { EmrConfigStore } from './emr-config.store';

/** Fields drawn on a card: six for Vitals, four for the others (mockup #Main preview). */
function fieldLimit(section: EmrLayoutSection): number {
  return section.key === 'vitals' ? 6 : 4;
}

/** Cards that span two columns (mockup #Main SPAN2). */
const SPAN2: ReadonlySet<string> = new Set([
  'dental_chart',
  'rx',
  'anc',
  'rom',
  'vitals',
  'tx_plan',
  'refraction',
  'growth',
  'immunization',
]);

type ToothState = 'caries' | 'filled' | 'rct' | 'missing' | 'none';
interface PreviewTooth {
  readonly n: number;
  readonly state: ToothState;
}

/** Example teeth drawn in the preview (mockup #Main TEETH): sample text, not patient data. */
const EXAMPLE_TEETH: Readonly<Record<number, ToothState>> = {
  16: 'caries',
  26: 'filled',
  36: 'rct',
  46: 'missing',
  47: 'caries',
  11: 'filled',
};

function teeth(numbers: readonly number[]): PreviewTooth[] {
  return numbers.map((n) => ({ n, state: EXAMPLE_TEETH[n] ?? 'none' }));
}

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
  protected readonly teethUpper = teeth([18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28]);
  protected readonly teethLower = teeth([48, 47, 46, 45, 44, 43, 42, 41, 31, 32, 33, 34, 35, 36, 37, 38]);
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

  protected spansTwo(section: EmrLayoutSection): boolean {
    return SPAN2.has(section.key);
  }
}
