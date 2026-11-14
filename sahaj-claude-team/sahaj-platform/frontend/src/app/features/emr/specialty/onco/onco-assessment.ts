import { ChangeDetectionStrategy, Component, computed, effect, signal } from '@angular/core';
import { BodyMapComponent } from '../../components/body-map.component';
import { pinsOf } from '../../components/body-map.logic';
import { EmrBodyMapValue } from '../../emr-phase3.model';
import { REFERENCE_LISTS } from '../specialty.context';
import { newId, TOX_KEYS, vitalsFlags } from '../specialty.logic';
import { SpEditor, numberOrNull, textOrNull, toggled } from '../shared/sp-editor';
import { SpSparklineComponent } from '../shared/sp-sparkline.component';
import { SpStepperComponent } from '../shared/sp-stepper.component';
import {
  BREAST_SKIN,
  ECOG_TEXT,
  EVENT_LABEL,
  GRADE_LINE,
  LAB_ROWS,
  LINE_CHECKS,
  LINE_DEVICES,
  ONC_SYSTEMS,
  RESPONSE_LABEL,
  RESPONSE_SHORT,
  SIGN_LABELS,
  TOX_LABEL,
  bsa,
  lesionColour,
  lesionPct,
  lesionSums,
} from '../onco.logic';
import {
  OncEvent,
  OncSystemKey,
  SpOncEcog,
  SpOncExam,
  SpOncLabs,
  SpOncTox,
  SpSignState,
  SpVitalsLog,
  SpSystemFinding,
  ToxKey,
} from '../specialty.model';

type ExamTab = 'v' | 't' | 'b' | 's' | 'p';

/**
 * ONC_TOX (SAH-65-1; tagged C-8): "1 · Side effects since last cycle". Nine symptoms with the CTCAE
 * grade 0 to 4 the doctor confirms today; the patient's own grade is shown beside it but never counts
 * as confirmed (the patient's symptom check needs the WhatsApp service and is Later). "Accept patient
 * grades" copies the patient's grades into the empty confirmations. Done: all nine confirmed.
 */
@Component({
  selector: 'app-sp-onc-tox',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './onco.scss'],
  template: `
    <div class="sp">
      <p class="sp-sub">{{ gradeLine }}</p>
      <div class="sp-row">
        <button type="button" class="btn pri" data-act="accept-grades" [disabled]="disabled() || acceptable() === 0" (click)="acceptPatient()">
          {{ unconfirmed() === 0 ? '✓ All grades confirmed' : 'Accept patient grades (' + acceptable() + ')' }}
        </button>
        <span class="sp-sub">{{ unconfirmed() }} not confirmed yet. The patient’s own symptom check on WhatsApp is Later.</span>
      </div>
      <div class="scroll">
        <table class="tbl" aria-label="Side effects">
          <thead><tr><th>Symptom</th>@for (c of cycleHeads(); track c) {<th>C{{ c }}</th>}<th>Patient said</th><th>Today · your grade</th></tr></thead>
          <tbody>
            @for (s of rows(); track s.key) {
              <tr>
                <td><b>{{ label(s.key) }}</b>@if (hint(s); as h) {<span class="sp-sub"> {{ h }}</span>}</td>
                @for (c of cycleHeads(); track c) {
                  <td>{{ cycleGrade(s.key, c) }}</td>
                }
                <td>{{ s.patientGrade ?? '—' }}</td>
                <td>
                  <div class="sp-row grades" role="group" [attr.aria-label]="'Your grade for ' + label(s.key)">
                    @for (g of grades; track g) {
                      <button type="button" class="gr" [attr.data-g]="g" [class.on]="s.confirmedGrade === g" [disabled]="disabled()" [attr.aria-pressed]="s.confirmedGrade === g" (click)="setGrade(s.key, g)">{{ g }}</button>
                    }
                  </div>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      <span class="sp-lbl">Since last cycle:</span>
      <div class="sp-row" role="group" aria-label="Events since last cycle">
        @for (e of events; track e.key) {
          <button type="button" class="chip" [class.on]="val().events.includes(e.key)" [disabled]="disabled()" (click)="patch({ events: toggled_(val().events, e.key) })">
            {{ val().events.includes(e.key) ? '✓ ' : '+ ' }}{{ e.label }}
          </button>
        }
      </div>
      @for (a of toxAlerts(); track a.title) {
        <div class="msg crit" role="alert" data-testid="tox-alert"><b>{{ a.title }}</b> <span>{{ a.text }}</span></div>
      }
    </div>
  `,
})
export class OncToxComponent extends SpEditor<SpOncTox> {
  protected readonly component = 'ONC_TOX' as const;
  protected blank(): SpOncTox {
    return { component: 'ONC_TOX', schemaVersion: 1, symptoms: [], events: [] };
  }
  protected readonly gradeLine = GRADE_LINE;
  protected readonly grades = [0, 1, 2, 3, 4];
  protected readonly events = (Object.keys(EVENT_LABEL) as OncEvent[]).map((key) => ({ key, label: EVENT_LABEL[key] }));

