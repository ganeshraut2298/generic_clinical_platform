import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';

/** Tells components whether the visitor prefers reduced motion. Updates if the setting changes. */
@Injectable({ providedIn: 'root' })
export class ReducedMotion {
  private readonly win = inject(DOCUMENT).defaultView;
  private readonly query =
    this.win && typeof this.win.matchMedia === 'function'
      ? this.win.matchMedia('(prefers-reduced-motion: reduce)')
      : null;

  readonly reduced = signal(this.query?.matches ?? false);

  constructor() {
    const query = this.query;
    if (query) {
      const onChange = (event: MediaQueryListEvent) => this.reduced.set(event.matches);
      query.addEventListener('change', onChange);
      inject(DestroyRef).onDestroy(() => query.removeEventListener('change', onChange));
    }
  }
}
