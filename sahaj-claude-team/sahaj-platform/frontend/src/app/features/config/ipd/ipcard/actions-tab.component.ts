import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  computed,
  inject,
  signal,
} from '@angular/core';
import { IconComponent, IconName } from '../../../../shared/icon/icon.component';
import { canMoveAction } from '../../../ipd/card/card-setup.logic';
import {
  CardActionKey,
  CardActionPlacement,
  CardCatalogueAction,
  ROLE_SHORT,
} from '../../../ipd/card/card-setup.model';
import { DragItem, refocusMoved, startDrag } from './card-editor.helpers';
import { CardSetupStore } from './card-setup.store';

interface ActionRow {
  action: CardActionPlacement;
  cat: CardCatalogueAction;
  icon: IconName;
  /** "Owner, Nurse, Doctor": the fixed rule, read from the catalogue (no "Change who can use each action"). */
  who: string;
  canUp: boolean;
  canDown: boolean;
}

/**
 * Tab "Quick actions" (SAH-37 story 4; mockup `cfgIpCard`, tab actions): order and on/off for Nursing,
 * EMR, Transfer, Discharge and Deposit. The "who" line is the server's fixed rule, read only.
 */
@Component({
  selector: 'app-card-actions-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrls: ['./ipcard-editor.scss'],
  templateUrl: './actions-tab.component.html',
})
export class CardActionsTabComponent {
  protected readonly store = inject(CardSetupStore);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly rows = computed<ActionRow[]>(() => {
    const e = this.store.editor();
    const c = this.store.catalogue();
    if (!e || !c) {
      return [];
    }
    return e.actions.map((action) => {
      const cat = c.actions.find((a) => a.key === action.key)!;
      return {
        action,
        cat,
        icon: cat.icon,
        who: cat.usableBy.map((r) => ROLE_SHORT[r]).join(', '),
        canUp: canMoveAction(e, action.key, -1),
        canDown: canMoveAction(e, action.key, 1),
      };
    });
  });

  private dragging: DragItem | null = null;
  protected readonly over = signal<string | null>(null);
  protected readonly dragged = signal<string | null>(null);

  protected move(key: CardActionKey, direction: -1 | 1): void {
    this.store.moveActionBy(key, direction);
    refocusMoved(this.injector, this.host.nativeElement, key, direction < 0 ? 'up' : 'down');
  }

  protected onDragStart(event: DragEvent, key: CardActionKey, row: HTMLElement): void {
    if (this.store.locked()) {
      event.preventDefault();
      return;
    }
    this.dragging = { type: 'act', key };
    this.dragged.set(key);
    startDrag(event, row);
  }

  protected onDragOver(event: DragEvent, key: CardActionKey): void {
    if (this.dragging?.type === 'act') {
      event.preventDefault();
      this.over.set(key);
    }
  }

  protected onDrop(event: DragEvent, targetKey: CardActionKey): void {
    const d = this.dragging;
    this.onDragEnd();
    if (!d || d.type !== 'act') {
      return;
    }
    event.preventDefault();
    const index = this.store.editor()?.actions.findIndex((a) => a.key === targetKey) ?? -1;
    if (index >= 0) {
      this.store.moveAction(d.key as CardActionKey, index);
    }
  }

  protected onDragEnd(): void {
    this.dragging = null;
    this.over.set(null);
    this.dragged.set(null);
  }
}
