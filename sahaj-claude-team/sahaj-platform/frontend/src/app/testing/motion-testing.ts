import { Provider, signal } from '@angular/core';
import { ReducedMotion } from '../core/motion/reduced-motion';

/** Test helper: sets whether the visitor prefers reduced motion. */
export function provideReducedMotion(reduced: boolean): Provider {
  return { provide: ReducedMotion, useValue: { reduced: signal(reduced) } };
}
