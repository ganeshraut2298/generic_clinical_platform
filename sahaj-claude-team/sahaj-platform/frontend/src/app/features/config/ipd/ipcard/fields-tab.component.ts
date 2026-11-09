import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  computed,
  inject,
  signal,
} from '@angular/core';
import { IconComponent } from '../../../../shared/icon/icon.component';
import { hasControlOrBidi, stripEdges } from '../../../ipd/card/card-label.rules';
import { canMoveField, groupName, labelLocked } from '../../../ipd/card/card-setup.logic';
import {
  CardCatalogueField,
  CardFieldKey,
  CardFieldPlacement,
  CardSection,
  CardSectionKey,
} from '../../../ipd/card/card-setup.model';
import { DragItem, refocusMoved, startDrag } from './card-editor.helpers';
import { CardSetupStore } from './card-setup.store';

interface FieldRow {
  field: CardFieldPlacement;
  cat: CardCatalogueField;
  line: string;
  /** The label box cannot be edited (patient name and the safety fields keep the standard name). */
  labelLocked: boolean;
  canUp: boolean;
  canDown: boolean;
  /** Message under the label box (the server's, else the browser's once the box was left); null when none. */
  message: string | null;
}

interface FieldGroup {
  section: CardSection;
  name: string;
  rows: FieldRow[];
}

/**
 * Tab "Fields" (SAH-37 story 3; mockup `cfgIpCard`, tab fields): each section is a group of its
 * fields, each with a label box, a switch, drag and Move up / Move down. Patient name is "Required" and
 * fixed; UHID, Alerts, Early warning and Allergies are "Safety · always on" with their label fixed.
 */
@Component({
  selector: 'app-card-fields-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrls: ['./ipcard-editor.scss'],
  templateUrl: './fields-tab.component.html',
})
export class CardFieldsTabComponent {
  protected readonly store = inject(CardSetupStore);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly groups = computed<FieldGroup[]>(() => {
    const e = this.store.editor();
    const c = this.store.catalogue();
    const messages = this.store.labelMessages();
    if (!e || !c) {
      return [];
    }
    return e.sections.map((section) => ({
      section,
      name: c.sections.find((s) => s.key === section.key)?.name ?? section.key,
      rows: section.fields.map((field) => {
        const cat = c.fields.find((f) => f.key === field.key)!;
        return {
          field,
          cat,
          line: `${cat.name} · ${groupName(c, cat.group)}`,
          labelLocked: labelLocked(c, field.key),
          canUp: canMoveField(e, c, field.key, -1),
          canDown: canMoveField(e, c, field.key, 1),
          message: messages.get(field.key) ?? null,
        };
      }),
    }));
  });
  protected readonly maxLength = computed(() => this.store.catalogue()?.labelMaxLength ?? 40);

  private dragging: DragItem | null = null;
  protected readonly over = signal<string | null>(null);
  protected readonly dragged = signal<string | null>(null);

  protected move(key: CardFieldKey, direction: -1 | 1): void {
    this.store.moveFieldBy(key, direction);
    refocusMoved(this.injector, this.host.nativeElement, key, direction < 0 ? 'up' : 'down');
  }

  protected onLabel(key: CardFieldKey, event: Event): void {
    this.store.setLabel(key, (event.target as HTMLInputElement).value);
  }

  /**
   * When the box is left the label is stripped at both ends (an empty label keeps the standard name). A label
   * with a control or text-direction character is left as typed, so the message can tell the owner.
   */
  protected onLabelDone(key: CardFieldKey, event: Event): void {
    const box = event.target as HTMLInputElement;
    const done = hasControlOrBidi(box.value) ? box.value : stripEdges(box.value);
    box.value = done;
    this.store.setLabel(key, done);
  }

  protected onDragStart(event: DragEvent, key: CardFieldKey, row: HTMLElement): void {
    if (this.store.locked()) {
      event.preventDefault();
      return;
    }
    this.dragging = { type: 'field', key };
    this.dragged.set(key);
    startDrag(event, row);
  }

  protected onDragOverRow(event: DragEvent, key: CardFieldKey): void {
    if (this.dragging?.type === 'field') {
      event.preventDefault();
      event.stopPropagation();
      this.over.set(key);
    }
  }

  protected onDragOverSection(event: DragEvent, key: CardSectionKey): void {
    if (this.dragging?.type === 'field') {
      event.preventDefault();
      this.over.set(key);
    }
  }

  /** Dropped on a field: placed before it (never above the patient name). */
  protected onDropRow(event: DragEvent, section: CardSectionKey, targetKey: CardFieldKey): void {
    const d = this.dragging;
    this.onDragEnd();
    if (!d || d.type !== 'field') {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    const group = this.groups().find((g) => g.section.key === section);
    const index = group?.section.fields.findIndex((f) => f.key === targetKey) ?? -1;
    if (index >= 0) {
      this.store.moveField(d.key as CardFieldKey, section, index);
    }
  }

  /** Dropped on the section itself (also an empty one): placed at the end. */
  protected onDropSection(event: DragEvent, section: CardSectionKey): void {
    const d = this.dragging;
    this.onDragEnd();
    if (!d || d.type !== 'field') {
      return;
    }
    event.preventDefault();
    const group = this.groups().find((g) => g.section.key === section);
    this.store.moveField(d.key as CardFieldKey, section, group?.section.fields.length ?? 0);
  }

  protected onDragEnd(): void {
    this.dragging = null;
    this.over.set(null);
    this.dragged.set(null);
  }
}
