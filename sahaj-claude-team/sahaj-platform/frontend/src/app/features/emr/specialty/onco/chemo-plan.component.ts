import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { formatDisplayDate, formatDisplayDateTimeOfInstant } from '../../../../shared/format/ist-date';
import { detailOr, problemSlug } from '../../emr-errors';
import { SpEditor, textOrNull, toggled } from '../shared/sp-editor';
import { SpecialtyApi } from '../specialty.api';
import { REFERENCE_LISTS, SpecialtyRxLine } from '../specialty.context';
import {
  CHECK_ACTION_LABEL,
  CHECK_LABEL,
  LEVELS,
  REDUCTION_REASONS,
  REFERRALS,
  STAGE_LABELS,
  TAKE_HOME,
  TAKE_HOME_DEFAULT,
  DEVIATION_REASONS,
  composeCycleNote,
  decisionLabel,
} from '../onco.logic';
import {
  ChemoCheckerOption,
  ChemoLevel,
  ChemoOrderInput,
  ChemoOrderView,
  ChemoProtocol,
  ChemoProtocolKey,
  ChemoScheme,
  Referral,
  SpChemoPlan,
  SpOncEcog,
  SpOncExam,
  SpOncLabs,
  SpOncTox,
  TakeHome,
} from '../specialty.model';

interface TakeHomeRow {
  key: TakeHome;
  /** The medicine as drawn, for the screen. */
  name: string;
  /** The dose line and the reason, for the screen. */
  text: string;
  /** Ticked at the start of a cycle (Pegfilgrastim follows the suggestion instead). */
  defaultOn: boolean;
  /** The prescription line the tick adds. */
  line: SpecialtyRxLine;
}

const SCHEMES: { key: ChemoScheme; label: string }[] = [
  { key: 'APPROVED', label: 'Approved' },
  { key: 'PREAUTH_RAISED', label: 'Pre-auth raised' },
  { key: 'NOT_IN_PACKAGE', label: 'Not in package' },
  { key: 'SELF_PAY', label: 'Self-pay' },
];

const TAKE_HOME_RX: Record<string, Omit<SpecialtyRxLine, 'name' | 'source'>> = {
  ONDANSETRON_8: { frequency: '1-0-1', days: 3, when: 'AFTER_FOOD', note: 'Nausea' },
  DEXAMETHASONE_4: { frequency: '1-0-1', days: 2, when: 'AFTER_FOOD', note: 'Delayed nausea' },
  PANTOPRAZOLE_40: { frequency: '1-0-0', days: 5, when: 'MORNING_EMPTY_STOMACH', note: 'Stomach protection' },
  PEGFILGRASTIM_6: { frequency: null, frequencyText: 'Once, day 2', days: 1, when: 'AFTER_FOOD', note: 'Prevents low counts / fever' },
  BENZYDAMINE_MOUTHWASH: { frequency: null, frequencyText: '3 times a day', days: 7, when: 'AFTER_FOOD', note: 'Mouth sores' },
  // No number of days is drawn for Loperamide (H22): the usual days of the prescription apply.
  LOPERAMIDE_2: { frequency: null, frequencyText: 'After each loose motion (max 8)', days: null, when: 'AFTER_FOOD', note: 'Loose motions' },
};
const TAKE_HOME_NAME: Record<string, string> = {
  ONDANSETRON_8: 'Ondansetron 8 mg',
  DEXAMETHASONE_4: 'Dexamethasone 4 mg',
  PANTOPRAZOLE_40: 'Pantoprazole 40 mg',
  PEGFILGRASTIM_6: 'Pegfilgrastim 6 mg',
  BENZYDAMINE_MOUTHWASH: 'Benzydamine mouthwash',
  LOPERAMIDE_2: 'Loperamide 2 mg',
};

/**
 * CHEMO_PLAN (SAH-66, SAH-70; tagged C-8): the chemotherapy order sheet and what goes home with the
 * patient. A protocol, B doses (per m², vial rounding, an edit more than 5% from calculated is
 * flagged, doxorubicin against the lifetime limit), C day-care sequence, D safety checks, the
 * independent second check, send, and the hold. Every number on the sheet is the server's answer
 * (it recomputes on every read and save); the screen never works out a dose. The second check is by
 * a person other than the prescriber, in one of two ways: the checker signs in on their own device and
 * confirms from "Second checks", or the checker enters their own PIN here (ADR-023 D6). What the
 * checker confirms is what is sent: any change after the check clears it. Nothing is sent to a
 * pharmacy, chair schedule or WhatsApp (Later); "Sent" is the record.
 */
