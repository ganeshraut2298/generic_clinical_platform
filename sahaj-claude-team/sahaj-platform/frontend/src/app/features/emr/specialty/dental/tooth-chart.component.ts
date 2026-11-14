import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { newId } from '../specialty.logic';
import { SpEditor } from '../shared/sp-editor';
import {
  CONDITIONS,
  SHADES,
  GUMS,
  SURFACES,
  SURFACE_CONDITIONS,
  archOf,
  conditionLabel,
  defaultSittings,
  emptyChart,
  isDuplicateRow,
  isPrimary,
  isUpper,
  legendCounts,
  procedureText,
  suggestionsFor,
  toggleSurface,
  toothName,
  toothOf,
  withTooth,
  Suggestion,
} from '../dental.logic';
import { PRESETS, jaw3d, rgbCss, toothColour, wrapYaw } from './dental3d.logic';
import { SpDentalPlan, SpToothChart, ToothCondition, ToothSurface } from '../specialty.model';

type DentalView = 'chart' | '3d' | 'detail';
type Look = 'real' | 'xray' | 'outline' | 'buttons';

const VIEWS: { key: DentalView; label: string }[] = [
  { key: 'chart', label: 'Chart' },
  { key: '3d', label: '3D model' },
  { key: 'detail', label: 'Tooth detail' },
];
const HINTS: Record<DentalView, string> = {
  chart: 'Tap a tooth, pick its condition on the right, then add treatment to the plan.',
  '3d': 'Drag to turn the jaw. Tap a tooth to select it. Colours show each tooth’s condition.',
  detail: 'Mark surfaces, see roots and canals, and check this tooth’s history.',
};
const LOOKS: { key: Look; label: string }[] = [
  { key: 'real', label: 'Realistic' },
  { key: 'xray', label: 'X-ray' },
  { key: 'outline', label: 'Outline' },
  { key: 'buttons', label: 'Buttons' },
];

/** Own simple drawing of one tooth: crown and roots by tooth type (this product's shapes, not clinical data). */
const CROWN: Record<string, string> = {
  inc: 'M10 52 h20 v24 q0 8 -10 8 q-10 0 -10 -8 z',
  can: 'M9 52 h22 v22 q0 8 -11 15 q-11 -7 -11 -15 z',
  pre: 'M7 52 h26 v22 q0 10 -13 10 q-13 0 -13 -10 z',
  mol: 'M3 52 h34 v22 q0 12 -17 12 q-17 0 -17 -12 z',
};
const ROOT: Record<string, string> = {
  inc: 'M12 52 q2 -36 8 -46 q6 10 8 46 z',
  can: 'M12 52 q3 -42 8 -50 q5 8 8 50 z',
  pre: 'M11 52 q2 -32 9 -42 q7 10 9 42 z',
  mol: 'M5 52 q0 -28 6 -40 q5 14 6 40 z M23 52 q1 -26 6 -40 q6 12 6 40 z',
};

function typeOf(n: number): 'inc' | 'can' | 'pre' | 'mol' {
  const q = Math.floor(n / 10);
  const p = n % 10;
  if (p <= 2) {
    return 'inc';
  }
  if (p === 3) {
    return 'can';
  }
  return q > 4 ? 'mol' : p <= 5 ? 'pre' : 'mol';
}

/**
 * TOOTH_CHART (SAH-49-1, tagged C-3): the dental chart in three views, all on one value. "Chart" is
 * the two jaws with a tooth per button; "3D model" is the jaw drawn with CSS 3D transforms (turn,
 * tilt, open, zoom, presets; no library); "Tooth detail" marks surfaces and mobility. Teeth are stored
 * by FDI number and only teeth that differ from healthy are kept. The child tab opens for a patient
 * under 13 when the date of birth is known (ADR-023 H15).
 */
