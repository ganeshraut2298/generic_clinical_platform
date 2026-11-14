import { ChangeDetectionStrategy, Component, Injector, computed, inject, output } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { ageText } from '../../../shared/format/patient-age';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmrVisitType } from '../emr.model';
import { EmrTriageDialogComponent } from './emr-triage-dialog.component';
import { TITLE_BY_ENCOUNTER, lastVisitText, visitLine } from './emr-record.logic';
import { EmrRecordStore } from './emr-record.store';

const SEX_WORD = { F: 'Female', M: 'Male', O: 'Other' } as const;

const VISIT_TYPES: readonly { value: EmrVisitType; label: string }[] = [
  { value: 'NEW', label: 'New' },
  { value: 'FOLLOW_UP', label: 'Follow-up' },
  { value: 'REVISIT', label: 'Revisit' },
  { value: 'EMERGENCY', label: 'Emergency' },
];

/**
 * The EMR header (SAH-47-1, SAH-52): the title for the encounter, the patient strip with allergy and
 * alert tags, the doctor and room, the visit-type control (OPD) or the read-only "IPD · day n" and
 * triage level, the department switch and the Admit to IPD button. It stays in view while the
 * doctor scrolls. Names and allergy text are for the screen only. The "ABHA linked" tag waits for
 * DEC-040 and is not drawn.
 */
@Component({
  selector: 'app-emr-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrl: './emr-header.component.scss',
  templateUrl: './emr-header.component.html',
})
export class EmrHeaderComponent {
  protected readonly store = inject(EmrRecordStore);
  private readonly dialog = inject(MatDialog);
  private readonly injector = inject(Injector);

  readonly back = output<void>();
  readonly admit = output<void>();

  protected readonly visitTypes = VISIT_TYPES;
  protected readonly record = this.store.record;
  protected readonly header = computed(() => this.store.record()?.header ?? null);
  protected readonly title = computed(() => {
    const type = this.store.record()?.encounterType;
    return type ? TITLE_BY_ENCOUNTER[type] : '';
  });
  /** Paediatrics shows the worked-out age ("18 months"); every other department keeps the whole years (SAH-73-4). */
  protected readonly ageLabel = computed(() => {
    const h = this.header();
    if (!h) {
      return null;
    }
    if (this.store.profile() === 'paeds' && h.ageDetail) {
      return ageText(h.ageDetail, 'header');
    }
    return h.age !== null ? `${h.age} y` : null;
  });
  protected readonly sexWord = computed(() => {
    const sex = this.header()?.sex;
    return sex ? SEX_WORD[sex] : '';
  });
  protected readonly visitText = computed(() => {
    const h = this.header();
    return h ? visitLine(h.visitType, h.visitOrdinal) : null;
  });
  protected readonly lastVisit = computed(() => lastVisitText(this.header()?.lastVisitDaysAgo ?? null));
  protected readonly isOpd = computed(() => this.store.record()?.encounterType === 'OPD_VISIT');
  protected readonly isIpd = computed(() => this.store.record()?.encounterType === 'IPD_ROUND');
  protected readonly canSwitch = computed(() => {
    const r = this.store.record();
    return !!r && !r.readOnly && r.departmentOptions.length > 1;
  });
  protected readonly layoutNote = computed(() =>
    this.store.record()?.layout.layoutSource === 'STARTER' ? 'Using the starter layout' : null,
  );
  protected readonly choiceNote = computed(() =>
    this.store.record()?.layout.choiceSource === 'CLINIC_DEFAULT' ? 'Department chosen by the clinic default' : null,
  );
  /** The Admit to IPD button follows the admission state of the record (SAH-52-4, SAH-67-3). */
  protected readonly admitLabel = computed(() => {
    const a = this.store.record()?.admission;
    switch (a?.state) {
      case 'ADVISED':
        return 'Admission advised';
      case 'REQUESTED':
        return 'Admission requested';
      case 'ADMITTED':
        return a.ipNumber ? `Admitted · ${a.ipNumber}` : 'Admitted';
      case 'ALREADY_ADMITTED':
        return a.ipNumber ? `Already admitted · ${a.ipNumber}` : 'Already admitted';
      default:
        return 'Admit to IPD';
    }
  });
  protected readonly admitPressed = computed(() => {
    const r = this.store.record();
    return !!r && (r.admissionAdvised || (r.admission !== undefined && r.admission.state !== 'NONE'));
  });
  /** An admitted patient's button opens the admission even when the visit is read-only. */
  protected readonly admitDisabled = computed(() => {
    const r = this.store.record();
    const state = r?.admission?.state;
    return !r || (this.store.readOnly() && state !== 'ADMITTED' && state !== 'ALREADY_ADMITTED');
  });
  protected readonly showAdmit = computed(() => {
    const r = this.store.record();
    return !!r && r.encounterType !== 'IPD_ROUND';
  });

  protected onSwitch(event: Event): void {
    const key = (event.target as HTMLSelectElement).value;
    if (key && key !== this.store.record()?.layout.departmentKey) {
      this.store.switchDepartment(key);
    }
  }

  protected visitTypeOn(value: string): boolean {
    return this.header()?.visitType === value;
  }

  /** The Emergency button reads "Emergency · <level>" once a level is set (SAH-67-4). */
  protected typeLabel(value: EmrVisitType, label: string): string {
    const h = this.header();
    return value === 'EMERGENCY' && h?.visitType === 'EMERGENCY' && h.triageLevelName ? `Emergency · ${h.triageLevelName}` : label;
  }

  protected pickVisitType(value: EmrVisitType): void {
    if (this.visitTypeOn(value)) {
      return;
    }
    if (value !== 'EMERGENCY') {
      this.store.changeVisitType(value);
      return;
    }
    this.dialog
      .open<EmrTriageDialogComponent, void, number | null>(EmrTriageDialogComponent, {
        panelClass: 'sheet-panel',
        width: '480px',
        maxWidth: '100vw',
        injector: this.injector,
        ariaLabelledBy: 'triage-title',
      })
      .afterClosed()
      .subscribe((level) => {
        if (typeof level === 'number') {
          this.store.changeVisitType('EMERGENCY', level);
        }
      });
  }
}
