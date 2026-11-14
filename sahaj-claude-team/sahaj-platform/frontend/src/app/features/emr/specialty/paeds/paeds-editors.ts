import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { printSheet } from '../print-sheet';
import { vaccinationCard } from './vaccination-card.logic';
import { formatDisplayDate } from '../../../../shared/format/ist-date';
import { REFERENCE_LISTS } from '../specialty.context';
import { SpEditor, toggled } from '../shared/sp-editor';
import { SpStepperComponent } from '../shared/sp-stepper.component';
import {
  DOSE_RULES,
  DRAWN_MILESTONES,
  GROWTH_STEPS,
  chartPoint,
  doseFor,
  doseText,
  weightNote,
  weightOnly,
} from '../obgyn-paeds-physio.logic';
import { EmrFlag, SpEmpty, SpGrowth, SpImmunization, SpMilestones } from '../specialty.model';

/**
 * GROWTH (SAH-62-1; tagged C-5): today's weight, length and head circumference with the centile note
 * for the weight and the weight-for-age chart. Notes come from the growth band reference; only the
 * drawn 18-month bands exist, otherwise "Centile not available for this age". The length and head
 * circumference notes are fixed text in the mockup and are not shown (human decision DEC-044, ADR-023
 * H8); the values and the weight chart are shown as drawn. Age comes from the date of birth.
 */
@Component({
  selector: 'app-sp-growth',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpStepperComponent],
  styleUrls: ['../specialty.shared.scss', './paeds.scss'],
  template: `
    <div class="sp">
      <div class="sp-row"><span class="sp-sub">WHO 0–5 y</span>@if (previousText(); as p) {<span class="sp-sub" data-testid="previous">{{ p }}</span>}</div>
      <div class="sp-grid">
        <app-sp-stepper
          label="Weight"
          unit="kg"
          [value]="val().weightKg"
          [stepBy]="steps.weightKg.step"
          [start]="steps.weightKg.start"
          [min]="steps.weightKg.min"
          [max]="steps.weightKg.max"
          [note]="weightFlag()"
          [disabled]="disabled()"
          (valueChange)="patch({ weightKg: $event })"
        />
        <app-sp-stepper
          label="Length"
          unit="cm"
          [value]="val().lengthCm"
          [stepBy]="steps.lengthCm.step"
          [start]="steps.lengthCm.start"
          [min]="steps.lengthCm.min"
          [max]="steps.lengthCm.max"
          [disabled]="disabled()"
          (valueChange)="patch({ lengthCm: $event })"
        />
        <app-sp-stepper
          label="Head circ."
          unit="cm"
          [value]="val().headCircCm"
          [stepBy]="steps.headCircCm.step"
          [start]="steps.headCircCm.start"
          [min]="steps.headCircCm.min"
          [max]="steps.headCircCm.max"
          [disabled]="disabled()"
          (valueChange)="patch({ headCircCm: $event })"
        />
      </div>
      <svg viewBox="0 0 400 200" role="img" aria-label="Weight-for-age chart with today's weight plotted" class="wchart">
        <rect x="40" y="10" width="350" height="160" class="plot" />
        <path d="M40 150 C 120 90, 220 60, 390 40" class="guide" />
        <path d="M40 158 C 120 105, 220 80, 390 62" class="guide mid" />
        <path d="M40 165 C 120 120, 220 98, 390 82" class="guide" />
        <text x="356" y="36">97th</text><text x="356" y="58">50th</text><text x="356" y="96">3rd</text>
        @if (line()) {
          <polyline [attr.points]="line()" class="trace" />
        }
        @for (p of points(); track $index) {
          <circle [attr.cx]="p.x" [attr.cy]="p.y" [attr.r]="p.today ? 6 : 4" class="pt" [class.today]="p.today" />
        }
        <text x="40" y="190">Birth</text><text x="190" y="190">12 m</text><text x="268" y="190">18 m</text><text x="360" y="190">24 m</text>
      </svg>
    </div>
  `,
})
export class GrowthComponent extends SpEditor<SpGrowth> {
  protected readonly component = 'GROWTH' as const;
  protected blank(): SpGrowth {
    return { component: 'GROWTH', schemaVersion: 1 };
  }
  protected readonly steps = GROWTH_STEPS;

  constructor() {
    super();
    effect(() => {
      if (this.ctx.history() === null) {
        this.ctx.loadHistory();
      }
    });
  }