@Component({
  selector: 'app-sp-chemo-plan',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './onco.scss', './chemo-plan.scss'],
  template: `
    <div class="sp">
      <div class="sp-row">
        <b class="otitle" data-testid="order-title">{{ title() }}</b>
        <button type="button" class="btn" data-act="refresh-order" [disabled]="loading()" (click)="refresh()">{{ ctx.store.dirty() ? 'Save draft and refresh the order' : 'Check the order again' }}</button>
      </div>
      <p class="sp-sub">{{ sub() }}</p>
      <ol class="stages" aria-label="Order stages">
        @for (s of stages(); track s.label) {
          <li [class.done]="s.done" [class.now]="s.now" [attr.aria-current]="s.now ? 'step' : null">
            <span class="dot" aria-hidden="true">{{ s.done ? '✓' : s.n }}</span>{{ s.label }}@if (s.later) {<span class="sp-sub"> Later</span>}
          </li>
        }
      </ol>
      @if (error(); as e) {
        <p class="msg crit" role="alert" data-testid="order-error">{{ e }}</p>
      }
      @if (notice(); as n) {
        <p class="msg info" role="status">{{ n }}</p>
      }
      @if (ctx.store.dirty() && !loading()) {
        <p class="msg warn" role="status">Some entries are not saved. The order is worked out from the saved record: save the draft to update it.</p>
      }

      @if (!order()?.decisionRecorded) {
        <div class="msg warn" data-testid="no-decision">
          <div><b>No decision yet.</b> Finish the assessment and confirm today’s decision. The order sheet fills itself from it.</div>
          <button type="button" class="btn" data-act="go-decision" (click)="goDecision()">Go to decision →</button>
        </div>
      } @else if (holdMode()) {
        <div class="hold" data-testid="hold">
          <h4 class="sp-h">{{ holdTitle() }}</h4>
          <dl class="facts">
            <div><dt>Reason</dt><dd>{{ holdReason() }}</dd></div>
            @if (order()?.decision === 'STOP') {
              <div><dt>Refer</dt><dd>Palliative care · counselling</dd></div>
              <div><dt>Discuss</dt><dd>Family meeting · tumour board</dd></div>
            } @else {
              <div><dt>Repeat CBC</dt><dd>{{ holdDate(6) }}</dd></div>
              <div><dt>New chemo date</dt><dd>{{ holdDate(7) }}</dd></div>
            }
          </dl>
          @if (order()?.status === 'HELD') {
            <p class="msg good" data-testid="hold-done">✓ Recorded: {{ order()!.hold?.reason }}</p>
          } @else {
            <button type="button" class="btn pri" data-act="record-hold" [disabled]="disabled() || busy()" (click)="recordHold()">
              {{ order()?.decision === 'STOP' ? 'Record and refer to palliative care' : 'Record hold and re-book' }}
            </button>
          }
          <p class="sp-sub">The chair, the pharmacy message and the WhatsApp date are Later. The record is the hold.</p>
        </div>
      } @else {
        <!-- A · Protocol -->
        <h4 class="sp-h">A · Protocol</h4>
        <p class="sp-sub">From the hospital’s approved protocol library · doses are per m²</p>
        <div class="sp-grid" role="group" aria-label="Protocol">
          @for (p of protocols(); track p.key) {
            <button type="button" class="tile" [class.on]="order()?.protocolKey === p.key" [disabled]="locked()" [attr.aria-pressed]="order()?.protocolKey === p.key" (click)="pickProtocol(p.key)">
              <b>{{ p.label }}</b>
              @if (tag(p.key); as t) {
                <span class="pill" [class.p-teal]="t === 'In plan'" [class.p-warn]="t !== 'In plan'">{{ t }}</span>
              }
              <span class="sp-sub">{{ p.subLine }}</span>
            </button>
          } @empty {
            <p class="sp-sub">The protocol library is not available. Try again.</p>
          }
        </div>

        @if (order()?.protocolKey; as pk) {
          <!-- B · Doses -->
          <h4 class="sp-h">B · Doses</h4>
          <p class="sp-sub" data-testid="bsa-line">
            Height {{ exam()?.heightCm ?? '—' }} cm · weight {{ exam()?.weightKg ?? '—' }} kg · BSA {{ bsaText() }}{{ order()!.bsa?.capped ? ' (capped at 2.0)' : '' }}
          </p>
          @if (order()!.recalculated) {
            <p class="msg warn" role="status" data-testid="recalc-msg">Weight changed by {{ order()!.weightChangePct }}%. Doses recalculated.</p>
          }
          <span class="sp-lbl">Dose level</span>
          <div class="sp-row" role="group" aria-label="Dose level">
            @for (l of levels; track l) {
              <button type="button" class="chip sq" [class.on]="order()!.levelPct === l" [disabled]="locked()" (click)="setLevel(l)">{{ l }}%</button>
            }
          </div>
          @if (order()!.levelPct < 100) {
            <span class="sp-lbl">Reason for reduction (required):</span>
            <div class="sp-row" role="group" aria-label="Reason for reduction">
              @for (r of reductionReasons; track r.key) {
                <button type="button" class="chip sq" [class.on]="reductionKey() === r.key" [disabled]="locked()" (click)="setReduction(r.key)">{{ r.label }}</button>
              }
            </div>
          }
          <div class="scroll">
            <table class="tbl" aria-label="Doses">
              <thead><tr><th>Drug</th><th>mg/m²</th><th>Calculated</th><th>Rounded</th><th>Final dose</th><th>Lifetime dose</th></tr></thead>
              <tbody>
                @for (d of order()!.drugs; track d.drugKey) {
                  <tr>
                    <td><b>{{ d.name }}</b>@if (d.vesicant) {<span class="pill p-crit"> Vesicant</span>}@if (d.note) {<span class="sp-sub"> {{ d.note }}</span>}</td>
                    <td>{{ d.dosePerM2 }}</td>
                    <td>{{ mg(d.calculatedMg) }}</td>
                    <td>{{ mg(d.roundedMg) }}</td>
                    <td>
                      <input class="input deg" type="number" min="0" max="10000" step="1" [value]="d.finalMg ?? ''" [disabled]="locked()" [attr.aria-label]="'Final dose of ' + d.name + ' in mg'" (change)="setFinal(d.drugKey, $event)" />
                      @if (d.moreThan5Pct) {
                        <span class="note alert" role="status">More than 5% from calculated</span>
                      }
                    </td>
                    <td>
                      @if (d.lifetime; as lt) {
                        <span [class.alert]="lt.red" [class.note]="!lt.red">{{ lt.earlierMgM2 }} earlier + {{ lt.thisCycleMgM2 }} this cycle = {{ lt.totalMgM2 }} of {{ lt.limitMgM2 }} mg/m²</span>
                      } @else {
                        <span class="sp-sub">No lifetime limit</span>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <label class="field narrow">
            <span class="sp-lbl">Earlier doxorubicin doses before this clinic (mg/m²)</span>
            <input class="input" type="number" min="0" max="2000" step="1" [value]="order()!.priorOutsideDoxorubicinMgM2 ?? 0" [disabled]="locked()" (change)="setPrior($event)" />
          </label>
          <p class="sp-sub">Rounded to the nearest vial size (within 5% of calculated). Doses recalculate if weight changes by 10% or more.</p>

          <!-- C · Sequence -->
          <h4 class="sp-h">C · Day-care sequence <span class="sp-sub">Chair time ≈ {{ chairMinutes() }} min</span></h4>
          <ol class="seq" aria-label="Sequence">
            @for (s of sequence(); track s.label) {
              <li><b>{{ s.label }}</b><span class="sp-sub">{{ s.min }} min</span></li>
            }
          </ol>
          @for (p of order()!.premeds; track p.key) {
            <label class="chk">
              <input type="checkbox" [checked]="p.given" [disabled]="locked()" (change)="setPremed(p.key, $event)" />
              Pre-med · {{ p.text }}
            </label>
          }
          @if (order()!.emeticRisk) {
            <p class="sp-sub">Emetic risk: {{ order()!.emeticRisk }}</p>
          }

          <!-- D · Safety checks -->
          <h4 class="sp-h">D · Safety checks before sending <span class="pill" [class.p-good]="allPass()" [class.p-crit]="!allPass()" data-testid="check-badge">{{ order()!.passedCount }} of {{ order()!.totalCount }} passed</span></h4>
          <span class="sp-lbl">Scheme / TPA</span>
          <div class="sp-row" role="group" aria-label="Scheme">
            @for (s of schemes; track s.key) {
              <button type="button" class="chip sq" [class.on]="order()!.scheme === s.key" [disabled]="locked()" (click)="save({ scheme: s.key })">{{ s.label }}</button>
            }
          </div>
          <ul class="checks">
            @for (c of order()!.checks; track c.key) {
              <li [class.ok]="c.passed" [attr.data-check]="c.key">
                <span class="icon" aria-hidden="true">{{ c.passed ? '✓' : '!' }}</span>
                <span class="sr-only">{{ c.passed ? 'Passed:' : 'Not passed:' }}</span>
                <span class="grow"><b>{{ checkLabel(c.key) }}</b> <span class="sp-sub">{{ c.detail }}</span></span>
                @if (c.action && !locked()) {
                  <button type="button" class="btn small" (click)="doAction(c.key, c.action)">{{ actionLabel(c.action) }}</button>
                }
              </li>
            }
          </ul>

          <!-- Second check -->
          <h4 class="sp-h">Independent second check by (not the prescriber)</h4>
          @if (order()!.status === 'DRAFT' || order()!.status === 'NONE') {
            @if (order()!.secondCheck.state === 'CONFIRMED') {
              <p class="msg good" role="status" data-testid="second-confirmed">{{ confirmedText() }}</p>
            } @else {
              @if (order()!.secondCheck.state === 'CLEARED') {
                <p class="msg warn" role="status" data-testid="second-cleared">Order changed after the second check. A new confirmation is needed.</p>
              }
              @if (!allPass()) {
                <p class="sp-sub">Clear the red checks before the second check.</p>
              }
              <div class="sp-row" role="group" aria-label="Second checker">
                @for (o of checkerOptions(); track o.membershipId) {
                  <button type="button" class="chip sq" [class.on]="order()!.checker?.membershipId === o.membershipId" [disabled]="locked() || !allPass()" (click)="nameChecker(o.membershipId)">
                    {{ o.name }} · {{ roleLabel(o.role) }}
                  </button>
                } @empty {
                  <span class="sp-sub">No one else in this clinic can be the second checker.</span>
                }
                @if (order()!.checker && !locked()) {
                  <button type="button" class="link" (click)="nameChecker(null)">Remove the name</button>
                }
              </div>
              @if (order()!.checker; as ck) {
                <p class="msg" role="status" data-testid="waiting">Waiting for {{ ck.name }} to confirm the second check</p>
                <p class="sp-sub">{{ ck.name }} can sign in on their own device and open Second checks to confirm, or enter their own PIN below at this device.</p>
                @if (checkerHasPin()) {
                  <div class="sp-row pinrow">
                    <label class="field">
                      <span class="sp-lbl">{{ ck.name }}’s PIN</span>
                      <input class="input pin" type="password" inputmode="numeric" autocomplete="off" maxlength="4" pattern="[0-9]*" [value]="pin()" aria-describedby="pin-help" (input)="pin.set(digits($event))" />
                    </label>
                    <button type="button" class="btn pri" data-act="confirm-pin" [disabled]="pin().length !== 4 || busy()" (click)="confirmByPin()">Confirm with PIN</button>
                  </div>
                  <p id="pin-help" class="sp-sub">The PIN is checked once and never kept or shown.</p>
                } @else {
                  <p class="sp-sub">{{ ck.name }} has no PIN set. They can confirm from Second checks.</p>
                }
                <button type="button" class="link" data-act="recheck" (click)="refresh()">Check whether {{ ck.name }} has confirmed</button>
              }
            }
          } @else {
            <p class="sp-sub">{{ order()!.secondCheck.state === 'CONFIRMED' ? confirmedText() : 'No second check was needed for this order.' }}</p>
          }

          <!-- Send -->
          <p class="send" [class.ok]="order()!.send.ready || order()!.status === 'SENT'" data-testid="send-note">{{ sendNote() }}</p>
          @if (order()!.status === 'SENT') {
            <p class="msg good" data-testid="sent">✓ Sent to pharmacy</p>
            @if (order()!.factsChangedSinceSent) {
              <p class="msg warn">Facts changed after sending. The sent doses stay.</p>
            }
          } @else {
            <button type="button" class="btn pri big" data-act="send-order" [disabled]="!order()!.send.ready || busy() || disabled()" (click)="send()">Sign and send to pharmacy</button>
            <p class="sp-sub">The pharmacy and the chair are not connected yet (Later). “Sent” is the record.</p>
          }
        }
      }

      <!-- E · Take-home -->
      <h4 class="sp-h">E · Take-home medicines</h4>
      <p class="sp-sub">Generic names first. Ticked lines go on the prescription.</p>
      <ul class="plain">
        @for (m of takeHome(); track m.key) {
          <li>
            <label class="chk">
              <input type="checkbox" [checked]="val().takeHome.includes(m.key)" [disabled]="disabled()" (change)="toggleTake(m.key)" />
              <span><b>{{ m.name }}</b> <span class="sp-sub">{{ m.text }}{{ m.key === 'PEGFILGRASTIM_6' && pegNote() ? ' · suggested: fever / regimen' : '' }}</span></span>
            </label>
          </li>
        }
      </ul>

      <!-- F · Next cycle -->
      <h4 class="sp-h">F · Next cycle and patient calendar</h4>
      <dl class="facts" data-testid="next-cycle">
        <div>
          <dt>{{ nextLabel() }}</dt>
          <dd>
            <input class="input" type="date" [value]="val().nextCycleDate ?? nextDefault() ?? ''" [disabled]="disabled()" aria-label="Next chemo date" (change)="patch({ nextCycleDate: txt($event) })" />
          </dd>
        </div>
        @if (onco()?.nextCycle; as nc) {
          <div><dt>Blood test</dt><dd>{{ date(nc.bloodTestDate) }} <span class="sp-sub">CBC, kidney, liver · day before</span></dd></div>
          <div><dt>Echo</dt><dd>{{ nc.echo }} <span class="sp-sub">{{ lvefText() }}</span></dd></div>
        }
        <div>
          <dt>Imaging</dt>
          <dd><input class="input" type="text" maxlength="200" [value]="val().imagingText ?? ''" [disabled]="disabled()" aria-label="Imaging" placeholder="For example after cycle 4" (change)="patch({ imagingText: txt($event) })" /></dd>
        </div>
      </dl>
      <button type="button" class="btn" disabled title="Needs the WhatsApp service">Send chemo calendar on WhatsApp <span class="later">Later</span></button>

      <span class="sp-lbl">Referrals</span>
      <div class="sp-row" role="group" aria-label="Referrals">
        @for (r of referrals(); track r.key) {
          <button type="button" class="chip" [class.on]="val().referrals.includes(r.key)" [disabled]="disabled()" (click)="toggleRef(r.key)">{{ val().referrals.includes(r.key) ? '✓ ' : '+ ' }}{{ r.label }}</button>
        }
      </div>

      <label class="field">
        <span class="sp-lbl">Cycle note (written for you)</span>
        <textarea class="input notebox" maxlength="4000" [value]="val().cycleNote ?? ''" [disabled]="disabled()" (change)="patch({ cycleNote: txt($event) })"></textarea>
      </label>
      <div class="sp-row">
        @if (!disabled()) {
          <button type="button" class="btn" data-act="write-note" (click)="writeNote()">Write the note for me</button>
        }
        <button type="button" class="btn" data-act="copy-note" [disabled]="!val().cycleNote" (click)="copyNote()">Copy</button>
      </div>
    </div>
  `,
})
export class ChemoPlanComponent extends SpEditor<SpChemoPlan> {
  protected readonly component = 'CHEMO_PLAN' as const;
  private readonly api = inject(SpecialtyApi);

