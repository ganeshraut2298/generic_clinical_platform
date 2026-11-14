import { BodySide } from '../emr-phase3.model';

/**
 * The body drawing of the body map (SAH-57-1): vector regions built here in code, no photo of a
 * person. The drawing box is 240 by 480, centred on x = 120. Every region is a separate tap area;
 * left and right are separate. The patient's right side is on the viewer's left in the front view
 * and on the viewer's right in the back view.
 */

export type BodySex = 'male' | 'female' | 'other';

export const BOX_W = 240;
export const BOX_H = 480;
export const CENTRE_X = 120;

type Point = readonly [number, number];

export interface BodyRegion {
  /** Key stored on the pin: head, neck, torso1, torso2, pelvis, or r_/l_ uarm, farm, hand, thigh, leg, foot. */
  id: string;
  name: string;
  points: readonly Point[];
  d: string;
  cx: number;
  cy: number;
}

interface Shape {
  sh: number;
  ch: number;
  wa: number;
  hp: number;
  nk: number;
  hrx: number;
  hry: number;
}

const SHAPES: Record<BodySex, Shape> = {
  male: { sh: 60, ch: 47, wa: 38, hp: 43, nk: 12, hrx: 21, hry: 27 },
  female: { sh: 52, ch: 43, wa: 33, hp: 50, nk: 10.5, hrx: 20, hry: 26 },
  other: { sh: 56, ch: 45, wa: 36, hp: 46, nk: 11, hrx: 20.5, hry: 26.5 },
};

const BASE_NAMES: Record<BodySide, Record<string, string>> = {
  FRONT: {
    head: 'Head and face',
    neck: 'Neck',
    torso1: 'Chest',
    torso2: 'Abdomen',
    pelvis: 'Groin and pelvis',
    uarm: 'shoulder and upper arm',
    farm: 'forearm',
    hand: 'hand',
    thigh: 'thigh',
    leg: 'knee and leg',
    foot: 'foot',
  },
  BACK: {
    head: 'Back of head',
    neck: 'Back of neck',
    torso1: 'Upper back',
    torso2: 'Lower back',
    pelvis: 'Buttocks',
    uarm: 'upper arm (back)',
    farm: 'forearm (back)',
    hand: 'back of hand',
    thigh: 'back of thigh',
    leg: 'calf',
    foot: 'heel and sole',
  },
};


/** Region name on a view, for example "Right forearm" or "Back of neck". */
export function regionName(id: string, view: BodySide): string {
  const m = /^([rl])_(.+)$/.exec(id);
  const base = m ? m[2] : id;
  const text = BASE_NAMES[view][base] ?? id;
  const full = m ? `${m[1] === 'r' ? 'Right' : 'Left'} ${text}` : text;
  return full.charAt(0).toUpperCase() + full.slice(1);
}

/** Closed smooth path through the points (Catmull-Rom to cubic curves). */
export function smoothPath(points: readonly Point[], tension = 0.5): string {
  const n = points.length;
  const f = (v: number): string => v.toFixed(1);
  let d = `M${f(points[0][0])} ${f(points[0][1])}`;
  for (let i = 0; i < n; i++) {
    const p0 = points[(i - 1 + n) % n];
    const p1 = points[i];
    const p2 = points[(i + 1) % n];
    const p3 = points[(i + 2) % n];
    const c1x = p1[0] + ((p2[0] - p0[0]) / 6) * tension * 2;
    const c1y = p1[1] + ((p2[1] - p0[1]) / 6) * tension * 2;
    const c2x = p2[0] - ((p3[0] - p1[0]) / 6) * tension * 2;
    const c2y = p2[1] - ((p3[1] - p1[1]) / 6) * tension * 2;
    d += ` C${f(c1x)} ${f(c1y)} ${f(c2x)} ${f(c2y)} ${f(p2[0])} ${f(p2[1])}`;
  }
  return `${d} Z`;
}

function ellipse(cx: number, cy: number, rx: number, ry: number, n = 16): Point[] {
  const out: Point[] = [];
  for (let i = 0; i < n; i++) {
    const t = (i / n) * Math.PI * 2;
    out.push([cx + rx * Math.sin(t), cy - ry * Math.cos(t)]);
  }
  return out;
}

export function pointInPolygon(x: number, y: number, poly: readonly Point[]): boolean {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i];
    const [xj, yj] = poly[j];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) {
      inside = !inside;
    }
  }
  return inside;
}

function centroid(points: readonly Point[]): [number, number] {
  const sum = points.reduce<[number, number]>((acc, p) => [acc[0] + p[0], acc[1] + p[1]], [0, 0]);
  return [sum[0] / points.length, sum[1] / points.length];
}

