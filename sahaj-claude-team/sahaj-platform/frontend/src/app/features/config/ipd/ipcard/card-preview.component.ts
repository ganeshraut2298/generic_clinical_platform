import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { IconComponent } from '../../../../shared/icon/icon.component';
import { PatientCardComponent } from '../../../ipd/admitted/patient-card.component';
import { LAYOUT_PER_ROW, ROLE_NAME, CardRole } from '../../../ipd/card/card-setup.model';
import { PATIENTS_ERROR, CardSetupStore, Device } from './card-setup.store';

/**
 * The live preview (SAH-37 story 5; mockup `pcPreview`): the editor's setup drawn by the shared
 * patient card for a chosen role and patient, on desktop or tablet width. Inert: nothing opens, nothing
 * is saved. The role rule is applied to the owner's own answer using the catalogue the server sent.
 */
@Component({
  selector: 'app-card-preview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, PatientCardComponent],
  styleUrl: './card-preview.component.scss',
  templateUrl: './card-preview.component.html',
})
export class CardPreviewComponent {
  protected readonly store = inject(CardSetupStore);

  protected readonly patientsError = PATIENTS_ERROR;
  protected readonly roleName = ROLE_NAME;
  protected readonly devices: readonly { key: Device; label: string }[] = [
    { key: 'desktop', label: 'Desktop' },
    { key: 'tablet', label: 'Tablet' },
  ];
  protected readonly roles = computed(() => this.store.catalogue()?.viewAsRoles ?? []);
  protected readonly note = computed(() => {
    if (this.store.device() === 'tablet') {
      return 'Tablet: cards sit two to a row at 768 px width. Buttons stay at least 44 px tall.';
    }
    const layout = this.store.editor()?.layout ?? 'standard';
    return `Desktop: cards fill the width, ${LAYOUT_PER_ROW[layout]} to a row on a 1440 px screen.`;
  });
  protected readonly selectedId = computed(() => this.store.previewRow().id);

  protected onRole(event: Event): void {
    this.store.setRole((event.target as HTMLSelectElement).value as CardRole);
  }

  protected onPatient(event: Event): void {
    this.store.setPatient((event.target as HTMLSelectElement).value);
  }
}
