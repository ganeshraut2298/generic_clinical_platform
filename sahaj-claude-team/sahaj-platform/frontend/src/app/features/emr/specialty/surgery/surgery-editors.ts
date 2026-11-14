import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { newClientKey } from '../../../../shared/client-key';
import { formatInrPaise } from '../../../../shared/format/inr';
import { formatDisplayDate } from '../../../../shared/format/ist-date';
import { detailOr } from '../../emr-errors';
import { SpEditor, numberOrNull, textOrNull, toggled } from '../shared/sp-editor';
import {
  ADMISSION_LABEL,
  ANAESTHESIA_LABEL,
  APPROACHES,
  BLOOD_CHIPS,
  PAC_LABEL,
  PAYER_LABEL,
  PREOP_LABEL,
  PROCEDURES,
  SURGERY_CONSENT_ITEMS,
  TEST_ROWS,
  TEST_STATE_LABEL,
  applyProcedure,
  bookMissing,
  cycleTest,
  depositPaise,
  fitnessSummary,
  fitnessWarnings,
  payerChip,
  rowState,
} from '../surgery.logic';
import {
  AdmissionPlan,
  Anaesthesia,
  PreopKey,
  SpConsentChecklist,
  SpPreauth,
  SpPreopOrders,
  SpSurgicalExam,
  SpSurgeryFitness,
  SpSurgeryPlan,
  SurgeryPayer,
} from '../specialty.model';

/** Chips that clear the others when picked (the mockup's None, Normal and Soft). */
const EXCLUSIVE = new Set(['NONE', 'NORMAL', 'SOFT']);

function pickMulti<T extends string>(list: readonly T[], item: T): T[] {
  if (EXCLUSIVE.has(item)) {
    return list.includes(item) ? [] : [item];
  }
  return toggled(list.filter((x) => !EXCLUSIVE.has(x)), item);
}

