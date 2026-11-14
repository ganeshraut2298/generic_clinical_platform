import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PART_LABEL, EmrLayoutSection } from '../../emr/emr.model';
import { PART_HEAD, groupByPart, sectionTag } from './emr-config.logic';
import { EmrConfigStore } from './emr-config.store';

/**
 * Column "1 · Assessment" / "2 · Treatment" (mockup #Main): the sections of the selected
 * department by part, each row with Up, Down, name, tag, move to the other part, field count,
 * Required and the Show switch. Only the owner can change anything (SAH-45-2, SAH-45-3).
 */
@Component({
  selector: 'app-sections-column',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrl: './sections-column.component.scss',
  templateUrl: './sections-column.component.html',
})
export class SectionsColumnComponent {
  protected readonly store = inject(EmrConfigStore);
  protected readonly partHead = PART_HEAD;
  protected readonly groups = computed(() => groupByPart(this.store.sections()));
  protected readonly initials = computed(() => this.store.selected()?.initials ?? '');
  protected readonly departmentName = computed(() => this.store.selected()?.name ?? '');

  protected tag(section: EmrLayoutSection): string {
    return sectionTag(section, this.initials());
  }

  protected moveLabel(section: EmrLayoutSection): string {
    return section.part === 'ASSESSMENT' ? 'To Treatment →' : '← To Assessment';
  }

  protected otherPartName(section: EmrLayoutSection): string {
    return PART_LABEL[section.part === 'ASSESSMENT' ? 'TREATMENT' : 'ASSESSMENT'];
  }

  protected showLabel(section: EmrLayoutSection): string {
    return section.hideLocked && section.hideLockReason
      ? `Show ${section.name} in EMR. ${section.hideLockReason}`
      : `Show ${section.name} in EMR`;
  }
}
