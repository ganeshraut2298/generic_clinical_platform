import { DestroyRef } from '@angular/core';

/**
 * A timer for the Safety refresh and the page's counts poll (SAH-33, security review L4): the tick
 * is skipped while the tab is hidden, so an unattended ward screen does not keep the session alive
 * or read patient data nobody sees. When the tab shows again after a skipped tick, one immediate
 * tick follows. Stops when `destroyRef` is destroyed.
 */
export function startVisiblePoll(
  doc: Document,
  everyMs: number,
  tick: () => void,
  destroyRef: DestroyRef,
): void {
  let missed = false;
  const timer = setInterval(() => {
    if (doc.hidden) {
      missed = true;
      return;
    }
    tick();
  }, everyMs);
  const onVisible = (): void => {
    if (!doc.hidden && missed) {
      missed = false;
      tick();
    }
  };
  doc.addEventListener('visibilitychange', onVisible);
  destroyRef.onDestroy(() => {
    clearInterval(timer);
    doc.removeEventListener('visibilitychange', onVisible);
  });
}
