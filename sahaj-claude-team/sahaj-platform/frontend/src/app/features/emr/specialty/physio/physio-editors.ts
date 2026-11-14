import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { formatDisplayDate } from '../../../../shared/format/ist-date';
import { printSheet } from '../print-sheet';
import { PainSiteFigureComponent, figureSex } from './pain-site-figure.component';
import { REFERENCE_LISTS } from '../specialty.context';
import { SpEditor, numberOrNull, textOrNull } from '../shared/sp-editor';
import { SpSparklineComponent } from '../shared/sp-sparkline.component';
import {
  GAITS,
  HEP_DOSE_TEXT,
  KNEE_MMT,
  KNEE_ROM,
  KNEE_TESTS,
  MODALITIES,
  OUTCOME_SCALE_KEYS,
  OUTCOME_SCALE_MAX,
  RESULT_LABEL,
  jointItems,
  nextResult,
  normalText,
  painBand,
  romPercent,
  scaleChip,
} from '../obgyn-paeds-physio.logic';
import { newId } from '../specialty.logic';
import {
  Gait,
  OutcomeScaleKey,
  SpGoals,
  SpHomeExercises,
  SpMmtTable,
  SpModalities,
  SpOutcomeScales,
  SpPainScore,
  SpPostureGait,
  SpRomTable,
  SpSessionNote,
  SpSpecialTests,
} from '../specialty.model';

/**
 * Physiotherapy editors (SAH-63; tagged C-6). Short forms on one value each. Knee has the drawn
 * reference (normal range, bar of right vs normal, tests, strength groups); any other joint is typed
 * and has no normal range yet. Pictures, the exercise library and WhatsApp delivery are Later (OQ-45-28).
 */

/** PAIN_SCORE (SAH-63-1): "Pain today *", NPRS 0–10 and the pain site. */
@Component({
  selector: 'app-sp-pain-score',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PainSiteFigureComponent, SpSparklineComponent],
  styleUrls: ['../specialty.shared.scss', './physio.scss'],
  template: `
    <div class="sp">
      <p class="sp-sub">NPRS 0–10</p>
      <div class="pad" role="group" aria-label="Pain score from 0 to 10">
        @for (n of scale; track n) {
          <button type="button" class="pn" [attr.data-band]="band(n)" [class.on]="val().score === n" [disabled]="disabled()" [attr.aria-pressed]="val().score === n" (click)="patch({ score: n })">
            {{ n }}
          </button>
        }
      </div>
      <div class="sp-row ends"><span class="sp-sub">No pain</span><span class="sp-sub">Worst pain</span></div>
      @if (trendValues().length > 1) {
        <app-sp-sparkline [values]="trendValues()" [label]="trendLabel()" data-testid="pain-trend" />
      }
      <p class="sp-sub" data-testid="pain-text">{{ text() }}</p>
      <span class="sp-lbl">Pain site</span>
      <app-pain-site-figure [sex]="sex()" [marker]="val().siteMarker ?? null" [disabled]="disabled()" (markerChange)="patch({ siteMarker: $event })" />
      <label class="field">
        <span class="sp-lbl">Note on the pain site</span>
        <input class="input" type="text" maxlength="120" placeholder="Right knee, medial side · worse on stairs, better with rest" [value]="val().site ?? ''" [disabled]="disabled()" (change)="patch({ site: txt($event) })" />
      </label>
    </div>
  `,
})
export class PainScoreComponent extends SpEditor<SpPainScore> {
  protected readonly component = 'PAIN_SCORE' as const;
  protected readonly sex = computed(() => figureSex(this.ctx.store.patientSex()));
  protected blank(): SpPainScore {
    return { component: 'PAIN_SCORE', schemaVersion: 1, score: null };
  }
  protected readonly scale = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

  constructor() {
    super();
    effect(() => {
      if (this.ctx.history() === null) {
        this.ctx.loadHistory();
      }
    });
  }

  protected band(n: number): string {
    return painBand(n);
  }

  protected txt(e: Event): string | null {
    return textOrNull(e);
  }

  /** The earlier sessions' scores, then today's, for the small trend line (SAH-75-11 P-3). */
  protected readonly trendValues = computed(() => {
    const earlier = [...(this.ctx.history()?.physioProgress?.painBySession ?? [])].sort((a, b) => a.sessionNo - b.sessionNo).map((x) => x.score);
    const today = this.val().score;
    return today == null ? earlier : [...earlier, today];
  });
  protected readonly trendLabel = computed(() => `Pain over the sessions: ${this.trendValues().join(', ')}`);

