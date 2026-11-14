import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { printSheet } from '../print-sheet';
import { formatDisplayDate } from '../../../../shared/format/ist-date';
import { SpEditor, textOrNull, toggled } from '../shared/sp-editor';
import { SpStepperComponent } from '../shared/sp-stepper.component';
import {
  ANC_FIELDS,
  ANC_SYMPTOMS,
  AncAlert,
  DUE_ORDERS,
  EXTRA_ORDERS,
  GYN_QUICK_ORDERS,
  SCANS,
  SUPPLEMENTS,
  ancAlerts,
  ancNote,
  anaemiaGrade,
  highBp,
} from '../obgyn-paeds-physio.logic';
import { AncOrderKey, AncScanKey, EmrFlag, SpAncCheck, SpFormF, SpGynaeExam } from '../specialty.model';

const BLANK_ANC: SpAncCheck = {
  component: 'ANC_CHECK',
  schemaVersion: 1,
  today: {},
  symptoms: [],
  orders: [],
  scans: [],
};

/**
 * ANC_CHECK (SAH-61-1, SAH-61-4; tagged C-4): the antenatal summary, today's check with live notes and
 * alerts, scans and due orders, supplements and the ANC visits table. Gestational age, the due date and
 * the obstetric score come from the server (LMP and the obstetric history live in other sections; the
 * server's answer wins). An order recorded here is also added to Investigations; no lab order is sent
 * (OQ-45-28). Ticked supplements become prescription rows.
 */
