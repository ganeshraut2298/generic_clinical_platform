import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleGroup, MatButtonToggleModule } from '@angular/material/button-toggle';
import { formatInr } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { AdmissionsApi } from '../../ipd/admissions.api';
import {
  ADMISSION_TYPES,
  ALERT_MAX,
  ALERT_MIN,
  ALERT_STEP,
  AdmissionRulesInput,
  AdmissionType,
  MAX_DEPOSIT_RUPEES,
  NEW_RATE_CHOICES,
  NewRateFrom,
  PACKAGE_INCLUDES_TEXT,
  TransferRules,
  UPGRADE_CHOICES,
  packageChip,
} from '../../ipd/ipd.model';
import { AdmissionRulesStore } from './admission-rules.store';
import { IpdGateComponent } from './ipd-gate.component';
import { PackagesStore } from './packages.store';
import { CATEGORY_OPTIONS, CategoryKey, paiseToRupees, parseRupees, rupeesToPaise } from './ipd.model';

/**
 * Clinic setup → Admission and deposits (SAH-23), mockup `cfgIpAdm`: the Minimum deposit panel
 * (base deposit for each admission type, the add-on for each bed category, emergency without
 * deposit, the deposit alert level, and Confirm these rules). SAH-26 adds the Bed transfer rules
 * panel (moving to a higher category, when the new bed rate starts). SAH-29 adds the read-only
 * Packages panel (DEC-030). Amounts are whole rupees on screen
 * and integer paise in the API. Each change is saved at once.
 */
@Component({
  selector: 'app-ip-adm',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatButtonToggleModule, IconComponent, IpdGateComponent],
  providers: [AdmissionRulesStore, PackagesStore],
  styleUrls: ['../config-common.scss', './ipadm.page.scss'],
  templateUrl: './ipadm.page.html',
})
export class IpAdmPage {
  private readonly api = inject(AdmissionsApi);
  protected readonly store = inject(AdmissionRulesStore);
  protected readonly packages = inject(PackagesStore);

  protected readonly types = ADMISSION_TYPES;
  protected readonly categories = CATEGORY_OPTIONS;
  protected readonly upgradeChoices = UPGRADE_CHOICES;
  protected readonly newRateChoices = NEW_RATE_CHOICES;
  protected readonly alertMin = ALERT_MIN;
  protected readonly alertMax = ALERT_MAX;
  protected readonly paiseToRupees = paiseToRupees;
  protected readonly formatInr = formatInr;
  protected readonly chip = packageChip;
  protected readonly includesText = PACKAGE_INCLUDES_TEXT;

  private input(): AdmissionRulesInput | null {
    const r = this.store.rules();
    return r
      ? {
          depositPaise: { ...r.depositPaise },
          categoryAddPaise: { ...r.categoryAddPaise },
          emergencyWithoutDeposit: r.emergencyWithoutDeposit,
          alertPercent: r.alertPercent,
        }
      : null;
  }

  private save(body: AdmissionRulesInput, failed?: () => void): void {
    this.store.run(this.api.saveRules(body), 'We could not save that change. Try again.', { failed });
  }

  /** Reads whole rupees from the field; puts the old value back and tells the user when invalid. */
  private rupees(input: HTMLInputElement, before: number): number | null {
    const rupees = parseRupees(input.value, MAX_DEPOSIT_RUPEES);
    if (rupees === null) {
      input.value = String(before);
      this.store.toast(`Enter whole rupees from ${formatInr(0)} to ${formatInr(MAX_DEPOSIT_RUPEES)}`);
      return null;
    }
    if (rupees === before) {
      input.value = String(before);
      return null;
    }
    return rupees;
  }

  protected saveBase(type: AdmissionType, field: HTMLInputElement): void {
    const body = this.input();
    if (!body) {
      return;
    }
    const before = paiseToRupees(body.depositPaise[type]);
    const rupees = this.rupees(field, before);
    if (rupees === null) {
      return;
    }
    body.depositPaise[type] = rupeesToPaise(rupees);
    this.save(body, () => (field.value = String(before)));
  }

  protected saveAddOn(key: CategoryKey, field: HTMLInputElement): void {
    const body = this.input();
    if (!body) {
      return;
    }
    const before = paiseToRupees(body.categoryAddPaise[key]);
    const rupees = this.rupees(field, before);
    if (rupees === null) {
      return;
    }
    body.categoryAddPaise[key] = rupeesToPaise(rupees);
    this.save(body, () => (field.value = String(before)));
  }

  protected toggleEmergency(): void {
    const body = this.input();
    if (body) {
      body.emergencyWithoutDeposit = !body.emergencyWithoutDeposit;
      this.save(body);
    }
  }

  protected stepAlert(direction: 1 | -1): void {
    const body = this.input();
    if (!body) {
      return;
    }
    const next = body.alertPercent + direction * ALERT_STEP;
    if (next < ALERT_MIN || next > ALERT_MAX) {
      return;
    }
    body.alertPercent = next;
    this.save(body);
  }

  protected confirm(): void {
    this.store.run(this.api.confirmRules(), 'We could not save the deposit rules. Try again.', {
      done: () => this.store.toast('Deposit rules saved'),
    });
  }

  /** Saves one transfer rule at once; the choice goes back to the saved one when the save fails. */
  protected saveTransfer(
    change: Partial<TransferRules>,
    group: MatButtonToggleGroup,
    field: keyof TransferRules,
  ): void {
    const before = this.store.transfer();
    if (!before) {
      return;
    }
    const body: TransferRules = { ...before, ...change };
    if (body.upgradeNeedsOwner === before.upgradeNeedsOwner && body.newRateFrom === before.newRateFrom) {
      return;
    }
    this.store.runTransfer(
      this.api.saveTransferRules(body),
      'We could not save that change. Try again.',
      () => (group.value = before[field]),
    );
  }

  protected setUpgrade(value: boolean, group: MatButtonToggleGroup): void {
    this.saveTransfer({ upgradeNeedsOwner: value }, group, 'upgradeNeedsOwner');
  }

  protected setRateFrom(value: NewRateFrom, group: MatButtonToggleGroup): void {
    this.saveTransfer({ newRateFrom: value }, group, 'newRateFrom');
  }
}