  /** The weight note only (DEC-044): the server's when nothing is unsaved, else the band rule on screen. */
  protected readonly weightFlag = computed<EmrFlag | null>(() => {
    const paeds = this.view()?.paeds;
    if (this.fresh() && paeds) {
      return weightOnly(paeds.growthNotes);
    }
    return weightNote(this.ctx.reference()?.growthBands, paeds?.ageMonths ?? null, this.val().weightKg);
  });

  protected readonly previousText = computed(() => {
    const p = this.val().previous;
    return p?.weightKg != null ? `Copied from ${formatDisplayDate(p.date)}: ${p.weightKg.toFixed(1)} kg` : null;
  });

  protected readonly points = computed(() => {
    // A point with no worked-out age (no date of birth) is not plotted; no month count is made up (BR73-14).
    const past = (this.ctx.history()?.growthPoints ?? [])
      .filter((g) => g.weightKg != null && g.ageMonths != null)
      .map((g) => ({ ...chartPoint(g.ageMonths as number, g.weightKg as number), today: false }));
    const age = this.view()?.paeds?.ageMonths;
    const w = this.val().weightKg;
    return w != null && age != null ? [...past, { ...chartPoint(age, w), today: true }] : past;
  });
  protected readonly line = computed(() => (this.points().length > 1 ? this.points().map((p) => `${p.x},${p.y}`).join(' ') : ''));
}

const DOMAIN_LABEL: Record<string, string> = { GROSS_MOTOR: 'Gross motor', FINE_MOTOR: 'Fine motor', SOCIAL: 'Social', LANGUAGE: 'Language' };

interface VaccineRow {
  key: string;
  label: string;
}
interface VaccineGroup {
  key: string;
  label: string;
  vaccines: VaccineRow[];
}

/** The IAP schedule of the mockup, used for the labels when the reference list is absent. */
const DRAWN_SCHEDULE: { key: string; label: string; vaccines: string[] }[] = [
  { key: 'birth', label: 'Birth', vaccines: ['BCG', 'OPV 0', 'Hep B 1'] },
  { key: '6w', label: '6 weeks', vaccines: ['DTwP 1', 'IPV 1', 'Hib 1', 'Hep B 2', 'Rota 1', 'PCV 1'] },
  { key: '10w', label: '10 weeks', vaccines: ['DTwP 2', 'IPV 2', 'Hib 2', 'Rota 2', 'PCV 2'] },
  { key: '14w', label: '14 weeks', vaccines: ['DTwP 3', 'IPV 3', 'Hib 3', 'Rota 3', 'PCV 3'] },
  { key: '6_9m', label: '6–9 months', vaccines: ['Influenza 1', 'Influenza 2', 'Typhoid conj.'] },
  { key: '9m', label: '9 months', vaccines: ['MMR 1'] },
  { key: '12m', label: '12 months', vaccines: ['Hep A 1'] },
  { key: '15m', label: '15 months', vaccines: ['MMR 2', 'Varicella 1', 'PCV booster'] },
  { key: '16_18m', label: '16–18 months', vaccines: ['DTwP booster 1', 'IPV booster 1', 'Hib booster 1'] },
];

function slug(label: string): string {
  return label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '');
}

/**
 * IMMUNIZATION (SAH-62-2; tagged C-5, H9): the IAP schedule with Given, Due today and Overdue; tap a due
 * or overdue vaccine to mark it given today. A vaccine given in an earlier visit can be neither added
 * nor removed. "Mark all due as given" marks the vaccines due today only. States come from the server
 * (given anywhere in the patient's records, windows by age); the batch number and the reminder to the
 * parent are Later.
 */
