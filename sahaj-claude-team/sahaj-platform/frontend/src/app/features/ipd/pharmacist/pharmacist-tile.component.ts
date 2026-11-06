import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { PharmacistStore } from './pharmacist.store';
import { TILE_TEXT, TILE_TITLE } from './pharmacist.model';

/**
 * The tile "Pharmacist checks" of the Safety tab (mockup `ipSafety` tiles; SAH-31): the number of
 * orders waiting, in the warn colour above 0, and "Orders waiting before the first dose". The host
 * is `display: contents`, so the tile sits in the page's tile grid like the others.
 */
@Component({
  selector: 'app-pharmacist-tile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: contents;
    }
    .v.warn {
      color: var(--warn);
    }
  `,
  template: `
    <div class="kpi surf" data-kpi="pharmacist">
      <span class="eyebrow">{{ title }}</span>
      <span class="v num" [class.warn]="count() > 0" data-testid="pharmacist-waiting">{{ count() }}</span>
      <span class="s">{{ text }}</span>
    </div>
  `,
})
export class PharmacistTileComponent {
  private readonly store = inject(PharmacistStore);
  protected readonly title = TILE_TITLE;
  protected readonly text = TILE_TEXT;
  protected readonly count = computed(() => this.store.waiting() ?? 0);
}
