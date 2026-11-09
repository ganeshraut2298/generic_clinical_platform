import { Injector, afterNextRender } from '@angular/core';

/** The row being dragged in an editor list (HTML5 drag and drop, mouse only; AC24). */
export interface DragItem {
  type: 'sec' | 'field' | 'act';
  key: string;
}

/** Starts a drag from the grip; the whole row is the drag picture. */
export function startDrag(event: DragEvent, row: HTMLElement | null): void {
  const transfer = event.dataTransfer;
  if (!transfer) {
    return;
  }
  transfer.effectAllowed = 'move';
  try {
    transfer.setData('text/plain', '');
    if (row) {
      transfer.setDragImage?.(row, 12, 12);
    }
  } catch {
    // Some browsers refuse setData outside a user drag; the move still works from the component state.
  }
}

/**
 * After Move up or Move down the moved row keeps the keyboard focus (AC24, AC36): the same button when
 * it is still enabled, else the other one. Runs after the list has been drawn again.
 */
export function refocusMoved(
  injector: Injector,
  host: HTMLElement,
  rowKey: string,
  preferred: 'up' | 'down',
): void {
  afterNextRender(
    () => {
      const row = host.querySelector<HTMLElement>(`[data-row="${rowKey}"]`);
      const pick = (dir: string) =>
        row?.querySelector<HTMLButtonElement>(`[data-move="${dir}"]:not([disabled])`);
      (pick(preferred) ?? pick(preferred === 'up' ? 'down' : 'up'))?.focus();
    },
    { injector },
  );
}