export function buildBody(sex: BodySex, view: BodySide): BodyRegion[] {
  const { sh, ch, wa, hp, nk, hrx, hry } = SHAPES[sex];
  const C = CENTRE_X;
  const raw: { id: string; points: Point[] }[] = [];
  raw.push({ id: 'head', points: ellipse(C, 32, hrx, hry) });
  raw.push({ id: 'neck', points: [[C - nk, 56], [C + nk, 56], [C + nk + 3, 76], [C - nk - 3, 76]] });
  raw.push({
    id: 'torso1',
    points: [
      [C - nk - 3, 76], [C + nk + 3, 76], [C + sh - 14, 81], [C + ch + 3, 96], [C + ch + 1, 118],
      [C + ch, 140], [C + ch - 2, 152], [C - ch + 2, 152], [C - ch, 140], [C - ch - 1, 118],
      [C - ch - 3, 96], [C - sh + 14, 81],
    ],
  });
  raw.push({
    id: 'torso2',
    points: [
      [C - ch + 2, 152], [C + ch - 2, 152], [C + wa + 2, 168], [C + wa, 184], [C + hp - 4, 206],
      [C - hp + 4, 206], [C - wa, 184], [C - wa - 2, 168],
    ],
  });
  raw.push({
    id: 'pelvis',
    points: [
      [C - hp + 4, 206], [C + hp - 4, 206], [C + hp, 220], [C + hp - 2, 232], [C + 4, 244],
      [C - 4, 244], [C - hp + 2, 232], [C - hp, 220],
    ],
  });
  for (const isRight of [true, false]) {
    // Viewer's left in the front view is the patient's right; the back view is mirrored.
    const s = (view === 'FRONT') === isRight ? -1 : 1;
    const x = (d: number): number => C + s * d;
    const pre = isRight ? 'r_' : 'l_';
    raw.push({
      id: `${pre}uarm`,
      points: [
        [x(sh - 15), 80], [x(sh - 2), 83], [x(sh + 7), 98], [x(sh + 9), 126], [x(sh + 7), 184],
        [x(sh - 11), 184], [x(ch + 1), 128], [x(ch + 3), 98],
      ],
    });
    raw.push({
      id: `${pre}farm`,
      points: [[x(sh + 7), 184], [x(sh + 7), 216], [x(sh + 4), 256], [x(sh - 9), 256], [x(sh - 11), 216], [x(sh - 11), 184]],
    });
    raw.push({
      id: `${pre}hand`,
      points: [[x(sh + 4), 256], [x(sh + 6), 276], [x(sh + 4), 296], [x(sh - 2), 308], [x(sh - 7), 302], [x(sh - 10), 278], [x(sh - 9), 256]],
    });
    raw.push({
      id: `${pre}thigh`,
      points: [[x(hp), 220], [x(hp + 1), 256], [x(hp - 6), 300], [x(30), 336], [x(4), 336], [x(4), 292], [x(3), 244], [x(hp - 2), 232]],
    });
    raw.push({
      id: `${pre}leg`,
      points: [[x(30), 336], [x(32), 372], [x(27), 406], [x(22), 440], [x(6), 440], [x(5), 402], [x(4), 362], [x(4), 336]],
    });
    raw.push({
      id: `${pre}foot`,
      points:
        view === 'FRONT'
          ? [[x(22), 440], [x(24), 458], [x(26), 472], [x(3), 472], [x(4), 456], [x(6), 440]]
          : [[x(22), 440], [x(22), 458], [x(19), 471], [x(9), 471], [x(6), 458], [x(6), 440]],
    });
  }
  return raw.map((r) => {
    const [cx, cy] = centroid(r.points);
    return { id: r.id, name: regionName(r.id, view), points: r.points, d: smoothPath(r.points), cx, cy };
  });
}

/** Anatomy lines drawn over the regions (collar bones, chest, navel, knees; spine and shoulder blades at the back). */
export function detailPath(sex: BodySex, view: BodySide): string {
  const { sh, ch, hp, nk } = SHAPES[sex];
  const C = CENTRE_X;
  let d = '';
  const q = (a: Point, b: Point, c: Point): void => {
    d += ` M${a[0]} ${a[1]} Q${b[0]} ${b[1]} ${c[0]} ${c[1]}`;
  };
  if (view === 'FRONT') {
    q([C - nk - 1, 80], [C - sh / 2, 76], [C - sh + 14, 82]);
    q([C + nk + 1, 80], [C + sh / 2, 76], [C + sh - 14, 82]);
    if (sex === 'female') {
      q([C - ch + 4, 118], [C - ch / 2, 146], [C - 6, 132]);
      q([C + ch - 4, 118], [C + ch / 2, 146], [C + 6, 132]);
    } else {
      q([C - ch + 6, 124], [C - ch / 2, 134], [C - 3, 124]);
      q([C + ch - 6, 124], [C + ch / 2, 134], [C + 3, 124]);
    }
    d += ` M${C} 152 L${C} 200 M${C - 2} 176 a2 2.6 0 1 0 4 0 a2 2.6 0 1 0 -4 0`;
    q([C - 26, 332], [C - 17, 339], [C - 8, 332]);
    q([C + 26, 332], [C + 17, 339], [C + 8, 332]);
  } else {
    d += ` M${C} 78 L${C} 206 M${C} 214 L${C} 240`;
    q([C - ch + 4, 98], [C - ch / 2 - 4, 126], [C - 10, 120]);
    q([C + ch - 4, 98], [C + ch / 2 + 4, 126], [C + 10, 120]);
    q([C - hp + 6, 238], [C - hp / 2, 247], [C - 4, 241]);
    q([C + hp - 6, 238], [C + hp / 2, 247], [C + 4, 241]);
  }
  return d.trim();
}

/** Every region of both views for the "Choose…" list, front first. */
export function allRegions(sex: BodySex): { view: BodySide; id: string; name: string }[] {
  const out: { view: BodySide; id: string; name: string }[] = [];
  for (const view of ['FRONT', 'BACK'] as const) {
    for (const r of buildBody(sex, view)) {
      out.push({ view, id: r.id, name: r.name });
    }
  }
  return out;
}

export function regionAt(regions: readonly BodyRegion[], x: number, y: number): BodyRegion | null {
  return regions.find((r) => pointInPolygon(x, y, r.points)) ?? null;
}