@Component({
  selector: 'app-sp-anc-check',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpStepperComponent],
  styleUrls: ['../specialty.shared.scss', './obgyn.scss'],
  template: `
    <div class="sp">
      @if (main()) {
      @if (risks().length > 0) {
        <div class="sp-row" aria-label="Risks">
          @for (r of risks(); track r.label) {
            <span class="pill" [class.p-crit]="r.alert" [class.p-warn]="!r.alert">{{ r.label }}</span>
          }
        </div>
      }

      <div class="sum">
        <div class="cell">
          <span class="sp-lbl">Gestational age</span>
          <b data-testid="ga">{{ gaText() }}</b>
          <span class="sp-sub">{{ trimester() }}</span>
        </div>
        <div class="cell">
          <span class="sp-lbl">EDD</span>
          <b data-testid="edd">{{ eddText() }}</b>
          <span class="sp-sub">{{ eddSource() }}</span>
        </div>
        <div class="cell">
          <span class="sp-lbl">Obstetric score</span>
          <b>{{ og()?.obstetricScore ?? 'Not recorded' }}</b>
        </div>
        <div class="cell">
          <span class="sp-lbl">Days to EDD</span>
          <b>{{ daysText() }}</b>
        </div>
      </div>
      <label class="field narrow">
        <span class="sp-lbl">Dating scan EDD (replaces the date from LMP)</span>
        <input class="input" type="date" [value]="val().datingScanEdd ?? ''" [disabled]="disabled()" (change)="patch({ datingScanEdd: txt($event) })" />
      </label>

      <div class="timeline" role="img" [attr.aria-label]="'Pregnancy timeline, ' + gaText()">
        <div class="bar-track"><span class="bar" [style.width.%]="gaPct()"></span></div>
        <div class="ticks"><span>Week 0</span><span>12 w</span><span>28 w</span><span>40 w</span></div>
      </div>

      <h4 class="sp-h">Scans and tests</h4>
      <div class="sp-row" role="group" aria-label="Scans and tests">
        @for (s of scans; track s.key) {
          <button type="button" class="tile scan" [class.on]="scanSel() === s.key" [class.due]="scanState(s.key) === 'DUE'" (click)="scanSel.set(s.key)">
            <b>{{ s.name }}</b>
            <span class="sp-sub">{{ s.window }}</span>
            <span class="st" [attr.data-state]="scanState(s.key)">{{ scanStateText(s.key) }}</span>
          </button>
        }
      </div>
      <div class="msg" data-testid="scan-detail">
        <span>{{ scanDetail() }}</span>
        <label class="chk">
          <input type="checkbox" [checked]="scanDone(scanSel())" [disabled]="disabled()" (change)="setScanDone($event)" />
          Done
        </label>
        <input class="input" type="text" maxlength="200" [value]="scanFinding()" [disabled]="disabled()" aria-label="Finding" placeholder="Finding" (change)="setFinding($event)" />
      </div>

      <h4 class="sp-h">Today’s ANC check <span class="sp-sub">Use − / + ; warnings update as you go</span></h4>
      <div class="sp-grid">
        @for (f of fields; track f.key) {
          <app-sp-stepper
            [label]="f.label"
            [unit]="f.unit"
            [value]="val().today[f.key]"
            [stepBy]="f.step"
            [start]="f.start"
            [note]="note(f.key)"
            [disabled]="disabled()"
            (valueChange)="setToday(f.key, $event)"
          />
        }
      </div>
      <div class="sp-row" role="group" aria-label="Symptoms">
        @for (s of symptoms; track s.key) {
          <button type="button" class="chip bad" [class.on]="val().symptoms.includes(s.key)" [disabled]="disabled()" (click)="toggleSymptom(s.key)">
            {{ val().symptoms.includes(s.key) ? '✓ ' : '+ ' }}{{ s.label }}
          </button>
        }
      </div>
      @for (a of alerts(); track a.id) {
        <div class="msg crit" role="alert" data-testid="anc-alert">
          <div>
            <b>{{ a.title }}</b> <span>{{ a.text }}</span>
          </div>
          @if (!disabled()) {
            <button type="button" class="btn warn" (click)="alertAction(a)">{{ a.button }}</button>
          }
        </div>
      }

      }
      @if (tools()) {
      <h4 class="sp-h">Due now · {{ dueLeft() }} left</h4>
      <div class="sp-row">
        @for (d of due; track d.key) {
          <button type="button" class="chip sq" [class.on]="isOrdered(d.key)" [disabled]="disabled()" data-act="order" (click)="toggleOrder(d.key)">
            {{ isOrdered(d.key) ? '✓ ' : '' }}{{ d.label }} <span class="sp-sub">{{ isOrdered(d.key) ? 'ordered' : d.window }}</span>
          </button>
        }
        @if (!disabled()) {
          <button type="button" class="btn" data-act="order-all" [disabled]="dueLeft() === 0" (click)="orderAll()">Order all</button>
        }
      </div>
      <p class="sp-sub">Ordering records the test in Investigations. No order goes to a lab yet.</p>

      <div class="sp-row">
        <b>PCPNDT Form F</b> <span class="pill p-crit">Legal</span>
        <span class="sp-sub" data-testid="formf-text">{{ formFText() }}</span>
      </div>

      <h4 class="sp-h">Supplements</h4>
      <div class="sp-row">
        @for (s of supps; track s.key) {
          <button type="button" class="chip sq good" [class.on]="supplementOn(s.key)" [disabled]="disabled()" data-act="supplement" (click)="toggleSupplement(s.key)">
            {{ s.name }} <span class="sp-sub">{{ s.dose }}</span> · {{ supplementOn(s.key) ? 'Continue' : 'Stopped' }}
          </button>
        }
      </div>

      }
      @if (main()) {
      <div class="sp-row">
        <app-sp-stepper
          class="narrow"
          label="Next ANC at"
          unit="weeks"
          [value]="val().nextAncWeeks"
          [stepBy]="1"
          [start]="30"
          [min]="4"
          [max]="42"
          [disabled]="disabled()"
          (valueChange)="patch({ nextAncWeeks: $event })"
        />
        <label class="field grow">
          <span class="sp-lbl">Plan note</span>
          <input class="input" type="text" maxlength="200" [value]="val().planNote ?? ''" [disabled]="disabled()" (change)="patch({ planNote: txt($event) })" />
        </label>
      </div>

      <h4 class="sp-h">ANC visits</h4>
      <div class="scroll">
        <table class="tbl" aria-label="ANC visits">
          <thead><tr><th>Date</th><th>GA</th><th>Weight</th><th>BP</th><th>FH</th><th>Hb</th><th>Notes</th></tr></thead>
          <tbody>
            @for (v of visits(); track $index) {
              <tr [class.today]="v.today">
                <td>{{ v.date }}</td><td>{{ v.ga }}</td><td>{{ v.wt }}</td><td>{{ v.bp }}</td><td>{{ v.fh }}</td><td>{{ v.hb }}</td><td>{{ v.note }}</td>
              </tr>
            } @empty {
              <tr><td colspan="7" class="sp-sub">No earlier ANC visits for this pregnancy.</td></tr>
            }
          </tbody>
        </table>
      </div>
      }
    </div>
  `,
})
export class AncCheckComponent extends SpEditor<SpAncCheck> {
  protected readonly component = 'ANC_CHECK' as const;
  /** "main": the ANC column; "tools": the Quick tools cards (Due now, PCPNDT Form F, Supplements); "all": both (SAH-75-1, O2). */
  readonly area = input<'all' | 'main' | 'tools'>('all');
  protected readonly main = computed(() => this.area() !== 'tools');
  protected readonly tools = computed(() => this.area() !== 'main');
  protected blank(): SpAncCheck {
    return BLANK_ANC;
  }