@Component({
  selector: 'app-sp-tooth-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './tooth-chart.component.scss'],
  template: `
    <div class="sp">
      <div class="sp-row">
        <div class="seg" role="group" aria-label="Chart view">
          @for (v of views; track v.key) {
            <button type="button" [attr.aria-pressed]="viewTab() === v.key" [attr.data-view]="v.key" (click)="setView(v.key)">{{ v.label }}</button>
          }
        </div>
        <div class="seg" role="group" aria-label="Teeth set">
          <button type="button" [attr.aria-pressed]="dentition() === 'ADULT'" data-dent="ADULT" (click)="setDentition('ADULT')">Adult (32)</button>
          <button type="button" [attr.aria-pressed]="dentition() === 'CHILD'" data-dent="CHILD" (click)="setDentition('CHILD')">Child (20)</button>
        </div>
      </div>
      <p class="sp-sub" data-testid="chart-hint">{{ hint() }}</p>

      <div class="sp-row">
        <span class="sp-lbl">Look</span>
        @for (l of looks; track l.key) {
          <button type="button" class="chip sq" [class.on]="look() === l.key" (click)="look.set(l.key)">{{ l.label }}</button>
        }
        <span class="sp-lbl">Tooth shade</span>
        @for (s of shades; track s.key) {
          <button
            type="button"
            class="chip sq swatch"
            [class.on]="chart().shade === s.key"
            [disabled]="disabled()"
            [attr.aria-label]="'Tooth shade ' + s.key"
            (click)="patch({ shade: s.key })"
          >
            <span class="dot" [style.background]="s.swatch"></span>{{ s.key }}
          </button>
        }
        <span class="sp-lbl">Gum tone</span>
        @for (g of gums; track g.key) {
          <button
            type="button"
            class="chip sq swatch"
            [class.on]="chart().gum === g.key"
            [disabled]="disabled()"
            [attr.aria-label]="'Gum tone ' + g.label"
            (click)="patch({ gum: g.key })"
          >
            <span class="dot" [style.background]="g.swatch"></span>{{ g.label }}
          </button>
        }
      </div>

      <div class="cols">
        <div class="left">
          @if (viewTab() === 'chart') {
            <div class="jaws" [class]="'look-' + look()">
              <div class="quad"><span>Upper right</span><span>Upper left</span></div>
              <div class="arch" role="group" aria-label="Upper teeth">
                @for (n of upper(); track n) {
                  <ng-container *ngTemplateOutlet="toothBtn; context: { n: n, up: true }" />
                }
              </div>
              <div class="midline" aria-hidden="true"></div>
              <div class="arch" role="group" aria-label="Lower teeth">
                @for (n of lower(); track n) {
                  <ng-container *ngTemplateOutlet="toothBtn; context: { n: n, up: false }" />
                }
              </div>
              <div class="quad"><span>Lower right</span><span>Lower left</span></div>
            </div>
          } @else if (viewTab() === '3d') {
            <div class="three">
              <div class="sp-row">
                <span class="sp-lbl">View from</span>
                @for (p of presets; track p.key) {
                  <button type="button" class="chip sq" [class.on]="preset() === p.key" (click)="applyPreset(p.key)">{{ p.label }}</button>
                }
              </div>
              <div
                class="stage"
                role="group"
                aria-label="3D jaw. Drag to turn it; the sliders below do the same with the keyboard."
                (pointerdown)="dragStart($event)"
                (pointermove)="dragMove($event)"
                (pointerup)="dragEnd()"
                (pointercancel)="dragEnd()"
              >
                <div class="scene" [style.transform]="sceneTransform()">
                  @for (t of teeth3d(); track t.n) {
                    <button
                      type="button"
                      class="t3"
                      [class.sel]="t.n === sel()"
                      [style.display]="hidden3d(t.upper) ? 'none' : 'block'"
                      [style.transform]="t.transform + (t.n === sel() ? ' translateZ(10px)' : '')"
                      [attr.aria-label]="'Tooth ' + t.n + ', ' + condText(t.condition)"
                      (click)="pick3d(t.n)"
                    >
                      @for (f of t.faces; track $index) {
                        <span
                          class="face"
                          [class.ghost]="t.condition === 'MISSING'"
                          [style.width.px]="f.width"
                          [style.height.px]="f.height"
                          [style.margin-left.px]="-f.width / 2"
                          [style.margin-top.px]="-f.height / 2"
                          [style.transform]="f.transform"
                          [style.background]="faceColour(t.n, t.condition, f.shade)"
                          >@if ($index === 0 && chart().showNumbers) {{{ t.n }}}</span
                        >
                      }
                    </button>
                  }
                </div>
              </div>
              <div class="ctrls">
                <label class="field">
                  <span class="sp-lbl">Turn left / right · {{ yaw() }}°</span>
                  <input type="range" min="-180" max="180" [value]="yaw()" (input)="onYaw($event)" />
                </label>
                <label class="field">
                  <span class="sp-lbl">Tilt up / down · {{ tilt() }}°</span>
                  <input type="range" min="-90" max="90" [value]="tilt()" (input)="onTilt($event)" />
                </label>
                <label class="field">
                  <span class="sp-lbl">Open mouth · {{ open() }}</span>
                  <input type="range" min="0" max="140" [value]="open()" (input)="onOpen($event)" />
                </label>
                <div class="sp-row">
                  <button type="button" class="chip sq" aria-label="Zoom out" (click)="zoomBy(-0.1)">−</button>
                  <button type="button" class="chip sq" aria-label="Zoom in" (click)="zoomBy(0.1)">+</button>
                  <button type="button" class="chip sq" [class.on]="spin()" data-act="spin" (click)="toggleSpin()">{{ spin() ? 'Stop turning' : 'Auto-turn' }}</button>
                  <button type="button" class="chip sq" [class.on]="chart().showNumbers" [disabled]="disabled()" (click)="patch({ showNumbers: !chart().showNumbers })">Numbers</button>
                </div>
              </div>
              <p class="sp-sub">Tooth {{ sel() }} · {{ selCondition() }} · {{ selName() }}</p>
            </div>
          } @else {
            <div class="detail">
              <h4 class="sp-h">Surfaces of {{ sel() }} · tap to mark</h4>
              <div class="cross" role="group" aria-label="Surfaces of the selected tooth">
                @for (s of cross(); track s.key) {
                  <button
                    type="button"
                    class="surf"
                    [class]="'pos-' + s.pos"
                    [class.on]="s.on"
                    [disabled]="disabled() || !canSurface()"
                    [attr.aria-pressed]="s.on"
                    [attr.aria-label]="s.full"
                    (click)="surface(s.key)"
                  >
                    {{ s.label }}
                  </button>
                }
              </div>
              <p class="sp-sub" data-testid="surf-text">{{ surfText() }}</p>
              <h4 class="sp-h">Side view · crown and roots</h4>
              <div class="side" [class.up]="selUpper()" aria-hidden="true">
                <svg viewBox="0 0 40 100" width="90" height="180">
                  <path [attr.d]="rootPath()" class="root" [class.ghost]="selCondition() === 'Missing'" />
                  <path [attr.d]="crownPath()" class="crown" [class.ghost]="selCondition() === 'Missing'" [attr.data-cond]="selConditionKey()" />
                </svg>
              </div>
              <p class="sp-sub">{{ note() }}</p>
              <h4 class="sp-h">Mobility</h4>
              <div class="sp-row" role="group" aria-label="Mobility">
                @for (m of mobility; track m) {
                  <button type="button" class="chip sq" [class.on]="selTooth().mobility === m" [disabled]="disabled()" (click)="setMobility(m)">G{{ m }}</button>
                }
              </div>
              <h4 class="sp-h">History of {{ sel() }}</h4>
              <p class="sp-sub">No treatment recorded for this tooth</p>
              <div class="sp-row">
                <button type="button" class="btn" data-act="prev-tooth" (click)="step(-1)">‹ Previous tooth</button>
                <button type="button" class="btn" data-act="next-tooth" (click)="step(1)">Next tooth ›</button>
              </div>
            </div>
          }
        </div>

        <div class="right">
          <h4 class="sp-h">Tooth {{ sel() }}</h4>
          <p class="sp-sub">{{ selName() }}</p>
          <span class="sp-lbl">Condition</span>
          <div class="grid2" role="group" aria-label="Condition">
            @for (c of conditions; track c.key) {
              <button
                type="button"
                class="tile cond"
                [class.on]="selTooth().condition === c.key"
                [attr.data-cond]="c.key"
                [disabled]="disabled()"
                (click)="setCondition(c.key)"
              >
                <span class="sw" [attr.data-cond]="c.key"></span>{{ c.label }}
              </button>
            }
          </div>
          <span class="sp-lbl">Surfaces</span>
          <div class="sp-row" role="group" aria-label="Surfaces">
            @for (s of surfaces; track s.key) {
              <button
                type="button"
                class="chip sq sbtn"
                [class.on]="selTooth().surfaces.includes(s.key)"
                [disabled]="disabled() || !canSurface()"
                [attr.aria-label]="s.label"
                [attr.aria-pressed]="selTooth().surfaces.includes(s.key)"
                (click)="surface(s.key)"
              >
                {{ s.key }}
              </button>
            }
          </div>
          <span class="sp-lbl">Suggested treatment</span>
          @if (suggestions().length > 0) {
            <div class="sp-row">
              @for (s of suggestions(); track s.key) {
                <button type="button" class="chip sq" [disabled]="disabled() || !planKey()" data-act="add-plan" (click)="addToPlan(s)">
                  + {{ planLabel(s) }} <span class="sp-sub">Phase {{ s.phase }}</span>
                </button>
              }
            </div>
            @if (!planKey()) {
              <p class="sp-sub">The treatment plan section is hidden in this department.</p>
            }
          } @else {
            <p class="sp-sub">Healthy tooth: nothing to plan.</p>
          }
          <ul class="legend" aria-label="Legend">
            @for (l of legend(); track l.condition) {
              <li><span class="sw" [attr.data-cond]="l.condition"></span>{{ condText(l.condition) }}<span class="cnt">{{ l.count || '' }}</span></li>
            }
          </ul>
          @if (!disabled()) {
            <button type="button" class="chip sq" [class.on]="chart().allHealthyConfirmed" data-act="all-healthy" (click)="confirmAllHealthy()">
              {{ chart().allHealthyConfirmed ? '✓ All teeth healthy' : 'All teeth healthy' }}
            </button>
          }
        </div>
      </div>

      @if (undo(); as u) {
        <div class="msg info" role="status">
          <span>{{ u.message }}</span>
          <button type="button" class="link" data-act="undo" (click)="doUndo()">Undo</button>
        </div>
      }
    </div>

    <ng-template #toothBtn let-n="n" let-up="up">
      <button
        type="button"
        class="tooth"
        [class.sel]="n === sel()"
        [attr.data-tooth]="n"
        [attr.data-cond]="toothCond(n)"
        [attr.aria-label]="'Tooth ' + n + ', ' + condText(toothCond(n)) + (n === sel() ? ', selected' : '')"
        [attr.aria-pressed]="n === sel()"
        (click)="sel.set(n)"
      >
        @if (look() === 'buttons') {
          <span class="blk" [attr.data-cond]="toothCond(n)">{{ surfCode(n) || '' }}</span>
        } @else {
          <svg viewBox="0 0 40 100" width="34" height="84" aria-hidden="true" [class.flip]="!up">
            <path [attr.d]="rootOf(n)" class="root" [class.ghost]="toothCond(n) === 'MISSING'" />
            <path [attr.d]="crownOf(n)" class="crown" [attr.data-cond]="toothCond(n)" [style.fill]="crownFill(n)" />
            @if (toothCond(n) === 'TO_EXTRACT') {
              <path d="M10 56 L30 84 M30 56 L10 84" class="x" />
            }
            @if (toothCond(n) === 'CARIES') {
              <circle cx="20" cy="68" r="5" class="mark caries" />
            }
            @if (toothCond(n) === 'FILLED') {
              <rect x="14" y="62" width="12" height="10" rx="2" class="mark filled" />
            }
          </svg>
        }
        <span class="num">{{ n }}</span>
      </button>
    </ng-template>
  `,
  imports: [NgTemplateOutlet],
})
export class ToothChartComponent extends SpEditor<SpToothChart> {
  protected readonly component = 'TOOTH_CHART' as const;
  private readonly destroyRef = inject(DestroyRef);

