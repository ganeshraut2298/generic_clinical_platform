import { Signal, effect, inject, untracked } from '@angular/core';
import { ReducedMotion } from './reduced-motion';

export interface SceneHooks {
  /** Start the animation. Runs only while the scene is in view and motion is allowed. */
  play(): void;
  /** Show the final state. Runs when the scene is out of view, or motion is reduced. */
  settle(): void;
  /** Stop timers. Runs before every change of state and when the component is destroyed. */
  stop(): void;
}

/**
 * Runs an animated scene only while it is in view, and never when the visitor prefers
 * reduced motion (then the final state shows). Call it from a constructor.
 */
export function runScene(inView: Signal<boolean>, hooks: SceneHooks): void {
  const motion = inject(ReducedMotion);
  effect((onCleanup) => {
    const active = inView() && !motion.reduced();
    untracked(() => (active ? hooks.play() : hooks.settle()));
    onCleanup(() => untracked(() => hooks.stop()));
  });
}