  constructor() {
    super();
    effect(() => {
      if (this.ctx.history() === null) {
        this.ctx.loadHistory();
      }
    });
  }

  /** All nine symptoms are always listed; only edited ones are stored. */
  protected readonly rows = computed(() =>
    TOX_KEYS.map((key) => this.val().symptoms.find((s) => s.key === key) ?? { key, patientGrade: null, confirmedGrade: null }),
  );
  protected readonly unconfirmed = computed(() => this.rows().filter((r) => r.confirmedGrade == null).length);
  protected readonly acceptable = computed(() => this.rows().filter((r) => r.confirmedGrade == null && r.patientGrade != null).length);
  protected readonly cycles = computed(() => this.ctx.history()?.oncologyCycles ?? []);
  protected readonly cycleHeads = computed(() => this.cycles().slice(-3).map((c) => c.cycleNo));

  protected label(key: ToxKey): string {
    return TOX_LABEL[key];
  }

  protected toggled_(list: readonly OncEvent[], key: OncEvent): OncEvent[] {
    return toggled(list, key);
  }

  protected cycleGrade(key: ToxKey, cycleNo: number): string {
    const g = this.cycles().find((c) => c.cycleNo === cycleNo)?.grades?.[key];
    return g == null ? '—' : String(g);
  }

  protected hint(s: { key: ToxKey; confirmedGrade: number | null }): string {
    const g = s.confirmedGrade;
    if (g == null) {
      return 'Not confirmed yet';
    }
    if (g >= 3) {
      return 'Severe · hold or reduce';
    }
    if (s.key === 'TINGLING_NUMBNESS' && g >= 1) {
      return 'New · important before paclitaxel';
    }
    if (s.key === 'FEVER_LOW_COUNTS' && g >= 1) {
      return 'Needs G-CSF and dose cut';
    }
    return '';
  }

  protected readonly toxAlerts = computed(() => {
    const out: { title: string; text: string }[] = [];
    const worst = Math.max(0, ...this.rows().map((r) => r.confirmedGrade ?? 0));
    if (worst >= 3) {
      out.push({ title: 'Grade 3 or more side effect.', text: 'Hold today or reduce the dose; record the reason.' });
    }
    const fn = (this.rows().find((r) => r.key === 'FEVER_LOW_COUNTS')?.confirmedGrade ?? 0) >= 1 || this.val().events.includes('FEVER_38_AT_HOME') || this.val().events.includes('ADMITTED');
    if (fn) {
      out.push({ title: 'Fever or admission since last cycle.', text: 'Consider pegfilgrastim from this cycle and a 20% dose cut.' });
    }
    return out;
  });

  private write(rows: SpOncTox['symptoms']): void {
    this.patch({ symptoms: rows });
  }

  protected setGrade(key: ToxKey, grade: number): void {
    this.write(this.rows().map((r) => (r.key === key ? { ...r, confirmedGrade: r.confirmedGrade === grade ? null : grade } : r)));
  }

  protected acceptPatient(): void {
    this.write(this.rows().map((r) => (r.confirmedGrade == null && r.patientGrade != null ? { ...r, confirmedGrade: r.patientGrade } : r)));
  }
}

const SIGN_CYCLE: Record<string, SpSignState> = { none: 'ABSENT', ABSENT: 'PRESENT', PRESENT: null };

/**
 * ONC_EXAM (SAH-65-2; tagged C-8; ADR-023 H1: the whole "2 · Examination" card lives here because the
 * Oncology starter hides the general examination). Four tabs: Vitals and general (weight, height,
 * body surface area, signs), Tumour and response (lesions, new lesion, breast and skin), Systems, and
 * Port or line. BSA, the weight change, the lesion changes and the response are the server's when
 * nothing is unsaved. The body map is not part of this card (see Local examination). Done: weight,
 * height and every system marked.
 */