  protected readonly text = computed(() => {
    const p = this.ctx.history()?.physioProgress;
    const today = this.val().score;
    if (p?.onlyFirstVisit) {
      return 'Only the first visit is recorded.';
    }
    const by = [...(p?.painBySession ?? [])].sort((a, b) => a.sessionNo - b.sessionNo);
    if (by.length > 0) {
      const a = by[0].sessionNo;
      const b = by[by.length - 1].sessionNo;
      const head = `${a === b ? `Session ${a}` : `Sessions ${a}–${b}`}: ${by.map((x) => x.score).join(', ')}.`;
      if (today == null) {
        return head;
      }
      const diff = by[by.length - 1].score - today;
      return `${head} Today: ${today}${diff > 0 ? ' — improving.' : diff === 0 ? ' — same as last session.' : ' — worse than last session.'}`;
    }
    if (!p || p.painLast == null) {
      return today == null ? 'Pick today’s pain score.' : `Today: ${today}.`;
    }
    const first = p.painFirst != null ? `First visit ${p.painFirst}. ` : '';
    const diff = today == null ? null : p.painLast - today;
    const trend = diff === null ? '' : diff > 0 ? ' — improving.' : diff === 0 ? ' — same as last session.' : ' — worse than last session.';
    return `${first}Last session: ${p.painLast}.${today == null ? '' : ` Today: ${today}${trend}`}`;
  });
}

