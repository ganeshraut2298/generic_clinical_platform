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
import { canMoveSection, fieldsOnText } from '../../../ipd/card/card-setup.logic';
import {
  COLS_OPTIONS,
  CardCatalogueSection,
  CardSection,
  CardSectionKey,
  LAYOUTS,
  LAYOUT_NAME,
  LAYOUT_TEXT,
} from '../../../ipd/card/card-setup.model';
import { DragItem, refocusMoved, startDrag } from './card-editor.helpers';
import { CardSetupStore } from './card-setup.store';

interface SectionRow {
  section: CardSection;
  cat: CardCatalogueSection;
  summary: string;
  canUp: boolean;
  canDown: boolean;
}

/**
 * Tab "Layout and sections" (SAH-37 story 2; mockup `cfgIpCard`, tab layout): templates, the three
 * layouts, Cards per row and the sections list with switches, drag and Move up / Move down.
 */
@Component({
  selector: 'app-card-layout-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrls: ['./ipcard-editor.scss'],
  templateUrl: './layout-tab.component.html',
})
export class CardLayoutTabComponent {
  protected readonly store = inject(CardSetupStore);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly layouts = LAYOUTS;
  protected readonly layoutName = LAYOUT_NAME;
  protected readonly layoutText = LAYOUT_TEXT;
  protected readonly colsOptions = COLS_OPTIONS;

  protected readonly rows = computed<SectionRow[]>(() => {
    const e = this.store.editor();
    const c = this.store.catalogue();
    if (!e || !c) {
      return [];
    }
    return e.sections.map((section) => ({
      section,
      cat: c.sections.find((s) => s.key === section.key)!,
      summary: fieldsOnText(section),
      canUp: canMoveSection(e, section.key, -1),
      canDown: canMoveSection(e, section.key, 1),
    }));
  });
  protected readonly header = computed(() => this.rows()[0]);
  protected readonly rest = computed(() => this.rows().slice(1));

  private dragging: DragItem | null = null;
  protected readonly over = signal<string | null>(null);
  protected readonly dragged = signal<string | null>(null);

  protected move(key: CardSectionKey, direction: -1 | 1): void {
    this.store.moveSectionBy(key, direction);
    refocusMoved(this.injector, this.host.nativeElement, key, direction < 0 ? 'up' : 'down');
  }

  protected onDragStart(event: DragEvent, key: CardSectionKey, row: HTMLElement): void {
    if (this.store.locked()) {
      event.preventDefault();
      return;
    }
    this.dragging = { type: 'sec', key };
    this.dragged.set(key);
    startDrag(event, row);
  }

  protected onDragOver(event: DragEvent, key: CardSectionKey): void {
    if (this.dragging?.type === 'sec' && key !== 'header') {
      event.preventDefault();
      this.over.set(key);
    }
  }

  protected onDrop(event: DragEvent, targetKey: CardSectionKey): void {
    const d = this.dragging;
    this.onDragEnd();
    if (!d || d.type !== 'sec' || targetKey === 'header') {
      return;
    }
    event.preventDefault();
    const index = this.store.editor()?.sections.findIndex((s) => s.key === targetKey) ?? -1;
    if (index > 0) {
      this.store.moveSection(d.key as CardSectionKey, index);
    }
  }

  protected onDragEnd(): void {
    this.dragging = null;
    this.over.set(null);
    this.dragged.set(null);
  }
}