  protected blank(): SpToothChart {
    const child = this.view()?.dental?.childTabDefault ?? false;
    return emptyChart(child ? 'CHILD' : 'ADULT');
  }

  protected readonly views = VIEWS;
  protected readonly looks = LOOKS;
  protected readonly shades = SHADES;
  protected readonly gums = GUMS;
  protected readonly conditions = CONDITIONS;
  protected readonly surfaces = SURFACES;
  protected readonly presets = PRESETS;
  protected readonly mobility = [0, 1, 2, 3];

  protected readonly viewTab = signal<DentalView>('chart');
  protected readonly look = signal<Look>('real');
  protected readonly sel = signal<number>(36);
  protected readonly yaw = signal(-22);
  protected readonly tilt = signal(-14);
  protected readonly open = signal(34);
  protected readonly zoom = signal(1);
  protected readonly spin = signal(false);
  protected readonly arch = signal<'both' | 'upper' | 'lower'>('both');
  protected readonly preset = signal<string>('front');
  protected readonly undo = signal<{ message: string; restore: () => void } | null>(null);
  private spinTimer: ReturnType<typeof setInterval> | null = null;
  private dragFrom: { x: number; y: number } | null = null;
  private moved = false;

  protected readonly chart = this.val;
  protected readonly dentition = computed(() => this.chart().dentition);
  protected readonly upper = computed(() => archOf(this.dentition()).upper);
  protected readonly lower = computed(() => archOf(this.dentition()).lower);
  protected readonly hint = computed(() => HINTS[this.viewTab()]);
  protected readonly selTooth = computed(() => toothOf(this.chart(), this.sel()));
  protected readonly selCondition = computed(() => conditionLabel(this.selTooth().condition));
  protected readonly selConditionKey = computed(() => this.selTooth().condition);
  protected readonly selName = computed(() => toothName(this.sel()));
  protected readonly selUpper = computed(() => isUpper(this.sel()));
  protected readonly canSurface = computed(() => SURFACE_CONDITIONS.has(this.selTooth().condition));
  protected readonly suggestions = computed(() => suggestionsFor(this.selTooth().condition));
  protected readonly legend = computed(() => legendCounts(this.chart(), [...this.upper(), ...this.lower()]));
  protected readonly planKey = computed(() => this.ctx.sectionKeyOf('DENTAL_PLAN'));
  protected readonly teeth3d = computed(() => jaw3d(this.chart(), this.upper(), this.lower(), this.open()));
  protected readonly sceneTransform = computed(() => `scale(${this.zoom()}) rotateX(${this.tilt()}deg) rotateY(${this.yaw()}deg)`);
  protected readonly enamel = computed(() => SHADES.find((s) => s.key === this.chart().shade)?.swatch ?? '#F0E3C6');

