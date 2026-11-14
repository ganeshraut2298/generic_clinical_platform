import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  OnInit,
  computed,
  inject,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable, map } from 'rxjs';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { CardLeaveDialogComponent } from '../ipd/ipcard/leave-dialog.component';
import { DepartmentColumnComponent } from './department-column.component';
import { DoctorPreviewComponent } from './doctor-preview.component';
import { VISIT_TYPES, plural } from './emr-config.logic';
import { ConfigTab, EmrConfigStore } from './emr-config.store';
import { SectionSettingsComponent } from './section-settings.component';
import { SectionsColumnComponent } from './sections-column.component';

/**
 * EMR configuration by department (SAH-45, mockup #Main): the owner picks a department, chooses
 * which EMR sections the doctor sees, in what order and which are required, then saves a draft or
 * publishes. Every other clinic role reads the same page with the controls off.
 */
@Component({
  selector: 'app-emr-config',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    RouterLink,
    IconComponent,
    DepartmentColumnComponent,
    SectionsColumnComponent,
    SectionSettingsComponent,
    DoctorPreviewComponent,
  ],
  providers: [EmrConfigStore],
  styleUrl: './emr-config.component.scss',
  templateUrl: './emr-config.component.html',
})
export class EmrConfigComponent implements OnInit {
  protected readonly store = inject(EmrConfigStore);
  private readonly shell = inject(ShellStore);
  private readonly dialog = inject(MatDialog);
  private readonly route = inject(ActivatedRoute);

  protected readonly clinicName = computed(() => this.shell.me()?.clinic?.name ?? '');
  protected readonly visitTypes = VISIT_TYPES;
  protected readonly tabs: readonly { id: ConfigTab; label: string }[] = [
    { id: 'sections', label: 'Configure sections' },
    { id: 'preview', label: 'Doctor screen preview' },
  ];
  protected readonly summary = computed(() => {
    const c = this.store.selectedCounts();
    const version = this.store.selected()?.publishedVersion ?? 0;
    return `${c.on} ${plural(c.on, 'section', 'sections')} on · ${c.required} required · version v${version}`;
  });

  ngOnInit(): void {
    this.store.load(this.route.snapshot.queryParamMap.get('department'));
  }

  /** Asked by the router before the owner leaves the page with unsaved changes (SAH-45-4). */
  confirmLeave(): Observable<boolean> | boolean {
    if (this.store.unsaved() === 0) {
      return true;
    }
    return this.dialog
      .open<CardLeaveDialogComponent, void, boolean>(CardLeaveDialogComponent, {
        panelClass: 'sheet-panel',
        width: '420px',
        maxWidth: '100vw',
      })
      .afterClosed()
      .pipe(map((leave) => leave === true));
  }

  @HostListener('window:beforeunload', ['$event'])
  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.store.unsaved() > 0) {
      event.preventDefault();
    }
  }

  protected visitPressed(type: string): boolean {
    return this.store.visitType() === type;
  }
}