@Component({
  selector: 'app-sp-onc-exam',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpStepperComponent, BodyMapComponent, SpSparklineComponent],
  styleUrls: ['../specialty.shared.scss', './onco.scss'],
  template: `
    <div class="sp">
      <p class="sp-sub" data-testid="exam-summary">{{ summary() }}</p>
      <div class="seg" role="group" aria-label="Examination">
        @for (t of tabs; track t.key) {
          <button type="button" [attr.aria-pressed]="tab() === t.key" (click)="tab.set(t.key)">{{ t.label }}<span class="sp-sub"> · {{ tabSub(t.key) }}</span></button>
        }
      </div>

      @switch (tab()) {
        @case ('v') {
          <div class="sp-grid" role="group" aria-label="Vitals of this visit" data-testid="exam-vitals">
            @for (v of vitalTiles(); track v.label) {
              <div class="vtile" [class.bad]="v.alert">
                <span class="sp-lbl">{{ v.label }}</span>
                <b>{{ v.value }}</b>
                <span class="sp-sub">{{ v.note }}</span>
              </div>
            }
          </div>
          <div class="sp-grid">
            <app-sp-stepper label="Height" unit="cm" [value]="val().heightCm" [stepBy]="0.5" [start]="160" [min]="50" [max]="250" [disabled]="disabled()" (valueChange)="patch({ heightCm: $event })" />
            <app-sp-stepper label="Weight" unit="kg" [value]="val().weightKg" [stepBy]="0.1" [start]="60" [min]="2" [max]="300" [disabled]="disabled()" (valueChange)="patch({ weightKg: $event })" />
          </div>
          <div class="sp-row bsa">
            <b data-testid="bsa">Body surface area {{ bsaText() }}</b>
            <span class="sp-sub">{{ weightNote() }}</span>
            <button type="button" class="chip sq" [class.on]="val().capAt2" [disabled]="disabled()" data-act="cap" (click)="patch({ capAt2: !val().capAt2 })">{{ val().capAt2 ? '✓ ' : '' }}Cap at 2.0 m²</button>
          </div>
          @if (onco()?.recalculateDoses) {
            <p class="msg warn" role="status" data-testid="recalc">Weight changed by {{ pctText() }}. Doses are recalculated.</p>
          }
          <span class="sp-lbl">General signs <span class="sp-sub">{{ noneText() }}</span></span>
          <div class="sp-row" role="group" aria-label="General signs">
            @for (s of signs; track s.key) {
              @let st = sign(s.key);
              <button type="button" class="chip" [class.on]="st !== null" [class.bad]="st === 'PRESENT'" [class.good]="st === 'ABSENT'" [disabled]="disabled()" (click)="cycleSign(s.key)">
                {{ st === 'ABSENT' ? '✓ No ' + s.label.toLowerCase() : st === 'PRESENT' ? s.label : '+ ' + s.label }}
              </button>
            }
          </div>
        }
        @case ('t') {
          <span class="sp-lbl">Measurable lesions · longest diameter (cm, calliper)</span>
          <div class="scroll">
            <table class="tbl" aria-label="Lesions">
              <thead><tr><th>Lesion</th><th>Baseline</th><th>{{ lastHead() }}</th><th>Today</th><th>Trend</th><th>Change</th><th><span class="sr-only">Remove</span></th></tr></thead>
              <tbody>
                @for (l of val().lesions; track l.id) {
                  <tr>
                    <td>{{ l.name }}</td>
                    <td>{{ l.baselineCm.toFixed(1) }}</td>
                    <td>{{ size(l.lastCm) }}</td>
                    <td>
                      <div class="sp-row">
                        <button type="button" class="mini" [attr.aria-label]="'Decrease ' + l.name" [disabled]="disabled()" (click)="stepLesion(l.id, -0.1)">−</button>
                        <b>{{ size(l.todayCm) }}</b>
                        <button type="button" class="mini" [attr.aria-label]="'Increase ' + l.name" [disabled]="disabled()" (click)="stepLesion(l.id, 0.1)">+</button>
                      </div>
                    </td>
                    <td><div class="spk"><app-sp-sparkline [values]="trendOf(l)" [label]="'Size of ' + l.name + ' over the cycles: ' + trendOf(l).join(', ')" /></div></td>
                    <td><span class="ch" [attr.data-colour]="colour(l)">{{ change(l) }}</span></td>
                    <td>@if (!disabled()) {<button type="button" class="link" [attr.aria-label]="'Remove ' + l.name" (click)="removeLesion(l.id)">×</button>}</td>
                  </tr>
                } @empty {
                  <tr><td colspan="7" class="sp-sub">No lesion measured. Add one below.</td></tr>
                }
              </tbody>
            </table>
          </div>
          @if (!disabled() && val().lesions.length < 10) {
            <div class="lesion-chips" role="group" aria-label="Quick add a lesion">
              @for (n of quickLesions; track n) {
                <button type="button" class="chip sq" data-act="quick-lesion" (click)="addQuick(n)">+ {{ n }}</button>
              }
            </div>
            <div class="sp-row">
              <input class="input grow" type="text" maxlength="60" placeholder="Lesion name" aria-label="Lesion name" [value]="lName()" (input)="lName.set(txtv($event))" />
              <input class="input num" type="number" step="0.1" min="0" max="50" placeholder="Baseline cm" aria-label="Baseline size in centimetres" [value]="lBase() ?? ''" (input)="lBase.set(numv($event))" />
              <button type="button" class="btn" data-act="add-lesion" [disabled]="!lName().trim() || lBase() === null" (click)="addLesion()">+ Add lesion</button>
            </div>
          }
          <p class="sp-sub" data-testid="sum-text">{{ sumText() }}</p>
          <p class="resp" [attr.data-resp]="onco()?.response ?? ''" data-testid="response">{{ respText() }}</p>
          <p class="sp-sub">RECIST-style rule: complete = all gone · partial = 30% or more smaller · progressive = 20% or more bigger than the smallest so far, or a new lesion. Confirm with imaging after cycle 4.</p>
          <button type="button" class="chip bad" [class.on]="val().newLesionSeen" [disabled]="disabled()" (click)="patch({ newLesionSeen: !val().newLesionSeen })">{{ val().newLesionSeen ? '✓ ' : '+ ' }}New lesion seen</button>
          <span class="sp-lbl">Breast and skin</span>
          <div class="sp-row" role="group" aria-label="Breast and skin">
            @for (b of breast; track b.key) {
              <button type="button" class="chip" [class.on]="val().breastSkin.includes(b.key)" [disabled]="disabled()" (click)="patch({ breastSkin: toggledB(val().breastSkin, b.key) })">{{ val().breastSkin.includes(b.key) ? '✓ ' : '+ ' }}{{ b.label }}</button>
            }
          </div>
        }
        @case ('b') {
          <app-body-map
            title="Body map · mark lumps, nodes, skin lesions, port site"
            [value]="val().bodyMap ?? null"
            [disabled]="disabled()"
            [inputId]="'onc-body-map'"
            [allowNoFindings]="false"
            (valueChange)="setBodyMap($event)"
          />
        }
        @case ('s') {
          <div class="sp-row">
            <span class="sp-lbl">Systemic examination</span>
            @if (!disabled()) {
              <button type="button" class="chip sq" [class.on]="allNormal()" data-act="all-normal" (click)="markAllNormal()">{{ allNormal() ? '✓ All normal' : 'Mark all normal' }}</button>
            }
          </div>
          @for (s of systems(); track s.key) {
            @let f = finding(s.key);
            <div class="field sysrow">
              <div class="sp-row">
                <b>{{ s.label }}</b><span class="sp-sub">{{ s.why }}</span>
                <button type="button" class="chip sq good" [class.on]="f?.state === 'NORMAL'" [disabled]="disabled()" (click)="setSystem(s.key, 'NORMAL')">Normal</button>
                <button type="button" class="chip sq bad" [class.on]="f?.state === 'ABNORMAL'" [disabled]="disabled()" (click)="setSystem(s.key, 'ABNORMAL')">Abnormal</button>
              </div>
              @if (f?.state === 'ABNORMAL') {
                <div class="sp-row">
                  @for (c of s.chips; track c) {
                    <button type="button" class="chip" [class.on]="f?.chips?.includes(c)" [disabled]="disabled()" (click)="toggleChip(s.key, c)">{{ f?.chips?.includes(c) ? '✓ ' : '+ ' }}{{ c }}</button>
                  }
                </div>
                <textarea class="input" [class.bad]="!f?.text?.trim()" maxlength="1000" placeholder="What did you find?" [attr.aria-label]="s.label + ' findings'" [value]="f?.text ?? ''" [disabled]="disabled()" (change)="setText(s.key, $event)"></textarea>
              }
            </div>
          }
        }
        @case ('p') {
          <span class="sp-lbl">Chemo line / access device</span>
          <div class="sp-row" role="group" aria-label="Access device">
            @for (d of devices; track d.key) {
              <button type="button" class="chip sq" [class.on]="val().line.device === d.key" [disabled]="disabled()" (click)="setDevice(d.key)">{{ d.label }}</button>
            }
          </div>
          <div class="sp-row" role="group" aria-label="Line checks">
            @for (c of lineChecks; track c.key) {
              <button type="button" class="chip" [class.on]="val().line.checks.includes(c.key)" [disabled]="disabled()" (click)="toggleCheck(c.key)">{{ val().line.checks.includes(c.key) ? '✓ ' : '+ ' }}{{ c.label }}</button>
            }
          </div>
          <p class="msg" [class.good]="onco()?.lineReady === true" [class.warn]="onco()?.lineReady === false" data-testid="port-note">{{ portNote() }}</p>
        }
      }
    </div>
  `,
})
export class OncExamComponent extends SpEditor<SpOncExam> {
  protected readonly component = 'ONC_EXAM' as const;
  protected blank(): SpOncExam {
    return { component: 'ONC_EXAM', schemaVersion: 1, capAt2: false, signs: {}, lesions: [], newLesionSeen: false, breastSkin: [], systems: {}, line: { device: null, checks: [] } };
  }
  protected readonly tabs: { key: ExamTab; label: string }[] = [
    { key: 'v', label: 'Vitals & general' },
    { key: 't', label: 'Tumour & response' },
    { key: 'b', label: 'Body map' },
    { key: 's', label: 'Systems' },
    { key: 'p', label: 'Port / line' },
  ];
  protected readonly tab = signal<ExamTab>('v');
  protected readonly signs = SIGN_LABELS;
  protected readonly breast = BREAST_SKIN;
  /** The five systems in the drawn order; the reference lists `oncSystems` and `oncSystemChips` win when they have them. */
  protected readonly systems = computed(() => {
    const list = this.ctx.list(REFERENCE_LISTS.oncSystems);
    const known = new Set<string>(ONC_SYSTEMS.map((d) => d.key));
    if (!list || list.length === 0 || !list.every((i) => known.has(i.key))) {
      return ONC_SYSTEMS;
    }
    const chips = this.ctx.list(REFERENCE_LISTS.oncSystemChips) ?? [];
    return list.map((i) => {
      const drawn = ONC_SYSTEMS.find((d) => d.key === i.key);
      const own = chips.filter((c) => c.group === i.key).map((c) => c.key);
      return { key: i.key as OncSystemKey, label: i.label, why: i.sub ?? drawn?.why ?? '', chips: own.length > 0 ? own : (drawn?.chips ?? []), normalText: drawn?.normalText ?? '' };
    });
  });
  protected readonly devices = LINE_DEVICES;
  protected readonly lineChecks = LINE_CHECKS;
  protected readonly lName = signal('');
  protected readonly lBase = signal<number | null>(null);
  protected readonly onco = this.ctx.onco;

