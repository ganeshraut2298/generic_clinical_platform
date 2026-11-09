import { ApplicationRef } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { KINETIC_DEFAULTS, TOKEN_FALLBACKS } from './kinetic-background.config';
import { KineticBackgroundComponent, isSurfaceTarget } from './kinetic-background.component';
import { buildGrid, nodeOffset } from './kinetic-physics';

const win = document.defaultView as Window & typeof globalThis;
const REDUCED = '(prefers-reduced-motion: reduce)';
const FINE = '(pointer: fine)';

interface Draw {
  style: string;
  alpha: number;
  circles: { x: number; y: number; r: number }[];
}

/** A canvas context that records what was drawn since the last clear. */
class FakeContext {
  fillStyle = '#000';
  globalAlpha = 1;
  draws: Draw[] = [];
  clears: number[][] = [];
  clips: number[][] = [];
  transforms: number[][] = [];
  private path: { x: number; y: number; r: number }[] = [];
  save = vi.fn();
  restore = vi.fn();
  clip = vi.fn();
  moveTo = vi.fn();
  beginPath = () => {
    this.path = [];
  };
  rect = (...args: number[]) => {
    this.clips.push(args);
  };
  clearRect = (...args: number[]) => {
    this.clears.push(args);
    this.draws = [];
  };
  setTransform = (...args: number[]) => {
    this.transforms.push(args);
  };
  arc = (x: number, y: number, r: number) => {
    this.path.push({ x, y, r });
  };
  fill = () => {
    this.draws.push({ style: this.fillStyle, alpha: this.globalAlpha, circles: [...this.path] });
  };
  circles(): { x: number; y: number; r: number; style: string; alpha: number }[] {
    return this.draws.flatMap((d) => d.circles.map((c) => ({ ...c, style: d.style, alpha: d.alpha })));
  }
}

class FakeMql {
  listeners = new Set<(event: { matches: boolean }) => void>();
  addEventListener = vi.fn((_type: string, listener: (event: { matches: boolean }) => void) => {
    this.listeners.add(listener);
  });
  removeEventListener = vi.fn((_type: string, listener: (event: { matches: boolean }) => void) => {
    this.listeners.delete(listener);
  });
  constructor(public matches: boolean) {}
  change(matches: boolean): void {
    this.matches = matches;
    for (const listener of Array.from(this.listeners)) {
      listener({ matches });
    }
  }
}

class FakeResizeObserver {
  static instances: FakeResizeObserver[] = [];
  observed = new Set<Element>();
  observe = vi.fn((element: Element) => this.observed.add(element));
  unobserve = vi.fn((element: Element) => this.observed.delete(element));
  disconnect = vi.fn(() => this.observed.clear());
  constructor(public callback: () => void) {
    FakeResizeObserver.instances.push(this);
  }
  fire(): void {
    this.callback();
  }
}

interface Harness {
  fixture: ComponentFixture<KineticBackgroundComponent>;
  host: HTMLElement;
  ctx: FakeContext;
  reduced: FakeMql;
  fine: FakeMql;
  frames: () => number;
  runFrames: (max?: number) => number;
  move: (x: number, y: number, target?: EventTarget) => void;
  tokens: Record<string, string>;
}

