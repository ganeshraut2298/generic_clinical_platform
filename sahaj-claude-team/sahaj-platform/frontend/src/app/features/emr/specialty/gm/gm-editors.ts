import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { formatDisplayDateTimeOfInstant, formatDisplayDate } from '../../../../shared/format/ist-date';
import { SpEditor, textOrNull } from '../shared/sp-editor';
import { BpValue, SpBpStepperComponent } from '../shared/sp-bp-stepper.component';
import { SpSparklineComponent } from '../shared/sp-sparkline.component';
import { SpStepperComponent } from '../shared/sp-stepper.component';
import { SpTrendChartComponent } from '../shared/sp-trend-chart.component';
import { REFERENCE_LISTS, SpecialtyContext } from '../specialty.context';
import { newId, vitalsFlags } from '../specialty.logic';
import { EmrFlag, EmrHistoryVitals, SpExamGeneral, SpSignState, SpSystemFinding, SpVitalsEntry, SpVitalsLog } from '../specialty.model';

type GeView = 'today' | 'history' | 'trend';

interface VitalField {
  key: 'bpSystolic' | 'bpDiastolic' | 'pulse' | 'spo2' | 'temperature' | 'respRate' | 'pain';
  label: string;
  unit: string;
  step: number;
  start: number;
  min: number;
  max: number;
}

/** SAH-60-1 (C-1): the six readings of the mockup; BP is two fields because the contract stores systolic and diastolic. */
function vitalFields(unit: 'F' | 'C'): VitalField[] {
  return [
    { key: 'bpSystolic', label: 'BP systolic', unit: 'mmHg', step: 2, start: 120, min: 40, max: 300 },
    { key: 'bpDiastolic', label: 'BP diastolic', unit: 'mmHg', step: 2, start: 80, min: 20, max: 200 },
    { key: 'pulse', label: 'PR', unit: '/min', step: 1, start: 72, min: 20, max: 250 },
    { key: 'spo2', label: 'SpO₂', unit: '%', step: 1, start: 98, min: 50, max: 100 },
    unit === 'F'
      ? { key: 'temperature', label: 'Temp', unit: '°F', step: 0.1, start: 98.4, min: 85, max: 110 }
      : { key: 'temperature', label: 'Temp', unit: '°C', step: 0.1, start: 36.8, min: 30, max: 43 },
    { key: 'respRate', label: 'RR', unit: '/min', step: 1, start: 16, min: 4, max: 80 },
    { key: 'pain', label: 'Pain', unit: '0–10', step: 1, start: 0, min: 0, max: 10 },
  ];
}