  constructor() {
    super();
    effect(() => {
      if (this.tab() === 't' && this.ctx.history() === null) {
        this.ctx.loadHistory();
      }
    });
  }

  protected readonly bsaText = computed(() => {
    const used = this.onco()?.bsaUsed ?? bsa(this.val().heightCm, this.val().weightKg, this.val().capAt2).used;
    return used == null ? 'not worked out yet (needs height and weight)' : `${used.toFixed(2)} m²`;
  });
  protected readonly pctText = computed(() => {
    const p = this.onco()?.weightChangePct;
    return p == null ? '' : `${p > 0 ? '+' : ''}${p.toFixed(1)}%`;
  });
  protected readonly weightNote = computed(() => {
    const o = this.onco();
    return o?.cycle1WeightKg != null ? `Cycle 1: ${o.cycle1WeightKg} kg${o.weightChangePct != null ? ` · ${this.pctText()}` : ''}` : 'Cycle 1 weight is taken from today’s weight.';
  });
  /** The vitals of this visit, read from the Vitals card (VITALS_LOG), as tiles in the first tab (SAH-75-11 O-3). Nothing is typed here. */
  protected readonly vitalTiles = computed(() => {
    const key = this.ctx.sectionKeyOf('VITALS_LOG');
    const log = key ? this.ctx.value<SpVitalsLog>(key, 'VITALS_LOG') : null;
    const last = log?.entries[log.entries.length - 1] ?? null;
    const flags = vitalsFlags(last, this.ctx.profile());
    const tile = (label: string, value: string | null, field: string) => {
      const flag = flags.find((f) => f.field === field) ?? null;
      return { label, value: value ?? '—', alert: !!flag?.alert, note: value === null ? 'Not recorded yet' : (flag?.label ?? 'Normal') };
    };
    return [
      tile('BP', last?.bpSystolic != null && last.bpDiastolic != null ? `${last.bpSystolic}/${last.bpDiastolic}` : null, 'bp'),
      tile('PR', last?.pulse != null ? String(last.pulse) : null, 'pulse'),
      tile('Temp', last?.temperature ? `${last.temperature.value} °${last.temperature.unit}` : null, 'temperature'),
      tile('SpO₂', last?.spo2 != null ? `${last.spo2} %` : null, 'spo2'),
    ];
  });
  protected readonly noneText = computed(() => (Object.values(this.val().signs).some((s) => s === 'PRESENT') ? '' : 'None present'));

