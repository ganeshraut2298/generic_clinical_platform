import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export interface TrendPoint {
  label: string;
  value: number;
}

/** A small line chart in inline SVG (no chart library, ADR-023): the last readings, a dashed target line, and the values as text. */
@Component({
  selector: 'app-sp-trend-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './sp-trend-chart.component.scss',
  template: `
    <figure class="fig">
      <svg viewBox="0 0 300 120" role="img" [attr.aria-label]="description()">
        <rect x="30" y="8" width="262" height="92" class="plot" />
        @if (targetY() !== null) {
          <line x1="30" x2="292" [attr.y1]="targetY()" [attr.y2]="targetY()" class="target" />
          <text x="34" [attr.y]="(targetY() ?? 0) - 3" class="tl">{{ targetLabel() }}</text>
        }
        @if (path()) {
          <polyline [attr.points]="path()" class="line" />
        }
        @for (p of plotted(); track $index) {
          <circle [attr.cx]="p.x" [attr.cy]="p.y" r="4" class="dot" />
          <text [attr.x]="p.x" y="114" class="xl">{{ p.label }}</text>
        }
        <text x="2" y="14" class="yl">{{ hi() }}</text>
        <text x="2" y="100" class="yl">{{ lo() }}</text>
      </svg>
      <figcaption class="sr-only">{{ description() }}</figcaption>
    </figure>
  `,
})
export class SpTrendChartComponent {
  readonly points = input.required<readonly TrendPoint[]>();
  readonly target = input<number | null>(null);
  readonly targetLabel = input('');
  readonly min = input.required<number>();
  readonly max = input.required<number>();
  readonly name = input('Readings');

  protected readonly lo = computed(() => this.min());
  protected readonly hi = computed(() => this.max());

  private y(value: number): number {
    const span = this.max() - this.min() || 1;
    const clamped = Math.max(this.min(), Math.min(this.max(), value));
    return Math.round((100 - ((clamped - this.min()) / span) * 92) * 10) / 10;
  }

  protected readonly targetY = computed(() => {
    const t = this.target();
    return t === null ? null : this.y(t);
  });

  protected readonly plotted = computed(() => {
    const pts = this.points();
    return pts.map((p, i) => ({
      x: Math.round(46 + (pts.length <= 1 ? 120 : (i * 230) / (pts.length - 1))),
      y: this.y(p.value),
      label: p.label,
    }));
  });

  protected readonly path = computed(() => (this.plotted().length > 1 ? this.plotted().map((p) => `${p.x},${p.y}`).join(' ') : ''));

  protected readonly description = computed(
    () => `${this.name()}: ${this.points().map((p) => `${p.label} ${p.value}`).join(', ') || 'no readings yet'}${this.target() !== null ? `. ${this.targetLabel()}` : ''}`,
  );
}
