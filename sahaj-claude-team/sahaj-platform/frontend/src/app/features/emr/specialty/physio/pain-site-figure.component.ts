import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { BOX_H, BOX_W, BodySex, buildBody, detailPath, regionAt, regionName } from '../../components/body-geometry';
import { PainSiteMarker } from '../specialty.model';

/** The recorded sex of the patient as the figure it draws (the "other" figure when the sex is not recorded). */
export function figureSex(sex: 'F' | 'M' | 'O' | null): BodySex {
  return sex === 'F' ? 'female' : sex === 'M' ? 'male' : 'other';
}

/**
 * "Pain site" of Physiotherapy (SAH-75-5, OQ-75-3, ADR-026 D5): the body outline, front view only, "R" and "L"
 * marked, with one marker on the painful region. A tap or Enter on a region sets the marker; the same region
 * again takes it off. No register, no medico-legal flag. The geometry is the shared body figure; the marker is
 * clinical data and is never logged.
 */
@Component({
  selector: 'app-pain-site-figure',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <span class="lr" aria-hidden="true">R</span>
      <svg class="figure" role="group" [attr.viewBox]="'0 0 ' + w + ' ' + h" [attr.aria-label]="ariaLabel()" (pointerup)="onTap($event)">
        @for (r of regions(); track r.id) {
          <path class="skin" [attr.d]="r.d" />
        }
        <path class="detail" [attr.d]="details()" />
        @for (r of regions(); track r.id) {
          <path
            class="hit"
            [class.on]="marker()?.region === r.id"
            [attr.d]="r.d"
            [attr.tabindex]="disabled() ? null : 0"
            [attr.role]="disabled() ? null : 'button'"
            [attr.aria-label]="disabled() ? null : r.name + '. Press Enter to mark the pain here.'"
            [attr.aria-pressed]="disabled() ? null : marker()?.region === r.id"
            (keydown.enter)="mark(r.id, r.cx / w, r.cy / h)"
            (keydown.space)="mark(r.id, r.cx / w, r.cy / h); $event.preventDefault()"
          />
        }
        @if (marker(); as m) {
          <g class="pin" data-testid="pain-marker">
            <circle class="halo" [attr.cx]="m.x * w" [attr.cy]="m.y * h" r="14" />
            <circle class="dot" [attr.cx]="m.x * w" [attr.cy]="m.y * h" r="7" />
          </g>
        }
      </svg>
      <span class="lr" aria-hidden="true">L</span>
    </div>
    @if (marker(); as m) {
      <p class="sub" data-testid="pain-region">Marked: {{ regionText(m) }}</p>
    }
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .wrap {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 8px;
      }
      .figure {
        width: min(100%, 150px);
        height: auto;
        touch-action: manipulation;
      }
      .skin {
        fill: var(--muted-soft);
        stroke: none;
      }
      .detail {
        fill: none;
        stroke: var(--ink-3);
        stroke-width: 1;
      }
      .hit {
        fill: transparent;
        stroke: none;
        cursor: pointer;
      }
      .hit:focus-visible {
        outline: 2px solid var(--pri);
      }
      .hit.on {
        fill: var(--pri-soft);
      }
      .halo {
        fill: var(--crit-soft);
      }
      .dot {
        fill: var(--crit);
        stroke: var(--surface);
        stroke-width: 2;
      }
      .lr {
        font-weight: 700;
        color: var(--ink-3);
      }
      .sub {
        margin: 4px 0 0;
        text-align: center;
        color: var(--ink-2);
        font-size: 0.8rem;
      }
    `,
  ],
})
export class PainSiteFigureComponent {
  readonly sex = input<BodySex>('other');
  readonly marker = input<PainSiteMarker | null>(null);
  readonly disabled = input(false);
  readonly markerChange = output<PainSiteMarker | null>();

  protected readonly w = BOX_W;
  protected readonly h = BOX_H;
  protected readonly regions = computed(() => buildBody(this.sex(), 'FRONT'));
  protected readonly details = computed(() => detailPath(this.sex(), 'FRONT'));
  protected readonly ariaLabel = computed(() => {
    const m = this.marker();
    return m ? `Body, front view. Pain marked on the ${regionName(m.region, 'FRONT').toLowerCase()}.` : 'Body, front view. Tap where it hurts.';
  });

  protected regionText(m: PainSiteMarker): string {
    return regionName(m.region, 'FRONT');
  }

  protected onTap(event: PointerEvent): void {
    if (this.disabled()) {
      return;
    }
    const svg = event.currentTarget as SVGSVGElement;
    const rect = svg.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) {
      return;
    }
    const fx = (event.clientX - rect.left) / rect.width;
    const fy = (event.clientY - rect.top) / rect.height;
    const hit = regionAt(this.regions(), fx * this.w, fy * this.h);
    if (hit) {
      this.mark(hit.id, fx, fy);
    }
  }

  /** Marks the region; the same region again takes the marker off. */
  protected mark(region: string, x: number, y: number): void {
    if (this.disabled()) {
      return;
    }
    if (this.marker()?.region === region) {
      this.markerChange.emit(null);
      return;
    }
    this.markerChange.emit({ side: 'FRONT', x: Math.min(1, Math.max(0, x)), y: Math.min(1, Math.max(0, y)), region });
  }
}
