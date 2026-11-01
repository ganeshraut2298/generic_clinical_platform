import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';
import { ReducedMotion } from '../motion/reduced-motion';

/** Scrolls to a section by id and moves keyboard focus to it. */
@Injectable({ providedIn: 'root' })
export class ScrollTo {
  private readonly document = inject(DOCUMENT);
  private readonly motion = inject(ReducedMotion);

  section(id: string): void {
    const target = this.document.getElementById(id);
    if (!target) {
      return;
    }
    target.scrollIntoView?.({ behavior: this.motion.reduced() ? 'auto' : 'smooth' });
    target.focus?.({ preventScroll: true });
  }
}