  constructor() {
    super();
    this.destroyRef.onDestroy(() => this.stopSpin());
  }

  protected setView(v: DentalView): void {
    if (v !== '3d') {
      this.stopSpin();
    }
    this.viewTab.set(v);
  }

  protected setDentition(d: 'ADULT' | 'CHILD'): void {
    this.patch({ dentition: d });
    this.sel.set(d === 'ADULT' ? 36 : 75);
  }

  protected toothCond(n: number): ToothCondition {
    return toothOf(this.chart(), n).condition;
  }

  protected condText(c: ToothCondition): string {
    return conditionLabel(c);
  }

  protected surfCode(n: number): string {
    return toothOf(this.chart(), n).surfaces.join('');
  }

  protected crownOf(n: number): string {
    return CROWN[typeOf(n)];
  }

  protected rootOf(n: number): string {
    return ROOT[typeOf(n)];
  }

  protected crownPath(): string {
    return CROWN[typeOf(this.sel())];
  }

  protected rootPath(): string {
    return this.selCondition() === 'Implant' ? 'M16 52 h8 v46 h-8 z' : ROOT[typeOf(this.sel())];
  }

  /** The enamel colour of the chart tooth; the realistic look uses the picked shade, the others their own. */
  protected crownFill(n: number): string | null {
    if (this.toothCond(n) === 'MISSING') {
      return 'none';
    }
    const look = this.look();
    if (look === 'xray') {
      return '#D9DEE3';
    }
    return look === 'outline' ? 'transparent' : this.enamel();
  }

