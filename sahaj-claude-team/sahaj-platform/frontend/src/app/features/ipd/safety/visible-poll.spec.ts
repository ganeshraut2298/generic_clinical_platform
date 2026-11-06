import { DestroyRef } from '@angular/core';
import { startVisiblePoll } from './visible-poll';

describe('startVisiblePoll', () => {
  let hidden: boolean;
  let doc: Document;
  let destroy: () => void;
  const destroyRef = { onDestroy: (fn: () => void) => (destroy = fn) } as unknown as DestroyRef;

  beforeEach(() => {
    vi.useFakeTimers();
    hidden = false;
    doc = document.implementation.createHTMLDocument('t');
    Object.defineProperty(doc, 'hidden', { configurable: true, get: () => hidden });
  });
  afterEach(() => vi.useRealTimers());

  const change = (nowHidden: boolean) => {
    hidden = nowHidden;
    doc.dispatchEvent(new Event('visibilitychange'));
  };

  it('ticks every interval while the tab is visible', () => {
    const tick = vi.fn();
    startVisiblePoll(doc, 1000, tick, destroyRef);
    vi.advanceTimersByTime(3000);
    expect(tick).toHaveBeenCalledTimes(3);
  });

  it('skips the ticks while hidden and ticks once at once when visible again', () => {
    const tick = vi.fn();
    startVisiblePoll(doc, 1000, tick, destroyRef);
    change(true);
    vi.advanceTimersByTime(5000);
    expect(tick).not.toHaveBeenCalled();
    change(false);
    expect(tick).toHaveBeenCalledTimes(1);
    change(false);
    expect(tick).toHaveBeenCalledTimes(1);
  });

  it('does not tick on showing again when no tick was skipped', () => {
    const tick = vi.fn();
    startVisiblePoll(doc, 1000, tick, destroyRef);
    change(true);
    vi.advanceTimersByTime(500);
    change(false);
    expect(tick).not.toHaveBeenCalled();
  });

  it('stops on destroy', () => {
    const tick = vi.fn();
    startVisiblePoll(doc, 1000, tick, destroyRef);
    destroy();
    vi.advanceTimersByTime(3000);
    change(true);
    vi.advanceTimersByTime(3000);
    change(false);
    expect(tick).not.toHaveBeenCalled();
  });
});