  protected blank(): SpChemoPlan {
    return { component: 'CHEMO_PLAN', schemaVersion: 1, takeHome: [], referrals: [] };
  }

  protected readonly order = signal<ChemoOrderView | null>(null);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly checkerOptions = signal<ChemoCheckerOption[]>([]);
  protected readonly pin = signal('');
  protected readonly reductionKey = signal<string | null>(null);
  protected readonly onco = this.ctx.onco;

  protected readonly levels = LEVELS;
  protected readonly schemes = SCHEMES;
  protected readonly reductionReasons = REDUCTION_REASONS;
  /**
   * The take-home lines: the reference list oncTakeHome (contract 0.5.1: six lines, with the medicine,
   * the drawn dose and the reason), else the lines drawn in the mockup.
   */
  protected readonly takeHome = computed<TakeHomeRow[]>(() => {
    const list = this.ctx.list(REFERENCE_LISTS.oncTakeHome);
    if (!list || list.length === 0) {
      return TAKE_HOME.map((m) => ({
        key: m.key as TakeHome,
        name: m.name,
        text: `${m.dose} · ${m.why}`,
        defaultOn: TAKE_HOME_DEFAULT.includes(m.key),
        line: this.drawnLine(m.key),
      }));
    }
    return list.map((i) => {
      const d = i.defaults ?? null;
      const dose = [d?.frequency ?? d?.frequencyText ?? null, d?.days && d.days > 1 ? `× ${d.days} days` : null].filter((x) => !!x).join(' ');
      return {
        key: i.key as TakeHome,
        name: i.label,
        text: [dose, i.note].filter((x) => !!x).join(' · '),
        defaultOn: i.defaultOn === true,
        line: {
          name: i.medicine?.name ?? i.label,
          source: 'SPECIALTY_LIST',
          frequency: d?.frequency ?? null,
          frequencyText: d?.frequencyText ?? null,
          days: d?.days ?? null,
          when: 'AFTER_FOOD',
          note: i.note ?? null,
        },
      };
    });
  });
  /** The eight referral chips (reference oncReferrals, else as drawn). None is ticked at the start. */
  protected readonly referrals = computed<{ key: Referral; label: string }[]>(() => {
    const list = this.ctx.list(REFERENCE_LISTS.oncReferrals);
    return list && list.length > 0 ? list.map((i) => ({ key: i.key as Referral, label: i.label })) : REFERRALS.map((r) => ({ key: r.key as Referral, label: r.label }));
  });
  /** The start values of this cycle still apply: nothing was ticked or unticked by the doctor yet. */
  private readonly startValues = signal(false);
  protected readonly pegNote = computed(() => this.startValues() && this.pegSuggested());

