import { Injectable, computed, signal } from '@angular/core';
import { ALL_WARDS, AdmittedView, CardsPerRow, DEFAULT_COLS, PAGE_SIZE } from './admitted.model';

/**
 * What the user chose on the Admitted tab (SAH-36, OQ-36-5): view, cards per row, search text, ward
 * and how many cards are drawn. Kept in memory for the life of the IPD page only, so it survives a
 * 60-second reload and a change of tab; nothing goes to the server, the URL or the browser storage.
 * Changing the search, the ward or the view starts the list again at one page (AC8).
 */
@Injectable()
export class AdmittedViewState {
  readonly view = signal<AdmittedView>('cards');
  /** The person's own choice for this visit (SAH-36 AC1); null until they choose. */
  private readonly chosenCols = signal<CardsPerRow | null>(null);
  /** SAH-37. The clinic's published "Cards per row"; the grid opens with it. */
  readonly publishedCols = signal<CardsPerRow>(DEFAULT_COLS);
  readonly cols = computed<CardsPerRow>(() => this.chosenCols() ?? this.publishedCols());
  readonly query = signal('');
  readonly ward = signal(ALL_WARDS);
  readonly limit = signal(PAGE_SIZE);
  readonly filtering = computed(() => this.query().trim() !== '' || this.ward() !== ALL_WARDS);

  setView(view: AdmittedView): void {
    if (this.view() !== view) {
      this.view.set(view);
      this.limit.set(PAGE_SIZE);
    }
  }

  setCols(cols: CardsPerRow): void {
    this.chosenCols.set(cols);
  }

  /** The published setup of the latest list answer; the person's own choice still wins. */
  setPublishedCols(cols: CardsPerRow): void {
    this.publishedCols.set(cols);
  }

  setQuery(text: string): void {
    this.query.set(text);
    this.limit.set(PAGE_SIZE);
  }

  setWard(wardId: string): void {
    this.ward.set(wardId);
    this.limit.set(PAGE_SIZE);
  }

  /** "Show 24 more". */
  showMore(): void {
    this.limit.update((n) => n + PAGE_SIZE);
  }

  /** "Show all": stays "all" if the list grows at the next reload. */
  showAll(): void {
    this.limit.set(Number.POSITIVE_INFINITY);
  }
}
