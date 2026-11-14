import { TestBed } from '@angular/core/testing';
import { AUTOSAVE_MS, EmrAutosave } from './emr-autosave.service';
import { EmrRecordStore } from './emr-record.store';

describe('EmrAutosave (SAH-51-3)', () => {
  let tick: ReturnType<typeof vi.fn>;
  let autosave: EmrAutosave;

  beforeEach(() => {
    vi.useFakeTimers();
    tick = vi.fn();
    TestBed.configureTestingModule({
      providers: [{ provide: EmrRecordStore, useValue: { autosaveTick: tick } }, EmrAutosave],
    });
    autosave = TestBed.inject(EmrAutosave);
  });

  afterEach(() => {
    autosave.stop();
    vi.useRealTimers();
  });

  it('ticks every 30 seconds', () => {
    expect(AUTOSAVE_MS).toBe(30_000);
    autosave.start();
    vi.advanceTimersByTime(29_999);
    expect(tick).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(tick).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(60_000);
    expect(tick).toHaveBeenCalledTimes(3);
  });

  it('restarts the clock after a manual save', () => {
    autosave.start();
    vi.advanceTimersByTime(20_000);
    autosave.restart();
    vi.advanceTimersByTime(20_000);
    expect(tick).not.toHaveBeenCalled();
    vi.advanceTimersByTime(10_000);
    expect(tick).toHaveBeenCalledTimes(1);
  });

  it('saves at once when the connection comes back', () => {
    autosave.start();
    globalThis.dispatchEvent(new Event('online'));
    expect(tick).toHaveBeenCalledTimes(1);
  });

  it('stops ticking when stopped, and does not restart a stopped clock', () => {
    autosave.start();
    autosave.stop();
    vi.advanceTimersByTime(120_000);
    expect(tick).not.toHaveBeenCalled();
    autosave.restart();
    vi.advanceTimersByTime(120_000);
    expect(tick).not.toHaveBeenCalled();
  });

  it('keeps nothing in browser storage', () => {
    const before = [localStorage.length, sessionStorage.length];
    autosave.start();
    vi.advanceTimersByTime(AUTOSAVE_MS * 3);
    expect([localStorage.length, sessionStorage.length]).toEqual(before);
  });
});