  protected readonly fields = ANC_FIELDS;
  protected readonly symptoms = ANC_SYMPTOMS;
  protected readonly due = DUE_ORDERS;
  protected readonly supps = SUPPLEMENTS;
  protected readonly scans = SCANS;
  protected readonly scanSel = signal<AncScanKey>('GROWTH');
  protected readonly og = computed(() => this.view()?.og ?? null);

  constructor() {
    super();
    this.registerFooterPrint(() => this.printAncCard());
    effect(() => {
      if (this.ctx.history() === null) {
        this.ctx.loadHistory();
      }
    });
  }

  private readonly doc = inject(DOCUMENT);
  /** "Print ANC card": the pregnancy summary and the ANC visits on one page; nothing is stored or sent. */
  protected printAncCard(): void {
    const lines = this.visits().map((v) => `${v.date} · ${v.ga} · wt ${v.wt} · BP ${v.bp} · FH ${v.fh} · Hb ${v.hb}${v.note ? ` · ${v.note}` : ''}`);
    const summary = [`Gestational age: ${this.gaText()}`, `EDD: ${this.eddText()}`, `Obstetric score: ${this.og()?.obstetricScore ?? 'Not recorded'}`];
    printSheet(this.doc, this.ctx.sheet('ANC card', [{ heading: 'Pregnancy', lines: summary }, { heading: 'ANC visits', lines }]));
  }

  protected readonly gaWeeks = computed(() => this.og()?.gestationalAge?.weeks ?? null);
  protected readonly gaText = computed(() => {
    const ga = this.og()?.gestationalAge;
    return ga ? `${ga.weeks}w ${ga.days}d` : 'Not recorded';
  });
  protected readonly daysText = computed(() => {
    const d = this.og()?.daysToEdd;
    return d === null || d === undefined ? 'Not recorded' : `${d} days`;
  });
  protected readonly gaPct = computed(() => Math.min(100, Math.max(0, ((this.gaWeeks() ?? 0) / 40) * 100)));
  protected readonly trimester = computed(() => {
    const w = this.gaWeeks();
    return w === null ? 'Needs LMP or dating scan' : w < 14 ? '1st trimester' : w < 28 ? '2nd trimester' : '3rd trimester';
  });
  protected readonly eddText = computed(() => {
    const edd = this.og()?.edd;
    return edd ? formatDisplayDate(edd) : 'Not recorded';
  });
  protected readonly eddSource = computed(() => {
    const s = this.og()?.eddSource;
    return s === 'LMP' ? 'From LMP' : s === 'DATING_SCAN' ? 'From dating scan' : '';
  });

  protected readonly alerts = computed(() => ancAlerts(this.val()));
  protected readonly dueLeft = computed(() => DUE_ORDERS.filter((d) => !this.isOrdered(d.key)).length);

  /** Risk chips: the server's (previous caesarean from the obstetric history) plus the ones the values on screen give. */
  protected readonly risks = computed<EmrFlag[]>(() => {
    const server = this.fresh() ? (this.og()?.riskChips ?? []) : (this.og()?.riskChips ?? []).filter((c) => !/anaemia|BP high/i.test(c.label));
    const out = [...server];
    const t = this.val().today;
    const grade = anaemiaGrade(t.hbGdl);
    if (!this.fresh()) {
      if (grade) {
        out.push({ field: 'hb', label: `${grade} anaemia · Hb ${(t.hbGdl ?? 0).toFixed(1)}`, alert: false });
      }
      if (highBp(t)) {
        out.push({ field: 'bp', label: 'BP high today', alert: true });
      }
    }
    return out;
  });

