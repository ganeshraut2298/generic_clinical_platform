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
  readonly cols = signal<CardsPerRow>(DEFAULT_COLS);
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
    this.cols.set(cols);
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