@Component({
  selector: 'app-sp-immunization',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './paeds.scss'],
  template: `
    <div class="sp">
      <p class="sp-sub">Tap a due or overdue vaccine to mark it given today</p>
      <div class="sp-row legend" aria-label="Legend">
        <span class="lg given">Given</span><span class="lg due">Due today</span><span class="lg over">Overdue</span>
      </div>
      <div class="sched">
        @for (g of groups(); track g.key) {
          <div class="grow" [class.hot]="hot(g)">
            <span class="age">{{ g.label }}</span>
            <div class="sp-row">
              @for (v of g.vaccines; track v.key) {
                @let st = state(v.key);
                <button
                  type="button"
                  class="vac"
                  [attr.data-state]="st"
                  [disabled]="disabled() || st === 'GIVEN' || st === 'LATER'"
                  [attr.aria-pressed]="st === 'TODAY'"
                  (click)="toggle(v.key)"
                >
                  {{ prefix(st) }}{{ v.label }}{{ st === 'TODAY' ? ' · today' : '' }}
                </button>
              }
            </div>
          </div>
        } @empty {
          <p class="sp-sub">The vaccine schedule is not available yet.</p>
        }
      </div>
      <div class="sp-row">
        @if (!disabled()) {
          <button type="button" class="btn" data-act="mark-all-due" [disabled]="saving()" (click)="markAllDue()">Mark all due as given</button>
        }
        <label class="chk">
          <input type="checkbox" [checked]="val().scheduleReviewed" [disabled]="disabled()" (change)="patch({ scheduleReviewed: tick($event) })" />
          Schedule reviewed
        </label>
        <button type="button" class="btn" data-act="print-card" (click)="printCard()">Print vaccination card</button>
      </div>
      <p class="sp-sub" data-testid="vax-note">{{ note() }}</p>
      @if (toast(); as t) {
        <p class="sp-sub" role="status" data-testid="vax-toast">{{ t }}</p>
      }
      @if (cardMessage(); as m) {
        <p class="sp-sub" role="status" data-testid="card-message">{{ m }}</p>
      }
      <p class="sp-sub">The card prints English headings. Marathi and Hindi card wording: later.</p>
    </div>
  `,
})
export class ImmunizationComponent extends SpEditor<SpImmunization> {
  protected readonly component = 'IMMUNIZATION' as const;
  protected blank(): SpImmunization {
    return { component: 'IMMUNIZATION', schemaVersion: 1, givenToday: [], scheduleReviewed: false };
  }

  private readonly doc = inject(DOCUMENT);
  protected readonly cardMessage = signal<string | null>(null);

  constructor() {
    super();
    this.registerFooterPrint(() => this.printCard());
  }

  protected readonly groups = computed<VaccineGroup[]>(() => {
    const vaccines = this.ctx.list(REFERENCE_LISTS.iapVaccines);
    const groups = this.ctx.list(REFERENCE_LISTS.iapGroups);
    if (vaccines && groups) {
      return groups.map((g) => ({
        key: g.key,
        label: g.label,
        vaccines: vaccines.filter((v) => v.group === g.key).map((v) => ({ key: v.key, label: v.label })),
      }));
    }
    return DRAWN_SCHEDULE.map((g) => ({ key: g.key, label: g.label, vaccines: g.vaccines.map((label) => ({ key: slug(label), label })) }));
  });

  private readonly serverStates = computed(() => new Map((this.view()?.paeds?.vaccines ?? []).map((v) => [v.vaccineKey, v.state])));
  private readonly todayKeys = computed(() => new Set(this.val().givenToday.map((g) => g.vaccineKey)));

  protected state(key: string): 'GIVEN' | 'TODAY' | 'DUE_TODAY' | 'OVERDUE' | 'LATER' {
    if (this.todayKeys().has(key)) {
      return 'TODAY';
    }
    const server = this.serverStates().get(key) ?? 'LATER';
    // Given in this visit and saved, then taken back on screen: it is due again until the next save.
    return server === 'GIVEN' && this.givenHere(key) ? 'DUE_TODAY' : server;
  }

  private givenHere(key: string): boolean {
    return (this.view()?.paeds?.vaccines ?? []).some((v) => v.vaccineKey === key && v.givenHere);
  }

  protected prefix(st: string): string {
    return st === 'GIVEN' || st === 'TODAY' ? '✓ ' : st === 'OVERDUE' ? '! ' : '';
  }

  protected hot(g: VaccineGroup): boolean {
    return g.vaccines.some((v) => this.state(v.key) === 'DUE_TODAY' || this.state(v.key) === 'OVERDUE');
  }

  protected readonly toast = signal<string | null>(null);
  protected readonly saving = computed(() => this.ctx.store.saving());
  protected readonly pending = computed(() =>
    this.groups().flatMap((g) => g.vaccines).filter((v) => ['DUE_TODAY', 'OVERDUE'].includes(this.state(v.key))),
  );

  protected readonly note = computed(() => {
    const pending = this.pending();
    if (pending.length > 0) {
      return `${pending.length} still to give: ${pending.map((v) => v.label).join(', ')}`;
    }
    const next = this.view()?.paeds?.nextDue;
    return `All due vaccines given.${next ? ` Next: ${next.groupLabel}.` : ''}`;
  });

  /** The card: every vaccine of the schedule with its date given or its state, and the next due vaccine (SAH-62-4). */
  protected printCard(): void {
    const given = new Map((this.view()?.paeds?.vaccines ?? []).map((v) => [v.vaccineKey, v.givenOn ?? null]));
    const sheet = vaccinationCard({
      groups: this.groups(),
      stateOf: (key) => this.state(key),
      givenOn: (key) => given.get(key),
      nextDue: this.view()?.paeds?.nextDue,
      dateOfBirth: this.view()?.paeds?.dateOfBirth,
      sheetOf: (title, sections, footer) => this.ctx.sheet(title, sections, footer),
    });
    const ok = printSheet(this.doc, sheet);
    this.cardMessage.set(ok ? 'Vaccination card sent to printer' : 'We could not print. Try again.');
  }

