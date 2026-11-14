import { SpToothChart, ToothCondition } from '../specialty.model';
import { isUpper, toothOf } from '../dental.logic';

/**
 * Geometry of the 3D jaw (SAH-49-1): teeth as boxes on an arch, drawn with CSS 3D transforms only
 * (no library, ADR-023 D1). Sizes and the arch are the product's own drawing values, not clinical data.
 */

export interface Face {
  /** CSS transform of the face inside its tooth. */
  transform: string;
  width: number;
  height: number;
  /** Brightness factor of the face colour: light from the front and above. */
  shade: number;
}

export interface Tooth3d {
  n: number;
  upper: boolean;
  /** Placement of the tooth on the arch. */
  transform: string;
  w: number;
  h: number;
  d: number;
  faces: Face[];
  condition: ToothCondition;
}

const WIDTH: Record<number, number> = { 1: 10, 2: 8, 3: 9, 4: 9, 5: 9, 6: 13, 7: 12, 8: 11 };
const HEIGHT: Record<number, number> = { 1: 17, 2: 16, 3: 18, 4: 15, 5: 14, 6: 12, 7: 12, 8: 11 };
const SCALE = 2.1;

export function toothSize(tooth: number): { w: number; h: number; d: number } {
  const p = tooth % 10;
  const child = Math.floor(tooth / 10) > 4;
  const k = (child ? 0.85 : 1) * SCALE;
  const w = (WIDTH[p] ?? 10) * k;
  return { w: Math.round(w), h: Math.round((HEIGHT[p] ?? 14) * k), d: Math.round(w * 0.9) };
}

/** The six faces of a box (front, back, left, right, top, bottom) around its centre. */
export function boxFaces(w: number, h: number, d: number, topShade = 0.95, bottomShade = 0.88): Face[] {
  return [
    { transform: `translateZ(${d / 2}px)`, width: w, height: h, shade: 1 },
    { transform: `rotateY(180deg) translateZ(${d / 2}px)`, width: w, height: h, shade: 0.74 },
    { transform: `rotateY(-90deg) translateZ(${w / 2}px)`, width: d, height: h, shade: 0.84 },
    { transform: `rotateY(90deg) translateZ(${w / 2}px)`, width: d, height: h, shade: 0.84 },
    { transform: `rotateX(90deg) translateZ(${h / 2}px)`, width: w, height: d, shade: topShade },
    { transform: `rotateX(-90deg) translateZ(${h / 2}px)`, width: w, height: d, shade: bottomShade },
  ];
}

/** Places one jaw's teeth along a half ellipse; the incisors meet in the middle at the front. */
export function arch3d(list: readonly number[], upper: boolean, open: number, dentition: 'ADULT' | 'CHILD'): Tooth3d[] {
  const [rx, rz] = dentition === 'CHILD' ? [96, 88] : [132, 124];
  const widths = list.map((n) => toothSize(n).w + 2);
  const total = widths.reduce((a, b) => a + b, 0);
  const span = Math.PI * 0.94;
  let cumulative = 0;
  return list.map((n, i) => {
    const { w, h, d } = toothSize(n);
    const t = -span / 2 + (span * (cumulative + widths[i] / 2)) / total;
    cumulative += widths[i];
    const x = rx * Math.sin(t);
    const z = rz * Math.cos(t) - rz * 0.45;
    const phi = (Math.atan2(Math.sin(t) / rx, Math.cos(t) / rz) * 180) / Math.PI;
    const y = upper ? -(open / 2 + h / 2) : open / 2 + h / 2;
    return {
      n,
      upper,
      w,
      h,
      d,
      condition: 'HEALTHY',
      transform: `translate3d(${x.toFixed(1)}px, ${y.toFixed(1)}px, ${z.toFixed(1)}px) rotateY(${phi.toFixed(1)}deg)`,
      faces: boxFaces(w, h, d, upper ? 0.8 : 1.06, upper ? 1.06 : 0.8),
    };
  });
}

export function jaw3d(chart: SpToothChart, upperList: readonly number[], lowerList: readonly number[], open: number): Tooth3d[] {
  const all = [...arch3d(upperList, true, open, chart.dentition), ...arch3d(lowerList, false, open, chart.dentition)];
  return all.map((t) => ({ ...t, condition: toothOf(chart, t.n).condition }));
}

/** The viewpoints of the mockup: tilt, turn, mouth opening and which jaw shows. */
export const PRESETS: readonly { key: string; label: string; tilt: number; yaw: number; open: number; arch: 'both' | 'upper' | 'lower' }[] = [
  { key: 'front', label: 'Front', tilt: -8, yaw: 0, open: 34, arch: 'both' },
  { key: 'left', label: 'Patient’s left', tilt: -6, yaw: -68, open: 34, arch: 'both' },
  { key: 'right', label: 'Patient’s right', tilt: -6, yaw: 68, open: 34, arch: 'both' },
  { key: 'upper', label: 'Upper jaw (biting surface)', tilt: 90, yaw: 0, open: 60, arch: 'upper' },
  { key: 'lower', label: 'Lower jaw (biting surface)', tilt: -90, yaw: 0, open: 60, arch: 'lower' },
  { key: 'wide', label: 'Open wide', tilt: -28, yaw: -18, open: 120, arch: 'both' },
];

export function wrapYaw(yaw: number): number {
  let y = yaw;
  while (y > 180) {
    y -= 360;
  }
  while (y < -180) {
    y += 360;
  }
  return y;
}

export function isUpperTooth(n: number): boolean {
  return isUpper(n);
}

// ---- colour ----

export function hexToRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}

export function mixRgb(a: [number, number, number], b: [number, number, number], t: number): [number, number, number] {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
}

export function rgbCss(c: readonly number[], factor = 1): string {
  return `rgb(${c.map((v) => Math.max(0, Math.min(255, Math.round(v * factor)))).join(',')})`;
}

/** The tint a condition gives the tooth in the 3D view. */
export const TINT: Record<ToothCondition, string | null> = {
  HEALTHY: null,
  CARIES: '#B45309',
  FILLED: '#1D5FA8',
  RCT: '#0B6E69',
  CROWN: '#7C5AC4',
  MISSING: null,
  TO_EXTRACT: '#B42318',
  IMPLANT: '#6B7785',
};

export function toothColour(enamelHex: string, condition: ToothCondition): [number, number, number] {
  const enamel = hexToRgb(enamelHex);
  const tint = TINT[condition];
  return tint ? mixRgb(enamel, hexToRgb(tint), condition === 'IMPLANT' ? 0.35 : 0.5) : enamel;
}
