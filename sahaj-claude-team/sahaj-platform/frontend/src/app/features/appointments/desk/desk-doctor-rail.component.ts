import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import {
  DeskDoctor,
  doctorInitials,
  doctorStatusText,
  doctorStatusTone,
  nextFreeText,
  shortDoctorName,
} from './desk.model';

/** Doctor rail (SAH-90-2): department filter, one compact row per doctor, scrolls inside itself. */
@Component({
  selector: 'app-desk-doctor-rail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './desk-doctor-rail.component.scss',
  templateUrl: './desk-doctor-rail.component.html',
})
export class DeskDoctorRailComponent {
  readonly doctors = input.required<readonly DeskDoctor[]>();
  readonly total = input.required<number>();
  readonly departments = input.required<readonly string[]>();
  readonly department = input.required<string>();
  readonly selectedId = input<string | null>(null);
  readonly today = input.required<string>();

  readonly departmentChange = output<string>();
  readonly doctorSelect = output<string>();

  protected readonly shortName = shortDoctorName;
  protected readonly initials = doctorInitials;
  protected readonly statusText = doctorStatusText;
  protected readonly statusTone = doctorStatusTone;
  protected readonly nextFree = nextFreeText;

  protected onDepartment(event: Event): void {
    this.departmentChange.emit((event.target as HTMLSelectElement).value);
  }
}