  protected readonly protocols = computed<ChemoProtocol[]>(() => this.ctx.reference()?.protocols ?? []);
  protected readonly exam = computed(() => {
    const key = this.ctx.sectionKeyOf('ONC_EXAM');
    return key ? this.ctx.value<SpOncExam>(key, 'ONC_EXAM') : null;
  });
  protected readonly locked = computed(() => this.disabled() || this.busy() || ['SENT', 'HELD'].includes(this.order()?.status ?? ''));
  protected readonly allPass = computed(() => !!this.order() && this.order()!.passedCount === this.order()!.totalCount);
  protected readonly holdMode = computed(() => this.order()?.decision === 'DELAY_1_WEEK' || this.order()?.decision === 'STOP' || this.order()?.send.holdMode === true);
  private defaultsApplied = false;

  constructor() {
    super();
    this.ctx.loadReference();
    effect(() => {
      // The order is read once the record is open; unsaved entries are saved first so the server sees them.
      const id = this.ctx.store.recordId();
      if (id && untracked(() => this.order() === null && !this.loading())) {
        untracked(() => this.refresh());
      }
    });
    effect(() => {
      // Start of a cycle: the first, second, third and fifth take-home lines are ticked (SAH-70-1).
      const decided = this.order()?.decisionRecorded && !this.holdMode();
      if (decided && !this.defaultsApplied && !this.disabled() && untracked(() => this.stored() === null)) {
        this.defaultsApplied = true;
        untracked(() => this.applyDefaults());
      }
    });
  }

