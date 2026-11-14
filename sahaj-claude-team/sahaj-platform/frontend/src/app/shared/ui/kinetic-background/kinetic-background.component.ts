import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  NgZone,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { KINETIC_DEFAULTS, KineticColours } from './kinetic-background.config';
import {
  Box,
  capDpr,
  clampFriction,
  clampRadius,
  clampSpacing,
  buildGrid,
  exclusionFactor,
  nodeOffset,
  springStep,
  targetSplit,
} from './kinetic-physics';
import { readTokens } from './kinetic-tokens';

/** What the layer does right now. Written to the host as `data-state`, for tests and tools. */
export type KineticState = 'off' | 'still' | 'idle' | 'running';

type Mode = 'off' | 'still' | 'interactive';
type Remover = () => void;
type Win = Window & typeof globalThis;

const SURFACE_SELECTOR = '[data-surface], .cdk-overlay-container';
const EXCLUDE_SELECTOR = '[data-dot-exclude]';
/** Opacity of a resting dot is rounded to 1/64, so dots can be drawn in a few batches. */
const ALPHA_STEPS = 64;
/** Node reach (11 + 1.25) plus anti-aliasing, rounded up. */
const DIRTY_PAD = 14;

/** True when the pointer is over a text block, card or form (or a dialog), which never splits dots. */
export function isSurfaceTarget(target: EventTarget | null | undefined): boolean {
  const element = target as Element | null | undefined;
  return !!element && typeof element.closest === 'function' && !!element.closest(SURFACE_SELECTOR);
}

function sameBoxes(a: readonly Box[], b: readonly Box[]): boolean {
  if (a.length !== b.length) {
    return false;
  }
  return a.every(
    (box, i) =>
      box.left === b[i].left &&
      box.top === b[i].top &&
      box.right === b[i].right &&
      box.bottom === b[i].bottom,
  );
}

/**
 * Decorative dot grid behind the public home page (SAH-39) and the login page (SAH-74). Dots near a mouse pointer split
 * into four small coloured points and settle back. Still grid only for touch and reduced motion.
 *
 * It runs outside Angular's change detection: no template event bindings, no host listeners and
 * no signal writes from listeners or frames. Mount it once, in the home page and the login page, never in the shell.
 */
@Component({
  selector: 'app-kinetic-background',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true', 'data-state': 'off' },
  styles: `
    :host {
      display: block;
      position: fixed;
      inset: 0;
      z-index: 0;
      pointer-events: none;
      contain: strict;
      background: var(--bg-base);
    }
    :host([data-state='off']) {
      background: none;
    }
    :host([data-state='off']) canvas {
      display: none;
    }
    canvas {
      display: block;
      width: 100%;
      height: 100%;
      -webkit-mask-image: radial-gradient(ellipse farthest-corner at 50% 50%, #000 55%, transparent 100%);
      mask-image: radial-gradient(ellipse farthest-corner at 50% 50%, #000 55%, transparent 100%);
    }
  `,
  template: `
    @if (showCanvas()) {
      <canvas #surface aria-hidden="true"></canvas>
    }
  `,
})
export class KineticBackgroundComponent {
  readonly interactionRadius = input(KINETIC_DEFAULTS.interactionRadius);
  readonly gridSpacing = input(KINETIC_DEFAULTS.gridSpacing);
  readonly friction = input(KINETIC_DEFAULTS.friction);
  readonly enabled = input(KINETIC_DEFAULTS.enabled);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly doc = inject(DOCUMENT);
  private readonly zone = inject(NgZone);
  private readonly injector = inject(Injector);
  private readonly canvasRef = viewChild<ElementRef<HTMLCanvasElement>>('surface');

  /** Set once when the 2D context cannot be created. Removes the canvas for good. */
  private readonly noContext = signal(false);
  protected readonly showCanvas = computed(() => this.enabled() && !this.noContext());

  private readonly win = this.doc.defaultView as Win | null;
  private destroyed = false;
  private broken = false;
  private mode: Mode = 'off';
  private state: KineticState = 'off';

  private canvas: HTMLCanvasElement | null = null;
  private ctx: CanvasRenderingContext2D | null = null;
  private reducedQuery: MediaQueryList | null = null;
  private fineQuery: MediaQueryList | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private observed = new Set<Element>();

  private readonly themeRemovers: Remover[] = [];
  private readonly mediaRemovers: Remover[] = [];
  private readonly layoutRemovers: Remover[] = [];
  private readonly pointerRemovers: Remover[] = [];

