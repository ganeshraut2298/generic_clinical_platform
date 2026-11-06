import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subscription } from 'rxjs';
import { formatInr, formatInrPaise } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { ApiError } from '../../../core/http/problem-detail';
import { formatDate, formatWhen, problemMessage } from '../../config/config-format';
import { DischargeSheetComponent } from '../discharge/discharge-sheet.component';
import { DISCHARGE_TEMPLATE_LABEL, DISCHARGE_TYPE_LABEL, dischargeToast } from '../discharge.model';
import { AdmissionsApi } from '../admissions.api';
import { EmrSheetComponent, EmrSheetData } from '../emr/emr-sheet.component';
import { NursingSheetComponent, NursingSheetData } from '../nursing/nursing-sheet.component';
import {
  ADMISSION_TYPE_LABEL,
  AdmissionDetail,
  AdmissionBedOption,
  ChargeService,
  ChargeServiceKey,
  DEFAULT_ALERT_PERCENT,
  MAX_DEPOSIT_RUPEES,
  PAY_MODES,
  PayMode,
  billNote,
  chargePricePaise,
  parseWholeRupees,
  transferOptionLabel,
  transferToast,
} from '../ipd.model';

export interface AdmissionDialogData {
  id: string;
}

const LOAD_ERROR = 'We could not load this admission. Try again.';
const SAVE_ERROR = 'We could not record that deposit. Try again.';
const CHARGE_ERROR = 'We could not add that charge. Try again.';
const BEDS_ERROR = 'We could not load the free beds. Try again.';
const MOVE_ERROR = 'We could not move the patient. Check the bed and try again.';

/**
 * Admission sheet (SAH-23, SAH-25), mockup `openIp`: summary strip, deposit alert and insurance
 * notices, running bill, Collect deposit, Add a charge and (SAH-26) Move to another bed. SAH-27 adds
 * the footer button Start discharge (opens the Discharge sheet in place) and the read view of a
 * discharged admission (frozen final bill, discharge facts, no actions). Closes with `true` when
 * anything changed so the page can refresh.
 */
@Component({
  selector: 'app-admission-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent, DischargeSheetComponent],
  styleUrls: ['../ipd-bill.scss', './admission-dialog.component.scss'],
  templateUrl: './admission-dialog.component.html',
})
export class AdmissionDialogComponent {
  private readonly api = inject(AdmissionsApi);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly dialogRef = inject<MatDialogRef<AdmissionDialogComponent, boolean>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly data = inject<AdmissionDialogData>(MAT_DIALOG_DATA);

  protected readonly modes = PAY_MODES;
  protected readonly formatInrPaise = formatInrPaise;
  protected readonly when = formatWhen;
  protected readonly typeLabel = ADMISSION_TYPE_LABEL;
  protected readonly note = billNote;
  protected readonly defaultAlert = DEFAULT_ALERT_PERCENT;
  protected readonly pricePaise = chargePricePaise;
  /** `24 Oct 2026` from the date-time of a deposit. */
  protected readonly day = (iso: string): string => formatWhen(iso).split(',')[0];

  protected readonly dischargeTypeLabel = DISCHARGE_TYPE_LABEL;
  protected readonly dischargeTemplateLabel = DISCHARGE_TEMPLATE_LABEL;
  protected readonly longDate = formatDate;

  protected readonly admission = signal<AdmissionDetail | null>(null);
  /** `admission` shows the admission sheet; `discharge` the Discharge sheet (SAH-27). */
  protected readonly view = signal<'admission' | 'discharge'>('admission');
  protected readonly loadError = signal<string | null>(null);
  protected readonly collecting = signal(false);
  protected readonly collectError = signal<string | null>(null);
  protected readonly amount = new FormControl('', { nonNullable: true });
  protected readonly mode = signal<PayMode>('UPI');
  protected readonly services = signal<ChargeService[]>([]);
  /** The extra-charge select; disabled until the services have loaded. */
  protected readonly service = new FormControl<ChargeServiceKey | null>({ value: null, disabled: true });
  protected readonly serviceKey = toSignal(this.service.valueChanges, { initialValue: null });
  protected readonly adding = signal(false);
  protected readonly chargeError = signal<string | null>(null);
  /**
   * Available beds for the Move select (SAH-26), in the order the server sends: category, then room
   * creation order, then code. The client does not sort them again.
   */
  protected readonly freeBeds = signal<AdmissionBedOption[]>([]);
  protected readonly bed = new FormControl<string | null>({ value: null, disabled: true });
  protected readonly bedId = toSignal(this.bed.valueChanges, { initialValue: null });
  protected readonly moving = signal(false);
  protected readonly moveError = signal<string | null>(null);
  protected readonly optionLabel = (b: AdmissionBedOption): string => transferOptionLabel(b, formatInrPaise);
  private changed = false;
  private freeBedsCall?: Subscription;