  protected readonly summary = computed(() => {
    const o = this.onco();
    const marked = this.systems().filter((s) => this.finding(s.key)).length;
    const abn = this.systems().filter((s) => this.finding(s.key)?.state === 'ABNORMAL').length;
    const resp = o?.response ? RESPONSE_LABEL[o.response] : 'response not measured';
    return `BSA ${this.bsaText()} · ${resp} · systems ${marked}/${this.systems().length}${abn > 0 ? ` · ${abn} abnormal` : ''}`;
  });

  protected tabSub(key: ExamTab): string {
    switch (key) {
      case 'v':
        return this.val().weightKg != null ? `BSA ${this.bsaText().replace(' m²', '')}` : 'Needs weight';
      case 't': {
        const r = this.onco()?.response;
        return r ? RESPONSE_LABEL[r] : 'Not measured';
      }
      case 'b': {
        const n = pinsOf(this.val().bodyMap).length;
        return n > 0 ? `${n} marked` : 'Optional';
      }
      case 's':
        return `${this.systems().filter((s) => this.finding(s.key)).length} of ${this.systems().length} checked`;
      default:
        return this.onco()?.lineReady === true ? 'Ready' : 'Check blood return';
    }
  }

  /** The body map tab's value; an empty map is stored as none so an untouched optional tab adds nothing. */
  protected setBodyMap(value: unknown): void {
    const map = value as EmrBodyMapValue | null;
    this.patch({ bodyMap: map && (pinsOf(map).length > 0 || map.noFindingsConfirmed) ? map : null });
  }