  // Layout and dot state.
  private width = 0;
  private height = 0;
  private dpr = 1;
  private spacing: number = KINETIC_DEFAULTS.gridSpacing;
  private columns = 0;
  private rows = 0;
  private offsetX = 0;
  private offsetY = 0;
  private xs: Float32Array = new Float32Array(0);
  private ys: Float32Array = new Float32Array(0);
  private exclusion: Float32Array = new Float32Array(0);
  private splits = new Float64Array(0);
  private speeds = new Float64Array(0);
  private targets = new Float64Array(0);
  private scratch = new Int32Array(0);
  private boxes: Box[] = [];
  private colours: KineticColours | null = null;
  private colourKey = '';
  private lastDark = false;
  private readonly active = new Set<number>();
  private readonly buckets: number[][] = Array.from({ length: ALPHA_STEPS + 1 }, () => []);
  private readonly splitList: number[] = [];

  // Pointer and loop state.
  private pointerX = 0;
  private pointerY = 0;
  private pointerActive = false;
  private blocked = false;
  private measurePending = false;
  private frameId: number | null = null;
  private moreFrames = false;
  private scratchAll = new Int32Array(0);

  constructor() {
    effect(() => {
      this.enabled();
      this.gridSpacing();
      afterNextRender(() => this.apply(), { injector: this.injector });
    });
    inject(DestroyRef).onDestroy(() => this.destroy());
  }

  // ---------------------------------------------------------------- set-up and mode switches

  private apply(): void {
    if (this.destroyed || this.broken || !this.win) {
      return;
    }
    try {
      if (!this.enabled()) {
        this.shutdown();
        return;
      }
      if (!this.ensureContext()) {
        this.shutdown();
        return;
      }
      this.attachTheme();
      this.lastDark = this.isDark();
      if (this.lastDark) {
        this.detachAll(false);
        this.cancelFrame();
        this.resetDots();
        this.clearCanvas();
        this.colourKey = this.tokenKey();
        this.mode = 'off';
        this.setState('off');
        return;
      }
      this.attachMedia();
      this.attachLayout();
      this.mode = this.computeMode();
      if (this.mode === 'interactive') {
        this.attachPointer();
      } else {
        this.detachGroup(this.pointerRemovers);
        this.cancelFrame();
        this.resetDots();
        this.pointerActive = false;
      }
      this.layout(true);
      this.setState(this.mode === 'interactive' ? (this.frameId === null ? 'idle' : 'running') : 'still');
    } catch {
      this.fail();
    }
  }

  private ensureContext(): boolean {
    const canvas = this.canvasRef()?.nativeElement ?? null;
    if (!canvas) {
      return false;
    }
    if (canvas === this.canvas && this.ctx) {
      return true;
    }
    let ctx: CanvasRenderingContext2D | null;
    try {
      ctx = canvas.getContext('2d');
    } catch {
      ctx = null;
    }
    if (!ctx) {
      this.noContext.set(true);
      return false;
    }
    this.canvas = canvas;
    this.ctx = ctx;
    this.width = 0;
    this.height = 0;
    return true;
  }

  private computeMode(): Mode {
    const win = this.win;
    if (!win || typeof win.matchMedia !== 'function') {
      return 'still';
    }
    this.reducedQuery ??= win.matchMedia('(prefers-reduced-motion: reduce)');
    this.fineQuery ??= win.matchMedia('(pointer: fine)');
    return !this.reducedQuery.matches && this.fineQuery.matches ? 'interactive' : 'still';
  }

  private isDark(): boolean {
    return this.doc.documentElement.getAttribute('data-theme') === 'dark';
  }

  private setState(state: KineticState): void {
    if (state !== this.state) {
      this.state = state;
      this.host.nativeElement.setAttribute('data-state', state);
    }
  }

  // ---------------------------------------------------------------- listeners

  private listen(
    target: EventTarget,
    type: string,
    handler: EventListener,
    bucket: Remover[],
    options?: AddEventListenerOptions,
  ): void {
    this.zone.runOutsideAngular(() => target.addEventListener(type, handler, options));
    bucket.push(() => target.removeEventListener(type, handler, options));
  }

