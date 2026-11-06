import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { IconComponent } from '../../../shared/icon/icon.component';
import {
  BAYS_MAX,
  BAYS_MIN,
  EmergencyLevel,
  SCALE_CHOICES,
  TARGET_MAX,
  TARGET_MIN,
  TARGET_STEP,
  TriageScale,
  levelTitle,
  targetLabel,
} from '../../ipd/emergency/emergency.model';
import { EmergencySettingsStore } from './emergency-settings.store';
import { IpdGateComponent } from './ipd-gate.component';

/**
 * Clinic setup → Emergency and triage (SAH-28), mockup `cfgIpEr`, key `iper`: the Levels choice
 * (3 colours or 5 levels), one target-time stepper for each level (steps of 5 minutes from Now to
 * 240), the Emergency bays stepper and the Unknown patient registration switch. Each change is
 * saved at once with the whole setting; only the owner can change them, everyone else sees them
 * read-only.
 */
@Component({
  selector: 'app-ip-er',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, IpdGateComponent],
  providers: [EmergencySettingsStore],
  styleUrls: ['../config-common.scss', './iper.page.scss'],
  templateUrl: './iper.page.html',
})
export class IpErPage {
  protected readonly store = inject(EmergencySettingsStore);

  protected readonly scales = SCALE_CHOICES;
  protected readonly baysMin = BAYS_MIN;
  protected readonly baysMax = BAYS_MAX;
  protected readonly targetMin = TARGET_MIN;
  protected readonly targetMax = TARGET_MAX;
  protected readonly title = levelTitle;
  protected readonly targetLabel = targetLabel;

  /** The buttons are off while a save runs (`store.locked()`); the store ignores a click as well. */
  protected setScale(scale: TriageScale): void {
    const body = this.store.input();
    if (!body || body.scale === scale) {
      return;
    }
    body.scale = scale;
    this.store.save(body);
  }

  protected stepTarget(level: EmergencyLevel, direction: 1 | -1): void {
    const body = this.store.input();
    if (!body || this.store.busy()) {
      return;
    }
    const targets = body.scale === 3 ? body.targets3 : body.targets5;
    const next = (targets[level.level - 1] ?? level.targetMinutes) + direction * TARGET_STEP;
    if (next < TARGET_MIN || next > TARGET_MAX) {
      return;
    }
    targets[level.level - 1] = next;
    this.store.save(body);
  }

  protected stepBays(direction: 1 | -1): void {
    const body = this.store.input();
    if (!body || this.store.busy()) {
      return;
    }
    const next = body.bays + direction;
    if (next < BAYS_MIN || next > BAYS_MAX) {
      return;
    }
    body.bays = next;
    this.store.save(body);
  }

  protected toggleUnknown(): void {
    const body = this.store.input();
    if (body) {
      body.unknownPatientRegistration = !body.unknownPatientRegistration;
      this.store.save(body);
    }
  }
}