  protected txtv(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }
  protected numv(e: Event): number | null {
    return numberOrNull(e);
  }
  protected toggledB(list: SpOncExam['breastSkin'], key: SpOncExam['breastSkin'][number]): SpOncExam['breastSkin'] {
    return toggled(list, key);
  }

  // ---- signs and systems ----

  protected sign(key: keyof SpOncExam['signs']): SpSignState {
    return this.val().signs[key] ?? null;
  }

  protected cycleSign(key: keyof SpOncExam['signs']): void {
    const now = this.sign(key);
    this.patch({ signs: { ...this.val().signs, [key]: SIGN_CYCLE[now ?? 'none'] } });
  }

  protected finding(key: OncSystemKey): SpSystemFinding | null {
    return this.val().systems[key] ?? null;
  }

  protected allNormal(): boolean {
    return this.systems().every((s) => this.finding(s.key)?.state === 'NORMAL');
  }

  protected markAllNormal(): void {
    const next: SpOncExam['systems'] = {};
    for (const s of this.systems()) {
      next[s.key] = { state: 'NORMAL', text: s.normalText || null, chips: [] };
    }
    this.patch({ systems: next });
  }

  protected setSystem(key: OncSystemKey, state: 'NORMAL' | 'ABNORMAL'): void {
    const def = this.systems().find((s) => s.key === key);
    this.patch({ systems: { ...this.val().systems, [key]: { state, text: state === 'NORMAL' ? (def?.normalText || null) : '', chips: [] } } });
  }

  protected setText(key: OncSystemKey, e: Event): void {
    const f = this.finding(key);
    this.patch({ systems: { ...this.val().systems, [key]: { state: 'ABNORMAL', text: textOrNull(e), chips: f?.chips ?? [] } } });
  }

  protected toggleChip(key: OncSystemKey, chip: string): void {
    const f = this.finding(key);
    this.patch({ systems: { ...this.val().systems, [key]: { state: 'ABNORMAL', text: f?.text ?? null, chips: toggled(f?.chips ?? [], chip) } } });
  }

  // ---- lesions ----

  protected size(v: number | null | undefined): string {
    return v === null || v === undefined ? '—' : v.toFixed(1);
  }

  protected colour(l: SpOncExam['lesions'][number]): string {
    return lesionColour(lesionPct(l.baselineCm, l.todayCm));
  }

  protected change(l: SpOncExam['lesions'][number]): string {
    const p = lesionPct(l.baselineCm, l.todayCm);
    return p == null ? '—' : `${p > 0 ? '+' : ''}${p.toFixed(0)}%`;
  }

  protected stepLesion(id: string, d: number): void {
    this.patch({
      lesions: this.val().lesions.map((l) => (l.id === id ? { ...l, todayCm: Math.max(0, Math.round(((l.todayCm ?? l.lastCm ?? l.baselineCm) + d) * 10) / 10) } : l)),
    });
  }

  protected removeLesion(id: string): void {
    this.patch({ lesions: this.val().lesions.filter((l) => l.id !== id) });
  }

  /** The drawn quick-add names (mockup #DoctorOncology); a new lesion starts at 1.0 cm and the doctor measures from there. */
  protected readonly quickLesions = ['Supraclavicular node', 'Skin nodule', 'Opposite breast'];

  protected addQuick(name: string): void {
    if (this.val().lesions.length >= 10) {
      return;
    }
    this.patch({ lesions: [...this.val().lesions, { id: newId(), name, baselineCm: 1, todayCm: 1 }] });
  }

  /** "Last (C3)": the number of the latest earlier cycle. */
  protected readonly lastHead = computed(() => {
    const cycles = this.ctx.history()?.oncologyCycles ?? [];
    const last = cycles[cycles.length - 1];
    return last ? `Last (C${last.cycleNo})` : 'Last';
  });