  private attachTheme(): void {
    const win = this.win;
    if (this.themeRemovers.length || !win || typeof win.MutationObserver !== 'function') {
      return;
    }
    const observer = new win.MutationObserver(this.onTheme);
    this.zone.runOutsideAngular(() =>
      observer.observe(this.doc.documentElement, { attributes: true, attributeFilter: ['data-theme', 'style'] }),
    );
    this.themeRemovers.push(() => observer.disconnect());
  }

  private attachMedia(): void {
    const win = this.win;
    if (this.mediaRemovers.length || !win || typeof win.matchMedia !== 'function') {
      return;
    }
    this.reducedQuery ??= win.matchMedia('(prefers-reduced-motion: reduce)');
    this.fineQuery ??= win.matchMedia('(pointer: fine)');
    for (const query of [this.reducedQuery, this.fineQuery]) {
      if (query && typeof query.addEventListener === 'function') {
        this.listen(query, 'change', this.onMedia, this.mediaRemovers);
      }
    }
  }

  private attachLayout(): void {
    const win = this.win;
    if (this.layoutRemovers.length || !win) {
      return;
    }
    this.listen(win, 'resize', this.onResize, this.layoutRemovers, { passive: true });
    if (typeof win.ResizeObserver === 'function') {
      const observer = new win.ResizeObserver(this.onObserved);
      this.resizeObserver = observer;
      this.zone.runOutsideAngular(() => observer.observe(this.host.nativeElement));
      this.layoutRemovers.push(() => {
        observer.disconnect();
        this.observed.clear();
        this.resizeObserver = null;
      });
    }
  }

  private attachPointer(): void {
    const win = this.win;
    if (this.pointerRemovers.length || !win) {
      return;
    }
    const passive = { passive: true };
    this.listen(win, 'pointermove', this.onPointerMove, this.pointerRemovers, passive);
    this.listen(win, 'pointerout', this.onPointerOut, this.pointerRemovers, passive);
    this.listen(win, 'blur', this.onBlur, this.pointerRemovers, passive);
    this.listen(win, 'scroll', this.onScroll, this.pointerRemovers, passive);
  }

  private detachGroup(group: Remover[]): void {
    for (const remove of group.splice(0)) {
      remove();
    }
  }

  /** Removes the listeners. With `theme` false the theme observer stays (dark theme keeps watching). */
  private detachAll(theme: boolean): void {
    this.detachGroup(this.pointerRemovers);
    this.detachGroup(this.layoutRemovers);
    this.detachGroup(this.mediaRemovers);
    if (theme) {
      this.detachGroup(this.themeRemovers);
    }
  }

  private shutdown(): void {
    this.detachAll(true);
    this.cancelFrame();
    this.resetDots();
    this.clearCanvas();
    this.mode = 'off';
    this.setState('off');
    if (this.canvas && !this.canvasRef()) {
      this.canvas = null;
      this.ctx = null;
    }
  }

  /** An error anywhere: stop quietly and leave the page as it was. */
  private fail(): void {
    this.broken = true;
    try {
      this.detachAll(true);
      this.cancelFrame();
      this.ctx = null;
      this.setState('off');
    } catch {
      // Nothing else can be done, and nothing may be logged.
    }
  }

  private destroy(): void {
    this.destroyed = true;
    this.detachAll(true);
    this.cancelFrame();
    this.ctx = null;
    this.canvas = null;
    this.reducedQuery = null;
    this.fineQuery = null;
  }

  // ---------------------------------------------------------------- event handlers

  private readonly onTheme = (): void => {
    try {
      if (this.isDark() !== this.lastDark || this.tokenKey() !== this.colourKey) {
        this.apply();
      }
    } catch {
      this.fail();
    }
  };

  private readonly onMedia = (): void => {
    this.apply();
  };

  private readonly onResize = (): void => {
    try {
      this.layout(false);
      this.wake();
    } catch {
      this.fail();
    }
  };

  private readonly onObserved = (): void => {
    try {
      this.layout(false);
    } catch {
      this.fail();
    }
  };

  private readonly onPointerMove = (event: Event): void => {
    try {
      if ((event as PointerEvent).pointerType === 'touch') {
        return;
      }
      const pointer = event as MouseEvent;
      this.pointerX = pointer.clientX;
      this.pointerY = pointer.clientY;
      this.pointerActive = true;
      this.blocked = isSurfaceTarget(event.target);
      this.wake();
    } catch {
      this.fail();
    }
  };