  protected toggle(key: string): void {
    const state = this.state(key);
    const label = this.groups().flatMap((g) => g.vaccines).find((v) => v.key === key)?.label ?? key;
    if (state === 'TODAY') {
      this.patch({ givenToday: this.val().givenToday.filter((g) => g.vaccineKey !== key) });
      this.toast.set(`${label} unmarked`);
    } else if (state === 'DUE_TODAY' || state === 'OVERDUE') {
      this.patch({ givenToday: [...this.val().givenToday, { vaccineKey: key }] });
      this.toast.set(`${label} recorded as given today`);
    }
  }

  /**
   * "Mark all due as given" records every vaccine due today and every overdue one in one save, never a vaccine
   * that is not yet due or already given (SAH-75-9; supersedes SAH-62-2 "due today only"). A second press
   * finds nothing left to add, so each vaccine is recorded once.
   */
  protected markAllDue(): void {
    const keys = this.pending().map((v) => v.key).filter((k) => !this.todayKeys().has(k));
    if (keys.length > 0) {
      this.patch({ givenToday: [...this.val().givenToday, ...keys.map((vaccineKey) => ({ vaccineKey }))] });
    }
    this.toast.set(`${keys.length} vaccines recorded`);
  }

  protected tick(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }
}

/**
 * MILESTONES (SAH-62-2; tagged C-5): tap to mark a milestone achieved, tap again for not yet. Only the
 * 18-month band is set up; for any other age "Milestones for this age are not set up" and nothing is stored.
 */
@Component({
  selector: 'app-sp-milestones',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './paeds.scss'],
  template: `
    @if (setUp()) {
      <div class="sp">
        <p class="sp-sub">Tap to mark achieved or not yet</p>
        <ul class="ms">
          @for (m of items(); track m.key) {
            <li>
              <button type="button" class="mrow" [disabled]="disabled()" [attr.aria-pressed]="state(m.key) === 'ach'" (click)="toggle(m.key)">
                <span class="mark" [attr.data-on]="state(m.key) === 'ach'">{{ state(m.key) === 'ach' ? '✓' : '?' }}</span>
                <span>{{ m.label }}</span>
                <span class="sp-sub">{{ m.domain }}</span>
              </button>
            </li>
          }
        </ul>
        <p class="sp-sub" [class.warnText]="notYet().length > 0" data-testid="ms-note">{{ note() }}</p>
      </div>
    } @else {
      <p class="sp-sub" data-testid="ms-not-set-up">Milestones for this age are not set up</p>
    }
  `,
})
export class MilestonesComponent extends SpEditor<SpMilestones> {
  protected readonly component = 'MILESTONES' as const;
  protected blank(): SpMilestones {
    return { component: 'MILESTONES', schemaVersion: 1, ageBandMonths: this.view()?.paeds?.ageMonths ?? 18, achieved: [], notYet: [] };
  }

  protected readonly setUp = computed(() => this.view()?.paeds?.milestonesSetUp ?? (this.view()?.paeds?.ageMonths ?? 18) === 18);
  protected readonly items = computed<{ key: string; label: string; domain: string }[]>(() => {
    // Reference milestones (key = emr_milestone.milestone_key, group = domain, number = age band in months).
    const list = this.ctx.list(REFERENCE_LISTS.milestones);
    if (!list || list.length === 0) {
      return [...DRAWN_MILESTONES];
    }
    const band = this.val().ageBandMonths;
    const own = list.filter((m) => m.number == null || m.number === band);
    return (own.length > 0 ? own : list).map((m) => ({ key: m.key, label: m.label, domain: DOMAIN_LABEL[m.group ?? ''] ?? m.sub ?? m.group ?? '' }));
  });

  protected state(key: string): 'ach' | 'not' | 'none' {
    return this.val().achieved.includes(key) ? 'ach' : this.val().notYet.includes(key) ? 'not' : 'none';
  }

  protected readonly notYet = computed(() => this.items().filter((m) => this.state(m.key) !== 'ach'));
  protected readonly note = computed(() =>
    this.notYet().length > 0
      ? `Not yet: ${this.notYet().map((m) => m.label.toLowerCase()).join(', ')}. Recheck at next visit.`
      : 'All milestones for this age achieved.',
  );