  /** Baseline, the sizes of the earlier cycles and today's size, for the small trend line. */
  protected trendOf(l: SpOncExam['lesions'][number]): number[] {
    const earlier = (this.ctx.history()?.oncologyCycles ?? [])
      .map((c) => c.lesions?.find((x) => x.name === l.name)?.cm ?? null)
      .filter((v): v is number => v !== null);
    const today = l.todayCm ?? l.lastCm ?? null;
    return [l.baselineCm, ...earlier, ...(today === null ? [] : [today])];
  }

  protected addLesion(): void {
    const name = this.lName().trim();
    const base = this.lBase();
    if (!name || base === null) {
      return;
    }
    this.patch({ lesions: [...this.val().lesions, { id: newId(), name, baselineCm: Math.round(base * 10) / 10, todayCm: Math.round(base * 10) / 10 }] });
    this.lName.set('');
    this.lBase.set(null);
  }

  protected readonly sumText = computed(() => {
    const s = lesionSums(this.val());
    if (!s) {
      return 'Sum of diameters · no lesion measured';
    }
    const pct = s.pctFromBaseline;
    return `Sum of diameters · baseline ${s.baseline.toFixed(1)} cm → today ${s.today.toFixed(1)} cm (${pct > 0 ? '+' : ''}${pct.toFixed(0)}%)`;
  });

  protected readonly respText = computed(() => {
    const r = this.onco()?.response;
    const s = this.onco()?.sums;
    if (!r) {
      return 'Response not measured yet';
    }
    return `${RESPONSE_LABEL[r]} (${RESPONSE_SHORT[r]})${s ? ` · ${s.pctFromBaseline > 0 ? '+' : ''}${s.pctFromBaseline.toFixed(0)}% since baseline` : ''}`;
  });

  // ---- line ----

  protected setDevice(device: 'PORT' | 'PICC' | 'PERIPHERAL'): void {
    this.patch({ line: { ...this.val().line, device: this.val().line.device === device ? null : device } });
  }

  protected toggleCheck(check: SpOncExam['line']['checks'][number]): void {
    this.patch({ line: { ...this.val().line, checks: toggled(this.val().line.checks, check) } });
  }

  protected readonly portNote = computed(() => {
    const ready = this.onco()?.lineReady;
    if (this.val().line.device === null) {
      return 'Pick the access device.';
    }
    return ready === false ? 'Port: no blood return yet' : 'Line ready for a vesicant';
  });
}

/**
 * ONC_LABS (SAH-65-3; tagged C-8): "3 · Pre-chemo labs". Typed by the doctor (labs are not connected
 * yet, OQ-45-28); the drawn time cannot be in the future; the values are flagged against what is
 * needed and the badge says "n out of range" or "Within limits"; labs older than 72 hours say so.
 * Done: all seven values and the drawn time.
 */
@Component({
  selector: 'app-sp-onc-labs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpStepperComponent, SpSparklineComponent],
  styleUrls: ['../specialty.shared.scss', './onco.scss'],
  template: `
    <div class="sp">
      <p class="sp-sub">Typed by the doctor (labs are not connected yet)</p>
      <div class="sp-row">
        <label class="field">
          <span class="sp-lbl">Drawn at</span>
          <input class="input" type="datetime-local" [value]="drawnLocal()" [class.bad]="future()" [disabled]="disabled()" (change)="setDrawn($event)" />
        </label>
        <button type="button" class="btn" [disabled]="disabled()" data-act="now" (click)="drawnNow()">Now</button>
        <span class="pill" [class.p-crit]="(onco()?.labFails?.length ?? 0) > 0" [class.p-good]="(onco()?.labFails?.length ?? 0) === 0" data-testid="labs-badge">{{ onco()?.labsBadge ?? 'Within limits' }}</span>
        @if (onco()?.labsFresh === false) {
          <span class="pill p-warn" data-testid="labs-old">Older than 72 hours</span>
        }
      </div>
      @if (future()) {
        <p class="msg crit" role="alert">The drawn time cannot be in the future.</p>
      }
      <div class="scroll">
        <table class="tbl" aria-label="Pre-chemo labs">
          <thead><tr><th>Test</th><th>{{ cycleHead() }}</th><th>Trend</th><th>Today</th><th>Needed</th><th>Check</th></tr></thead>
          <tbody>
            @for (l of rows; track l.key) {
              <tr>
                <td>{{ l.label }} <span class="sp-sub">{{ l.unit }}</span></td>
                <td class="sp-sub">{{ earlier(l.key) }}</td>
                <td><div class="spk"><app-sp-sparkline [values]="trend(l.key)" [limit]="l.min ?? l.max" [bad]="failed(l.key)" [label]="l.label + ' over the cycles: ' + trend(l.key).join(', ')" /></div></td>
                <td>
                  <app-sp-stepper [label]="l.label" [unit]="l.unit" [value]="val().values[l.key]" [stepBy]="l.step" [start]="startOf(l.key)" [min]="0" [disabled]="disabled()" (valueChange)="setValue(l.key, $event)" />
                </td>
                <td>{{ l.needed }}</td>
                <td>
                  @if (hasValue(l.key)) {
                    <span class="ok" [class.bad]="failed(l.key)" [attr.data-testid]="'lab-' + l.key">{{ failed(l.key) ? '✗ Out of range' : '✓ OK' }}</span>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </div>
  `,
})
export class OncLabsComponent extends SpEditor<SpOncLabs> {
  protected readonly component = 'ONC_LABS' as const;
  protected blank(): SpOncLabs {
    return { component: 'ONC_LABS', schemaVersion: 1, drawnAt: null, values: {} };
  }
  protected readonly rows = LAB_ROWS;
  protected readonly onco = this.ctx.onco;

