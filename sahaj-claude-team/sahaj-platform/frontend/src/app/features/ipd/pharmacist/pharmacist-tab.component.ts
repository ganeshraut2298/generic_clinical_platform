import { ChangeDetectionStrategy, Component } from '@angular/core';
import { PharmacistPanelComponent } from './pharmacist-panel.component';
import { PharmacistTileComponent } from './pharmacist-tile.component';

/**
 * What a PHARMACIST sees under the Safety tab (SAH-31, ADR-009 D11, DEC-017): the tile "Pharmacist
 * checks" and the panel "Pharmacist check", nothing else. It never reads the admissions list or the
 * escalations board, which carry bills and escalations the role may not see.
 */
@Component({
  selector: 'app-pharmacist-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PharmacistTileComponent, PharmacistPanelComponent],
  styles: `
    :host {
      display: block;
    }
    .safety {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .kpis {
      grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
    }
  `,
  template: `
    <div class="safety" data-testid="pharmacist-tab">
      <section class="kpis" aria-label="Safety summary">
        <app-pharmacist-tile />
      </section>
      <app-pharmacist-panel />
    </div>
  `,
})
export class PharmacistTabComponent {}