  private readonly onPointerOut = (event: Event): void => {
    try {
      if ((event as PointerEvent).relatedTarget === null) {
        this.pointerActive = false;
        this.wake();
      }
    } catch {
      this.fail();
    }
  };

  private readonly onBlur = (): void => {
    try {
      this.pointerActive = false;
      this.wake();
    } catch {
      this.fail();
    }
  };

  private readonly onScroll = (): void => {
    try {
      this.measurePending = true;
      this.wake();
    } catch {
      this.fail();
    }
  };

  // ---------------------------------------------------------------- frame loop

  private wake(): void {
    const win = this.win;
    if (this.frameId !== null || this.destroyed || this.broken || this.mode !== 'interactive' || !win) {
      return;
    }
    this.frameId = win.requestAnimationFrame(this.frame);
    this.setState('running');
  }

  private cancelFrame(): void {
    if (this.frameId !== null) {
      this.win?.cancelAnimationFrame(this.frameId);
      this.frameId = null;
    }
  }

  private readonly frame = (): void => {
    this.frameId = null;
    try {
      let redrawn = false;
      if (this.measurePending) {
        this.measurePending = false;
        if (this.pointerActive && typeof this.doc.elementFromPoint === 'function') {
          this.blocked = isSurfaceTarget(this.doc.elementFromPoint(this.pointerX, this.pointerY));
        }
        redrawn = this.layout(false, false);
      }
      const count = this.step();
      if (redrawn) {
        // layout() changed the canvas but did not draw; draw everything once, after the step.
        this.drawCells(this.allCells(), this.xs.length);
      } else if (count > 0) {
        this.drawDirty(count);
      }
      const more = this.moreFrames;
      if (more && this.win) {
        this.frameId = this.win.requestAnimationFrame(this.frame);
      } else {
        this.setState('idle');
      }
    } catch {
      this.fail();
    }
  };

  /** Moves every active dot one step. Returns how many were moved; their indices are in `scratch`. */
  private step(): number {
    const radius = clampRadius(this.interactionRadius());
    const friction = clampFriction(this.friction());
    for (const index of this.active) {
      this.targets[index] = 0;
    }
    if (this.pointerActive && !this.blocked) {
      this.aim(radius);
    }
    let count = 0;
    let more = false;
    const finished: number[] = [];
    for (const index of this.active) {
      const result = springStep(this.splits[index], this.speeds[index], this.targets[index], friction);
      this.splits[index] = result.s;
      this.speeds[index] = result.v;
      this.scratch[count++] = index;
      if (!result.settled) {
        more = true;
      }
      if (result.s === 0 && result.v === 0 && this.targets[index] === 0) {
        finished.push(index);
      }
    }
    for (const index of finished) {
      this.active.delete(index);
    }
    this.moreFrames = more;
    return count;
  }

  /** Sets the target split of every dot within `radius` of the pointer. */
  private aim(radius: number): void {
    const step = this.spacing;
    const firstColumn = Math.max(0, Math.floor((this.pointerX - radius - this.offsetX) / step) - 1);
    const lastColumn = Math.min(this.columns - 1, Math.ceil((this.pointerX + radius - this.offsetX) / step) + 1);
    const firstRow = Math.max(0, Math.floor((this.pointerY - radius - this.offsetY) / step) - 1);
    const lastRow = Math.min(this.rows - 1, Math.ceil((this.pointerY + radius - this.offsetY) / step) + 1);
    for (let row = firstRow; row <= lastRow; row++) {
      for (let column = firstColumn; column <= lastColumn; column++) {
        const index = row * this.columns + column;
        const distance = Math.hypot(this.xs[index] - this.pointerX, this.ys[index] - this.pointerY);
        const target = targetSplit(distance, radius);
        if (target > 0) {
          this.targets[index] = target;
          this.active.add(index);
        }
      }
    }
  }

  // ---------------------------------------------------------------- layout and drawing

  private tokenKey(): string {
    return JSON.stringify(this.readColours());
  }

  private readColours(): KineticColours {
    const win = this.win as Win;
    const supports = win.CSS && typeof win.CSS.supports === 'function' ? win.CSS : null;
    return readTokens(
      win.getComputedStyle(this.doc.documentElement),
      supports ? (value) => supports.supports('color', value) : undefined,
    );
  }