  protected readonly amountText = signal('');

  /** Whole rupees from ₹1 to ₹10,00,000, or null while the text is not valid. */
  protected readonly amountRupees = computed(() => {
    const rupees = parseWholeRupees(this.amountText());
    return rupees !== null && rupees >= 1 ? rupees : null;
  });

  constructor() {
    this.amount.valueChanges.pipe(takeUntilDestroyed()).subscribe((v) => this.amountText.set(v));
    this.load();
  }

  protected load(): void {
    this.loadError.set(null);
    this.api
      .get(this.data.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (admission) => {
          this.admission.set(admission);
          this.prefill(admission);
          this.loadServices(admission);
          this.loadFreeBeds(admission);
        },
        error: (error: unknown) => {
          this.loadError.set(problemMessage(error, LOAD_ERROR));
        },
      });
  }

  /** Offers the shortfall to the minimum when the deposit is due. */
  private prefill(admission: AdmissionDetail): void {
    const short = (admission.minimumDepositPaise ?? 0) - admission.depositPaise;
    this.amount.setValue(admission.depositState === 'DUE' && short > 0 ? String(short / 100) : '');
  }

  /** The ten extra-charge services, only for users who can add a charge. */
  private loadServices(admission: AdmissionDetail): void {
    if (!admission.canAddCharge || this.services().length > 0) {
      return;
    }
    this.api
      .chargeServices()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (services) => {
          this.services.set(services);
          if (services.length > 0) {
            this.service.enable();
          }
          this.service.setValue(services[0]?.key ?? null);
        },
        error: (error: unknown) => this.chargeError.set(problemMessage(error, CHARGE_ERROR)),
      });
  }

  /**
   * The Available beds, only for users who can move the patient. Keeps the choice when still free.
   * A newer load cancels the one still running, so two loads cannot finish out of order.
   */
  private loadFreeBeds(admission: AdmissionDetail): void {
    this.freeBedsCall?.unsubscribe();
    this.freeBedsCall = undefined;
    if (!admission.canTransfer) {
      this.freeBeds.set([]);
      return;
    }
    this.freeBedsCall = this.api
      .options()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (options) => {
          // The server sends the beds in the display order (category, room, code); not sorted again here.
          const beds = options.beds;
          if (this.moveError() === BEDS_ERROR) {
            this.moveError.set(null);
          }
          const keep = beds.find((b) => b.id === this.bed.value)?.id;
          this.freeBeds.set(beds);
          if (beds.length > 0) {
            this.bed.enable();
          } else {
            this.bed.disable();
          }
          this.bed.setValue(keep ?? beds[0]?.id ?? null);
        },
        error: (error: unknown) => this.moveError.set(problemMessage(error, BEDS_ERROR)),
      });
  }

  protected move(): void {
    const admission = this.admission();
    const target = this.freeBeds().find((b) => b.id === this.bed.value);
    if (!admission || !target || this.moving()) {
      return;
    }
    const fromCode = admission.bedCode;
    const newRateFrom = admission.transferRules?.newRateFrom ?? 'same';
    this.moving.set(true);
    this.moveError.set(null);
    this.api
      .transfer(admission.id, target.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.moving.set(false);
          this.changed = true;
          this.admission.set(updated);
          this.loadFreeBeds(updated);
          this.snackBar.open(
            transferToast({
              patientName: updated.patientName,
              fromCode,
              toCode: target.code,
              categoryName: target.categoryName,
              newRateFrom,
            }),
            'Close',
            { duration: 6000 },
          );
        },
        error: (error: unknown) => {
          // Move stays disabled until the re-read comes back: the move may have happened anyway.
          this.moveError.set(problemMessage(error, MOVE_ERROR));
          this.refreshAfterMoveError(admission, error);
        },
      });
  }

  /**
   * Re-reads the sheet (the move may have happened) and, when the bed was taken, the free beds.
   * Move is enabled again when the re-read ends. When the patient is in another bed than before,
   * the error is cleared and a toast says where the patient is now.
   */
  private refreshAfterMoveError(admission: AdmissionDetail, error: unknown): void {
    const taken = error instanceof ApiError && error.problem.type.endsWith('bed-not-free');
    if (taken) {
      this.loadFreeBeds(admission);
    }
    this.api
      .get(admission.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (fresh) => {
          this.admission.set(fresh);
          if (fresh.bedCode !== admission.bedCode) {
            this.changed = true;
            this.moveError.set(null);
            this.loadFreeBeds(fresh);
            this.snackBar.open(`${fresh.patientName} is now in ${fresh.bedCode}`, 'Close', { duration: 6000 });
          }
          this.moving.set(false);
        },
        error: () => this.moving.set(false),
      });
  }

  protected addCharge(): void {
    const admission = this.admission();
    const service = this.services().find((s) => s.key === this.service.value);
    if (!admission || !service || this.adding()) {
      return;
    }
    this.adding.set(true);
    this.chargeError.set(null);
    this.api
      .addCharge(admission.id, service.key)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.admission.set(updated);
          this.adding.set(false);
          this.changed = true;
          this.snackBar.open(`${service.name} added to ${updated.patientName}'s bill`, 'Close', {
            duration: 6000,
          });
        },
        error: (error: unknown) => {
          this.adding.set(false);
          this.chargeError.set(problemMessage(error, CHARGE_ERROR));
        },
      });
  }

  /** SAH-30 (OQ-30-1): the Nursing sheet opens over the admission sheet; this sheet stays underneath. */
  protected openNursing(): void {
    const admission = this.admission();
    if (!admission) {
      return;
    }
    this.dialog.open<NursingSheetComponent, NursingSheetData, boolean>(NursingSheetComponent, {
      panelClass: 'sheet-panel',
      width: '640px',
      maxWidth: '100vw',
      ariaLabelledBy: 'nv-title',
      data: { admissionId: admission.id },
    });
  }

  /** SAH-31 (OQ-31-7): the EMR sheet opens over the admission sheet; this sheet stays underneath. */
  protected openEmr(): void {
    const admission = this.admission();
    if (!admission) {
      return;
    }
    this.dialog.open<EmrSheetComponent, EmrSheetData>(EmrSheetComponent, {
      panelClass: 'sheet-panel',
      width: '640px',
      maxWidth: '100vw',
      ariaLabelledBy: 'emr-title',
      data: { admissionId: admission.id },
    });
  }

  protected startDischarge(): void {
    this.view.set('discharge');
  }

  protected backToAdmission(): void {
    this.view.set('admission');
  }

  /** A signature was recorded: the "Going home today" counters behind the dialog need a refresh. */
  protected markChanged(): void {
    this.changed = true;
  }

  /** The admission changed under the Discharge sheet (discharged by someone else, or IPD read-only). */
  protected reloadAfterOutdated(): void {
    this.changed = true;
    this.api
      .get(this.data.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (fresh) => this.admission.set(fresh), error: () => undefined });
  }

  protected onDischarged(admission: AdmissionDetail): void {
    this.snackBar.open(dischargeToast(admission, formatDate), 'Close', { duration: 8000 });
    this.dialogRef.close(true);
  }

  protected setMode(mode: PayMode): void {
    this.mode.set(mode);
  }

  protected invalidAmount(): string | null {
    const text = this.amountText().trim();
    if (text === '' || this.amountRupees() !== null) {
      return null;
    }
    return `Enter whole rupees from ${formatInr(1)} to ${formatInr(MAX_DEPOSIT_RUPEES)}`;
  }

  protected collect(): void {
    const admission = this.admission();
    const rupees = this.amountRupees();
    if (!admission || rupees === null || this.collecting()) {
      return;
    }
    this.collecting.set(true);
    this.collectError.set(null);
    this.api
      .collectDeposit(admission.id, rupees * 100, this.mode())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.admission.set(updated);
          this.amount.setValue('');
          this.collecting.set(false);
          this.changed = true;
          this.snackBar.open(
            `${formatInr(rupees)} deposit received from ${updated.patientName}'s family`,
            'Close',
            { duration: 6000 },
          );
        },
        error: (error: unknown) => {
          this.collecting.set(false);
          this.collectError.set(problemMessage(error, SAVE_ERROR));
        },
      });
  }

  protected close(): void {
    this.dialogRef.close(this.changed);
  }
}