  // ---- headings ----

  protected readonly title = computed(() => {
    const o = this.order();
    const cycle = this.onco()?.cycleNo;
    const proto = this.protocols().find((p) => p.key === o?.protocolKey)?.label;
    if (!o?.decisionRecorded) {
      return `Cycle ${cycle ?? '—'} · Day 1`;
    }
    if (this.holdMode()) {
      return 'No chemotherapy today';
    }
    return `Cycle ${cycle ?? '—'} · Day 1${proto ? ` · ${proto}${o.levelPct < 100 ? ` at ${o.levelPct}%` : ''}` : ''}`;
  });
  protected readonly sub = computed(() => `${this.order()?.decision ? 'Decision: ' + decisionLabel(this.order()!.decision) : 'Waiting for decision'} · Day care`);

  protected readonly stages = computed(() => {
    const st = this.order()?.stages ?? {};
    const flags = [!!st.decided, !!st.dosesChecked, !!st.secondCheck, st.pharmacy === 'SENT', false];
    const firstOpen = flags.findIndex((f) => !f);
    return STAGE_LABELS.map((label, i) => ({ label, n: String(i + 1), done: flags[i], now: i === firstOpen && i < 4, later: i === 4 }));
  });

  protected readonly holdTitle = computed(() => (this.order()?.decision === 'STOP' ? 'Stop chemotherapy · move to supportive / palliative care' : 'Delay chemotherapy by 1 week'));
  protected readonly holdReason = computed(() => {
    const failed = this.order()?.checks.find((c) => !c.passed);
    const dev = DEVIATION_REASONS.find((r) => r.key === this.onco()?.decision?.reasonKey)?.label;
    return failed ? `${checkLabelOf(failed.key)}: ${failed.detail}` : (dev ?? 'Clinical judgement');
  });
  protected readonly confirmedText = computed(() => {
    const s = this.order()?.secondCheck;
    return `Second check confirmed by ${s?.confirmedByName ?? ''}, ${roleText(s?.confirmedRole)}, ${s?.confirmedAt ? formatDisplayDateTimeOfInstant(s.confirmedAt) : ''}`;
  });
  protected readonly sendNote = computed(() => {
    const o = this.order();
    if (o?.status === 'SENT') {
      return `Sent ${o.drugs.map((d) => `${d.name} ${d.finalMg} mg`).join(', ')} · pharmacy preparing`;
    }
    return o?.send.note ?? '';
  });
  protected readonly bsaText = computed(() => {
    const used = this.order()?.bsa?.used;
    return used === null || used === undefined ? '—' : `${used.toFixed(2)} m²`;
  });
  protected readonly chairMinutes = computed(() => this.sequence().reduce((a, s) => a + s.min, 0));
  protected readonly sequence = computed(() => {
    const o = this.order();
    if (!o) {
      return [];
    }
    const pre = o.premeds.reduce((a, p) => a + p.minutes, 0) + 15;
    return [
      { label: 'Pre-meds', min: pre },
      ...o.drugs.map((d) => ({ label: d.name, min: d.infusionMinutes ?? 0 })),
      { label: 'Flush + observe', min: 30 },
    ];
  });
  protected readonly checkerHasPin = computed(() => !!this.checkerOptions().find((o) => o.membershipId === this.order()?.checker?.membershipId)?.hasPin);
  protected readonly nextDefault = computed(() => this.onco()?.nextCycle?.defaultDate ?? null);
  protected readonly nextLabel = computed(() => (this.onco()?.nextCycle?.label === 'REBOOKED_CHEMO' ? 'Re-booked chemo' : 'Next chemo'));
  protected readonly lvefText = computed(() => {
    const key = this.ctx.sectionKeyOf('ONC_LABS');
    const l = key ? this.ctx.value<SpOncLabs>(key, 'ONC_LABS') : null;
    return l?.values.lvef != null ? `LVEF last ${l.values.lvef}%` : '';
  });
  /** The server's answer on the last read (contract 0.5.1), or the same rule on what is on screen: protocol TC, or a fever or admission event. */
  protected readonly pegSuggested = computed(
    () => this.ctx.view()?.onco?.pegfilgrastimSuggested === true || !!this.onco()?.feverOrAdmissionEvent || this.order()?.protocolKey === 'TC',
  );

