import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export interface SparkPoint {
  x: number;
  y: number;
}

/** Points of a polyline in a 200 x 54 box, the way the mockup draws its mini trend charts (a lone value sits in the middle). */
export function sparkPoints(values: readonly number[], limit: number | null = null): SparkPoint[] {
  if (values.length === 0) {
    return [];
  }
  const all = limit === null ? [...values] : [...values, limit];
  let lo = Math.min(...all);
  let hi = Math.max(...all);
  if (hi === lo) {
    hi += 1;
    lo -= 1;
  }
  const step = values.length > 1 ? 180 / (values.length - 1) : 0;
  return values.map((v, i) => ({
    x: values.length > 1 ? Math.round(10 + i * step) : 100,
    y: Math.round(46 - ((v - lo) / (hi - lo)) * 38),
  }));
}

/** The y of a dashed limit line in the same box (null when no limit or no values). */
export function sparkLimitY(values: readonly number[], limit: number | null): number | null {
  if (limit === null || values.length === 0) {
    return null;
  }
  let lo = Math.min(...values, limit);
  let hi = Math.max(...values, limit);
  if (hi === lo) {
    hi += 1;
    lo -= 1;
  }
  return Math.round(46 - ((limit - lo) / (hi - lo)) * 38);
}

/** A small line of the last readings in inline SVG (no chart library). The meaning is always given as text beside it. */
@Component({
  selector: 'app-sp-sparkline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
    }
    svg {
      width: 100%;
      height: auto;
    }
    .line {
      fill: none;
      stroke: var(--pri);
      stroke-width: 2.5;
      stroke-linejoin: round;
    }
    .lim {
      stroke: var(--crit);
      stroke-width: 1.5;
      stroke-dasharray: 4 3;
    }
    .dot {
      fill: var(--good);
    }
    .dot.bad {
      fill: var(--crit);
    }
  `,
  template: `
    <svg viewBox="0 0 200 54" role="img" [attr.aria-label]="label()">
      @if (limitY() !== null) {
        <line class="lim" x1="0" x2="200" [attr.y1]="limitY()" [attr.y2]="limitY()" />
      }
      @if (points().length > 1) {
        <polyline class="line" [attr.points]="polyline()" />
      }
      @if (last(); as l) {
        <circle class="dot" [class.bad]="bad()" [attr.cx]="l.x" [attr.cy]="l.y" r="4" />
      }
    </svg>
  `,
})
export class SpSparklineComponent {
  readonly values = input.required<readonly number[]>();
  readonly limit = input<number | null>(null);
  readonly bad = input(false);
  readonly label = input.required<string>();

  protected readonly points = computed(() => sparkPoints(this.values(), this.limit()));
  protected readonly polyline = computed(() => this.points().map((p) => `${p.x},${p.y}`).join(' '));
  protected readonly last = computed(() => this.points()[this.points().length - 1] ?? null);
  protected readonly limitY = computed(() => sparkLimitY(this.values(), this.limit()));
}