  protected note(key: (typeof ANC_FIELDS)[number]['key']): EmrFlag | null {
    if (this.fresh()) {
      const server = (this.og()?.notes ?? []).find((n) => n.field === key || (key === 'bpDiastolic' && n.field === 'bpSystolic'));
      if (server) {
        return server;
      }
    }
    return ancNote(key, this.val().today, this.gaWeeks(), this.og()?.weightChangeKg ?? null);
  }

  protected setToday(key: (typeof ANC_FIELDS)[number]['key'], value: number | null): void {
    this.patch({ today: { ...this.val().today, [key]: value } });
  }

  protected toggleSymptom(key: SpAncCheck['symptoms'][number]): void {
    this.patch({ symptoms: toggled(this.val().symptoms, key) });
  }

  protected txt(e: Event): string | null {
    return textOrNull(e);
  }

  protected visits = computed(() => {
    const rows = this.ctx.history()?.ancVisits ?? [];
    return rows.map((v) => ({
      date: formatDisplayDate(v.date),
      ga: v.gestationalAge ?? '—',
      wt: v.weightKg != null ? v.weightKg.toFixed(1) : '—',
      bp: v.bp ?? '—',
      fh: v.fundalHeightCm != null ? String(v.fundalHeightCm) : '—',
      hb: v.hbGdl != null ? v.hbGdl.toFixed(1) : '—',
      note: v.today ? 'Today' : (v.note ?? ''),
      today: v.today,
    }));
  });

  // ---- orders ----

  protected isOrdered(key: AncOrderKey): boolean {
    return this.val().orders.some((o) => o.key === key);
  }

  private investigationText(key: AncOrderKey): string {
    const due = DUE_ORDERS.find((d) => d.key === key);
    if (due) {
      return due.investigation;
    }
    return key === 'PIH_WORKUP' ? EXTRA_ORDERS.PIH_WORKUP.investigation : EXTRA_ORDERS.NST.investigation;
  }

  protected toggleOrder(key: AncOrderKey): void {
    if (this.isOrdered(key)) {
      this.patch({ orders: this.val().orders.filter((o) => o.key !== key) });
      this.ctx.removeInvestigation(this.investigationText(key));
      return;
    }
    this.patch({ orders: [...this.val().orders, { key }] });
    this.ctx.addInvestigations([this.investigationText(key)]);
  }

  protected orderAll(): void {
    const fresh = DUE_ORDERS.filter((d) => !this.isOrdered(d.key));
    if (fresh.length === 0) {
      return;
    }
    this.patch({ orders: [...this.val().orders, ...fresh.map((d) => ({ key: d.key }))] });
    this.ctx.addInvestigations(fresh.map((d) => d.investigation));
  }

  protected alertAction(a: AncAlert): void {
    if (a.order) {
      if (!this.isOrdered(a.order)) {
        this.toggleOrder(a.order);
      }
      return;
    }
    this.patch({ anaemiaPlanAdded: true });
  }

  // ---- scans ----

  protected scanDone(key: AncScanKey): boolean {
    return this.val().scans.find((s) => s.key === key)?.done ?? false;
  }

  protected scanState(key: AncScanKey): 'DONE' | 'DUE' | 'LATER' {
    if (this.scanDone(key)) {
      return 'DONE';
    }
    return (this.og()?.scanStates ?? []).find((s) => s.key === key)?.state === 'DUE' ? 'DUE' : (this.og()?.scanStates ?? []).find((s) => s.key === key)?.state === 'DONE' ? 'DONE' : 'LATER';
  }

  protected scanStateText(key: AncScanKey): string {
    const s = this.scanState(key);
    return s === 'DONE' ? 'Done' : s === 'DUE' ? 'Due' : 'Later';
  }

  protected scanFinding(): string {
    return this.val().scans.find((s) => s.key === this.scanSel())?.finding ?? '';
  }