/** POSTURE_GAIT (SAH-63-1): gait chips. */
@Component({
  selector: 'app-sp-posture-gait',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      <span class="sp-lbl">Gait</span>
      <div class="sp-row" role="group" aria-label="Gait">
        @for (g of gaits; track g.key) {
          <button type="button" class="chip sq" [class.on]="val().gait === g.key" [disabled]="disabled()" [attr.aria-pressed]="val().gait === g.key" (click)="pick(g.key)">
            {{ g.label }}
          </button>
        }
      </div>
    </div>
  `,
})
export class PostureGaitComponent extends SpEditor<SpPostureGait> {
  protected readonly component = 'POSTURE_GAIT' as const;
  protected blank(): SpPostureGait {
    return { component: 'POSTURE_GAIT', schemaVersion: 1, gait: null };
  }
  protected readonly gaits = GAITS;
  protected pick(g: Gait): void {
    this.patch({ gait: this.val().gait === g ? null : g });
  }
}

/** ROM_TABLE (SAH-63-1): range of motion with the normal range and "Right vs normal" for the knee. */
@Component({
  selector: 'app-sp-rom-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './physio.scss'],
  template: `
    <div class="sp">
      <label class="field narrow">
        <span class="sp-lbl">Joint</span>
        <input class="input" type="text" maxlength="40" [value]="val().joint" [disabled]="disabled()" (change)="setJoint($event)" />
      </label>
      <p class="sp-sub">{{ items().length > 0 ? 'Normal range shown for comparison' : 'No normal range is set up for this joint yet. Type the movements.' }}</p>
      <div class="scroll">
        <table class="tbl" aria-label="Range of motion">
          <thead><tr><th>Movement</th><th>Right</th><th>Left</th><th>Normal</th><th>Right vs normal</th></tr></thead>
          <tbody>
            @for (r of rows(); track r.movement) {
              <tr>
                <td>{{ r.label }}</td>
                <td><input class="input deg" type="number" inputmode="numeric" [value]="r.rightDeg ?? ''" [disabled]="disabled()" [attr.aria-label]="'Right ' + r.label + ' in degrees'" (change)="setDeg(r.movement, 'rightDeg', $event)" />°</td>
                <td data-testid="rom-left">{{ r.leftDeg === null ? '—' : r.leftDeg + '°' }}</td>
                <td>{{ r.normalMax === null ? '—' : normal(r.normalMax) }}</td>
                <td>
                  @if (pct(r); as p) {
                    <div class="bar-track" role="img" [attr.aria-label]="p + ' percent of normal'"><span class="bar" [style.width.%]="p" [class.low]="p < 90"></span></div>
                    <span class="sp-sub">{{ p }}%</span>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      @if (allChips().length > 0) {
        <div class="sp-row" role="group" aria-label="Strength and outcome scores" data-testid="scale-chips">
          @for (c of allChips(); track c) {
            <span class="chipnote">{{ c }}</span>
          }
        </div>
      }
      @if (!disabled() && items().length === 0) {
        <div class="sp-row">
          <input class="input" type="text" maxlength="40" placeholder="Movement, e.g. abduction" [value]="newMove()" aria-label="New movement" (input)="newMove.set(val_($event))" />
          <button type="button" class="btn" [disabled]="!newMove().trim()" (click)="addMove()">+ Add movement</button>
        </div>
      }
    </div>
  `,
})
export class RomTableComponent extends SpEditor<SpRomTable> {
  protected readonly component = 'ROM_TABLE' as const;
  protected blank(): SpRomTable {
    return { component: 'ROM_TABLE', schemaVersion: 1, joint: 'knee', rows: [] };
  }
  protected readonly newMove = signal('');
  /** The joint's reference movements (reference list jointRom: key = item key, number = normal maximum), else the drawn knee ones. */
  protected readonly items = computed(() =>
    jointItems(this.ctx.list(REFERENCE_LISTS.jointRom), this.val().joint, KNEE_ROM.map((k) => ({ key: k.key, label: k.label, normalMax: k.normalMax }))),
  );

  /** A joint with reference movements shows them even before a value is typed; typed movements follow them. */
  protected readonly rows = computed(() => {
    const stored = this.val().rows;
    const items = this.items();
    if (items.length === 0) {
      return stored.map((r) => ({ movement: r.movement, label: r.movement, normalMax: null as number | null, rightDeg: r.rightDeg, leftDeg: r.leftDeg }));
    }
    const own = items.map((k) => {
      const s = stored.find((r) => r.movement === k.key);
      return { movement: k.key, label: k.label, normalMax: (k.normalMax ?? null) as number | null, rightDeg: s?.rightDeg ?? null, leftDeg: s?.leftDeg ?? null };
    });
    const typed = stored
      .filter((r) => !items.some((k) => k.key === r.movement))
      .map((r) => ({ movement: r.movement, label: r.movement, normalMax: null as number | null, rightDeg: r.rightDeg, leftDeg: r.leftDeg }));
    return [...own, ...typed];
  });

  /** "WOMAC 48 → 41" (mockup, range-of-motion card): the outcome scales scored today or earlier. */
  protected readonly chips = computed(() => scaleChipsOf(this.view()?.physio?.outcomeScales, this.ctx.value<SpOutcomeScales>(this.ctx.sectionKeyOf('OUTCOME_SCALES') ?? '', 'OUTCOME_SCALES'), this.fresh()));

  /** "Quadriceps R 4/5 · L 5/5" for each muscle group tested in this visit (mockup, range-of-motion card; SAH-75-11 P-4). */
  protected readonly strengthChips = computed(() => {
    const key = this.ctx.sectionKeyOf('MMT_TABLE');
    const mmt = key ? this.ctx.value<SpMmtTable>(key, 'MMT_TABLE') : null;
    const items = jointItems(this.ctx.list(REFERENCE_LISTS.jointMmt), 'knee', KNEE_MMT);
    return (mmt?.rows ?? [])
      .filter((r) => r.right != null || r.left != null)
      .map((r) => `${items.find((k) => k.key === r.group)?.label ?? r.group} R ${r.right ?? '–'}/5 · L ${r.left ?? '–'}/5`);
  });
  protected readonly allChips = computed(() => [...this.strengthChips(), ...this.chips()]);

  protected normal(max: number): string {
    return normalText(max);
  }

  protected pct(r: { rightDeg: number | null; normalMax: number | null }): number | null {
    return r.normalMax === null ? null : romPercent(r.rightDeg, r.normalMax);
  }

  protected val_(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected setJoint(e: Event): void {
    const joint = (e.target as HTMLInputElement).value.trim() || 'knee';
    this.patch({ joint, rows: joint.toLowerCase() === this.val().joint.trim().toLowerCase() ? this.val().rows : [] });
  }

  protected setDeg(movement: string, side: 'rightDeg' | 'leftDeg', e: Event): void {
    const value = numberOrNull(e);
    const rows = this.val().rows;
    const exists = rows.some((r) => r.movement === movement);
    const next = exists
      ? rows.map((r) => (r.movement === movement ? { ...r, [side]: value } : r))
      : [...rows, { movement, rightDeg: null, leftDeg: null, [side]: value }];
    this.patch({ rows: next });
  }

  protected addMove(): void {
    const movement = this.newMove().trim();
    if (movement && !this.val().rows.some((r) => r.movement.toLowerCase() === movement.toLowerCase())) {
      this.patch({ rows: [...this.val().rows, { movement, rightDeg: null, leftDeg: null }] });
    }
    this.newMove.set('');
  }
}

/** MMT_TABLE (SAH-63-1): manual muscle testing 0–5, right and left. */
@Component({
  selector: 'app-sp-mmt-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './physio.scss'],
  template: `
    <div class="sp">
      <div class="scroll">
        <table class="tbl" aria-label="Manual muscle testing">
          <thead><tr><th>Muscle group</th><th>Right (0–5)</th><th>Left (0–5)</th></tr></thead>
          <tbody>
            @for (r of rows(); track r.group) {
              <tr>
                <td>{{ r.label }}</td>
                <td><input class="input deg" type="number" min="0" max="5" inputmode="numeric" [value]="r.right ?? ''" [disabled]="disabled()" [attr.aria-label]="'Right ' + r.label" (change)="set(r.group, 'right', $event)" />/5</td>
                <td><input class="input deg" type="number" min="0" max="5" inputmode="numeric" [value]="r.left ?? ''" [disabled]="disabled()" [attr.aria-label]="'Left ' + r.label" (change)="set(r.group, 'left', $event)" />/5</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      @if (!disabled()) {
        <div class="sp-row">
          <input class="input" type="text" maxlength="60" placeholder="Muscle group" [value]="extra()" aria-label="New muscle group" (input)="extra.set(txt($event))" />
          <button type="button" class="btn" [disabled]="!extra().trim()" (click)="add()">+ Add group</button>
        </div>
      }
    </div>
  `,
})
export class MmtTableComponent extends SpEditor<SpMmtTable> {
  protected readonly component = 'MMT_TABLE' as const;
  protected blank(): SpMmtTable {
    return { component: 'MMT_TABLE', schemaVersion: 1, rows: [] };
  }
  protected readonly extra = signal('');
  /** The strength groups of the knee (reference list jointMmt: key = item key, stored in "group"); a typed group stores its text. */
  protected readonly rows = computed(() => {
    const stored = this.val().rows;
    const items = jointItems(this.ctx.list(REFERENCE_LISTS.jointMmt), 'knee', KNEE_MMT);
    const own = items.map((k) => ({ ...(stored.find((r) => r.group === k.key) ?? { group: k.key, right: null, left: null }), label: k.label }));
    const typed = stored.filter((r) => !items.some((k) => k.key === r.group)).map((r) => ({ ...r, label: r.group }));
    return [...own, ...typed];
  });

  protected txt(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected set(group: string, side: 'right' | 'left', e: Event): void {
    const raw = numberOrNull(e);
    const value = raw === null ? null : Math.max(0, Math.min(5, Math.round(raw)));
    const rows = this.val().rows;
    this.patch({
      rows: rows.some((r) => r.group === group)
        ? rows.map((r) => (r.group === group ? { ...r, [side]: value } : r))
        : [...rows, { group, right: null, left: null, [side]: value }],
    });
  }

  protected add(): void {
    const group = this.extra().trim();
    if (group && !this.rows().some((r) => r.group.toLowerCase() === group.toLowerCase() || r.label.toLowerCase() === group.toLowerCase())) {
      this.patch({ rows: [...this.val().rows, { group, right: null, left: null }] });
    }
    this.extra.set('');
  }
}

/** SPECIAL_TESTS (SAH-63-1): tap once for positive, again for negative, again to clear. */
@Component({
  selector: 'app-sp-special-tests',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './physio.scss'],
  template: `
    <div class="sp">
      <p class="sp-sub">Tap once for positive, again for negative, again to clear</p>
      <div class="sp-grid">
        @for (t of tests(); track t.test) {
          <button type="button" class="tile res" [attr.data-result]="t.result" [disabled]="disabled()" (click)="cycle(t.test)">
            <b>{{ t.label }}</b><span>{{ label(t.result) }}</span>
          </button>
        }
      </div>
    </div>
  `,
})
export class SpecialTestsComponent extends SpEditor<SpSpecialTests> {
  protected readonly component = 'SPECIAL_TESTS' as const;
  protected blank(): SpSpecialTests {
    return { component: 'SPECIAL_TESTS', schemaVersion: 1, joint: 'knee', results: [] };
  }
  /** The tests of the joint (reference list jointSpecialTests: key = item key, stored in "test"). */
  protected readonly tests = computed(() => {
    const stored = this.val().results;
    const items = jointItems(this.ctx.list(REFERENCE_LISTS.jointSpecialTests), this.val().joint, KNEE_TESTS);
    return items.map((t) => ({ test: t.key, label: t.label, result: stored.find((r) => r.test === t.key)?.result ?? ('NOT_DONE' as const) }));
  });
  protected label(r: keyof typeof RESULT_LABEL): string {
    return RESULT_LABEL[r];
  }
  protected cycle(test: string): void {
    const current = this.tests().find((t) => t.test === test)?.result ?? 'NOT_DONE';
    const next = nextResult(current);
    const rest = this.val().results.filter((r) => r.test !== test);
    this.patch({ results: next === 'NOT_DONE' ? rest : [...rest, { test, result: next }] });
  }
}

/**
 * The chips "<scale> <previous> → <today>" of the mockup. Today's score is what is typed on screen while
 * something is unsaved, else the server's; the previous score is always the server's (latest earlier
 * signed record).
 */
function scaleChipsOf(
  server: { scale: OutcomeScaleKey; previousScore: number | null; previousDate: string | null; todayScore: number | null }[] | undefined,
  local: SpOutcomeScales | null,
  fresh: boolean,
): string[] {
  const scales = OUTCOME_SCALE_KEYS.filter((k) => (server ?? []).some((s) => s.scale === k) || (local?.rows ?? []).some((r) => r.scale === k && r.score != null));
  return scales
    .map((k) => {
      const s = (server ?? []).find((x) => x.scale === k);
      const typed = local?.rows.find((r) => r.scale === k);
      const today = fresh || !typed ? (s?.todayScore ?? typed?.score ?? null) : typed.score;
      return scaleChip(k, s?.previousScore, today);
    })
    .filter((c): c is string => c !== null);
}

/**
 * OUTCOME_SCALES (contract 0.5.1, ADR-023 D13; tagged C-6, H18, H20): ODI, NDI, WOMAC and LEFS, one total
 * score per scale, typed by the therapist (the library says "Calculated" but no questionnaire is drawn).
 * The range of each scale is the sanity limit; a scale already on the list is refused. The earlier score
 * of the patient is shown when the server has one.
 */
@Component({
  selector: 'app-sp-outcome-scales',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './physio.scss'],
  template: `
    <div class="sp">
      <p class="sp-sub">Standard function scores to show progress. Type the total score.</p>
      <div class="scroll">
        <table class="tbl" aria-label="Outcome scales">
          <thead><tr><th>Scale</th><th>Score</th><th>Earlier</th><th></th></tr></thead>
          <tbody>
            @for (r of val().rows; track r.scale) {
              <tr>
                <td><b>{{ r.scale }}</b></td>
                <td>
                  <input
                    class="input deg"
                    type="number"
                    min="0"
                    [attr.max]="max(r.scale)"
                    inputmode="numeric"
                    [value]="r.score ?? ''"
                    [disabled]="disabled()"
                    [attr.aria-label]="r.scale + ' score, 0 to ' + max(r.scale)"
                    (change)="setScore(r.scale, $event)"
                  />
                  <span class="sp-sub">of {{ max(r.scale) }}</span>
                </td>
                <td class="sp-sub">{{ earlier(r.scale) }}</td>
                <td>
                  @if (!disabled()) {
                    <button type="button" class="link" [attr.aria-label]="'Remove ' + r.scale" (click)="remove(r.scale)">×</button>
                  }
                </td>
              </tr>
            } @empty {
              <tr><td colspan="4" class="sp-sub">No scale yet.</td></tr>
            }
          </tbody>
        </table>
      </div>
      @if (error(); as e) {
        <p class="msg warn" role="alert" data-testid="scale-error">{{ e }}</p>
      }
      @if (chips().length > 0) {
        <div class="sp-row" role="group" aria-label="Outcome scores" data-testid="scale-chips">
          @for (c of chips(); track c) {
            <span class="chipnote">{{ c }}</span>
          }
        </div>
      }
      @if (!disabled()) {
        <div class="sp-row">
          <select class="input" aria-label="Scale to add" [value]="pick()" (change)="pick.set(sel($event))">
            @for (k of scales; track k) {
              <option [value]="k">{{ k }}</option>
            }
          </select>
          <button type="button" class="btn" data-act="add-scale" (click)="add()">+ Add scale</button>
        </div>
      }
    </div>
  `,
})
export class OutcomeScalesComponent extends SpEditor<SpOutcomeScales> {
  protected readonly component = 'OUTCOME_SCALES' as const;
  protected blank(): SpOutcomeScales {
    return { component: 'OUTCOME_SCALES', schemaVersion: 1, rows: [] };
  }
  protected readonly scales = OUTCOME_SCALE_KEYS;
  protected readonly pick = signal<OutcomeScaleKey>('ODI');
  protected readonly error = signal<string | null>(null);

  protected readonly chips = computed(() => scaleChipsOf(this.view()?.physio?.outcomeScales, this.stored(), this.fresh()));

  /** The range of a scale, from the reference list outcomeScales (number = the maximum), else as drawn. */
  protected max(scale: OutcomeScaleKey): number {
    return this.ctx.list(REFERENCE_LISTS.outcomeScales)?.find((i) => i.key === scale)?.number ?? OUTCOME_SCALE_MAX[scale];
  }

  protected earlier(scale: OutcomeScaleKey): string {
    const s = this.view()?.physio?.outcomeScales?.find((x) => x.scale === scale);
    if (s?.previousScore == null) {
      return '';
    }
    return s.previousDate ? `${s.previousScore} on ${formatDisplayDate(s.previousDate)}` : String(s.previousScore);
  }

  protected sel(e: Event): OutcomeScaleKey {
    return (e.target as HTMLSelectElement).value as OutcomeScaleKey;
  }

  protected add(): void {
    const scale = this.pick();
    if (this.val().rows.some((r) => r.scale === scale)) {
      this.error.set(`${scale} is already on the list.`);
      return;
    }
    this.error.set(null);
    this.patch({ rows: [...this.val().rows, { scale, score: null }] });
  }

  protected remove(scale: OutcomeScaleKey): void {
    this.error.set(null);
    this.patch({ rows: this.val().rows.filter((r) => r.scale !== scale) });
  }

  /** Whole points from 0 to the scale's maximum; anything else is refused and the earlier value stays. */
  protected setScore(scale: OutcomeScaleKey, e: Event): void {
    const input = e.target as HTMLInputElement;
    const raw = input.value.trim();
    const value = raw === '' ? null : Number(raw);
    const max = this.max(scale);
    if (value !== null && (!Number.isInteger(value) || value < 0 || value > max)) {
      this.error.set(`${scale} score must be a whole number from 0 to ${max}.`);
      input.value = String(this.val().rows.find((r) => r.scale === scale)?.score ?? '');
      return;
    }
    this.error.set(null);
    this.patch({ rows: this.val().rows.map((r) => (r.scale === scale ? { ...r, score: value } : r)) });
  }
}

/** GOALS (SAH-63-2): goals set at the first visit; tap when achieved. */
@Component({
  selector: 'app-sp-goals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './physio.scss'],
  template: `
    <div class="sp">
      <p class="sp-sub">Tap when achieved</p>
      <ul class="plain">
        @for (g of val().goals; track g.id) {
          <li class="sp-row">
            <button type="button" class="chip sq good" [class.on]="g.achieved" [disabled]="disabled()" [attr.aria-pressed]="g.achieved" [attr.aria-label]="'Goal: ' + g.text + (g.achieved ? ', achieved' : '')" (click)="toggle(g.id)">
              {{ g.achieved ? '✓' : '○' }}
            </button>
            <span class="gtext">{{ g.text }}</span>
            @if (g.achievedOn) {
              <span class="sp-sub">Achieved {{ g.achievedOn }}</span>
            }
            @if (!disabled()) {
              <button type="button" class="link" [attr.aria-label]="'Remove goal ' + g.text" (click)="remove(g.id)">×</button>
            }
          </li>
        } @empty {
          <li class="sp-sub">No goals yet.</li>
        }
      </ul>
      @if (!disabled()) {
        <div class="sp-row">
          <input class="input grow" type="text" maxlength="200" placeholder="New goal" aria-label="New goal" [value]="draft()" (input)="draft.set(txt($event))" (keydown.enter)="add()" />
          <button type="button" class="btn" [disabled]="!draft().trim()" (click)="add()">+ Add goal</button>
        </div>
      }
    </div>
  `,
})
export class GoalsComponent extends SpEditor<SpGoals> {
  protected readonly component = 'GOALS' as const;
  protected blank(): SpGoals {
    return { component: 'GOALS', schemaVersion: 1, goals: [] };
  }
  protected readonly draft = signal('');
  protected txt(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }
  protected toggle(id: string): void {
    this.patch({ goals: this.val().goals.map((g) => (g.id === id ? { ...g, achieved: !g.achieved } : g)) });
  }
  protected remove(id: string): void {
    this.patch({ goals: this.val().goals.filter((g) => g.id !== id) });
  }
  protected add(): void {
    const text = this.draft().trim();
    if (text && this.val().goals.length < 20) {
      this.patch({ goals: [...this.val().goals, { id: newId(), text, achieved: false }] });
    }
    this.draft.set('');
  }
}

/** MODALITIES (SAH-63-2): treatment given today with the drawn default parameters; a typed one stays on this visit only. */
@Component({
  selector: 'app-sp-modalities',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './physio.scss'],
  template: `
    <div class="sp">
      <p class="sp-sub" data-testid="mod-count">{{ val().given.length }} selected</p>
      <div class="sp-grid">
        @for (m of mods; track m.key) {
          <button type="button" class="tile" [class.on]="on(m.key)" [disabled]="disabled()" [attr.aria-pressed]="on(m.key)" (click)="toggle(m)">
            <b>{{ on(m.key) ? '✓ ' : '' }}{{ m.name }}</b><span class="sp-sub">{{ m.params }}</span>
          </button>
        }
        @for (g of typed(); track $index) {
          <div class="tile on">
            <b>✓ {{ g.text }}</b><span class="sp-sub">{{ g.params }}</span>
            @if (!disabled()) {
              <button type="button" class="link" [attr.aria-label]="'Remove ' + g.text" (click)="removeTyped(g.text)">Remove</button>
            }
          </div>
        }
      </div>
      @if (!disabled()) {
        <div class="sp-row">
          <input class="input" type="text" maxlength="60" placeholder="Other treatment" aria-label="Other treatment" [value]="draft()" (input)="draft.set(txt($event))" />
          <button type="button" class="btn" [disabled]="!draft().trim()" (click)="addTyped()">+ Add</button>
        </div>
      }
    </div>
  `,
})
export class ModalitiesComponent extends SpEditor<SpModalities> {
  protected readonly component = 'MODALITIES' as const;
  protected blank(): SpModalities {
    return { component: 'MODALITIES', schemaVersion: 1, given: [] };
  }
  protected readonly mods = MODALITIES;
  protected readonly draft = signal('');
  protected readonly typed = computed(() => this.val().given.filter((g) => g.key === null));
  protected txt(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }
  protected on(key: string): boolean {
    return this.val().given.some((g) => g.key === key);
  }
  protected toggle(m: (typeof MODALITIES)[number]): void {
    this.patch({
      given: this.on(m.key) ? this.val().given.filter((g) => g.key !== m.key) : [...this.val().given, { key: m.key, text: m.name, params: m.params }],
    });
  }
  protected addTyped(): void {
    const text = this.draft().trim();
    if (text && !this.val().given.some((g) => g.text.toLowerCase() === text.toLowerCase())) {
      this.patch({ given: [...this.val().given, { key: null, text, params: null }] });
    }
    this.draft.set('');
  }
  protected removeTyped(text: string): void {
    this.patch({ given: this.val().given.filter((g) => !(g.key === null && g.text === text)) });
  }
}

/** HOME_EXERCISES (SAH-63-4): the home programme, typed (the library and pictures are Later). */
@Component({
  selector: 'app-sp-home-exercises',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './physio.scss'],
  template: `
    <div class="sp">
      <p class="sp-sub">Home exercises ({{ val().items.length }})</p>
      <ul class="plain">
        @for (e of val().items; track e.id) {
          <li class="sp-row ex">
            <b class="gtext">{{ e.name }}</b>
            <span class="sp-sub">{{ dose(e) }}</span>
            @if (!disabled()) {
              <label class="mini"><span class="sr-only">Sets for {{ e.name }}</span><input class="input deg" type="number" min="1" max="20" placeholder="Sets" [value]="e.sets ?? ''" (change)="num(e.id, 'sets', $event)" /></label>
              <label class="mini"><span class="sr-only">Reps for {{ e.name }}</span><input class="input deg" type="number" min="1" max="100" placeholder="Reps" [value]="e.reps ?? ''" (change)="num(e.id, 'reps', $event)" /></label>
              <label class="mini"><span class="sr-only">Hold seconds for {{ e.name }}</span><input class="input deg" type="number" min="0" max="600" placeholder="Hold s" [value]="e.holdSeconds ?? ''" (change)="num(e.id, 'holdSeconds', $event)" /></label>
              <button type="button" class="link" [attr.aria-label]="'Remove ' + e.name" (click)="remove(e.id)">×</button>
            }
          </li>
        } @empty {
          <li class="sp-sub">No home exercises yet.</li>
        }
      </ul>
      @if (!disabled()) {
        <div class="sp-row">
          <input class="input grow" type="text" maxlength="120" placeholder="Exercise name" aria-label="Exercise name" [value]="draft()" (input)="draft.set(txt($event))" (keydown.enter)="add()" />
          <button type="button" class="btn" [disabled]="!draft().trim()" (click)="add()">+ Add exercise</button>
        </div>
      }
      <div class="sp-row">
        <button type="button" class="btn" data-act="print-programme" [disabled]="val().items.length === 0" (click)="print()">Print programme</button>
        <button type="button" class="btn" disabled title="Needs the WhatsApp service">Send to patient on WhatsApp <span class="later">Later</span></button>
      </div>
      @if (printError()) {
        <p class="msg crit" role="alert">We could not print. Try again.</p>
      }
    </div>
  `,
})
export class HomeExercisesComponent extends SpEditor<SpHomeExercises> {
  protected readonly component = 'HOME_EXERCISES' as const;
  protected blank(): SpHomeExercises {
    return { component: 'HOME_EXERCISES', schemaVersion: 1, items: [] };
  }
  private readonly doc = inject(DOCUMENT);
  protected readonly printError = signal(false);
  protected readonly draft = signal('');
  protected txt(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  /** The programme prints with the clinic header: exercises, sets and times (pictures are Later). */
  protected print(): void {
    const lines = this.val().items.map((i, n) => `${n + 1}. ${i.name}${HEP_DOSE_TEXT(i) ? ` · ${HEP_DOSE_TEXT(i)}` : ''}`);
    this.printError.set(!printSheet(this.doc, this.ctx.sheet('Home exercise programme', [{ heading: 'Exercises', lines }], 'Do the exercises as shown. Stop and call if the pain gets worse.')));
  }
  protected dose(e: SpHomeExercises['items'][number]): string {
    return HEP_DOSE_TEXT(e);
  }
  protected add(): void {
    const name = this.draft().trim();
    if (name && this.val().items.length < 30) {
      this.patch({ items: [...this.val().items, { id: newId(), name }] });
    }
    this.draft.set('');
  }
  protected remove(id: string): void {
    this.patch({ items: this.val().items.filter((i) => i.id !== id) });
  }
  protected num(id: string, key: 'sets' | 'reps' | 'holdSeconds', e: Event): void {
    const value = numberOrNull(e);
    this.patch({ items: this.val().items.map((i) => (i.id === id ? { ...i, [key]: value === null ? null : Math.round(value) } : i)) });
  }
}

/** SESSION_NOTE (SAH-63-2) and the progress report of SAH-63-4: the note of today's session, and a one-page report for the doctor who referred the patient. */
@Component({
  selector: 'app-sp-session-note',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      <label class="field">
        <span class="sp-lbl">Session note</span>
        <textarea class="input" maxlength="2000" [value]="val().note ?? ''" [disabled]="disabled()" (change)="patch({ note: txt($event) })"></textarea>
      </label>
      <div class="sp-row">
        <button type="button" class="btn" data-act="progress-report" (click)="report()">Progress report</button>
        @if (reportMessage(); as m) {
          <span class="sp-sub" role="status" data-testid="report-message">{{ m }}</span>
        }
      </div>
    </div>
  `,
})
export class SessionNoteComponent extends SpEditor<SpSessionNote> {
  protected readonly component = 'SESSION_NOTE' as const;
  protected blank(): SpSessionNote {
    return { component: 'SESSION_NOTE', schemaVersion: 1, note: null };
  }
  private readonly doc = inject(DOCUMENT);
  protected readonly reportMessage = signal<string | null>(null);

  constructor() {
    super();
    this.registerFooterPrint(() => this.report());
    effect(() => {
      if (this.ctx.history() === null) {
        this.ctx.loadHistory();
      }
    });
  }

  protected txt(e: Event): string | null {
    return textOrNull(e);
  }

  /** One page for the referring doctor: diagnosis, sessions done of planned, pain and range of motion first and last, goals achieved. Sharing is by print; sending is Later. */
  protected report(): void {
    const p = this.ctx.history()?.physioProgress;
    const dx = this.ctx.store.listEntries('DIAGNOSIS').map((e) => e.text);
    const romLine = (r: Record<string, unknown> | null | undefined): string => (r ? Object.entries(r).map(([k, v]) => `${k}: ${String(v)}`).join(', ') : '—');
    const sections = [{ heading: 'Diagnosis', lines: dx }];
    if (!p || p.onlyFirstVisit) {
      sections.push({ heading: 'Progress', lines: ['Only the first visit is recorded.'] });
    } else {
      sections.push(
        { heading: 'Sessions', lines: [`${p.sessionsDone} done${p.sessionsPlanned ? ` of ${p.sessionsPlanned} planned` : ''}`] },
        { heading: 'Pain score', lines: [`First visit ${p.painFirst ?? '—'} · latest ${p.painLast ?? '—'}`] },
        { heading: 'Range of motion', lines: [`First visit: ${romLine(p.romFirst)}`, `Latest: ${romLine(p.romLast)}`] },
        { heading: 'Goals achieved', lines: p.goalsAchieved },
      );
    }
    const ok = printSheet(this.doc, this.ctx.sheet('Physiotherapy progress report', sections));
    this.reportMessage.set(ok ? `Progress report${p && !p.onlyFirstVisit ? ` (sessions 1–${p.sessionsDone})` : ''} ready to share with the referring doctor` : 'We could not print. Try again.');
  }
}