  protected faceColour(n: number, condition: ToothCondition, shade: number): string {
    return condition === 'MISSING' ? 'transparent' : rgbCss(toothColour(this.enamel(), condition), shade);
  }

  protected hidden3d(upper: boolean): boolean {
    return (this.arch() === 'upper' && !upper) || (this.arch() === 'lower' && upper);
  }

  protected pick3d(n: number): void {
    // A drag must not also select the tooth under the finger.
    if (!this.moved) {
      this.sel.set(n);
    }
  }

  // ---- turning the jaw ----

  protected applyPreset(key: string): void {
    const p = PRESETS.find((x) => x.key === key);
    if (!p) {
      return;
    }
    this.stopSpin();
    this.preset.set(key);
    this.tilt.set(p.tilt);
    this.yaw.set(p.yaw);
    this.open.set(p.open);
    this.arch.set(p.arch);
  }

  protected onYaw(e: Event): void {
    this.stopSpin();
    this.preset.set('');
    this.yaw.set(Number((e.target as HTMLInputElement).value));
  }

  protected onTilt(e: Event): void {
    this.preset.set('');
    this.tilt.set(Number((e.target as HTMLInputElement).value));
  }

  protected onOpen(e: Event): void {
    this.open.set(Number((e.target as HTMLInputElement).value));
  }

