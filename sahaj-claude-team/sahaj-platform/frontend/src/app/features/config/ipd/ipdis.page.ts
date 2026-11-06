import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { IconComponent } from '../../../shared/icon/icon.component';
import {
  DischargeSettingsInput,
  DischargeTemplateKey,
  FOLLOW_UP_DAYS_MAX,
  FOLLOW_UP_DAYS_MIN,
  followUpRuleHint,
} from '../../ipd/discharge.model';
import { DischargeSettingsStore } from './discharge-settings.store';
import { IpdGateComponent } from './ipd-gate.component';

/**
 * Clinic setup → Discharge (SAH-27), mockup `cfgIpDis`, key `ipdis`: the Summary template select,
 * the Approval chain switch, the Follow-up after discharge stepper (1 to 30 days) and the read-only
 * Discharge types chips. Each change is saved at once with the whole setting; only the owner can
 * change them, everyone else sees them read-only.
 */
@Component({
  selector: 'app-ip-dis',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, IpdGateComponent],
  providers: [DischargeSettingsStore],
  styleUrls: ['../config-common.scss', './ipdis.page.scss'],
  templateUrl: './ipdis.page.html',
})
export class IpDisPage {
  protected readonly store = inject(DischargeSettingsStore);

  protected readonly minDays = FOLLOW_UP_DAYS_MIN;
  protected readonly maxDays = FOLLOW_UP_DAYS_MAX;
  protected readonly hint = followUpRuleHint;

  private input(): DischargeSettingsInput | null {
    const s = this.store.settings();
    return s
      ? { templateKey: s.templateKey, approvalChain: s.approvalChain, followUpDays: s.followUpDays }
      : null;
  }

  /** The value is '' for "Choose a template". The select goes back to the saved value if the save fails. */
  protected setTemplate(select: HTMLSelectElement): void {
    const body = this.input();
    if (!body) {
      return;
    }
    const before = body.templateKey ?? '';
    const next = select.value === '' ? null : (select.value as DischargeTemplateKey);
    if ((next ?? '') === before) {
      return;
    }
    body.templateKey = next;
    this.store.save(body, () => (select.value = before));
  }

  protected toggleChain(): void {
    const body = this.input();
    if (body) {
      body.approvalChain = !body.approvalChain;
      this.store.save(body);
    }
  }

  /** The buttons stay enabled (and keep keyboard focus) while a save runs; a click then does nothing. */
  protected stepDays(direction: 1 | -1): void {
    const body = this.input();
    if (!body || this.store.busy()) {
      return;
    }
    const next = body.followUpDays + direction;
    if (next < FOLLOW_UP_DAYS_MIN || next > FOLLOW_UP_DAYS_MAX) {
      return;
    }
    body.followUpDays = next;
    this.store.save(body);
  }
}