  // ---- helpers for the template ----

  protected txt(e: Event): string | null {
    return textOrNull(e);
  }
  protected digits(e: Event): string {
    return (e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 4);
  }
  protected mg(n: number | null): string {
    return n == null ? '—' : `${n} mg`;
  }
  protected date(iso: string): string {
    return formatDisplayDate(iso);
  }
  protected holdDate(days: number): string {
    const d = new Date();
    d.setDate(d.getDate() + days);
    return formatDisplayDate(d.toISOString().slice(0, 10));
  }
  protected checkLabel(key: string): string {
    return checkLabelOf(key);
  }
  protected actionLabel(action: string): string {
    return CHECK_ACTION_LABEL[action] ?? action;
  }
  protected roleLabel(role: string): string {
    return roleText(role);
  }
  protected tag(key: string): string | null {
    const t = this.order()?.protocolTags?.[key] ?? (this.protocols().find((p) => p.key === key)?.suggestedOnChange && this.order()?.decision === 'CHANGE_REGIMEN' ? 'SUGGESTED' : null);
    return t === 'IN_PLAN' ? 'In plan' : t === 'SUGGESTED' ? 'Suggested' : t === 'NEW_CONSENT' ? 'New consent' : null;
  }

  // ---- reading and saving the order ----

  /** Saves what is on screen first (the server works from the saved record), then reads the order. */
  protected refresh(): void {
    const id = this.ctx.store.recordId();
    if (!id) {
      return;
    }
    const read = (): void => {
      this.loading.set(true);
      this.api.getChemoOrder(id).subscribe({
        next: (o) => {
          this.loading.set(false);
          this.adopt(o);
          this.loadCheckers(id);
        },
        error: (e: unknown) => {
          this.loading.set(false);
          this.error.set(detailOr(e, 'We could not read the order sheet. Try again.'));
        },
      });
    };
    if (this.ctx.store.dirty()) {
      this.ctx.store.save().subscribe((ok) => (ok ? read() : undefined));
    } else {
      read();
    }
  }

  private loadCheckers(id: string): void {
    if (this.order()?.status === 'DRAFT' || this.order()?.status === 'NONE') {
      this.api.listChemoCheckerOptions(id).subscribe({ next: (o) => this.checkerOptions.set(o), error: () => this.checkerOptions.set([]) });
    }
  }