  private measureBoxes(): Box[] {
    const elements = Array.from(this.doc.querySelectorAll(EXCLUDE_SELECTOR));
    const observer = this.resizeObserver;
    if (observer) {
      for (const element of elements) {
        if (!this.observed.has(element)) {
          observer.observe(element);
          this.observed.add(element);
        }
      }
      for (const element of Array.from(this.observed)) {
        if (!elements.includes(element)) {
          observer.unobserve(element);
          this.observed.delete(element);
        }
      }
    }
    const boxes: Box[] = [];
    for (const element of elements) {
      const rect = element.getBoundingClientRect();
      if (rect.width > 0 || rect.height > 0) {
        boxes.push({ left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom });
      }
    }
    return boxes;
  }

  /**
   * Fits the canvas to the viewport and redraws when something changed (or when `force`).
   * Returns true when something changed and the grid was (or, with `draw` false, must be) redrawn.
   * By default drawing happens in the same call, so no blank strip shows. The frame loop passes
   * `draw` false and draws once after the step, so a relayout frame does not draw the grid twice.
   */
  private layout(force: boolean, draw = true): boolean {
    const win = this.win;
    const canvas = this.canvas;
    const ctx = this.ctx;
    if (!win || !canvas || !ctx) {
      return false;
    }
    const host = this.host.nativeElement;
    const width = host.clientWidth || win.innerWidth || 0;
    const height = host.clientHeight || win.innerHeight || 0;
    const dpr = capDpr(win.devicePixelRatio);
    const spacing = clampSpacing(this.gridSpacing());
    const boxes = this.measureBoxes();
    const colours = this.readColours();
    const colourKey = JSON.stringify(colours);

    const sizeChanged =
      width !== this.width || height !== this.height || dpr !== this.dpr || spacing !== this.spacing;
    const boxesChanged = !sameBoxes(boxes, this.boxes);
    if (!force && !sizeChanged && !boxesChanged && colourKey === this.colourKey) {
      return false;
    }

    if (sizeChanged) {
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      this.width = width;
      this.height = height;
      this.dpr = dpr;
      this.spacing = spacing;
      this.buildDots(width, height, spacing);
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.boxes = boxes;
    if (sizeChanged || boxesChanged || force) {
      this.computeExclusion();
    }
    this.colours = colours;
    this.colourKey = colourKey;
    if (draw) {
      this.drawCells(this.allCells(), this.xs.length);
    }
    return true;
  }

  private buildDots(width: number, height: number, spacing: number): void {
    const grid = buildGrid(width, height, spacing);
    const total = grid.xs.length;
    const sameShape = grid.columns === this.columns && grid.rows === this.rows;
    this.columns = grid.columns;
    this.rows = grid.rows;
    this.offsetX = (width % spacing) / 2;
    this.offsetY = (height % spacing) / 2;
    this.xs = grid.xs;
    this.ys = grid.ys;
    this.exclusion = new Float32Array(total);
    this.scratch = new Int32Array(total);
    if (!sameShape) {
      this.splits = new Float64Array(total);
      this.speeds = new Float64Array(total);
      this.targets = new Float64Array(total);
      this.active.clear();
    }
  }

  private computeExclusion(): void {
    for (let index = 0; index < this.xs.length; index++) {
      this.exclusion[index] = exclusionFactor(this.xs[index], this.ys[index], this.boxes);
    }
  }

  private resetDots(): void {
    this.splits.fill(0);
    this.speeds.fill(0);
    this.targets.fill(0);
    this.active.clear();
  }

  private clearCanvas(): void {
    this.ctx?.clearRect(0, 0, this.width, this.height);
  }

  private allCells(): Int32Array {
    const total = this.xs.length;
    const all = this.scratchAll.length === total ? this.scratchAll : (this.scratchAll = new Int32Array(total));
    for (let index = 0; index < total; index++) {
      all[index] = index;
    }
    return all;
  }

  /** Clears and redraws the whole canvas. */
  private drawCells(cells: Int32Array, count: number): void {
    const ctx = this.ctx;
    if (!ctx) {
      return;
    }
    ctx.clearRect(0, 0, this.width, this.height);
    this.paint(ctx, cells, count);
  }

  /** Redraws only the rectangle around the dots that moved this frame. */
  private drawDirty(count: number): void {
    const ctx = this.ctx;
    if (!ctx || count === 0) {
      return;
    }
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (let k = 0; k < count; k++) {
      const index = this.scratch[k];
      minX = Math.min(minX, this.xs[index]);
      maxX = Math.max(maxX, this.xs[index]);
      minY = Math.min(minY, this.ys[index]);
      maxY = Math.max(maxY, this.ys[index]);
    }
    const dpr = this.dpr;
    const left = Math.max(0, Math.floor((minX - DIRTY_PAD) * dpr) / dpr);
    const top = Math.max(0, Math.floor((minY - DIRTY_PAD) * dpr) / dpr);
    const right = Math.min(this.width, Math.ceil((maxX + DIRTY_PAD) * dpr) / dpr);
    const bottom = Math.min(this.height, Math.ceil((maxY + DIRTY_PAD) * dpr) / dpr);
    if (right <= left || bottom <= top) {
      return;
    }
    const step = this.spacing;
    const firstColumn = Math.max(0, Math.floor((left - this.offsetX) / step) - 1);
    const lastColumn = Math.min(this.columns - 1, Math.ceil((right - this.offsetX) / step) + 1);
    const firstRow = Math.max(0, Math.floor((top - this.offsetY) / step) - 1);
    const lastRow = Math.min(this.rows - 1, Math.ceil((bottom - this.offsetY) / step) + 1);
    // The cells to redraw go into a fresh list: `scratch` still holds the moved dots.
    const cells: number[] = [];
    for (let row = firstRow; row <= lastRow; row++) {
      for (let column = firstColumn; column <= lastColumn; column++) {
        cells.push(row * this.columns + column);
      }
    }
    ctx.save();
    ctx.beginPath();
    ctx.rect(left, top, right - left, bottom - top);
    ctx.clip();
    ctx.clearRect(left, top, right - left, bottom - top);
    this.paint(ctx, Int32Array.from(cells), cells.length);
    ctx.restore();
  }

  /** Draws the given dots: resting ones in a few batches, split ones one by one. */
  private paint(ctx: CanvasRenderingContext2D, cells: Int32Array, count: number): void {
    const colours = this.colours;
    if (!colours) {
      return;
    }
    for (const bucket of this.buckets) {
      bucket.length = 0;
    }
    this.splitList.length = 0;
    for (let k = 0; k < count; k++) {
      const index = cells[k];
      if (this.splits[index] > 0) {
        this.splitList.push(index);
      } else {
        const step = Math.round(this.exclusion[index] * ALPHA_STEPS);
        if (step > 0) {
          this.buckets[step].push(index);
        }
      }
    }
    const radius = KINETIC_DEFAULTS.dotRadius;
    ctx.fillStyle = colours.dot;
    for (let step = 1; step <= ALPHA_STEPS; step++) {
      const bucket = this.buckets[step];
      if (bucket.length === 0) {
        continue;
      }
      ctx.globalAlpha = step / ALPHA_STEPS;
      ctx.beginPath();
      for (const index of bucket) {
        const x = this.xs[index];
        ctx.moveTo(x + radius, this.ys[index]);
        ctx.arc(x, this.ys[index], radius, 0, Math.PI * 2);
      }
      ctx.fill();
    }
    for (const index of this.splitList) {
      this.paintSplit(ctx, index, colours);
    }
    ctx.globalAlpha = 1;
  }

  private paintSplit(ctx: CanvasRenderingContext2D, index: number, colours: KineticColours): void {
    const split = Math.min(1, this.splits[index]);
    const exclusion = this.exclusion[index];
    const x = this.xs[index];
    const y = this.ys[index];
    const dotAlpha = (1 - split) * exclusion;
    if (dotAlpha > 0) {
      ctx.globalAlpha = dotAlpha;
      ctx.fillStyle = colours.dot;
      ctx.beginPath();
      ctx.arc(x, y, KINETIC_DEFAULTS.dotRadius, 0, Math.PI * 2);
      ctx.fill();
    }
    const nodeAlpha = split * exclusion;
    const { axis, radius } = nodeOffset(split);
    if (nodeAlpha <= 0 || radius <= 0) {
      return;
    }
    ctx.globalAlpha = nodeAlpha;
    const nodes: readonly [number, number, string][] = [
      [-axis, -axis, colours.nodeA],
      [axis, -axis, colours.nodeB],
      [-axis, axis, colours.nodeC],
      [axis, axis, colours.nodeD],
    ];
    for (const [dx, dy, colour] of nodes) {
      ctx.fillStyle = colour;
      ctx.beginPath();
      ctx.arc(x + dx, y + dy, radius, 0, Math.PI * 2);
      ctx.fill();
    }
  }
}
