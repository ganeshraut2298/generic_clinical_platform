import { KINETIC_DEFAULTS } from './kinetic-background.config';

/** A rectangle in viewport coordinates. */
export interface Box {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/** Pointer reach: not a positive number gives 90, more than 90 is cut to 90. */
export function clampRadius(radius: number): number {
  if (!Number.isFinite(radius) || radius <= 0) {
    return KINETIC_DEFAULTS.maxRadius;
  }
  return Math.min(radius, KINETIC_DEFAULTS.maxRadius);
}

/** A friction of 1 or more never settles, so anything outside (0, 1) gives the default. */
export function clampFriction(friction: number): number {
  if (!Number.isFinite(friction) || friction <= 0 || friction >= 1) {
    return KINETIC_DEFAULTS.friction;
  }
  return friction;
}

/** Spacing under 12 px would draw too many dots, so it gives the default. */
export function clampSpacing(spacing: number): number {
  if (!Number.isFinite(spacing) || spacing < 12) {
    return KINETIC_DEFAULTS.gridSpacing;
  }
  return spacing;
}

/** Pixel ratio of the canvas, at most 2. */
export function capDpr(dpr: number): number {
  if (!Number.isFinite(dpr) || dpr <= 0) {
    return 1;
  }
  return Math.min(dpr, KINETIC_DEFAULTS.dprCap);
}

export interface Grid {
  columns: number;
  rows: number;
  /** x and y of every dot, row by row. */
  xs: Float32Array;
  ys: Float32Array;
}

/**
 * Dots every `spacing` px, centred in the viewport and placed on half pixels, so a dot of
 * radius 1.6 px covers its centre pixel completely.
 */
export function buildGrid(width: number, height: number, spacing: number): Grid {
  const step = clampSpacing(spacing);
  const w = Math.max(0, width);
  const h = Math.max(0, height);
  const columns = Math.floor(w / step) + 1;
  const rows = Math.floor(h / step) + 1;
  const offsetX = (w % step) / 2;
  const offsetY = (h % step) / 2;
  const xs = new Float32Array(columns * rows);
  const ys = new Float32Array(columns * rows);
  for (let row = 0; row < rows; row++) {
    for (let column = 0; column < columns; column++) {
      const index = row * columns + column;
      xs[index] = Math.round(offsetX + column * step) + 0.5;
      ys[index] = Math.round(offsetY + row * step) + 0.5;
    }
  }
  return { columns, rows, xs, ys };
}

/** How far a dot splits for a pointer at distance `distance`: 1 on the dot, 0 at the radius. */
export function targetSplit(distance: number, radius: number): number {
  return distance >= radius ? 0 : 1 - distance / radius;
}

export interface SpringResult {
  s: number;
  v: number;
  /** True when the dot has reached its target and stopped. */
  settled: boolean;
}

/**
 * One frame of the spring: `v += (target - s) * 0.14; v *= friction; s += v`.
 * When the dot is within 0.02 of its target and slower than 0.02 it snaps to the target with
 * speed 0, so a dot going home is back at exactly 0.
 */
export function springStep(s: number, v: number, target: number, friction: number): SpringResult {
  const { spring, settle } = KINETIC_DEFAULTS;
  let speed = (v + (target - s) * spring) * friction;
  let split = s + speed;
  const settled = Math.abs(split - target) < settle && Math.abs(speed) < settle;
  if (settled) {
    split = target;
    speed = 0;
  }
  return { s: split, v: speed, settled };
}

export interface NodeOffset {
  /** Distance along each axis. */
  axis: number;
  /** Radius of a micro-node. */
  radius: number;
}

/** Where the micro-nodes sit for a split: `nodeTravel * s` along the diagonal. */
export function nodeOffset(split: number): NodeOffset {
  const { nodeTravel, nodeRadius } = KINETIC_DEFAULTS;
  return { axis: (nodeTravel * split) / Math.SQRT2, radius: nodeRadius * split };
}

/** Distance from a point to the edge of a rectangle. 0 inside. */
export function rectDistance(x: number, y: number, box: Box): number {
  const dx = Math.max(box.left - x, 0, x - box.right);
  const dy = Math.max(box.top - y, 0, y - box.bottom);
  return Math.hypot(dx, dy);
}

/** 0 at the element, 0.5 at 60 px, 1 from 120 px on. */
export function smoothstep(distance: number, zone: number = KINETIC_DEFAULTS.exclusion): number {
  const t = Math.min(1, Math.max(0, distance / zone));
  return t * t * (3 - 2 * t);
}

/** Opacity factor of a point near the excluded elements. 1 when there are none. */
export function exclusionFactor(x: number, y: number, boxes: readonly Box[]): number {
  let factor = 1;
  for (const box of boxes) {
    factor = Math.min(factor, smoothstep(rectDistance(x, y, box)));
  }
  return factor;
}