  protected toggle(key: string): void {
    const ach = this.val().achieved;
    const not = this.val().notYet;
    this.patch(ach.includes(key) ? { achieved: toggled(ach, key), notYet: [...not, key] } : { achieved: [...ach, key], notYet: not.filter((k) => k !== key) });
  }
}

/**
 * WEIGHT_DOSE (SAH-62-3; tagged C-5): dose by weight for three syrups. mg = min(max mg, round(weight ×
 * mg/kg)), ml = round(mg ÷ mg-per-ml, 1 decimal); there is no dose without today's weight (never an
 * estimate from age). "Add to prescription" adds a row the server checks against the same rule; when
 * the weight changes later the row is listed as "Weight changed. Add the dose again." and never
 * changed silently. Stores nothing in the section itself.
 */
@Component({
  selector: 'app-sp-weight-dose',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './paeds.scss'],
  template: `
    <div class="sp">
      @if (weight() !== null) {
        <p class="sp-sub" data-testid="dose-weight">Uses today’s weight: <b>{{ weight()!.toFixed(1) }} kg</b>. Change weight on the left and this updates.</p>
      } @else {
        <p class="msg warn" data-testid="dose-no-weight">Record today’s weight first</p>
      }
      <div class="sp-row" role="group" aria-label="Medicine">
        @for (r of rules; track r.key; let i = $index) {
          <button type="button" class="tile" [class.on]="pick() === i" (click)="pick.set(i)">
            <b>{{ r.name }}</b><span class="sp-sub">{{ r.sub }}</span>
          </button>
        }
      </div>
      <div class="dose">
        <div><span class="sp-lbl">Dose rule</span><b>{{ rule().perKg }} mg/kg</b></div>
        <div><span class="sp-lbl">Per dose</span><b data-testid="dose-mg">{{ dose() ? dose()!.mg + ' mg' : '—' }}</b></div>
      </div>
      <div class="give" data-testid="dose-give">
        <span class="give-l">Give</span>
        <b class="give-v" data-testid="dose-ml">{{ dose() ? dose()!.ml.toFixed(1) + ' ml' : '—' }}</b>
        <span class="give-f">{{ rule().freq }}</span>
      </div>
      @if (dose()?.capped) {
        <p class="sp-sub">The dose is limited to {{ rule().maxMg }} mg.</p>
      }
      @if (!disabled()) {
        <button type="button" class="btn pri" data-act="add-dose" [disabled]="!dose() || busy()" (click)="add()">Add to prescription</button>
      }
      @if (changed().length > 0) {
        <p class="msg warn" role="status" data-testid="dose-changed">Weight changed. Add the dose again.</p>
      }
      @if (message(); as m) {
        <p class="sp-sub" role="status">{{ m }}</p>
      }
    </div>
  `,
})
export class WeightDoseComponent extends SpEditor<SpEmpty> {
  protected readonly component = 'WEIGHT_DOSE' as const;
  protected blank(): SpEmpty {
    return { component: 'WEIGHT_DOSE', schemaVersion: 1 };
  }
  protected readonly rules = DOSE_RULES;
  protected readonly pick = signal(0);
  protected readonly busy = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly rule = computed(() => DOSE_RULES[this.pick()]);

  /** Today's weight is the one typed in Growth. */
  protected readonly weight = computed<number | null>(() => {
    const key = this.ctx.sectionKeyOf('GROWTH');
    const g = key ? this.ctx.value<SpGrowth>(key, 'GROWTH') : null;
    return g?.weightKg ?? this.view()?.paeds?.doseWeightKg ?? null;
  });
  protected readonly dose = computed(() => doseFor(this.weight(), this.rule()));
  protected readonly changed = computed(() => this.view()?.paeds?.doseWeightChangedRowIds ?? []);

  protected add(): void {
    const dose = this.dose();
    const weight = this.weight();
    const rule = this.rule();
    if (!dose || weight === null) {
      return;
    }
    this.busy.set(true);
    this.ctx
      .addSpecialtyRx([
        {
          name: rule.name,
          frequency: null,
          frequencyText: rule.freq,
          days: 3,
          when: 'AFTER_FOOD',
          source: 'DOSE_BY_WEIGHT',
          doseText: doseText(dose),
          note: 'Calculated by weight',
          calculation: { doseRuleKey: rule.key, weightKg: weight, mg: dose.mg, ml: dose.ml },
        },
      ])
      .subscribe((ok) => {
        this.busy.set(false);
        this.message.set(ok ? 'Added to prescription' : null);
      });
  }
}