describe('KineticBackgroundComponent', () => {
  let ctx: FakeContext;
  let reduced: FakeMql;
  let fine: FakeMql;
  let queue: Map<number, () => void>;
  let nextId: number;
  let rafCalls: number;
  let cancelCalls: number[];
  let tokens: Record<string, string>;
  let getContext: ReturnType<typeof vi.fn>;
  const restore: (() => void)[] = [];

  function define(target: object, name: string, value: unknown): void {
    const original = Object.getOwnPropertyDescriptor(target, name);
    Object.defineProperty(target, name, { configurable: true, writable: true, value });
    restore.push(() => {
      if (original) {
        Object.defineProperty(target, name, original);
      } else {
        delete (target as Record<string, unknown>)[name];
      }
    });
  }

  beforeEach(() => {
    ctx = new FakeContext();
    reduced = new FakeMql(false);
    fine = new FakeMql(true);
    queue = new Map();
    nextId = 1;
    rafCalls = 0;
    cancelCalls = [];
    tokens = {};
    FakeResizeObserver.instances = [];

    define(win, 'matchMedia', (query: string) => (query === REDUCED ? reduced : query === FINE ? fine : new FakeMql(false)));
    // Angular's own scheduler also uses requestAnimationFrame, so only the component's frame is counted.
    const originalRaf = win.requestAnimationFrame.bind(win);
    const originalCancel = win.cancelAnimationFrame.bind(win);
    define(win, 'requestAnimationFrame', (callback: () => void) => {
      if (callback.name !== 'frame') {
        return originalRaf(callback);
      }
      rafCalls++;
      const id = nextId++;
      queue.set(id, callback);
      return id;
    });
    define(win, 'cancelAnimationFrame', (id: number) => {
      if (!queue.has(id)) {
        originalCancel(id);
        return;
      }
      cancelCalls.push(id);
      queue.delete(id);
    });
    define(win, 'ResizeObserver', FakeResizeObserver);
    define(win, 'devicePixelRatio', 1);
    define(win, 'innerWidth', 1440);
    define(win, 'innerHeight', 900);
    define(
      win,
      'getComputedStyle',
      () => ({ getPropertyValue: (name: string) => tokens[name] ?? '' }) as unknown as CSSStyleDeclaration,
    );
    getContext = vi.fn(() => ctx as unknown as CanvasRenderingContext2D);
    define(HTMLCanvasElement.prototype, 'getContext', getContext);
    document.documentElement.removeAttribute('data-theme');
  });

  afterEach(() => {
    while (restore.length) {
      restore.pop()!();
    }
    document.querySelectorAll('[data-test-node]').forEach((n) => n.remove());
    document.documentElement.removeAttribute('data-theme');
    vi.restoreAllMocks();
  });

  async function create(inputs: Record<string, unknown> = {}): Promise<Harness> {
    const fixture = TestBed.createComponent(KineticBackgroundComponent);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const runFrames = (max = 600): number => {
      let count = 0;
      while (queue.size > 0 && count < max) {
        const [id, callback] = queue.entries().next().value as [number, () => void];
        queue.delete(id);
        callback();
        count++;
      }
      return count;
    };
    const move = (x: number, y: number, target: EventTarget = win): void => {
      const event = new MouseEvent('pointermove', { clientX: x, clientY: y, bubbles: true });
      target.dispatchEvent(event);
    };
    return { fixture, host, ctx, reduced, fine, frames: () => queue.size, runFrames, move, tokens };
  }

  const state = (h: Harness) => h.host.getAttribute('data-state');

  /** The grid dot nearest the middle of the viewport, away from any other element. */
  function gridDot(index: number): { x: number; y: number } {
    const grid = buildGrid(1440, 900, 28);
    return { x: grid.xs[index], y: grid.ys[index] };
  }
  const MIDDLE = 52 * 16 + 26;

  describe('defaults and markup', () => {
    it('declares inputs with the spec defaults', async () => {
      const h = await create();
      const c = h.fixture.componentInstance;
      expect(c.interactionRadius()).toBe(90);
      expect(c.gridSpacing()).toBe(28);
      expect(c.friction()).toBe(0.75);
      expect(c.enabled()).toBe(true);
      expect(KINETIC_DEFAULTS.dotRadius).toBe(1.6);
      expect(KINETIC_DEFAULTS.nodeRadius).toBe(1.25);
      expect(KINETIC_DEFAULTS.nodeTravel).toBe(11);
      expect(KINETIC_DEFAULTS.dprCap).toBe(2);
    });

    it('renders one canvas, hidden from assistive tools and never focusable', async () => {
      const h = await create();
      const canvases = h.host.querySelectorAll('canvas');
      expect(canvases.length).toBe(1);
      expect(canvases[0].getAttribute('aria-hidden')).toBe('true');
      expect(h.host.getAttribute('aria-hidden')).toBe('true');
      expect(h.host.hasAttribute('tabindex')).toBe(false);
      expect(canvases[0].hasAttribute('tabindex')).toBe(false);
    });
  });

  describe('modes and data-state', () => {
    it('is idle on a desktop with a mouse and runs no frame after the first draw', async () => {
      const h = await create();
      expect(state(h)).toBe('idle');
      expect(rafCalls).toBe(0);
      expect(h.ctx.circles().length).toBe(buildGrid(1440, 900, 28).xs.length);
    });

    it('is still when reduced motion is on: grid only, no pointer listeners, no frames', async () => {
      reduced.matches = true;
      const add = vi.spyOn(win, 'addEventListener');
      const h = await create();
      expect(state(h)).toBe('still');
      expect(h.ctx.circles().length).toBeGreaterThan(0);
      const types = add.mock.calls.map((c) => c[0]);
      expect(types).not.toContain('pointermove');
      expect(types).not.toContain('scroll');
      h.move(100, 100);
      win.dispatchEvent(new Event('scroll'));
      expect(rafCalls).toBe(0);
      expect(h.ctx.circles().every((c) => c.r === 1.6)).toBe(true);
    });

    it('is still when the primary pointer is not fine (touch device)', async () => {
      fine.matches = false;
      const h = await create();
      expect(state(h)).toBe('still');
      h.move(100, 100);
      expect(rafCalls).toBe(0);
    });

    it('is still when matchMedia does not exist, and does not throw', async () => {
      define(win, 'matchMedia', undefined);
      const h = await create();
      expect(state(h)).toBe('still');
    });

    it('is off in the dark theme and comes back when the theme returns to light', async () => {
      document.documentElement.setAttribute('data-theme', 'dark');
      const add = vi.spyOn(win, 'addEventListener');
      const h = await create();
      expect(state(h)).toBe('off');
      expect(h.ctx.draws.length).toBe(0);
      expect(add.mock.calls.map((c) => c[0])).not.toContain('pointermove');
      document.documentElement.setAttribute('data-theme', 'light');
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(state(h)).toBe('idle');
      expect(h.ctx.draws.length).toBeGreaterThan(0);
    });

    it('with enabled false: no canvas, no listener, no observer, no frame, state off', async () => {
      const addWin = vi.spyOn(win, 'addEventListener');
      const h = await create({ enabled: false });
      expect(h.host.querySelector('canvas')).toBeNull();
      expect(state(h)).toBe('off');
      expect(getContext).not.toHaveBeenCalled();
      expect(addWin).not.toHaveBeenCalled();
      expect(reduced.addEventListener).not.toHaveBeenCalled();
      expect(FakeResizeObserver.instances.length).toBe(0);
      expect(rafCalls).toBe(0);
    });

    it('switches on without a reload when enabled turns true', async () => {
      const h = await create({ enabled: false });
      h.fixture.componentRef.setInput('enabled', true);
      h.fixture.detectChanges();
      await h.fixture.whenStable();
      expect(h.host.querySelector('canvas')).not.toBeNull();
      expect(state(h)).toBe('idle');
    });

    it('does nothing and does not throw when the 2D context is null', async () => {
      getContext.mockReturnValue(null);
      const add = vi.spyOn(win, 'addEventListener');
      const h = await create();
      h.fixture.detectChanges();
      expect(state(h)).toBe('off');
      expect(h.host.querySelector('canvas')).toBeNull();
      expect(add).not.toHaveBeenCalled();
    });

    it('does nothing and does not throw when getContext throws', async () => {
      getContext.mockImplementation(() => {
        throw new Error('blocked');
      });
      const h = await create();
      expect(state(h)).toBe('off');
      expect(h.host.querySelector('canvas')).toBeNull();
    });

    it('goes quiet and off if drawing throws, without logging', async () => {
      const log = vi.spyOn(console, 'error').mockImplementation(() => undefined);
      const h = await create();
      h.ctx.clearRect = () => {
        throw new Error('boom');
      };
      h.move(300, 300);
      h.runFrames();
      expect(state(h)).toBe('off');
      expect(log).not.toHaveBeenCalled();
    });

    it('switches to still and back when the media queries change, adding and removing listeners', async () => {
      const add = vi.spyOn(win, 'addEventListener');
      const remove = vi.spyOn(win, 'removeEventListener');
      const h = await create();
      expect(add.mock.calls.map((c) => c[0])).toContain('pointermove');

      reduced.change(true);
      expect(state(h)).toBe('still');
      expect(remove.mock.calls.map((c) => c[0])).toContain('pointermove');
      expect(remove.mock.calls.map((c) => c[0])).toContain('scroll');
      const before = rafCalls;
      h.move(200, 200);
      expect(rafCalls).toBe(before);

      reduced.change(false);
      expect(state(h)).toBe('idle');
      h.move(200, 200);
      expect(rafCalls).toBe(before + 1);
      expect(state(h)).toBe('running');

      fine.change(false);
      expect(state(h)).toBe('still');
    });

    it('clears any split dots when it switches to still', async () => {
      const h = await create();
      const dot = gridDot(MIDDLE);
      h.move(dot.x, dot.y);
      h.runFrames();
      expect(h.ctx.circles().some((c) => c.style === TOKEN_FALLBACKS.nodeA)).toBe(true);
      reduced.change(true);
      expect(h.ctx.circles().some((c) => c.style === TOKEN_FALLBACKS.nodeA)).toBe(false);
      expect(h.ctx.circles().length).toBe(buildGrid(1440, 900, 28).xs.length);
    });
  });

  describe('pointer effect', () => {
    it('splits the dot under the pointer into four nodes 11 px away on the diagonals', async () => {
      const h = await create();
      const dot = gridDot(MIDDLE);
      h.move(dot.x, dot.y);
      expect(state(h)).toBe('running');
      h.runFrames();
      expect(state(h)).toBe('idle');
      const { axis, radius } = nodeOffset(1);
      const circles = h.ctx.circles();
      const find = (dx: number, dy: number) =>
        circles.find((c) => Math.abs(c.x - (dot.x + dx)) < 1e-6 && Math.abs(c.y - (dot.y + dy)) < 1e-6 && c.r > 0.5 && c.r < 1.5);
      expect(find(-axis, -axis)?.style).toBe(TOKEN_FALLBACKS.nodeA);
      expect(find(axis, -axis)?.style).toBe(TOKEN_FALLBACKS.nodeB);
      expect(find(-axis, axis)?.style).toBe(TOKEN_FALLBACKS.nodeC);
      expect(find(axis, axis)?.style).toBe(TOKEN_FALLBACKS.nodeD);
      expect(find(-axis, -axis)?.r).toBeCloseTo(radius, 6);
      expect(find(-axis, -axis)?.alpha).toBeCloseTo(1, 6);
      // The dot itself is gone.
      expect(circles.find((c) => c.x === dot.x && c.y === dot.y && c.r === 1.6)).toBeUndefined();
    });

    it('splits half way at 45 px: nodes 5.5 px away', async () => {
      const h = await create();
      const dot = gridDot(MIDDLE);
      // Move away along x; the neighbours at 28 px get their own split, the centre dot gets 0.5.
      h.move(dot.x + 45, dot.y);
      h.runFrames();
      const { axis, radius } = nodeOffset(0.5);
      const hit = h.ctx
        .circles()
        .find((c) => Math.abs(c.x - (dot.x - axis)) < 1e-6 && Math.abs(c.y - (dot.y - axis)) < 1e-6 && Math.abs(c.r - radius) < 1e-6);
      expect(hit?.style).toBe(TOKEN_FALLBACKS.nodeA);
    });

    it('does not touch a dot 95 px away', async () => {
      const h = await create();
      const dot = gridDot(MIDDLE);
      h.move(dot.x + 95, dot.y);
      h.runFrames();
      const circles = h.ctx.circles();
      expect(circles.some((c) => c.x === dot.x && c.y === dot.y && c.r === 1.6 && c.alpha === 1)).toBe(true);
      expect(circles.some((c) => Math.abs(c.x - dot.x) < 12 && Math.abs(c.y - dot.y) < 12 && c.r !== 1.6)).toBe(false);
    });

    it('treats a radius above 90 as 90, uses 60, and falls back to 90 for zero', async () => {
      const probe = async (radius: number, gap: number) => {
        const h = await create({ interactionRadius: radius });
        const dot = gridDot(MIDDLE);
        h.move(dot.x + gap, dot.y);
        h.runFrames();
        const split = h.ctx.circles().some((c) => Math.abs(c.x - dot.x) < 12 && Math.abs(c.y - dot.y) < 12 && c.r !== 1.6 && c.r > 0);
        h.fixture.destroy();
        return split;
      };
      expect(await probe(150, 95)).toBe(false);
      expect(await probe(150, 85)).toBe(true);
      expect(await probe(60, 70)).toBe(false);
      expect(await probe(60, 50)).toBe(true);
      expect(await probe(0, 85)).toBe(true);
      expect(await probe(-3, 95)).toBe(false);
    });

    it('returns to exactly the original dot when the pointer leaves the window', async () => {
      const h = await create();
      const dot = gridDot(MIDDLE);
      h.move(dot.x, dot.y);
      h.runFrames();
      win.dispatchEvent(new MouseEvent('pointerout', { relatedTarget: null }));
      expect(state(h)).toBe('running');
      h.runFrames();
      expect(state(h)).toBe('idle');
      const circles = h.ctx.circles();
      expect(circles.filter((c) => c.r !== 1.6).length).toBe(0);
      expect(circles.some((c) => c.x === dot.x && c.y === dot.y && c.r === 1.6 && c.alpha === 1 && c.style === TOKEN_FALLBACKS.dot)).toBe(true);
    });

    it('also returns when the window loses focus', async () => {
      const h = await create();
      const dot = gridDot(MIDDLE);
      h.move(dot.x, dot.y);
      h.runFrames();
      win.dispatchEvent(new Event('blur'));
      h.runFrames();
      expect(h.ctx.circles().filter((c) => c.r !== 1.6).length).toBe(0);
    });

    it('ignores pointerout between two elements (the pointer is still in the window)', async () => {
      const h = await create();
      const dot = gridDot(MIDDLE);
      h.move(dot.x, dot.y);
      h.runFrames();
      win.dispatchEvent(new MouseEvent('pointerout', { relatedTarget: document.body }));
      expect(h.frames()).toBe(0);
    });

    it('ignores touch pointers', async () => {
      await create();
      const event = new MouseEvent('pointermove', { clientX: 300, clientY: 300 });
      Object.defineProperty(event, 'pointerType', { value: 'touch' });
      win.dispatchEvent(event);
      expect(rafCalls).toBe(0);
    });

    it('does not split dots while the pointer is over a data-surface block', async () => {
      const h = await create();
      const card = document.createElement('div');
      card.setAttribute('data-surface', '');
      card.setAttribute('data-test-node', '');
      const inner = document.createElement('span');
      card.appendChild(inner);
      document.body.appendChild(card);
      const dot = gridDot(MIDDLE);
      h.move(dot.x, dot.y, inner);
      h.runFrames();
      expect(h.ctx.circles().filter((c) => c.r !== 1.6).length).toBe(0);
      expect(state(h)).toBe('idle');
    });

    it('lets split dots return when the pointer moves onto a surface, and splits again on open background', async () => {
      const h = await create();
      const card = document.createElement('div');
      card.setAttribute('data-surface', '');
      card.setAttribute('data-test-node', '');
      document.body.appendChild(card);
      const dot = gridDot(MIDDLE);
      h.move(dot.x, dot.y);
      h.runFrames();
      expect(h.ctx.circles().some((c) => c.style === TOKEN_FALLBACKS.nodeA)).toBe(true);
      h.move(dot.x, dot.y, card);
      h.runFrames();
      expect(h.ctx.circles().filter((c) => c.r !== 1.6).length).toBe(0);
      h.move(dot.x, dot.y);
      h.runFrames();
      expect(h.ctx.circles().some((c) => c.style === TOKEN_FALLBACKS.nodeA)).toBe(true);
    });

    it('re-checks the element under the pointer after a scroll', async () => {
      const h = await create();
      const card = document.createElement('div');
      card.setAttribute('data-surface', '');
      card.setAttribute('data-test-node', '');
      document.body.appendChild(card);
      define(document, 'elementFromPoint', () => card);
      const dot = gridDot(MIDDLE);
      h.move(dot.x, dot.y);
      h.runFrames();
      expect(h.ctx.circles().some((c) => c.style === TOKEN_FALLBACKS.nodeA)).toBe(true);
      win.dispatchEvent(new Event('scroll'));
      h.runFrames();
      expect(h.ctx.circles().filter((c) => c.r !== 1.6).length).toBe(0);
    });

    it('a changed friction changes how many frames the settling takes', async () => {
      const frames = async (friction: number) => {
        const h = await create({ friction });
        const dot = gridDot(MIDDLE);
        h.move(dot.x, dot.y);
        const n = h.runFrames();
        h.fixture.destroy();
        return n;
      };
      expect(await frames(0.5)).not.toBe(await frames(0.75));
    });
  });

  describe('idle loop (no frame when nothing moves)', () => {
    it('stops requesting frames when the last dot has settled', async () => {
      const h = await create();
      const dot = gridDot(MIDDLE);
      h.move(dot.x, dot.y);
      const used = h.runFrames();
      expect(used).toBeGreaterThan(3);
      expect(used).toBeLessThan(600);
      expect(h.frames()).toBe(0);
      expect(state(h)).toBe('idle');
    });

    it('restarts on a pointer move, a scroll and a window resize', async () => {
      const h = await create();
      h.move(10, 10);
      h.runFrames();
      expect(h.frames()).toBe(0);

      win.dispatchEvent(new Event('scroll'));
      expect(h.frames()).toBe(1);
      h.runFrames();

      win.dispatchEvent(new Event('resize'));
      expect(h.frames()).toBe(1);
      h.runFrames();

      h.move(20, 20);
      expect(h.frames()).toBe(1);
    });

    it('asks for one frame however many events arrive before it runs', async () => {
      const h = await create();
      for (let i = 0; i < 20; i++) {
        h.move(100 + i, 100);
        win.dispatchEvent(new Event('scroll'));
      }
      expect(rafCalls).toBe(1);
    });

    it('cancels a pending frame when it switches to still', async () => {
      const h = await create();
      h.move(300, 300);
      expect(h.frames()).toBe(1);
      reduced.change(true);
      expect(cancelCalls.length).toBe(1);
      expect(h.frames()).toBe(0);
    });
  });

  describe('change detection', () => {
    it('causes none from pointer events, scrolls and frames', async () => {
      const h = await create();
      const tick = vi.spyOn(TestBed.inject(ApplicationRef), 'tick');
      const dot = gridDot(MIDDLE);
      for (let i = 0; i < 30; i++) {
        h.move(dot.x + i, dot.y + i);
      }
      win.dispatchEvent(new Event('scroll'));
      win.dispatchEvent(new MouseEvent('pointerout', { relatedTarget: null }));
      h.runFrames();
      expect(tick).not.toHaveBeenCalled();
      expect(h.fixture.isStable()).toBe(true);
    });
  });

  describe('canvas size', () => {
    it('uses a pixel ratio of 2 when the device ratio is 3, and 1 when it is 1', async () => {
      define(win, 'devicePixelRatio', 3);
      const high = await create();
      const canvas = high.host.querySelector('canvas')!;
      expect(canvas.width).toBe(2880);
      expect(canvas.height).toBe(1800);
      expect(high.ctx.transforms.at(-1)).toEqual([2, 0, 0, 2, 0, 0]);
      high.fixture.destroy();

      define(win, 'devicePixelRatio', 1);
      const low = await create();
      const canvas1 = low.host.querySelector('canvas')!;
      expect(canvas1.width).toBe(1440);
      expect(canvas1.height).toBe(900);
    });

    it('covers the new viewport and redraws on resize, in the same call', async () => {
      const h = await create();
      define(win, 'innerWidth', 360);
      define(win, 'innerHeight', 740);
      const clearsBefore = h.ctx.clears.length;
      win.dispatchEvent(new Event('resize'));
      const canvas = h.host.querySelector('canvas')!;
      expect(canvas.width).toBe(360);
      expect(canvas.height).toBe(740);
      expect(h.ctx.clears.length).toBeGreaterThan(clearsBefore);
      expect(h.ctx.circles().length).toBe(buildGrid(360, 740, 28).xs.length);
    });

    it('uses a grid spacing set by the input', async () => {
      const h = await create({ gridSpacing: 40 });
      expect(h.ctx.circles().length).toBe(buildGrid(1440, 900, 40).xs.length);
    });
  });

  describe('tokens', () => {
    it('draws dots in the --dot-base colour read at run time', async () => {
      tokens['--dot-base'] = ' #102030 ';
      const h = await create();
      expect(h.ctx.draws.every((d) => d.style === '#102030')).toBe(true);
    });

    it('follows a token change on the next resize', async () => {
      const h = await create();
      expect(h.ctx.draws[0].style).toBe(TOKEN_FALLBACKS.dot);
      tokens['--dot-base'] = '#445566';
      win.dispatchEvent(new Event('resize'));
      expect(h.ctx.draws.every((d) => d.style === '#445566')).toBe(true);
    });

    it('uses the spec colour when a token is missing', async () => {
      const h = await create();
      expect(h.ctx.draws[0].style).toBe('#E2E8F0');
    });
  });

  describe('header exclusion', () => {
    function addHeader(rect: { left: number; top: number; right: number; bottom: number }): HTMLElement {
      const header = document.createElement('header');
      header.setAttribute('data-dot-exclude', '');
      header.setAttribute('data-test-node', '');
      const read = { ...rect };
      header.getBoundingClientRect = () =>
        ({ ...read, width: read.right - read.left, height: read.bottom - read.top, x: read.left, y: read.top, toJSON: () => read }) as DOMRect;
      document.body.appendChild(header);
      return header;
    }

    it('fades dots next to the header: none at the edge, half at 60 px, full at 120 px', async () => {
      addHeader({ left: 0, top: 0, right: 1440, bottom: 80 });
      const h = await create();
      const grid = buildGrid(1440, 900, 28);
      const alphaAt = (row: number) => {
        const index = row * grid.columns + 5;
        return h.ctx.circles().find((c) => c.x === grid.xs[index] && c.y === grid.ys[index])?.alpha;
      };
      // Row 0 is at y = 2.5 (inside the header), row 2 at 58.5 (inside), row 3 at 86.5 (6.5 px away).
      expect(alphaAt(0)).toBeUndefined();
      expect(alphaAt(2)).toBeUndefined();
      const sixPointFive = alphaAt(3)!;
      expect(sixPointFive).toBeGreaterThan(0);
      expect(sixPointFive).toBeLessThan(0.05);
      // Row 5 at 142.5 is 62.5 px away: close to half.
      expect(alphaAt(5)!).toBeGreaterThan(0.45);
      expect(alphaAt(5)!).toBeLessThan(0.6);
      // Row 8 at 226.5 is 146.5 px away: full.
      expect(alphaAt(8)).toBe(1);
    });

    it('draws the whole grid when nothing carries data-dot-exclude', async () => {
      const h = await create();
      expect(h.ctx.circles().length).toBe(buildGrid(1440, 900, 28).xs.length);
      expect(h.ctx.circles().every((c) => c.alpha === 1)).toBe(true);
    });

    it('observes the host and each excluded element, and re-measures when the observer fires', async () => {
      const header = addHeader({ left: 0, top: 0, right: 1440, bottom: 80 });
      const h = await create();
      const observer = FakeResizeObserver.instances[0];
      expect(observer.observed.has(h.host)).toBe(true);
      expect(observer.observed.has(header)).toBe(true);

      const grid = buildGrid(1440, 900, 28);
      const index = 5 * grid.columns + 5;
      const hasDot = () => h.ctx.circles().some((c) => c.x === grid.xs[index] && c.y === grid.ys[index]);
      expect(hasDot()).toBe(true);
      // The header grows (for example fonts load) and now covers the row at 142.5.
      header.getBoundingClientRect = () =>
        ({ left: 0, top: 0, right: 1440, bottom: 200, width: 1440, height: 200, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
      observer.fire();
      expect(hasDot()).toBe(false);
    });

    it('stops observing an element that has left the page', async () => {
      const header = addHeader({ left: 0, top: 0, right: 1440, bottom: 80 });
      const h = await create();
      const observer = FakeResizeObserver.instances[0];
      header.remove();
      observer.fire();
      expect(observer.unobserve).toHaveBeenCalledWith(header);
      expect(h.ctx.circles().length).toBe(buildGrid(1440, 900, 28).xs.length);
    });

    it('re-measures on window resize when there is no ResizeObserver, without throwing', async () => {
      define(win, 'ResizeObserver', undefined);
      const header = addHeader({ left: 0, top: 0, right: 1440, bottom: 80 });
      const h = await create();
      const grid = buildGrid(1440, 900, 28);
      const index = 5 * grid.columns + 5;
      const hasDot = () => h.ctx.circles().some((c) => c.x === grid.xs[index] && c.y === grid.ys[index]);
      expect(hasDot()).toBe(true);
      header.getBoundingClientRect = () =>
        ({ left: 0, top: 0, right: 1440, bottom: 200, width: 1440, height: 200, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
      win.dispatchEvent(new Event('resize'));
      expect(hasDot()).toBe(false);
    });

    it('re-measures once in the next frame after a scroll', async () => {
      const header = addHeader({ left: 0, top: 0, right: 1440, bottom: 80 });
      const h = await create();
      const grid = buildGrid(1440, 900, 28);
      const index = 5 * grid.columns + 5;
      const measure = vi.spyOn(header, 'getBoundingClientRect');
      for (let i = 0; i < 10; i++) {
        win.dispatchEvent(new Event('scroll'));
      }
      expect(measure).not.toHaveBeenCalled();
      header.getBoundingClientRect = () =>
        ({ left: 0, top: 0, right: 1440, bottom: 200, width: 1440, height: 200, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
      h.runFrames();
      expect(h.ctx.circles().some((c) => c.x === grid.xs[index] && c.y === grid.ys[index])).toBe(false);
    });

    it('scales split nodes by the same curve: nothing coloured on the header', async () => {
      addHeader({ left: 0, top: 0, right: 1440, bottom: 80 });
      const h = await create();
      h.move(700, 60);
      h.runFrames();
      const colours = [TOKEN_FALLBACKS.nodeA, TOKEN_FALLBACKS.nodeB, TOKEN_FALLBACKS.nodeC, TOKEN_FALLBACKS.nodeD];
      const nodes = h.ctx.circles().filter((c) => colours.includes(c.style));
      for (const node of nodes) {
        expect(node.alpha).toBeLessThan(0.2);
      }
    });
  });

  describe('clean-up', () => {
    it('removes every listener it added, disconnects its observers and cancels a pending frame', async () => {
      const addWin = vi.spyOn(win, 'addEventListener');
      const removeWin = vi.spyOn(win, 'removeEventListener');
      const disconnectMo = vi.spyOn(MutationObserver.prototype, 'disconnect');
      const h = await create();
      h.move(300, 300);
      expect(h.frames()).toBe(1);

      h.fixture.destroy();

      const key = (call: unknown[]) => `${String(call[0])}`;
      const added = addWin.mock.calls.map(key).sort();
      const removed = removeWin.mock.calls.map(key).sort();
      expect(added.length).toBeGreaterThan(3);
      expect(removed).toEqual(added);
      for (const call of addWin.mock.calls) {
        expect(removeWin.mock.calls.some((r) => r[0] === call[0] && r[1] === call[1])).toBe(true);
      }
      expect(reduced.addEventListener).toHaveBeenCalledTimes(1);
      expect(reduced.removeEventListener).toHaveBeenCalledTimes(1);
      expect(fine.addEventListener).toHaveBeenCalledTimes(1);
      expect(fine.removeEventListener).toHaveBeenCalledTimes(1);
      expect(reduced.listeners.size).toBe(0);
      expect(fine.listeners.size).toBe(0);
      expect(FakeResizeObserver.instances[0].disconnect).toHaveBeenCalled();
      expect(disconnectMo).toHaveBeenCalled();
      expect(cancelCalls.length).toBe(1);
      expect(h.frames()).toBe(0);
    });

    it('is quiet after destroy: events do nothing', async () => {
      const h = await create();
      h.fixture.destroy();
      const before = rafCalls;
      h.move(100, 100);
      win.dispatchEvent(new Event('scroll'));
      win.dispatchEvent(new Event('resize'));
      reduced.change(true);
      expect(rafCalls).toBe(before);
    });

    it('leaves nothing behind after repeated create and destroy', async () => {
      const addWin = vi.spyOn(win, 'addEventListener');
      const removeWin = vi.spyOn(win, 'removeEventListener');
      for (let i = 0; i < 5; i++) {
        const h = await create();
        h.fixture.destroy();
      }
      expect(removeWin.mock.calls.length).toBe(addWin.mock.calls.length);
      expect(reduced.listeners.size).toBe(0);
    });

    it('makes no storage write, cookie or console output', async () => {
      const log = vi.spyOn(console, 'log');
      const warn = vi.spyOn(console, 'warn');
      const error = vi.spyOn(console, 'error');
      const setItem = vi.spyOn(Storage.prototype, 'setItem');
      const h = await create();
      const dot = gridDot(MIDDLE);
      h.move(dot.x, dot.y);
      h.runFrames();
      h.fixture.destroy();
      expect(setItem).not.toHaveBeenCalled();
      expect(document.cookie).toBe('');
      expect(log).not.toHaveBeenCalled();
      expect(warn).not.toHaveBeenCalled();
      expect(error).not.toHaveBeenCalled();
    });
  });

  describe('isSurfaceTarget', () => {
    it('is true for a data-surface element, for anything inside it and for a dialog overlay', () => {
      const card = document.createElement('div');
      card.setAttribute('data-surface', '');
      const inner = document.createElement('p');
      card.appendChild(inner);
      const overlay = document.createElement('div');
      overlay.className = 'cdk-overlay-container';
      const dialog = document.createElement('div');
      overlay.appendChild(dialog);
      expect(isSurfaceTarget(card)).toBe(true);
      expect(isSurfaceTarget(inner)).toBe(true);
      expect(isSurfaceTarget(dialog)).toBe(true);
    });

    it('is false for open background, null and things that are not elements', () => {
      expect(isSurfaceTarget(document.createElement('div'))).toBe(false);
      expect(isSurfaceTarget(document.body)).toBe(false);
      expect(isSurfaceTarget(null)).toBe(false);
      expect(isSurfaceTarget(undefined)).toBe(false);
      expect(isSurfaceTarget(win)).toBe(false);
      expect(isSurfaceTarget(document)).toBe(false);
    });
  });
});