  protected zoomBy(d: number): void {
    this.zoom.update((z) => Math.round(Math.max(0.5, Math.min(2, z + d)) * 10) / 10);
  }

  protected toggleSpin(): void {
    if (this.spin()) {
      this.stopSpin();
      return;
    }
    this.spin.set(true);
    this.spinTimer = setInterval(() => this.yaw.update((y) => wrapYaw(y + 2)), 60);
  }

  private stopSpin(): void {
    if (this.spinTimer !== null) {
      clearInterval(this.spinTimer);
      this.spinTimer = null;
    }
    this.spin.set(false);
  }

  protected dragStart(e: PointerEvent): void {
    this.stopSpin();
    this.dragFrom = { x: e.clientX, y: e.clientY };
    this.moved = false;
  }

  protected dragMove(e: PointerEvent): void {
    if (!this.dragFrom) {
      return;
    }
    const dx = e.clientX - this.dragFrom.x;
    const dy = e.clientY - this.dragFrom.y;
    if (Math.abs(dx) + Math.abs(dy) > 3) {
      this.moved = true;
    }
    this.yaw.update((y) => wrapYaw(y + dx * 0.5));
    this.tilt.update((t) => Math.max(-90, Math.min(90, t - dy * 0.4)));
    this.preset.set('');
    this.dragFrom = { x: e.clientX, y: e.clientY };
  }

  protected dragEnd(): void {
    this.dragFrom = null;
  }

  // ---- editing ----

  private remember(message: string): void {
    const before = this.stored();
    const planKey = this.planKey();
    const planBefore = planKey ? this.ctx.value<SpDentalPlan>(planKey, 'DENTAL_PLAN') : null;
    this.undo.set({
      message,
      restore: () => {
        if (before) {
          this.replace(before);
        } else {
          this.replace(this.blank());
        }
        if (planKey && planBefore) {
          this.ctx.set(planKey, planBefore);
        } else if (planKey) {
          this.ctx.set(planKey, { component: 'DENTAL_PLAN', schemaVersion: 1, rows: [] });
        }
      },
    });
  }

  protected setCondition(condition: ToothCondition): void {
    this.remember(`Tooth ${this.sel()} marked ${conditionLabel(condition)}`);
    this.replace(withTooth(this.chart(), { ...this.selTooth(), condition }));
  }

  protected surface(s: ToothSurface): void {
    if (!this.canSurface()) {
      return;
    }
    this.replace(withTooth(this.chart(), toggleSurface(this.selTooth(), s)));
  }

