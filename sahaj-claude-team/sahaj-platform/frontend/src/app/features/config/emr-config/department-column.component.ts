import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmrDepartmentSummary, STATUS_LABEL } from '../../emr/emr.model';
import { counts } from './emr-config.logic';
import { EmrConfigStore } from './emr-config.store';

/**
 * Column "Departments" (mockup #Main): search, one card per department with initials, name,
 * "x of y sections on", status and version, and the link "Add department from template".
 */
@Component({
  selector: 'app-department-column',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent],
  styleUrl: './department-column.component.scss',
  templateUrl: './department-column.component.html',
})
export class DepartmentColumnComponent {
  protected readonly store = inject(EmrConfigStore);
  protected readonly statusLabel = STATUS_LABEL;

  protected onSearch(event: Event): void {
    this.store.setQuery((event.target as HTMLInputElement).value);
  }

  /** The loaded department shows the live counts of its working copy; the others the server's. */
  protected countText(department: EmrDepartmentSummary): string {
    if (department.departmentKey === this.store.selectedKey() && this.store.sections().length > 0) {
      const c = counts(this.store.sections());
      return `${c.on} of ${this.store.sections().length} sections on`;
    }
    return `${department.sectionsOn} of ${department.sectionsTotal} sections on`;
  }
}
