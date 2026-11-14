import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { EmrFillerRole, EmrLayoutSection, FILLER_LABEL, FILLER_ORDER, fieldTypeLabel } from '../../emr/emr.model';
import { ToggleFlag, sectionKindLabel } from './emr-config.logic';
import { EmrConfigStore } from './emr-config.store';

interface SettingRow {
  readonly id: 'shown' | 'required' | 'print' | 'copy' | 'collapsed';
  readonly label: string;
  readonly hint: (department: string) => string;
}

/** The five switches of the mockup "Section settings" panel, with their words. */
const ROWS: readonly SettingRow[] = [
  { id: 'shown', label: 'Show in EMR', hint: (d) => `Doctor sees this section in ${d}` },
  { id: 'required', label: 'Required to close visit', hint: () => 'Visit cannot be completed until filled' },
  { id: 'print', label: 'Print on prescription', hint: () => 'Appears on the printed Rx / summary' },
  { id: 'copy', label: 'Copy from last visit', hint: () => 'Pre-fills with the previous values' },
  { id: 'collapsed', label: 'Collapsed by default', hint: () => 'Doctor taps to open it' },
];

/** Column "Section settings" (mockup #Main): the five switches, the read-only fields and Who can fill. */
@Component({
  selector: 'app-section-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './section-settings.component.scss',
  templateUrl: './section-settings.component.html',
})
export class SectionSettingsComponent {
  protected readonly store = inject(EmrConfigStore);
  protected readonly rows = ROWS;
  protected readonly fillers = FILLER_ORDER;
  protected readonly fillerLabel = FILLER_LABEL;
  protected readonly typeLabel = fieldTypeLabel;
  protected readonly departmentName = computed(() => this.store.selected()?.name ?? '');
  protected readonly kindLabel = computed(() => {
    const section = this.store.selectedSection();
    return section ? sectionKindLabel(section, this.departmentName()) : '';
  });

  protected isOn(section: EmrLayoutSection, row: SettingRow): boolean {
    return section[row.id];
  }

  protected isDisabled(section: EmrLayoutSection, row: SettingRow): boolean {
    if (!this.store.canEdit()) {
      return true;
    }
    if (row.id === 'shown') {
      return section.hideLocked && section.shown;
    }
    if (row.id === 'required') {
      return !section.shown && !section.required;
    }
    return false;
  }

  protected toggle(section: EmrLayoutSection, row: SettingRow): void {
    switch (row.id) {
      case 'shown':
        this.store.toggleShown(section.key);
        break;
      case 'required':
        this.store.toggleRequired(section.key);
        break;
      default:
        this.store.toggleFlag(section.key, row.id satisfies ToggleFlag);
    }
  }

  protected fillerOn(section: EmrLayoutSection, role: EmrFillerRole): boolean {
    return section.whoCanFill.includes(role);
  }
}