  constructor() {
    super();
    effect(() => {
      if (this.ctx.history() === null) {
        this.ctx.loadHistory();
      }
    });
  }

  protected readonly future = computed(() => {
    const at = this.val().drawnAt;
    return !!at && new Date(at).getTime() > Date.now() + 60_000;
  });

  protected drawnLocal(): string {
    const at = this.val().drawnAt;
    if (!at) {
      return '';
    }
    const d = new Date(at);
    const pad = (n: number): string => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  protected setDrawn(e: Event): void {
    const raw = (e.target as HTMLInputElement).value;
    this.patch({ drawnAt: raw ? new Date(raw).toISOString() : null });
  }

  protected drawnNow(): void {
    this.patch({ drawnAt: new Date().toISOString() });
  }

  protected setValue(key: (typeof LAB_ROWS)[number]['key'], value: number | null): void {
    this.patch({ values: { ...this.val().values, [key]: value } });
  }

  /** Where "+" starts: the limit that is needed, so the first tap lands near a sensible value. */
  protected startOf(key: (typeof LAB_ROWS)[number]['key']): number {
    const r = LAB_ROWS.find((x) => x.key === key);
    return r?.min ?? r?.max ?? 0;
  }

  protected hasValue(key: (typeof LAB_ROWS)[number]['key']): boolean {
    const v = this.val().values[key];
    return v !== null && v !== undefined;
  }

  protected failed(key: string): boolean {
    return (this.onco()?.labFails ?? []).some((f) => f.key === key);
  }

  /** "C1 · C2 · C3": the cycles whose values are listed. */
  protected readonly cycleHead = computed(() => {
    const cycles = (this.ctx.history()?.oncologyCycles ?? []).slice(-3);
    return cycles.length > 0 ? cycles.map((c) => `C${c.cycleNo}`).join(' · ') : 'Earlier cycles';
  });

  /** The earlier cycles' values then today's, for the trend line with the dashed limit. */
  protected trend(key: (typeof LAB_ROWS)[number]['key']): number[] {
    const earlier = (this.ctx.history()?.oncologyCycles ?? []).slice(-3).map((c) => c.labs[key]).filter((v): v is number => v != null);
    const today = this.val().values[key];
    return today == null ? earlier : [...earlier, today];
  }

  protected earlier(key: string): string {
    const cycles = (this.ctx.history()?.oncologyCycles ?? []).slice(-3);
    const vals = cycles.map((c) => c.labs[key]).filter((v): v is number => v != null);
    return vals.length > 0 ? vals.join(' · ') : '—';
  }

}

/** ONC_ECOG (SAH-65-3; tagged C-8): "4 · Performance status (ECOG)" 0 to 4; fit for chemo at 2 or lower. */
@Component({
  selector: 'app-sp-onc-ecog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './onco.scss'],
  template: `
    <div class="sp">
      <p class="sp-sub" data-testid="ecog-note">{{ note() }}</p>
      <div class="grid5" role="group" aria-label="ECOG performance status">
        @for (t of texts; track $index) {
          <button type="button" class="tile" [class.on]="val().grade === $index" [disabled]="disabled()" [attr.aria-pressed]="val().grade === $index" (click)="patch({ grade: val().grade === $index ? null : $index })">
            <b>{{ $index }}</b><span class="sp-sub">{{ t }}</span>
          </button>
        }
      </div>
    </div>
  `,
})
export class OncEcogComponent extends SpEditor<SpOncEcog> {
  protected readonly component = 'ONC_ECOG' as const;
  protected blank(): SpOncEcog {
    return { component: 'ONC_ECOG', schemaVersion: 1, grade: null };
  }
  protected readonly texts = ECOG_TEXT;
  protected readonly note = computed(() => {
    const g = this.val().grade;
    return g == null ? 'Pick the ECOG grade.' : `ECOG ${g} · ${g <= 2 ? 'fit for chemo' : 'usually not fit for chemo'}`;
  });
}