  protected scanDetail(): string {
    const def = SCANS.find((s) => s.key === this.scanSel());
    const state = this.scanState(this.scanSel());
    const base = state === 'DONE' ? `${def?.name}: done.` : state === 'DUE' ? `${def?.name}: due now (${def?.window}).` : `${def?.name}: planned for ${def?.window}.`;
    return def?.later ? `${base} ${def.later}` : base;
  }

  private upsertScan(patch: Partial<SpAncCheck['scans'][number]>): void {
    const key = this.scanSel();
    const rest = this.val().scans.filter((s) => s.key !== key);
    const current = this.val().scans.find((s) => s.key === key) ?? { key, done: false };
    this.patch({ scans: [...rest, { ...current, ...patch }] });
  }

  protected setScanDone(e: Event): void {
    this.upsertScan({ done: (e.target as HTMLInputElement).checked });
  }

  protected setFinding(e: Event): void {
    this.upsertScan({ finding: textOrNull(e) });
  }

  // ---- supplements ----

  protected supplementOn(key: NonNullable<SpAncCheck['supplements']>[number]): boolean {
    return (this.val().supplements ?? []).includes(key);
  }

  protected toggleSupplement(key: NonNullable<SpAncCheck['supplements']>[number]): void {
    const s = SUPPLEMENTS.find((x) => x.key === key);
    if (!s) {
      return;
    }
    const on = this.supplementOn(key);
    this.patch({ supplements: toggled(this.val().supplements ?? [], key) });
    if (on) {
      this.ctx.removeRx([s.name]);
    } else {
      this.ctx
        .addSpecialtyRx([{ name: s.name, frequency: s.rx.frequency, days: 30, when: s.rx.when, source: 'SPECIALTY_LIST', note: s.rx.note }])
        .subscribe();
    }
  }

  protected readonly formFText = computed(() => {
    const c = this.og()?.formFCount;
    return c ? `${c.complete} of ${c.total} forms complete for this pregnancy` : 'Form F opens when a growth scan is ordered.';
  });
}

/**
 * FORM_F (SAH-61-2; tagged L-2): the PCPNDT Form F tick. Needed while an obstetric scan is ordered in
 * this visit. The referral slip and the declaration signature are Later (OQ-45-28).
 */