/** "Trend · last 5 visits": BP (systolic, target 140) and blood sugar, from the earlier signed visits (empty until visits are signed, SAH-51). */
@Component({
  selector: 'app-gm-trend',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpTrendChartComponent],
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      <h4 class="sp-h">Trend · last 5 visits</h4>
      <div class="seg" role="group" aria-label="Trend">
        <button type="button" [attr.aria-pressed]="tab() === 'bp'" (click)="tab.set('bp')">BP</button>
        <button type="button" [attr.aria-pressed]="tab() === 'sugar'" (click)="tab.set('sugar')">Sugar</button>
      </div>
      @if (points().length === 0) {
        <p class="sp-sub" data-testid="trend-empty">No earlier readings yet. They appear once earlier visits are signed.</p>
      } @else if (tab() === 'bp') {
        <app-sp-trend-chart [points]="points()" [target]="140" targetLabel="140 target" [min]="120" [max]="170" name="Systolic BP" />
      } @else {
        <app-sp-trend-chart [points]="points()" [target]="140" targetLabel="140 target" [min]="100" [max]="230" name="Random sugar" />
      }
      @if (note(); as n) {
        <p class="sp-sub" data-testid="trend-note">{{ n }}</p>
      }
    </div>
  `,
})
export class GmTrendComponent {
  protected readonly ctx = inject(SpecialtyContext);
  protected readonly tab = signal<'bp' | 'sugar'>('bp');
  protected readonly points = computed(() => {
    const t = this.ctx.history()?.trend;
    if (!t) {
      return [];
    }
    return this.tab() === 'bp'
      ? t.bp.map((p) => ({ label: formatDisplayDate(p.date).slice(0, 6), value: p.systolic }))
      : t.sugar.map((p) => ({ label: formatDisplayDate(p.date).slice(0, 6), value: p.mgdl }));
  });
  protected readonly note = computed(() => {
    const p = this.points();
    if (p.length < 2) {
      return null;
    }
    const last = p[p.length - 1].value;
    const first = p[0].value;
    const direction = last < first ? 'Coming down' : last > first ? 'Going up' : 'Steady';
    return `${direction}; ${last > 140 ? 'still above' : 'at or below'} the 140 target.`;
  });

  constructor() {
    if (this.ctx.history() === null) {
      this.ctx.loadHistory();
    }
  }
}

/**
 * VITALS_LOG (SAH-60-1, SAH-65-2): "General examination" with Today, History and Trend. Typing is the
 * only way in this phase; Handwriting and Voice are Later (OQ-45-27). One entry is added per "Record
 * general examination"; nurse entries arrive with SAH-72. Flags are text as well as colour, and the
 * server's flags win when nothing is unsaved.
 */
@Component({
  selector: 'app-sp-vitals-log',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpStepperComponent, SpBpStepperComponent, SpSparklineComponent],
  styleUrls: ['../specialty.shared.scss'],
  template: `
    <div class="sp">
      <div class="sp-row">
        <div class="seg" role="group" aria-label="How to enter the readings">
          <button type="button" aria-pressed="false" disabled title="Needs the handwriting service">Handwriting <span class="later">Later</span></button>
          <button type="button" aria-pressed="true">Type</button>
          <button type="button" aria-pressed="false" disabled title="Needs the voice service">Voice <span class="later">Later</span></button>
        </div>
        <div class="seg" role="group" aria-label="Readings view">
          @for (v of views; track v.key) {
            <button type="button" [attr.aria-pressed]="shown() === v.key" (click)="shown.set(v.key)">
              {{ v.label }}@if (v.key === 'history' && historyCount() > 0) {<span> ({{ historyCount() }})</span>}
            </button>
          }
        </div>
      </div>

      @if (shown() === 'today') {
        <p class="sp-sub" data-testid="vitals-sub">{{ sub() }}</p>
        <div class="sp-grid">
          <app-sp-bp-stepper
            [systolic]="draft().bpSystolic"
            [diastolic]="draft().bpDiastolic"
            [note]="flagOf('bpSystolic')"
            [disabled]="disabled()"
            (valueChange)="setBp($event)"
          />
          @for (f of fields(); track f.key) {
            <app-sp-stepper
              [label]="f.label"
              [unit]="f.unit"
              [value]="draftValue(f.key)"
              [stepBy]="f.step"
              [start]="f.start"
              [min]="f.min"
              [max]="f.max"
              [note]="flagOf(f.key)"
              [disabled]="disabled()"
              (valueChange)="setDraft(f.key, $event)"
            />
          }
        </div>
        @if (!disabled()) {
          <div class="sp-row">
            <button type="button" class="btn pri" data-act="record-vitals" [disabled]="!canRecord()" (click)="record()">
              Record general examination
            </button>
            <span class="sp-sub" data-testid="ge-summary">{{ summary() }}</span>
          </div>
        }
        @if (serverFlags().length > 0) {
          <div class="sp-row" aria-label="Flags of the latest entry" data-testid="server-flags">
            @for (f of serverFlags(); track $index) {
              <span class="pill" [class.p-crit]="f.alert" [class.p-warn]="!f.alert">{{ f.field }}: {{ f.label }}</span>
            }
          </div>
        }
        @if (limitReached()) {
          <p class="msg warn" role="status">Up to 20 entries for one visit.</p>
        }
      } @else if (shown() === 'history') {
        @if (ctx.historyError(); as err) {
          <p class="msg crit" role="alert">{{ err }}</p>
        }
        <div class="scroll">
          <table class="tbl">
            <thead>
              <tr>
                <th>Date &amp; time</th><th>Recorded by</th><th>BP</th><th>PR</th><th>SpO2</th><th>Temp</th><th>RR</th><th>Pain</th><th>How</th>
              </tr>
            </thead>
            <tbody>
              @for (e of unsavedEntries(); track e.id) {
                <tr class="today">
                  <td>This visit <span class="pill p-wait">Not saved</span></td><td>You</td>
                  <td>{{ bp(e) }}</td><td>{{ e.pulse ?? '' }}</td><td>{{ e.spo2 ?? '' }}</td><td>{{ e.temperature?.value ?? '' }}</td>
                  <td>{{ e.respRate ?? '' }}</td><td>{{ e.pain ?? '' }}</td><td>Typed</td>
                </tr>
              }
              @for (r of history(); track $index) {
                <tr>
                  <td>{{ when(r.recordedAt) }}</td><td>{{ r.recordedByName }}</td><td>{{ r.bp ?? '' }}</td><td>{{ r.pulse ?? '' }}</td>
                  <td>{{ r.spo2 ?? '' }}</td><td>{{ r.temperature ?? '' }}</td><td>{{ r.respRate ?? '' }}</td><td>{{ r.pain ?? '' }}</td><td>{{ how(r.how) }}</td>
                </tr>
              } @empty {
                @if (unsavedEntries().length === 0) {
                  <tr><td colspan="9" class="sp-sub">No earlier readings.</td></tr>
                }
              }
            </tbody>
          </table>
        </div>
        @if (ctx.history()?.someHidden) {
          <p class="sp-sub">Some readings are hidden</p>
        }
      } @else {
        <div class="six" data-testid="ge-trends">
          @for (t of trends(); track t.key) {
            <div class="tcard" [class.bad]="t.bad">
              <div class="trow">
                <span class="sp-lbl">{{ t.label }}</span>
                <b class="tlast" [class.bad]="t.bad">{{ t.last }}</b>
              </div>
              <app-sp-sparkline [values]="t.values" [bad]="t.bad" [label]="t.aria" />
              <span class="sp-sub">{{ t.note }}</span>
            </div>
          } @empty {
            <p class="sp-sub" data-testid="trend-empty">No readings yet. They appear once a reading is recorded.</p>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .six {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 10px;
    }
    .tcard {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 10px 12px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
    }
    .trow {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      gap: 6px;
    }
    .tlast {
      font-size: 1rem;
      color: var(--good);
    }
    .tlast.bad {
      color: var(--crit);
    }
    @media (max-width: 720px) {
      .six {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
  `,
})
export class VitalsLogComponent extends SpEditor<SpVitalsLog> {
  protected readonly component = 'VITALS_LOG' as const;
  protected blank(): SpVitalsLog {
    return { component: 'VITALS_LOG', schemaVersion: 1, entries: [] };
  }

  protected readonly views: { key: GeView; label: string }[] = [
    { key: 'today', label: 'Today' },
    { key: 'history', label: 'History' },
    { key: 'trend', label: 'Trend' },
  ];
  protected readonly shown = signal<GeView>('today');
  protected readonly draft = signal<Partial<SpVitalsEntry>>({});
  protected readonly unit = computed<'F' | 'C'>(() => (this.ctx.profile() === 'onco' ? 'C' : 'F'));
  /** The five single-value readings; BP is its own control that writes both of its values. */
  protected readonly fields = computed(() => vitalFields(this.unit()).filter((f) => f.key !== 'bpSystolic' && f.key !== 'bpDiastolic'));
  /** General Medicine records the six values together (mockup #DoctorGeneral); other profiles keep partial entries. */
  protected readonly needsAllSix = computed(() => this.ctx.profile() === 'gm');
  private readonly filledCount = computed(() => {
    const d = this.draft();
    const bp = d.bpSystolic != null && d.bpDiastolic != null ? 1 : 0;
    return bp + [d.pulse, d.spo2, d.temperature, d.respRate, d.pain].filter((v) => v !== null && v !== undefined).length;
  });
  protected readonly history = computed(() => this.ctx.history()?.vitals ?? []);
  protected readonly historyCount = computed(() => this.history().length + this.unsavedEntries().length);
  protected readonly limitReached = computed(() => this.val().entries.length >= 20);
  /** The server's flags of the latest recorded entry, shown while nothing is unsaved. */
  protected readonly serverFlags = computed(() => (this.fresh() ? (this.view()?.vitalsFlags ?? []) : []));

  constructor() {
    super();
    effect(() => {
      if (this.shown() !== 'today' && this.ctx.history() === null) {
        this.ctx.loadHistory();
      }
    });
  }

  /** Entries recorded in this visit that the server has not stored yet. */
  protected readonly unsavedEntries = computed(() => (this.fresh() ? [] : [...this.val().entries].reverse()));

  protected readonly canRecord = computed(() => {
    if (this.limitReached()) {
      return false;
    }
    if (this.needsAllSix()) {
      return this.filledCount() === 6;
    }
    return Object.values(this.draft()).some((v) => v !== null && v !== undefined);
  });

  /** The line beside "Record general examination" (mockup #DoctorGeneral geSummary); only the entry modes that are on are named. */
  protected readonly summary = computed(() => {
    if (!this.needsAllSix()) {
      return this.canRecord() ? '' : 'Type at least one reading, then record.';
    }
    const filled = this.filledCount();
    if (filled === 6) {
      const d = this.draft();
      return `Ready: BP ${d.bpSystolic}/${d.bpDiastolic} · PR ${d.pulse} · SpO2 ${d.spo2} · Temp ${d.temperature?.value} · RR ${d.respRate} · Pain ${d.pain}`;
    }
    return filled > 0 ? `${6 - filled} value(s) still empty.` : 'Type the 6 values, then record them.';
  });

  /** Readings of this visit and earlier visits, newest first, as the numbers the six charts need. */
  private readonly readings = computed(() => {
    const unsaved = this.unsavedEntries().map((e) => ({
      sys: e.bpSystolic ?? null,
      pulse: e.pulse ?? null,
      spo2: e.spo2 ?? null,
      temp: e.temperature?.value ?? null,
      rr: e.respRate ?? null,
      pain: e.pain ?? null,
    }));
    const earlier = this.history().map((r) => ({
      sys: r.bp ? Number(r.bp.split('/')[0]) || null : null,
      pulse: r.pulse ?? null,
      spo2: r.spo2 ?? null,
      temp: r.temperature != null && r.temperature !== '' ? Number(r.temperature) : null,
      rr: r.respRate ?? null,
      pain: r.pain ?? null,
    }));
    return [...unsaved, ...earlier];
  });

  /** "Trend" view: six small charts, each with the last value, the line and a sentence (mockup #DoctorGeneral). */
  protected readonly trends = computed(() => {
    const rows = this.readings();
    if (rows.length === 0) {
      return [];
    }
    const unit = this.unit();
    type Row = (typeof rows)[number];
    const defs: { key: string; label: string; unit: string; pick: (r: Row) => number | null; field: string }[] = [
      { key: 'bp', label: 'BP (systolic)', unit: '', pick: (r) => r.sys, field: 'bp' },
      { key: 'pr', label: 'PR', unit: ' /min', pick: (r) => r.pulse, field: 'pulse' },
      { key: 'spo2', label: 'SpO2', unit: ' %', pick: (r) => r.spo2, field: 'spo2' },
      { key: 'temp', label: 'Temp', unit: unit === 'F' ? ' °F' : ' °C', pick: (r) => r.temp, field: 'temperature' },
      { key: 'rr', label: 'RR', unit: ' /min', pick: (r) => r.rr, field: 'respRate' },
      { key: 'pain', label: 'Pain', unit: ' /10', pick: (r) => r.pain, field: 'pain' },
    ];
    const latest = this.history()[0];
    const lastEntry = this.unsavedEntries()[0];
    const probe: SpVitalsEntry | null = lastEntry ?? (latest ? this.entryOf(latest) : null);
    const flags = vitalsFlags(probe, this.ctx.profile());
    return defs.map((d) => {
      const values = rows.map(d.pick).filter((v): v is number => v !== null).reverse();
      const lastValue = values[values.length - 1];
      const flag = flags.find((f) => f.field === d.field) ?? null;
      return {
        key: d.key,
        label: d.label,
        values,
        last: lastValue === undefined ? '—' : `${lastValue}${d.unit}`,
        bad: flag !== null,
        note: lastValue === undefined ? 'No reading yet' : flag ? `${flag.label} at the last reading` : 'Within normal range',
        aria: `${d.label} over ${values.length} visit${values.length === 1 ? '' : 's'}: ${values.join(', ')}`,
      };
    });
  });

  protected readonly sub = computed(() => {
    const entries = this.val().entries;
    const last = entries[entries.length - 1];
    const by = last?.recorded?.byName;
    if (last) {
      return `${by ? `Recorded by ${by}` : 'Recorded'}${last.recorded?.at ? ` at ${formatDisplayDateTimeOfInstant(last.recorded.at)}` : ''}. Record again to add a new entry.`;
    }
    return 'The doctor’s entry for this visit is still needed.';
  });

  protected draftValue(key: VitalField['key']): number | null {
    const d = this.draft()[key];
    if (key === 'temperature') {
      return (d as SpVitalsEntry['temperature'])?.value ?? null;
    }
    return (d as number | null | undefined) ?? null;
  }

  protected setBp(bp: BpValue): void {
    this.draft.set({ ...this.draft(), bpSystolic: bp.systolic, bpDiastolic: bp.diastolic });
  }

  private entryOf(r: EmrHistoryVitals): SpVitalsEntry {
    const [sys, dia] = r.bp ? r.bp.split('/').map(Number) : [null, null];
    return {
      id: 'earlier',
      bpSystolic: sys || null,
      bpDiastolic: dia || null,
      pulse: r.pulse ?? null,
      spo2: r.spo2 ?? null,
      temperature: r.temperature ? { value: Number(r.temperature), unit: this.unit() } : null,
      respRate: r.respRate ?? null,
      pain: r.pain ?? null,
    };
  }

  protected setDraft(key: VitalField['key'], value: number | null): void {
    const next: Partial<SpVitalsEntry> = { ...this.draft() };
    if (key === 'temperature') {
      next.temperature = value === null ? null : { value, unit: this.unit() };
    } else {
      (next as Record<string, number | null>)[key] = value;
    }
    this.draft.set(next);
  }

  /** The flag beside a reading, from the rules on screen for the values being typed (the server's flags come back with the saved entry). */
  protected flagOf(key: VitalField['key']): EmrFlag | null {
    const flags = vitalsFlags({ id: 'draft', ...this.draft() }, this.ctx.profile());
    const field = key === 'bpSystolic' || key === 'bpDiastolic' ? 'bp' : key;
    return flags.find((f) => f.field === field) ?? null;
  }

  protected record(): void {
    if (!this.canRecord() || this.disabled()) {
      return;
    }
    const entry: SpVitalsEntry = { id: newId(), ...this.draft() };
    this.patch({ entries: [...this.val().entries, entry] });
  }

  protected bp(e: SpVitalsEntry): string {
    return e.bpSystolic != null && e.bpDiastolic != null ? `${e.bpSystolic}/${e.bpDiastolic}` : '';
  }

  protected when(instant: string): string {
    return formatDisplayDateTimeOfInstant(instant);
  }

  protected how(how: string): string {
    return how === 'TYPED' ? 'Typed' : how === 'NURSE' ? 'Typed at triage' : how;
  }
}

const SIGNS: { key: keyof SpExamGeneral['signs']; label: string }[] = [
  { key: 'pallor', label: 'Pallor' },
  { key: 'icterus', label: 'Icterus' },
  { key: 'cyanosis', label: 'Cyanosis' },
  { key: 'clubbing', label: 'Clubbing' },
  { key: 'lymphadenopathy', label: 'Lymphadenopathy' },
  { key: 'oedema', label: 'Oedema' },
  { key: 'dehydration', label: 'Dehydration' },
];

type GmSystemKey = keyof SpExamGeneral['systems'];

/** The drawn default texts of "Mark all normal" (tagged C-10); the first line is "General" (contract 0.5.1, ADR-023 D13). */
export const GM_SYSTEMS: { key: GmSystemKey; label: string; normal: string }[] = [
  { key: 'general', label: 'General', normal: 'No pallor, icterus, cyanosis, oedema' },
  { key: 'cvs', label: 'CVS', normal: 'S1 S2 heard, no murmur' },
  { key: 'rs', label: 'RS', normal: 'Air entry equal, clear' },
  { key: 'pa', label: 'P/A', normal: 'Soft, non-tender' },
  { key: 'cns', label: 'CNS', normal: 'Conscious, oriented, no focal deficit' },
];

/** EXAM_GENERAL (SAH-60-3): general signs (absent → present → clear, "All absent") and the systemic examination ("Mark all normal"). */
@Component({
  selector: 'app-sp-exam-general',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      <div class="sp-row">
        <h4 class="sp-h">General signs</h4>
        <span class="sp-sub">tap: absent → present → clear</span>
        @if (!disabled()) {
          <button type="button" class="link" data-act="all-absent" (click)="allAbsent()">All absent</button>
        }
      </div>
      <div class="sp-row" role="group" aria-label="General signs">
        @for (s of signs; track s.key) {
          @let st = signState(s.key);
          <button
            type="button"
            class="chip"
            [class.on]="st !== null"
            [class.bad]="st === 'PRESENT'"
            [class.good]="st === 'ABSENT'"
            [disabled]="disabled()"
            [attr.aria-label]="s.label + ': ' + signText(st)"
            (click)="cycle(s.key)"
          >
            {{ st === 'ABSENT' ? '✓ No ' + s.label.toLowerCase() : st === 'PRESENT' ? s.label : '+ ' + s.label }}
          </button>
        }
      </div>

      <div class="sp-row">
        <h4 class="sp-h">Systemic examination</h4>
        @if (!disabled()) {
          <button type="button" class="chip sq" [class.on]="allNormal()" data-act="all-normal" (click)="markAllNormal()">
            {{ allNormal() ? '✓ All normal' : 'Mark all normal' }}
          </button>
        }
      </div>
      @for (s of systems(); track s.key) {
        @let f = finding(s.key);
        <div class="field">
          <div class="sp-row">
            <span class="sp-lbl">{{ s.label }}</span>
            <button type="button" class="chip sq good" [class.on]="f?.state === 'NORMAL'" [disabled]="disabled()" (click)="setState(s.key, 'NORMAL')">Normal</button>
            <button type="button" class="chip sq bad" [class.on]="f?.state === 'ABNORMAL'" [disabled]="disabled()" (click)="setState(s.key, 'ABNORMAL')">Abnormal</button>
          </div>
          <textarea
            class="input"
            [class.bad]="f?.state === 'ABNORMAL' && !f?.text?.trim()"
            [attr.aria-label]="s.label + ' findings'"
            [disabled]="disabled()"
            [value]="f?.text ?? ''"
            maxlength="1000"
            [placeholder]="f?.state === 'ABNORMAL' ? 'What did you find?' : 'Type findings or tap “Mark all normal”'"
            (change)="setText(s.key, $event)"
          ></textarea>
        </div>
      }
    </div>
  `,
})
export class ExamGeneralComponent extends SpEditor<SpExamGeneral> {
  protected readonly component = 'EXAM_GENERAL' as const;
  protected blank(): SpExamGeneral {
    return { component: 'EXAM_GENERAL', schemaVersion: 1, signs: {}, systems: {} };
  }
  protected readonly signs = SIGNS;
  /** The systems in the drawn order; the reference list `gmSystems` (key = property name, sub = the normal text) wins when it has them. */
  protected readonly systems = computed(() => {
    const list = this.ctx.list(REFERENCE_LISTS.gmSystems);
    const known = new Set<string>(GM_SYSTEMS.map((d) => d.key));
    if (!list || list.length === 0 || !list.every((i) => known.has(i.key))) {
      return GM_SYSTEMS;
    }
    return list.map((i) => ({
      key: i.key as GmSystemKey,
      label: i.label,
      normal: i.sub ?? GM_SYSTEMS.find((d) => d.key === i.key)?.normal ?? '',
    }));
  });

  protected signState(key: keyof SpExamGeneral['signs']): SpSignState {
    return this.val().signs[key] ?? null;
  }

  protected signText(state: SpSignState): string {
    return state === 'ABSENT' ? 'absent' : state === 'PRESENT' ? 'present' : 'not asked';
  }

  protected cycle(key: keyof SpExamGeneral['signs']): void {
    const now = this.signState(key);
    const next: SpSignState = now === null ? 'ABSENT' : now === 'ABSENT' ? 'PRESENT' : null;
    this.patch({ signs: { ...this.val().signs, [key]: next } });
  }

  protected allAbsent(): void {
    this.patch({ signs: Object.fromEntries(SIGNS.map((s) => [s.key, 'ABSENT' as SpSignState])) });
  }

  protected finding(key: keyof SpExamGeneral['systems']): SpSystemFinding | null {
    return this.val().systems[key] ?? null;
  }

  protected allNormal(): boolean {
    return this.systems().every((s) => this.finding(s.key)?.state === 'NORMAL');
  }

  protected markAllNormal(): void {
    const systems: SpExamGeneral['systems'] = {};
    for (const s of this.systems()) {
      systems[s.key] = { state: 'NORMAL', text: s.normal };
    }
    this.patch({ systems });
  }

  protected setState(key: keyof SpExamGeneral['systems'], state: 'NORMAL' | 'ABNORMAL'): void {
    const current = this.finding(key);
    const normalText = this.systems().find((s) => s.key === key)?.normal ?? '';
    const text = state === 'NORMAL' ? (current?.state === 'NORMAL' && current.text ? current.text : normalText) : current?.state === 'ABNORMAL' ? (current.text ?? '') : '';
    this.patch({ systems: { ...this.val().systems, [key]: { state, text } } });
  }

  protected setText(key: keyof SpExamGeneral['systems'], event: Event): void {
    const current = this.finding(key);
    this.patch({ systems: { ...this.val().systems, [key]: { state: current?.state ?? 'ABNORMAL', text: textOrNull(event) } } });
  }
}
