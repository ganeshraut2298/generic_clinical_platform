import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { formatInr, formatInrPaise } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { IpdApi } from './ipd.api';
import { IpdGateComponent } from './ipd-gate.component';
import { IpdSetupStore } from './ipd-setup.store';
import {
  BED_DAY_CUTS,
  BedDayCut,
  IpdCategory,
  IpdCharges,
  MAX_RATE_RUPEES,
  paiseToRupees,
  parseMultiplier,
  parseRupees,
  rupeesToPaise,
} from './ipd.model';

type ChargeField = 'nursingPaise' | 'visitPaise';

/**
 * Bed categories and charges (SAH-22), mockup `cfgIpRates`: the four categories with their daily
 * bed charge and rate on services, when a bed day is counted, and the nursing and doctor visit
 * charges. Amounts are whole rupees on screen and integer paise in the API.
 */
@Component({
  selector: 'app-ip-rates',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatButtonToggleModule, IconComponent, IpdGateComponent],
  providers: [IpdSetupStore],
  styleUrls: ['../config-common.scss', './iprates.page.scss'],
  templateUrl: './iprates.page.html',
})
export class IpRatesPage {
  private readonly api = inject(IpdApi);
  protected readonly store = inject(IpdSetupStore);

  protected readonly cuts = BED_DAY_CUTS;
  protected readonly paiseToRupees = paiseToRupees;
  protected readonly formatInrPaise = formatInrPaise;

  protected saveRate(category: IpdCategory, input: HTMLInputElement): void {
    const rupees = parseRupees(input.value);
    const before = paiseToRupees(category.ratePaise);
    if (rupees === null) {
      input.value = String(before);
      this.store.toast(`Enter whole rupees from ${formatInr(0)} to ${formatInr(MAX_RATE_RUPEES)}`);
      return;
    }
    if (rupees === before) {
      input.value = String(before);
      return;
    }
    this.store.run(
      this.api.setCategory(category.key, rupeesToPaise(rupees), category.multiplier),
      'We could not save that charge. Try again.',
      { failed: () => (input.value = String(before)) },
    );
  }

  protected saveMultiplier(category: IpdCategory, input: HTMLInputElement): void {
    const multiplier = parseMultiplier(input.value);
    if (multiplier === null) {
      input.value = String(category.multiplier);
      this.store.toast('Enter a rate on services from 0.5 to 5, with up to two decimals');
      return;
    }
    if (multiplier === category.multiplier) {
      input.value = String(multiplier);
      return;
    }
    this.store.run(
      this.api.setCategory(category.key, category.ratePaise, multiplier),
      'We could not save that rate. Try again.',
      { failed: () => (input.value = String(category.multiplier)) },
    );
  }

  protected setBedDayCut(value: BedDayCut): void {
    const charges = this.store.layout()?.charges;
    if (charges && charges.bedDayCut !== value) {
      this.saveCharges({ ...charges, bedDayCut: value });
    }
  }

  protected saveCharge(field: ChargeField, input: HTMLInputElement): void {
    const charges = this.store.layout()?.charges;
    if (!charges) {
      return;
    }
    const rupees = parseRupees(input.value);
    const before = paiseToRupees(charges[field]);
    if (rupees === null) {
      input.value = String(before);
      this.store.toast(`Enter whole rupees from ${formatInr(0)} to ${formatInr(MAX_RATE_RUPEES)}`);
      return;
    }
    if (rupees === before) {
      input.value = String(before);
      return;
    }
    this.saveCharges({ ...charges, [field]: rupeesToPaise(rupees) }, () => (input.value = String(before)));
  }

  private saveCharges(charges: IpdCharges, failed?: () => void): void {
    this.store.run(this.api.setCharges(charges), 'We could not save that change. Try again.', { failed });
  }
}
