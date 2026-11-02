import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

const SIZE = 21;

/** Builds the dark cells of a sample QR pattern. The same seed always gives the same picture. */
export function sampleQrCells(seed: string): { x: number; y: number }[] {
  let h = 7;
  for (const ch of seed) {
    h = (Math.imul(h, 31) + ch.charCodeAt(0)) >>> 0;
  }
  h |= 1;
  const next = () => {
    h ^= h << 13;
    h >>>= 0;
    h ^= h >>> 17;
    h ^= h << 5;
    h >>>= 0;
    return h / 4294967296;
  };
  const cells: { x: number; y: number }[] = [];
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      const finder = (x < 8 && y < 8) || (x > 12 && y < 8) || (x < 8 && y > 12);
      if (!finder && next() > 0.5) {
        cells.push({ x, y });
      }
    }
  }
  return cells;
}

/** A sample QR picture for the demo screens. It is not a real code and does not scan. */
@Component({
  selector: 'app-qr-sample',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
      flex: none;
    }
    svg {
      display: block;
      border-radius: 4px;
    }
  `,
  template: `
    <svg
      [attr.width]="size()"
      [attr.height]="size()"
      viewBox="-1 -1 23 23"
      shape-rendering="crispEdges"
      role="img"
      aria-label="Sample QR code"
    >
      <rect x="-1" y="-1" width="23" height="23" fill="var(--paper)" />
      <g fill="var(--paper-ink)">
        @for (cell of cells(); track $index) {
          <rect [attr.x]="cell.x" [attr.y]="cell.y" width="1" height="1" />
        }
        @for (f of finders; track f.x + '-' + f.y) {
          <rect [attr.x]="f.x" [attr.y]="f.y" width="7" height="7" />
          <rect [attr.x]="f.x + 1" [attr.y]="f.y + 1" width="5" height="5" fill="var(--paper)" />
          <rect [attr.x]="f.x + 2" [attr.y]="f.y + 2" width="3" height="3" />
        }
      </g>
    </svg>
  `,
})
export class QrSampleComponent {
  readonly seed = input.required<string>();
  readonly size = input(84);
  protected readonly cells = computed(() => sampleQrCells(this.seed()));
  protected readonly finders = [
    { x: 0, y: 0 },
    { x: 14, y: 0 },
    { x: 0, y: 14 },
  ];
}
