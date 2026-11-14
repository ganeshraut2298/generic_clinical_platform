import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { IconComponent } from '../../shared/icon/icon.component';
import { EmrAccess } from './emr-access.service';
import { EmrLauncher } from './emr-launcher.service';
import { EmrEncounterType } from './emr.model';

/**
 * The "Open EMR" button on a Queue card, an Admitted card and an Emergency card (SAH-46-1). One
 * label in all three places; the IPD "EMR sheet" for medicine orders keeps its own name. Shown only
 * to a doctor, visiting doctor or owner with doctor details, and on the OPD card only for the
 * doctor's own patients (the owner can open any). The server checks again on every open.
 */
@Component({
  selector: 'app-open-emr-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styles: `
    :host {
      display: contents;
    }
    button {
      min-height: 44px;
    }
  `,
  template: `
    @if (visible()) {
      <button
        mat-stroked-button
        type="button"
        data-act="open-emr"
        [disabled]="launcher.opening() !== null"
        [attr.aria-label]="label()"
        (click)="launcher.open(encounterType(), encounterId())"
      >
        <app-icon name="emr" [size]="15" /> Open EMR
      </button>
    }
  `,
})
export class OpenEmrButtonComponent implements OnInit {
  protected readonly access = inject(EmrAccess);
  protected readonly launcher = inject(EmrLauncher);

  readonly encounterType = input.required<EmrEncounterType>();
  readonly encounterId = input.required<string>();
  /** OPD cards pass the visit's doctor so a doctor sees the button only on their own patients. */
  readonly doctorMembershipId = input<string | null>(null);
  /** The patient's name for the accessible name only (never shown, stored or logged). */
  readonly patientLabel = input<string | null>(null);

  protected readonly visible = computed(() => {
    const doctor = this.doctorMembershipId();
    return doctor ? this.access.canOpenVisit(doctor) : this.access.canOpen();
  });
  protected readonly label = computed(() => {
    const who = this.patientLabel();
    return who ? `Open EMR for ${who}` : 'Open EMR';
  });

  ngOnInit(): void {
    this.access.ensureLoaded();
  }
}