/** SURGICAL_EXAM (SAH-64-1; tagged C-10): abdomen, Murphy sign, jaundice, hernia sites and per-rectal. */
@Component({
  selector: 'app-sp-surgical-exam',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      <span class="sp-lbl">Abdomen</span>
      <div class="sp-row" role="group" aria-label="Abdomen">
        @for (o of abdomen; track o.key) {
          <button type="button" class="chip" [class.on]="val().abdomen.includes(o.key)" [disabled]="disabled()" [attr.aria-pressed]="val().abdomen.includes(o.key)" (click)="patch({ abdomen: multi(val().abdomen, o.key) })">
            {{ val().abdomen.includes(o.key) ? '✓ ' : '' }}{{ o.label }}
          </button>
        }
      </div>
      <span class="sp-lbl">Murphy sign</span>
      <div class="sp-row" role="group" aria-label="Murphy sign">
        <button type="button" class="chip sq bad" [class.on]="val().murphySign === 'POSITIVE'" [disabled]="disabled()" (click)="patch({ murphySign: val().murphySign === 'POSITIVE' ? null : 'POSITIVE' })">Positive</button>
        <button type="button" class="chip sq" [class.on]="val().murphySign === 'NEGATIVE'" [disabled]="disabled()" (click)="patch({ murphySign: val().murphySign === 'NEGATIVE' ? null : 'NEGATIVE' })">Negative</button>
      </div>
      <span class="sp-lbl">Jaundice</span>
      <div class="sp-row" role="group" aria-label="Jaundice">
        <button type="button" class="chip sq" [class.on]="val().jaundice === 'NO'" [disabled]="disabled()" (click)="patch({ jaundice: val().jaundice === 'NO' ? null : 'NO' })">No</button>
        <button type="button" class="chip sq bad" [class.on]="val().jaundice === 'YES'" [disabled]="disabled()" (click)="patch({ jaundice: val().jaundice === 'YES' ? null : 'YES' })">Yes</button>
      </div>
      <span class="sp-lbl">Hernia sites</span>
      <div class="sp-row" role="group" aria-label="Hernia sites">
        @for (o of hernia; track o.key) {
          <button type="button" class="chip" [class.on]="val().herniaSites.includes(o.key)" [disabled]="disabled()" [attr.aria-pressed]="val().herniaSites.includes(o.key)" (click)="patch({ herniaSites: multi(val().herniaSites, o.key) })">
            {{ val().herniaSites.includes(o.key) ? '✓ ' : '' }}{{ o.label }}
          </button>
        }
      </div>
      <span class="sp-lbl">Per-rectal</span>
      <div class="sp-row" role="group" aria-label="Per-rectal">
        @for (o of rectal; track o.key) {
          <button type="button" class="chip sq" [class.on]="val().perRectal === o.key" [disabled]="disabled()" (click)="patch({ perRectal: val().perRectal === o.key ? null : o.key })">{{ o.label }}</button>
        }
      </div>
    </div>
  `,
})
export class SurgicalExamComponent extends SpEditor<SpSurgicalExam> {
  protected readonly component = 'SURGICAL_EXAM' as const;
  protected blank(): SpSurgicalExam {
    return { component: 'SURGICAL_EXAM', schemaVersion: 1, abdomen: [], herniaSites: [] };
  }
  protected readonly abdomen: { key: SpSurgicalExam['abdomen'][number]; label: string }[] = [
    { key: 'SOFT', label: 'Soft' },
    { key: 'TENDER_RIGHT_UPPER', label: 'Tender right upper' },
    { key: 'TENDER_RIGHT_LOWER', label: 'Tender right lower' },
    { key: 'GUARDING', label: 'Guarding' },
    { key: 'DISTENDED', label: 'Distended' },
    { key: 'LUMP_FELT', label: 'Lump felt' },
  ];
  protected readonly hernia: { key: SpSurgicalExam['herniaSites'][number]; label: string }[] = [
    { key: 'NORMAL', label: 'Normal' },
    { key: 'RIGHT_INGUINAL', label: 'Right inguinal' },
    { key: 'LEFT_INGUINAL', label: 'Left inguinal' },
    { key: 'UMBILICAL', label: 'Umbilical' },
    { key: 'INCISIONAL', label: 'Incisional' },
  ];
  protected readonly rectal: { key: NonNullable<SpSurgicalExam['perRectal']>; label: string }[] = [
    { key: 'NOT_NEEDED', label: 'Not needed' },
    { key: 'NORMAL', label: 'Normal' },
    { key: 'PILES', label: 'Piles' },
    { key: 'FISSURE', label: 'Fissure' },
    { key: 'MASS', label: 'Mass' },
  ];
  protected multi<T extends string>(list: readonly T[], item: T): T[] {
    return pickMulti(list, item);
  }
}

/**
 * SURGERY_FITNESS (SAH-64-2; tagged C-7): ASA grade, other illness, blood thinners, the anaesthesia
 * check (PAC) and the pre-op work-up. The summary is "Fit for surgery", tests pending, PAC not done or
 * "Not fit yet"; a blood thinner, another illness or PAC unfit is a warning, not a block.
 */
@Component({
  selector: 'app-sp-surgery-fitness',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss'],
  template: `
    <div class="sp">
      <span class="sp-lbl">ASA grade</span>
      <div class="sp-row" role="group" aria-label="ASA grade">
        @for (a of asa; track a) {
          <button type="button" class="chip sq" [class.on]="val().asa === a" [disabled]="disabled()" (click)="patch({ asa: val().asa === a ? null : a })">{{ a }}</button>
        }
      </div>
      <span class="sp-lbl">Other illness</span>
      <div class="sp-row" role="group" aria-label="Other illness">
        @for (o of illness; track o.key) {
          <button type="button" class="chip bad" [class.on]="val().otherIllness.includes(o.key)" [disabled]="disabled()" (click)="patch({ otherIllness: multi(val().otherIllness, o.key) })">
            {{ val().otherIllness.includes(o.key) ? '✓ ' : '' }}{{ o.label }}
          </button>
        }
      </div>
      <span class="sp-lbl">Blood thinners</span>
      <div class="sp-row" role="group" aria-label="Blood thinners">
        @for (o of thinners; track o.key) {
          <button type="button" class="chip sq bad" [class.on]="val().bloodThinner === o.key" [disabled]="disabled()" (click)="patch({ bloodThinner: val().bloodThinner === o.key ? null : o.key })">{{ o.label }}</button>
        }
      </div>
      <span class="sp-lbl">Anaesthesia check (PAC)</span>
      <div class="sp-row" role="group" aria-label="Anaesthesia check">
        @for (o of pacs; track o.key) {
          <button type="button" class="chip sq" [class.bad]="o.key === 'UNFIT'" [class.on]="val().pac === o.key" [disabled]="disabled()" (click)="patch({ pac: o.key })">{{ o.label }}</button>
        }
      </div>
      @if (warnings(); as w) {
        @if (w.blood || w.illness || w.pac) {
          <p class="msg warn" role="status" data-testid="fit-warning">
            {{ w.blood ? 'Blood thinner: plan when to stop it. ' : '' }}{{ w.illness ? 'Other illness: review before surgery. ' : '' }}{{ w.pac ? 'PAC unfit.' : '' }}
          </p>
        }
      }

      <section class="workup" data-testid="preop-card" aria-labelledby="preop-h">
        <div class="sp-row between">
          <h4 class="sp-h" id="preop-h">Pre-op work-up<span class="star" aria-hidden="true"> *</span></h4>
          <span
            class="pill"
            [class.p-good]="summary().state === 'FIT'"
            [class.p-crit]="summary().state === 'NOT_FIT'"
            [class.p-warn]="summary().state === 'TESTS_PENDING' || summary().state === 'PAC_NOT_DONE'"
            data-testid="fit-text"
            >{{ fitText() }}</span
          >
        </div>
        <div class="tests3">
          @for (t of tests; track t.label) {
            <button type="button" class="tile" [attr.data-state]="rowState_(t.keys)" [disabled]="disabled()" (click)="cycle(t.keys)">
              <b>{{ t.label }}</b><span [class.note]="rowState_(t.keys) === 'NORMAL'" [class.alert]="rowState_(t.keys) === 'ABNORMAL'">{{ stateLabel[rowState_(t.keys)] }}</span>
            </button>
          }
        </div>
        <p class="sp-sub">Tap a test to move it: pending → normal → abnormal.</p>
      </section>
    </div>
  `,
  styles: `
    .workup {
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding: 14px;
      border: 1px solid var(--line);
      border-radius: var(--r);
      background: var(--glass-hi);
    }
    .between {
      justify-content: space-between;
    }
    .star {
      color: var(--crit);
    }
    .tests3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 8px;
    }
    @media (max-width: 720px) {
      .tests3 {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
  `,
})
export class SurgeryFitnessComponent extends SpEditor<SpSurgeryFitness> {
  protected readonly component = 'SURGERY_FITNESS' as const;
  protected blank(): SpSurgeryFitness {
    return { component: 'SURGERY_FITNESS', schemaVersion: 1, otherIllness: [], pac: 'NOT_DONE', tests: [] };
  }
  protected readonly asa = ['I', 'II', 'III', 'IV'] as const;
  protected readonly tests = TEST_ROWS;
  protected readonly stateLabel = TEST_STATE_LABEL;
  protected readonly illness: { key: SpSurgeryFitness['otherIllness'][number]; label: string }[] = [
    { key: 'NONE', label: 'None' },
    { key: 'DIABETES', label: 'Diabetes' },
    { key: 'HIGH_BP', label: 'High BP' },
    { key: 'HEART_DISEASE', label: 'Heart disease' },
    { key: 'ASTHMA_COPD', label: 'Asthma / COPD' },
    { key: 'KIDNEY_DISEASE', label: 'Kidney disease' },
  ];
  protected readonly thinners: { key: NonNullable<SpSurgeryFitness['bloodThinner']>; label: string }[] = [
    { key: 'NONE', label: 'None' },
    { key: 'ASPIRIN', label: 'Aspirin' },
    { key: 'CLOPIDOGREL', label: 'Clopidogrel' },
    { key: 'WARFARIN_DOAC', label: 'Warfarin / DOAC' },
  ];
  protected readonly pacs = (Object.keys(PAC_LABEL) as (keyof typeof PAC_LABEL)[]).map((key) => ({ key, label: PAC_LABEL[key] }));
  protected readonly summary = computed(() => fitnessSummary(this.val()));
  protected readonly warnings = computed(() => fitnessWarnings(this.val()));
  /** The server's sentence when nothing is unsaved, else the same rule on screen. */
  protected readonly fitText = computed(() => (this.fresh() ? (this.view()?.surgery?.fitness.text ?? this.summary().text) : this.summary().text));

  protected multi<T extends string>(list: readonly T[], item: T): T[] {
    return pickMulti(list, item);
  }
  protected rowState_(keys: Parameters<typeof rowState>[1]): ReturnType<typeof rowState> {
    return rowState(this.val(), keys);
  }
  protected cycle(keys: Parameters<typeof cycleTest>[1]): void {
    this.replace(cycleTest(this.val(), keys));
  }
}

const OT_SLOTS = ['OT 1 · 08:00', 'OT 2 · 10:00', 'OT 2 · 13:30'];

/**
 * SURGERY_PLAN (SAH-64-3, SAH-71-1; tagged C-7, B-1, B-3): procedure with its default anaesthesia and
 * stay, the OT date and slot (text: there is no OT calendar, OQ-45-28), who pays, the package (the
 * clinic tariff, else typed: "Not in tariff") and the deposit. "Book OT and admission" is the server's
 * booking: it checks a diagnosis, every consent item and, unless Self, the pre-authorisation, saves
 * the plan, and asks for a ward bed when this clinic has IPD (H14).
 */
@Component({
  selector: 'app-sp-surgery-plan',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      <p class="msg" [class.good]="payerGood()" [class.warn]="!payerGood()" data-testid="payer-chip">{{ chip() }}</p>
      <span class="sp-lbl">Procedure</span>
      <div class="sp-row" role="group" aria-label="Procedure">
        @for (p of procedures; track p.key) {
          <button type="button" class="chip sq" [class.on]="val().procedureKey === p.key" [disabled]="disabled()" (click)="setProcedure(p.key)">{{ p.label }}</button>
        }
      </div>
      <label class="field">
        <span class="sp-lbl">Another procedure (typed; no default and no rate)</span>
        <input class="input" type="text" maxlength="160" [value]="val().procedureKey ? '' : (val().procedureText ?? '')" [disabled]="disabled()" (change)="setTyped($event)" />
      </label>
      <span class="sp-lbl">Approach</span>
      <div class="sp-row" role="group" aria-label="Approach">
        @for (a of approaches; track a) {
          <button type="button" class="chip sq" [class.on]="val().approach === a" [disabled]="disabled()" (click)="change({ approach: a })">{{ a }}</button>
        }
      </div>
      <span class="sp-lbl">Anaesthesia</span>
      <div class="sp-row" role="group" aria-label="Anaesthesia">
        @for (a of anaesthesias; track a.key) {
          <button type="button" class="chip sq" [class.on]="val().anaesthesia === a.key" [disabled]="disabled()" (click)="change({ anaesthesia: a.key })">{{ a.label }}</button>
        }
      </div>
      <span class="sp-lbl">Admission</span>
      <div class="sp-row" role="group" aria-label="Admission">
        @for (a of admissions; track a.key) {
          <button type="button" class="chip sq" [class.on]="val().admission === a.key" [disabled]="disabled()" (click)="change({ admission: a.key })">{{ a.label }}</button>
        }
      </div>
      <div class="sp-row">
        <label class="field">
          <span class="sp-lbl">OT date</span>
          <input class="input" type="date" [value]="val().otDate ?? ''" [disabled]="disabled()" (change)="change({ otDate: txt($event) })" />
        </label>
        <div class="field">
          <span class="sp-lbl">OT and time</span>
          <div class="sp-row" role="group" aria-label="OT and time">
            @for (s of slots; track s) {
              <button type="button" class="chip sq" [class.on]="val().slotText === s" [disabled]="disabled()" (click)="change({ slotText: s })">{{ s }}</button>
            }
          </div>
        </div>
      </div>
      <p class="sp-sub">The date and time are text. There is no OT schedule yet.</p>
      <span class="sp-lbl">Who pays</span>
      <div class="sp-row" role="group" aria-label="Who pays">
        @for (p of payers; track p.key) {
          <button type="button" class="chip sq" [class.on]="val().payer === p.key" [disabled]="disabled()" (click)="change({ payer: p.key })">{{ p.label }}</button>
        }
      </div>

      <dl class="est" aria-label="Estimate">
        <div>
          <dt>Package (clinic tariff)</dt>
          <dd data-testid="package">
            @if (packagePaise() !== null) {
              {{ money(packagePaise()) }}
            } @else {
              <span class="sp-sub">Not in tariff</span>
            }
          </dd>
        </div>
        @if (val().packageSource !== 'TARIFF') {
          <div>
            <dt><label for="pkg-amt">Package typed by the doctor (₹)</label></dt>
            <dd><input id="pkg-amt" class="input" type="number" inputmode="numeric" min="0" step="100" [value]="typedRupees()" [disabled]="disabled()" (change)="setPackage($event)" /></dd>
          </div>
        }
        <div><dt>Anaesthesia</dt><dd>{{ val().anaesthesia ? anaesthesiaLabel[val().anaesthesia!] : '—' }}</dd></div>
        <div><dt>Stay</dt><dd>{{ val().admission ? admissionLabel[val().admission!] : '—' }}</dd></div>
        <div><dt>Deposit at admission</dt><dd data-testid="deposit">{{ deposit() !== null ? money(deposit()) : '—' }}</dd></div>
      </dl>

      <p class="msg" [class.good]="preauthSent() || val().payer === 'SELF'" data-testid="preauth-line">{{ preauthLine() }}</p>
      @if (val().payer !== 'SELF' && preauthKey() && !disabled()) {
        <button type="button" class="btn" data-act="toggle-preauth" (click)="togglePreauth()">
          {{ preauthSent() ? 'Undo pre-authorisation sent' : 'Send pre-authorisation to ' + payerLabel[val().payer] }}
        </button>
      }

      @if (preauthNote(); as note) {
        <p class="sp-sub" role="status" data-testid="preauth-note">{{ note }}</p>
      }

      <div class="sp-row">
        <button type="button" class="btn pri" data-act="book" [disabled]="disabled() || !canBook() || booking()" (click)="book()">{{ bookLabel() }}</button>
        <span class="sp-sub" data-testid="book-text">{{ bookText() }}</span>
      </div>
      @if (message(); as m) {
        <p class="msg good" role="status" data-testid="book-message">{{ m }}</p>
      }
      @if (error(); as e) {
        <p class="msg crit" role="alert">{{ e }}</p>
      }
    </div>
  `,
  styles: [
    `
      .est {
        display: grid;
        gap: 6px;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        margin: 0;
        padding: 10px 12px;
        border-radius: var(--r-sm);
        border: 1px solid var(--line);
        background: var(--glass-hi);
      }
      .est dt {
        font-size: 0.75rem;
        color: var(--ink-3);
      }
      .est dd {
        margin: 0;
        font-weight: 700;
      }
    `,
  ],
})
export class SurgeryPlanComponent extends SpEditor<SpSurgeryPlan> {
  protected readonly component = 'SURGERY_PLAN' as const;
  protected blank(): SpSurgeryPlan {
    return { component: 'SURGERY_PLAN', schemaVersion: 1, procedureKey: null, procedureText: null, anaesthesia: null, admission: null, payer: 'SELF' };
  }
  protected readonly procedures = PROCEDURES;
  protected readonly approaches = APPROACHES;
  protected readonly slots = OT_SLOTS;
  protected readonly anaesthesiaLabel = ANAESTHESIA_LABEL;
  protected readonly admissionLabel = ADMISSION_LABEL;
  protected readonly payerLabel = PAYER_LABEL;
  protected readonly anaesthesias = (Object.keys(ANAESTHESIA_LABEL) as Anaesthesia[]).map((key) => ({ key, label: ANAESTHESIA_LABEL[key] }));
  protected readonly admissions = (Object.keys(ADMISSION_LABEL) as AdmissionPlan[]).map((key) => ({ key, label: ADMISSION_LABEL[key] }));
  protected readonly payers = (Object.keys(PAYER_LABEL) as SurgeryPayer[]).map((key) => ({ key, label: PAYER_LABEL[key] }));
  protected readonly booking = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  private readonly bookKey = signal(newClientKey());

  protected readonly preauthKey = computed(() => this.ctx.sectionKeyOf('PREAUTH'));
  protected readonly preauthSent = computed(() => {
    const key = this.preauthKey();
    return !!(key && this.ctx.value<SpPreauth>(key, 'PREAUTH')?.sent);
  });
  protected readonly consentKey = computed(() => this.ctx.sectionKeyOf('CONSENT_CHECKLIST'));
  private readonly consent = computed(() => {
    const key = this.consentKey();
    const items = key ? (this.ctx.value<SpConsentChecklist>(key, 'CONSENT_CHECKLIST')?.items ?? []) : [];
    return { ticked: items.filter((i) => i.confirmed).length, total: SURGERY_CONSENT_ITEMS.length };
  });

  protected readonly packagePaise = computed(() => this.val().packagePaise ?? this.val().typedPackagePaise ?? null);
  /** The server's answers win while nothing is unsaved; a missing one falls back to the same rule on screen. */
  private readonly server = computed(() => (this.fresh() ? (this.view()?.surgery ?? null) : null));
  protected readonly deposit = computed(() => this.server()?.depositPaise ?? depositPaise(this.val().payer, this.packagePaise()));
  protected readonly chip = computed(() => this.server()?.payerChip ?? payerChip(this.val().payer, this.preauthSent()));
  protected readonly preauthNote = signal<string | null>(null);
  protected readonly payerGood = computed(() => this.val().payer === 'SELF' || this.preauthSent());
  protected readonly preauthLine = computed(() =>
    this.val().payer === 'SELF'
      ? 'No pre-authorisation needed (self-pay)'
      : this.preauthSent()
        ? `✓ Pre-authorisation sent to ${PAYER_LABEL[this.val().payer]}`
        : `Pre-authorisation to ${PAYER_LABEL[this.val().payer]} is not sent yet`,
  );

  /** What is missing before the booking: the server's list when nothing is unsaved, else the rule on screen. */
  protected readonly missing = computed(
    () =>
      this.server()?.bookMissing ??
      bookMissing({
        hasDiagnosis: this.ctx.store.hasDiagnosis(),
        consentTicked: this.consent().ticked,
        consentTotal: this.consent().total,
        payer: this.val().payer,
        preauthSent: this.preauthSent(),
      }),
  );
  protected readonly bookState = computed(() => this.server()?.bookState ?? null);
  protected readonly canBook = computed(() => this.missing().length === 0 && this.bookState() !== 'BOOKED');
  protected readonly bookLabel = computed(() => {
    const b = this.val().booking;
    switch (this.bookState()) {
      case 'BOOKED':
        return `✓ Booked${this.val().otDate ? ` for ${formatDisplayDate(this.val().otDate!)}` : ''}${this.val().slotText ? `, ${this.val().slotText}` : ''}`;
      case 'CHANGED':
        return 'Changed after booking. Book again';
      default:
        return b ? 'Book again' : 'Book OT and admission';
    }
  });
  protected readonly bookText = computed(() => (this.missing().length > 0 ? `Before booking: ${this.missing().join(', ')}.` : ''));

  protected money(paise: number | null): string {
    return formatInrPaise(paise ?? 0);
  }
  protected typedRupees(): number | string {
    return this.val().typedPackagePaise != null ? this.val().typedPackagePaise! / 100 : '';
  }
  protected txt(e: Event): string | null {
    return textOrNull(e);
  }

  /** Any change of the plan makes a booking stale; the server then says "Changed after booking". */
  protected change(patch: Partial<SpSurgeryPlan>): void {
    this.bookKey.set(newClientKey());
    this.message.set(null);
    this.patch(patch);
  }

  protected setProcedure(key: NonNullable<SpSurgeryPlan['procedureKey']>): void {
    this.bookKey.set(newClientKey());
    this.replace(applyProcedure(this.val(), key));
  }

  protected setTyped(e: Event): void {
    const text = textOrNull(e);
    this.change({ procedureKey: null, procedureText: text, packagePaise: undefined });
  }

  protected setPackage(e: Event): void {
    const rupees = numberOrNull(e);
    this.change({ typedPackagePaise: rupees === null ? null : Math.round(rupees) * 100 });
  }

  protected togglePreauth(): void {
    const key = this.preauthKey();
    if (!key) {
      return;
    }
    const current = this.ctx.value<SpPreauth>(key, 'PREAUTH') ?? { component: 'PREAUTH' as const, schemaVersion: 1 as const, sent: false };
    this.ctx.set(key, { ...current, sent: !current.sent });
    // Sending to the payer is Later (no integration): the visit records that the clinic requested it, and the words say so (G-S7).
    this.preauthNote.set(current.sent ? null : `Pre-authorisation recorded as requested. Nothing is sent to ${PAYER_LABEL[this.val().payer]} from here.`);
  }

  protected book(): void {
    this.booking.set(true);
    this.error.set(null);
    this.ctx.book(this.bookKey()).subscribe({
      next: (result) => {
        this.booking.set(false);
        this.message.set(result?.message ?? null);
      },
      error: (e: unknown) => {
        this.booking.set(false);
        this.error.set(detailOr(e, 'We could not book. Try again.'));
      },
    });
  }
}

/** PREAUTH (SAH-71-1; tagged B-3, L-5): "Pre-authorisation sent", the date and the reference. Nothing is sent to an insurer or scheme. */
@Component({
  selector: 'app-sp-preauth',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      <label class="chk3">
        <input type="checkbox" [checked]="val().sent" [disabled]="disabled()" (change)="patch({ sent: tick($event) })" />
        Pre-authorisation sent
      </label>
      <div class="sp-row">
        <label class="field">
          <span class="sp-lbl">Sent on</span>
          <input class="input" type="date" [value]="val().sentOn ?? ''" [disabled]="disabled()" (change)="patch({ sentOn: txt($event) })" />
        </label>
        <label class="field">
          <span class="sp-lbl">Reference</span>
          <input class="input" type="text" maxlength="60" [value]="val().reference ?? ''" [disabled]="disabled()" (change)="patch({ reference: txt($event) })" />
        </label>
      </div>
      <p class="sp-sub">Nothing is sent to an insurer or scheme from here.</p>
    </div>
  `,
  styles: [
    `
      .chk3 {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        min-height: 44px;
        font-weight: 600;
      }
      .chk3 input {
        width: 20px;
        height: 20px;
      }
    `,
  ],
})
export class PreauthComponent extends SpEditor<SpPreauth> {
  protected readonly component = 'PREAUTH' as const;
  protected blank(): SpPreauth {
    return { component: 'PREAUTH', schemaVersion: 1, sent: false };
  }
  protected tick(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }
  protected txt(e: Event): string | null {
    return textOrNull(e);
  }
}

/** PREOP_ORDERS (SAH-71-3; tagged C-7): instructions for the pre-op sheet (never medicine orders; the ward places those, OQ-43-24). */
@Component({
  selector: 'app-sp-preop-orders',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      <p class="sp-sub">Go to ward and OT with the booking</p>
      <ul class="lst">
        @for (o of orders; track o.key) {
          <li>
            <label class="chk4">
              <input type="checkbox" [checked]="val().ticked.includes(o.key)" [disabled]="disabled()" (change)="patch({ ticked: toggled_(val().ticked, o.key) })" />
              {{ o.label }}
            </label>
          </li>
        }
        @for (l of val().extraLines; track $index) {
          <li class="sp-row">
            <span>{{ l }}</span>
            @if (!disabled()) {
              <button type="button" class="link" [attr.aria-label]="'Remove ' + l" (click)="removeLine($index)">×</button>
            }
          </li>
        }
      </ul>
      @if (!disabled() && val().extraLines.length < 10) {
        <div class="sp-row">
          <input class="input grow" type="text" maxlength="200" placeholder="Another instruction" aria-label="Another instruction" [value]="draft()" (input)="draft.set(txtv($event))" (keydown.enter)="addLine()" />
          <button type="button" class="btn" [disabled]="!draft().trim()" (click)="addLine()">+ Add</button>
        </div>
      }
      <span class="sp-lbl">Blood</span>
      <div class="sp-row" role="group" aria-label="Blood">
        @for (b of blood; track b.key) {
          <button type="button" class="chip sq" [class.on]="val().blood === b.key" [disabled]="disabled()" (click)="patch({ blood: val().blood === b.key ? null : b.key })">{{ b.label }}</button>
        }
      </div>
      <p class="sp-sub">Medicine orders are placed by the ward. The WHO surgical safety checklist opens in OT on the day.</p>
    </div>
  `,
  styles: [
    `
      .lst {
        display: flex;
        flex-direction: column;
        gap: 4px;
        margin: 0;
        padding: 0;
        list-style: none;
      }
      .chk4 {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        min-height: 44px;
        font-weight: 600;
      }
      .chk4 input {
        width: 20px;
        height: 20px;
      }
      .grow {
        flex: 1 1 220px;
      }
    `,
  ],
})
export class PreopOrdersComponent extends SpEditor<SpPreopOrders> {
  protected readonly component = 'PREOP_ORDERS' as const;
  protected blank(): SpPreopOrders {
    return { component: 'PREOP_ORDERS', schemaVersion: 1, ticked: [], extraLines: [] };
  }
  protected readonly orders = (Object.keys(PREOP_LABEL) as PreopKey[]).map((key) => ({ key, label: PREOP_LABEL[key] }));
  protected readonly blood = BLOOD_CHIPS;
  protected readonly draft = signal('');
  protected toggled_(list: readonly PreopKey[], key: PreopKey): PreopKey[] {
    return toggled(list, key);
  }
  protected txtv(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }
  protected addLine(): void {
    const line = this.draft().trim();
    if (line) {
      this.patch({ extraLines: [...this.val().extraLines, line] });
    }
    this.draft.set('');
  }
  protected removeLine(i: number): void {
    this.patch({ extraLines: this.val().extraLines.filter((_, j) => j !== i) });
  }
}