  private adopt(o: ChemoOrderView): void {
    this.order.set(o);
    this.ctx.store.orderStatusOverride.set(o.status);
    if (o.levelPct === 100) {
      this.reductionKey.set(null);
    } else if (this.reductionKey() === null && o.reductionReason) {
      this.reductionKey.set(REDUCTION_REASONS.find((r) => r.label === o.reductionReason)?.key ?? null);
    }
  }

  private input(patch: Partial<ChemoOrderInput>): ChemoOrderInput | null {
    const o = this.order();
    const protocolKey = patch.protocolKey ?? o?.protocolKey;
    if (!protocolKey) {
      return null;
    }
    const level = (patch.levelPct ?? o?.levelPct ?? 100) as ChemoLevel;
    const text = !this.reductionKey() && o?.reductionReason && !REDUCTION_REASONS.some((r) => r.label === o.reductionReason) ? o.reductionReason : null;
    return {
      revision: o?.revision ?? 0,
      protocolKey,
      levelPct: level,
      reductionReasonKey: (this.reductionKey() as ChemoOrderInput['reductionReasonKey']) ?? null,
      reductionReasonText: text,
      finalDoses: (o?.drugs ?? []).filter((d) => d.finalEdited && d.finalMg != null).map((d) => ({ drugKey: d.drugKey, finalMg: d.finalMg as number })),
      premedsNotGiven: (o?.premeds ?? []).filter((p) => !p.given).map((p) => p.key),
      allergyConfirmed: o?.allergyConfirmed ?? false,
      consentNewSigned: o?.consentNewSigned ?? false,
      scheme: (o?.scheme as ChemoScheme | null) ?? undefined,
      priorOutsideDoxorubicinMgM2: o?.priorOutsideDoxorubicinMgM2 ?? 0,
      ...patch,
    };
  }