@Component({
  selector: 'app-sp-form-f',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      <p class="msg" [class.warn]="required() && !val().filledAndSigned" [class.good]="val().filledAndSigned" data-testid="formf-state">
        {{ stateText() }}
      </p>
      <label class="chk2">
        <input type="checkbox" [checked]="val().filledAndSigned" [disabled]="disabled()" (change)="patch({ filledAndSigned: tick($event) })" />
        Form F filled and declaration signed
      </label>
      @if (val().scanRemoved) {
        <p class="sp-sub">Scan removed. Form F kept</p>
      }
      <p class="sp-sub">The referral slip file and the signature image are Later.</p>
    </div>
  `,
  styles: [
    `
      .chk2 {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        min-height: 44px;
        font-weight: 600;
      }
      .chk2 input {
        width: 20px;
        height: 20px;
      }
    `,
  ],
})
export class FormFComponent extends SpEditor<SpFormF> {
  protected readonly component = 'FORM_F' as const;
  protected blank(): SpFormF {
    return { component: 'FORM_F', schemaVersion: 1, filledAndSigned: false };
  }
  protected readonly required = computed(() => {
    const key = this.ctx.sectionKeyOf('ANC_CHECK');
    const anc = key ? this.ctx.value<SpAncCheck>(key, 'ANC_CHECK') : null;
    return !!anc?.orders.some((o) => o.key === 'GROWTH_SCAN_FORM_F') || (this.view()?.og?.formFRequired ?? false);
  });
  protected readonly stateText = computed(() =>
    this.val().filledAndSigned
      ? 'Form F is filled and signed.'
      : this.required()
        ? 'Form F is needed: an obstetric scan is ordered in this visit.'
        : 'No obstetric scan is ordered in this visit, so Form F is not needed yet.',
  );
  protected tick(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }
}

type AreaKey = 'perAbdomen' | 'perSpeculum' | 'perVaginum';

/** GYNAE_EXAM (SAH-61-3): P/A, P/S and P/V findings and the chaperone, who must be named or declined (safety rule). */
@Component({
  selector: 'app-sp-gynae-exam',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss'],
  styles: `
    .exam3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 10px;
    }
    @media (max-width: 900px) {
      .exam3 {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
  template: `
    <div class="sp">
      @if (main()) {
      <div class="exam3">
        @for (f of areas; track f.key) {
          <label class="field">
            <span class="sp-lbl">{{ f.label }}</span>
            <textarea class="input" maxlength="1000" [placeholder]="f.hint" [value]="text(f.key)" [disabled]="disabled()" (change)="setText(f.key, $event)"></textarea>
          </label>
        }
      </div>
      <div class="sp-row">
        <label class="field grow">
          <span class="sp-lbl">Female chaperone present</span>
          <input
            class="input"
            type="text"
            maxlength="80"
            autocomplete="off"
            [value]="val().chaperoneName ?? ''"
            [disabled]="disabled() || !!val().chaperoneDeclined"
            (change)="patch({ chaperoneName: name($event) })"
          />
        </label>
        <button type="button" class="chip sq" [class.on]="val().chaperoneDeclined" [disabled]="disabled()" data-act="declined" (click)="declined()">
          {{ val().chaperoneDeclined ? '✓ Declined by patient' : 'Declined by patient' }}
        </button>
      </div>
      @if (needsChaperone()) {
        <p class="msg warn" role="status">Record the chaperone’s name, or tap Declined by patient.</p>
      }
      }
      @if (tools()) {
      <span class="sp-lbl">Quick orders</span>
      <div class="sp-row" role="group" aria-label="Quick orders">
        @for (q of quick; track q) {
          <button type="button" class="chip" [class.on]="ordered().has(q)" [disabled]="disabled()" (click)="toggleQuick(q)">{{ ordered().has(q) ? '✓ ' : '+ ' }}{{ q }}</button>
        }
      </div>
      }
    </div>
  `,
})
export class GynaeExamComponent extends SpEditor<SpGynaeExam> {
  protected readonly component = 'GYNAE_EXAM' as const;
  /** "main": the examination; "tools": the "Quick orders" card in Quick tools; "all": both. */
  readonly area = input<'all' | 'main' | 'tools'>('all');
  protected readonly main = computed(() => this.area() !== 'tools');
  protected readonly tools = computed(() => this.area() !== 'main');
  protected blank(): SpGynaeExam {
    return { component: 'GYNAE_EXAM', schemaVersion: 1 };
  }
  protected readonly areas: { key: AreaKey; label: string; hint: string }[] = [
    { key: 'perAbdomen', label: 'Per abdomen', hint: '' },
    { key: 'perSpeculum', label: 'Per speculum', hint: 'Cervix, discharge' },
    { key: 'perVaginum', label: 'Per vaginum', hint: 'Uterus size, adnexa' },
  ];
  protected readonly quick = GYN_QUICK_ORDERS;
  protected readonly ordered = signal<ReadonlySet<string>>(new Set());

  protected toggleQuick(name: string): void {
    const next = new Set(this.ordered());
    if (next.has(name)) {
      next.delete(name);
      this.ctx.removeInvestigation(name);
    } else {
      next.add(name);
      this.ctx.addInvestigations([name]);
    }
    this.ordered.set(next);
  }
  protected readonly needsChaperone = computed(() => {
    const v = this.val();
    return !!(v.perAbdomen?.trim() || v.perSpeculum?.trim() || v.perVaginum?.trim()) && !v.chaperoneName?.trim() && !v.chaperoneDeclined;
  });

  protected text(key: AreaKey): string {
    return this.val()[key] ?? '';
  }

  protected setText(key: AreaKey, e: Event): void {
    this.patch({ [key]: textOrNull(e) } as Partial<SpGynaeExam>);
  }

  protected name(e: Event): string | null {
    return textOrNull(e);
  }

  protected declined(): void {
    const next = !this.val().chaperoneDeclined;
    this.patch({ chaperoneDeclined: next, chaperoneName: next ? null : this.val().chaperoneName });
  }
}