  protected setMobility(m: number): void {
    this.replace(withTooth(this.chart(), { ...this.selTooth(), mobility: m }));
  }

  protected confirmAllHealthy(): void {
    this.patch({ allHealthyConfirmed: !this.chart().allHealthyConfirmed, teeth: this.chart().allHealthyConfirmed ? this.chart().teeth : [] });
  }

  protected step(direction: 1 | -1): void {
    const order = [...this.upper(), ...this.lower()];
    const i = order.indexOf(this.sel());
    this.sel.set(order[(i + direction + order.length) % order.length]);
  }

  protected planLabel(s: Suggestion): string {
    return procedureText(s.label, this.selTooth().surfaces);
  }

  protected addToPlan(s: Suggestion): void {
    const key = this.planKey();
    if (!key || this.disabled()) {
      return;
    }
    const plan = this.ctx.value<SpDentalPlan>(key, 'DENTAL_PLAN') ?? { component: 'DENTAL_PLAN' as const, schemaVersion: 1 as const, rows: [] };
    const text = this.planLabel(s);
    if (isDuplicateRow(plan.rows, this.sel(), text)) {
      this.undo.set({ message: `${text} on ${this.sel()} is already on the plan`, restore: () => undefined });
      return;
    }
    this.remember(`${s.label} on ${this.sel()} added to plan`);
    const sittings = defaultSittings(s.key);
    this.ctx.set(key, {
      ...plan,
      rows: [
        ...plan.rows,
        {
          id: newId(),
          tooth: this.sel(),
          procedureKey: s.key,
          procedureText: text,
          phase: s.phase,
          status: 'PLANNED',
          sittingsPlanned: sittings,
          currentSitting: 1,
        },
      ],
    });
  }

  protected doUndo(): void {
    this.undo()?.restore();
    this.undo.set(null);
  }

  // ---- detail view ----

  protected readonly cross = computed(() => {
    const n = this.sel();
    const q = Math.floor(n / 10);
    const right = q === 1 || q === 4 || q === 5 || q === 8;
    const anterior = n % 10 <= 3;
    const palatal = isUpper(n);
    const marked = this.selTooth().surfaces;
    const entry = (key: ToothSurface, pos: string, label: string, full: string): { key: ToothSurface; pos: string; label: string; full: string; on: boolean } => ({
      key,
      pos,
      label,
      full,
      on: marked.includes(key),
    });
    return [
      entry('B', 'top', 'B', 'Buccal / labial'),
      entry('L', 'bottom', palatal ? 'P' : 'L', 'Lingual / palatal'),
      entry('O', 'mid', anterior ? 'I' : 'O', 'Occlusal / incisal'),
      entry(right ? 'D' : 'M', 'left', right ? 'D' : 'M', right ? 'Distal' : 'Mesial'),
      entry(right ? 'M' : 'D', 'right', right ? 'M' : 'D', right ? 'Mesial' : 'Distal'),
    ];
  });

  protected readonly surfText = computed(() => {
    const s = this.selTooth().surfaces;
    const q = Math.floor(this.sel() / 10);
    return s.length > 0
      ? `Marked: ${s.join(', ')}. B buccal · ${q === 1 || q === 2 ? 'P palatal' : 'L lingual'} · M toward midline · D away from midline`
      : 'No surface marked. B buccal · M toward midline · D away from midline';
  });

  protected readonly note = computed(() => {
    const notes: Record<ToothCondition, string> = {
      HEALTHY: 'Healthy tooth.',
      CARIES: 'Decay on the marked surfaces. Check depth on the X-ray.',
      FILLED: 'Existing filling on the marked surfaces.',
      RCT: 'Root canal: canals cleaned and dressed.',
      CROWN: 'Crown covers the whole tooth.',
      MISSING: 'Tooth missing: the dashed outline shows where it was.',
      TO_EXTRACT: 'Planned for extraction.',
      IMPLANT: 'Implant fixture in bone with a crown on top.',
    };
    return notes[this.selTooth().condition] + (isPrimary(this.sel()) ? ' Milk tooth.' : '');
  });
}

