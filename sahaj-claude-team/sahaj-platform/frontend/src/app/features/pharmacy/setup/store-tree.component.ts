import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { IconComponent } from '../../../shared/icon/icon.component';
import { delegateEndedText, storeLine, storeTypeChip } from '../pharmacy-display.util';
import { Store } from '../pharmacy.model';

/**
 * Card "Stores in this unit" (mockup 16): the Central Store on top, every other store one level
 * below it with its type chip, "Issues from ..." and the number of staff. The selected store is
 * marked (and says so to screen readers). "+ Store" is the one primary button of the screen.
 */
@Component({
  selector: 'app-store-tree',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet, MatButtonModule, IconComponent],
  styleUrl: './store-tree.component.scss',
  template: `
    <section class="panel surf" aria-labelledby="tree-h" data-testid="store-tree">
      <div class="panel-h">
        <h2 id="tree-h">Stores in this unit</h2>
        @if (canAdd()) {
          <button
            mat-flat-button
            type="button"
            data-testid="add-store"
            aria-label="Add store"
            (click)="add.emit()"
          >
            <app-icon name="plus" [size]="14" /> Store
          </button>
        }
      </div>
      <div class="nodes">
        @if (central(); as c) {
          <ng-container *ngTemplateOutlet="node; context: { $implicit: c }" />
        }
        @if (others().length > 0) {
          <div class="kids">
            @for (s of others(); track s.id) {
              <ng-container *ngTemplateOutlet="node; context: { $implicit: s }" />
            }
          </div>
        }
      </div>
    </section>

    <ng-template #node let-s>
      <button
        type="button"
        class="node"
        [class.sel]="s.id === selectedId()"
        [attr.aria-current]="s.id === selectedId() ? 'true' : null"
        [attr.data-testid]="'store-' + s.id"
        (click)="picked.emit(s.id)"
      >
        <span class="top">
          <strong>{{ s.name }}</strong>
          <span class="pill" [class]="chip(s.type).css">{{ chip(s.type).text }}</span>
        </span>
        <span class="line">{{ line(s) }}</span>
        @if (ended(s); as text) {
          <span class="line warn" data-testid="delegate-ended">{{ text }}</span>
        }
      </button>
    </ng-template>
  `,
})
export class StoreTreeComponent {
  readonly stores = input.required<readonly Store[]>();
  readonly selectedId = input<string | null>(null);
  readonly canAdd = input(false);
  readonly picked = output<string>();
  readonly add = output<void>();

  protected readonly central = computed(() => this.stores().find((s) => s.type === 'CENTRAL') ?? null);
  protected readonly others = computed(() => this.stores().filter((s) => s.type !== 'CENTRAL'));

  protected readonly chip = storeTypeChip;
  protected readonly line = storeLine;
  protected readonly ended = delegateEndedText;
}