  protected save(patch: Partial<ChemoOrderInput>): void {
    const body = this.input(patch);
    const id = this.ctx.store.recordId();
    if (!body || !id || this.locked()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.saveChemoOrder(id, body).subscribe({
      next: (o) => {
        this.busy.set(false);
        this.adopt(o);
        this.loadCheckers(id);
      },
      error: (e: unknown) => this.failed(e, 'We could not save the order. Try again.'),
    });
  }

  private failed(e: unknown, fallback: string): void {
    this.busy.set(false);
    const slug = problemSlug(e);
    if (slug === 'chemo-order-changed' || slug === 'chemo-order-sent') {
      this.error.set(detailOr(e, 'The order changed. The sheet was read again.'));
      this.refresh();
      return;
    }
    this.error.set(detailOr(e, fallback));
  }

  protected pickProtocol(key: ChemoProtocolKey): void {
    this.save({ protocolKey: key });
  }

  protected setLevel(level: ChemoLevel): void {
    if (level === 100) {
      this.reductionKey.set(null);
    }
    this.save({ levelPct: level, ...(level === 100 ? { reductionReasonKey: null, reductionReasonText: null } : {}) });
  }

  protected setReduction(key: string): void {
    this.reductionKey.set(key);
    this.save({ reductionReasonKey: key as ChemoOrderInput['reductionReasonKey'], reductionReasonText: null });
  }

  protected setFinal(drugKey: string, e: Event): void {
    const raw = (e.target as HTMLInputElement).value.trim();
    const o = this.order();
    if (!o) {
      return;
    }
    const others = (o.drugs ?? []).filter((d) => d.finalEdited && d.drugKey !== drugKey && d.finalMg != null).map((d) => ({ drugKey: d.drugKey, finalMg: d.finalMg as number }));
    this.save({ finalDoses: raw === '' ? others : [...others, { drugKey, finalMg: Number(raw) }] });
  }

  protected setPrior(e: Event): void {
    this.save({ priorOutsideDoxorubicinMgM2: Math.max(0, Math.min(2000, Number((e.target as HTMLInputElement).value) || 0)) });
  }

  protected setPremed(key: string, e: Event): void {
    const given = (e.target as HTMLInputElement).checked;
    const notGiven = (this.order()?.premeds ?? []).filter((p) => (p.key === key ? !given : !p.given)).map((p) => p.key);
    this.save({ premedsNotGiven: notGiven });
  }

  protected doAction(key: string, action: string): void {
    const o = this.order();
    if (action === 'GO') {
      this.goDecision();
    } else if (key === 'ALLERGIES_REVIEWED') {
      this.save({ allergyConfirmed: !(o?.allergyConfirmed ?? false) });
    } else if (key === 'CONSENT_REGIMEN') {
      this.save({ consentNewSigned: !(o?.consentNewSigned ?? false) });
    } else if (key === 'SCHEME_APPROVAL') {
      this.save({ scheme: action === 'UNDO' ? 'NOT_IN_PACKAGE' : 'PREAUTH_RAISED' });
    }
  }

  protected goDecision(): void {
    this.ctx.store.setPart('ASSESSMENT');
  }

  // ---- second check ----

  protected nameChecker(membershipId: string | null): void {
    const id = this.ctx.store.recordId();
    const o = this.order();
    if (!id || !o || this.locked()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.nameChemoChecker(id, { revision: o.revision ?? 0, checkerMembershipId: membershipId }).subscribe({
      next: (view) => {
        this.busy.set(false);
        this.adopt(view);
      },
      error: (e: unknown) => this.failed(e, 'We could not name the checker. Try again.'),
    });
  }

  /** Way (b): the named checker's own PIN. It is sent once; the field is cleared whatever the answer. */
  protected confirmByPin(): void {
    const id = this.ctx.store.recordId();
    const o = this.order();
    const checker = o?.checker;
    const pin = this.pin();
    this.pin.set('');
    if (!id || !o || !checker || pin.length !== 4) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api
      .confirmChemoSecondCheckByPin(id, { orderRevision: o.revision ?? 0, fingerprint: o.fingerprint, checkerMembershipId: checker.membershipId, pin })
      .subscribe({
        next: (view) => {
          this.busy.set(false);
          this.adopt(view);
        },
        error: (e: unknown) => this.failed(e, 'We could not check the PIN. Try again.'),
      });
  }

  // ---- send and hold ----

  protected send(): void {
    const id = this.ctx.store.recordId();
    const o = this.order();
    if (!id || !o || !o.send.ready) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.sendChemoOrder(id, { revision: o.revision ?? 0, fingerprint: o.fingerprint }).subscribe({
      next: (view) => {
        this.busy.set(false);
        this.adopt(view);
        this.ctx.store.refreshRecord();
      },
      error: (e: unknown) => this.failed(e, 'We could not send the order. Try again.'),
    });
  }

  protected recordHold(): void {
    const id = this.ctx.store.recordId();
    const o = this.order();
    if (!id || !o) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.recordChemoHold(id, { revision: o.revision ?? 0 }).subscribe({
      next: (view) => {
        this.busy.set(false);
        this.adopt(view);
        this.ctx.store.refreshRecord();
      },
      error: (e: unknown) => this.failed(e, 'We could not record the hold. Try again.'),
    });
  }

  // ---- take-home, referrals and the note ----

  private drawnLine(key: string): SpecialtyRxLine {
    return { name: TAKE_HOME_NAME[key], source: 'SPECIALTY_LIST', ...TAKE_HOME_RX[key] };
  }

  /** Start of a cycle (SAH-70-1): the lines marked on are ticked, Pegfilgrastim too when it is suggested; Loperamide is not. */
  private applyDefaults(): void {
    const rows = this.takeHome().filter((m) => m.defaultOn || (m.key === 'PEGFILGRASTIM_6' && this.pegSuggested()));
    this.startValues.set(true);
    this.patch({ takeHome: rows.map((m) => m.key) });
    this.ctx.addSpecialtyRx(rows.map((m) => m.line)).subscribe();
  }

  protected toggleTake(key: TakeHome): void {
    const row = this.takeHome().find((m) => m.key === key);
    if (!row) {
      return;
    }
    const on = this.val().takeHome.includes(key);
    this.startValues.set(false);
    this.patch({ takeHome: toggled(this.val().takeHome, key) });
    if (on) {
      this.ctx.removeRx([row.line.name]);
    } else {
      this.ctx.addSpecialtyRx([row.line]).subscribe();
    }
  }

  protected toggleRef(key: Referral): void {
    this.patch({ referrals: toggled(this.val().referrals, key) });
  }

  protected writeNote(): void {
    const o = this.onco();
    const order = this.order();
    const at = <T extends SpOncTox | SpOncLabs>(component: T['component']): T | null => {
      const key = this.ctx.sectionKeyOf(component);
      return key ? this.ctx.value<T>(key, component) : null;
    };
    const labs = at<SpOncLabs>('ONC_LABS');
    this.patch({
      cycleNote: composeCycleNote({
        cycleNo: o?.cycleNo ?? null,
        ecog: this.ctx.value<SpOncEcog>(this.ctx.sectionKeyOf('ONC_ECOG') ?? '', 'ONC_ECOG')?.grade ?? null,
        worstGrade: o?.worstGrade ?? null,
        weightKg: this.exam()?.weightKg ?? null,
        bsaUsed: o?.bsaUsed ?? null,
        labs,
        labsDrawnText: labs?.drawnAt ? formatDisplayDateTimeOfInstant(labs.drawnAt) : null,
        response: o?.response ?? null,
        decision: o?.decision?.choice ?? order?.decision ?? null,
        reasonLabel: DEVIATION_REASONS.find((r) => r.key === o?.decision?.reasonKey)?.label ?? null,
        protocolLabel: this.protocols().find((p) => p.key === order?.protocolKey)?.label ?? null,
        levelPct: order?.protocolKey ? order.levelPct : null,
        doses: (order?.drugs ?? []).map((d) => ({ name: d.name, finalMg: d.finalMg })),
      }),
    });
  }

  protected copyNote(): void {
    const text = this.val().cycleNote;
    if (text && typeof navigator !== 'undefined' && navigator.clipboard) {
      void navigator.clipboard.writeText(text).then(
        () => this.notice.set('Copied'),
        () => this.notice.set('We could not copy. Select the text and copy it.'),
      );
    }
  }
}

function checkLabelOf(key: string): string {
  return CHECK_LABEL[key] ?? key;
}

function roleText(role: string | null | undefined): string {
  switch (role) {
    case 'NURSE':
      return 'Nurse';
    case 'PHARMACIST':
      return 'Pharmacist';
    case 'DOCTOR':
      return 'Doctor';
    case 'VISITING_DOCTOR':
      return 'Visiting doctor';
    case 'OWNER':
      return 'Owner';
    default:
      return role ?? '';
  }
}
