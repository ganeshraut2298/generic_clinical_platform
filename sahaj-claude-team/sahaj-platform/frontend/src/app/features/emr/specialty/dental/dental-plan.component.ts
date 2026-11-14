import { ChangeDetectionStrategy, Component, DOCUMENT, computed, inject, signal } from '@angular/core';
import { CoursePanelComponent } from '../../course/course-panel.component';
import { newClientKey } from '../../../../shared/client-key';
import { formatInrPaise } from '../../../../shared/format/inr';
import { istToday } from '../../../../shared/format/ist-date';
import { CourseApi } from '../../course/course.api';
import { detailOr } from '../../emr-errors';
import { printSheet } from '../print-sheet';
import { SpEditor, numberOrNull, textOrNull } from '../shared/sp-editor';
import {
  NO_TARIFF,
  STATUS_LABEL,
  estimateTotalPaise,
  isDuplicateRow,
  isValidTooth,
  nextStatus,
  planSummary,
  rowAmountPaise,
  splitShares,
  toothOf,
} from '../dental.logic';
import { doneTodayRows, newId } from '../specialty.logic';
import { LabItemKind, SpDentalPlan, SpEmpty, SpLabItem, SpLabWork, SpPlanRow, SpToothChart } from '../specialty.model';

const BLANK_PLAN: SpDentalPlan = { component: 'DENTAL_PLAN', schemaVersion: 1, rows: [] };

/**
 * DENTAL_PLAN (SAH-49-2, SAH-69-1; tagged C-3, B-2): "Treatment plan & estimate". Rows come from the
 * chart's suggestions or are typed; the same tooth and procedure twice is refused. The estimate is the
 * clinic's tariff price, else "Not in tariff" with an amount the doctor types; the server's total wins.
 * Signing the estimate is a tick; any later change of rows, teeth, procedures or amounts needs a new
 * tick (the server sets the state). Print uses the browser; the WhatsApp copy is Later.
 */
@Component({
  selector: 'app-sp-dental-plan',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CoursePanelComponent],
  styleUrls: ['../specialty.shared.scss', './dental-plan.component.scss'],
  template: `
    <div class="sp">
      <p class="sp-sub">Tap a status to move it forward</p>
      <div class="scroll" #sheet>
        <table class="tbl" aria-label="Treatment plan and estimate">
          <thead>
            <tr><th>Tooth</th><th>Procedure</th><th>Phase</th><th>Estimate</th><th>Status</th><th><span class="sr-only">Remove</span></th></tr>
          </thead>
          <tbody>
            @for (r of rows(); track r.id) {
              <tr [class.hl]="r.status === 'DONE'">
                <td>{{ r.tooth }}</td>
                <td>
                  {{ r.procedureText }}@if (r.sittingsPlanned > 1) {<span class="sp-sub"> · sitting {{ r.currentSitting }} of {{ r.sittingsPlanned }}</span>}
                  @if (!disabled()) {
                    <span class="sit">
                      <button type="button" class="mini" [attr.aria-label]="'Fewer sittings for ' + r.procedureText" [disabled]="r.sittingsPlanned <= 1" (click)="sittings(r, -1)">−</button>
                      <button type="button" class="mini" [attr.aria-label]="'More sittings for ' + r.procedureText" [disabled]="r.sittingsPlanned >= 6" (click)="sittings(r, 1)">+</button>
                    </span>
                  }
                </td>
                <td>Phase {{ r.phase }}</td>
                <td>
                  @if (amount(r) !== null) {
                    {{ money(amount(r)) }}
                    @if (r.sittingsPlanned > 1) {
                      <span class="sp-sub">{{ shares(r) }}</span>
                    }
                  } @else {
                    <span class="sp-sub">{{ noTariff }}</span>
                  }
                  @if (r.estimateSource !== 'TARIFF' && !disabled()) {
                    <label class="amt">
                      <span class="sr-only">Amount in rupees for {{ r.procedureText }}</span>
                      <span aria-hidden="true">₹</span>
                      <input
                        class="input"
                        type="number"
                        inputmode="numeric"
                        min="0"
                        step="50"
                        placeholder="Amount"
                        [value]="typedRupees(r)"
                        (change)="typed(r, $event)"
                      />
                    </label>
                  }
                </td>
                <td>
                  <button type="button" class="chip sq stat" [class.on]="r.status !== 'PLANNED'" [class.good]="r.status === 'DONE'" [disabled]="disabled()" data-act="status" (click)="cycle(r)">
                    {{ statusLabel[r.status] }} ›
                  </button>
                </td>
                <td>
                  @if (!disabled()) {
                    <button type="button" class="link" [attr.aria-label]="'Remove ' + r.procedureText + ' from the plan'" (click)="remove(r)">×</button>
                  }
                </td>
              </tr>
            } @empty {
              <tr><td colspan="6" class="sp-sub">No treatment planned yet. Pick a tooth on the chart and add a suggested treatment, or type one below.</td></tr>
            }
          </tbody>
        </table>
      </div>
      <p class="sp-sub" data-testid="plan-summary">{{ summary() }}</p>
      <p class="total" data-testid="plan-total">{{ money(total()) }} <span class="sp-sub">(clinic tariff)</span></p>

      @if (!disabled()) {
        <div class="sp-row add">
          <label class="field">
            <span class="sp-lbl">Tooth</span>
            <input class="input num" type="number" inputmode="numeric" [value]="tTooth() ?? ''" (input)="tTooth.set(num($event))" placeholder="e.g. 36" />
          </label>
          <label class="field grow">
            <span class="sp-lbl">Procedure / service</span>
            <input class="input" type="text" maxlength="160" [value]="tText()" (input)="tText.set(txt($event))" placeholder="Type a procedure" />
          </label>
          <button type="button" class="btn" data-act="add-typed" [disabled]="!canAddTyped()" (click)="addTyped()">+ Add to plan</button>
        </div>
        @if (addProblem(); as p) {
          <p class="msg warn" role="alert">{{ p }}</p>
        }
      }

      <div class="sp-row est">
        <label class="chk">
          <input
            type="checkbox"
            [checked]="signedTick()"
            [disabled]="disabled() || rows().length === 0 || estimateState() === 'SIGNED'"
            data-act="sign-estimate"
            (change)="sign($event)"
          />
          Estimate signed by patient
        </label>
        <span class="pill" [class.p-good]="estimateState() === 'SIGNED'" [class.p-warn]="estimateState() === 'CHANGED'" [class.p-wait]="estimateState() === 'NOT_SIGNED'" data-testid="estimate-state">
          {{ estimateText() }}
        </span>
        <button type="button" class="btn" data-act="print-estimate" [disabled]="rows().length === 0" (click)="print()">Print estimate</button>
        @if (rows().length === 0) {
          <span class="sp-sub">Add a treatment first</span>
        }
        <button type="button" class="btn" disabled title="Needs the WhatsApp service">Send on WhatsApp <span class="later">Later</span></button>
      </div>
      @if (printMessage(); as m) {
        <p class="sp-sub" role="status" data-testid="print-message">{{ m }}</p>
      }

      <div class="sp-row">
        <button type="button" class="btn pri" data-act="start-course" [disabled]="disabled() || openRows() === 0 || starting() || !!course()" (click)="startCourse()">
          {{ course() ? 'Course started' : 'Start treatment course from this plan' }}
        </button>
        @if (course()) {
          <app-course-panel kind="DENTAL" [showStart]="false" />
        }
        @if (courseError(); as e) {
          <span class="msg crit" role="alert">{{ e }}</span>
        }
      </div>
    </div>
  `,
})
export class DentalPlanComponent extends SpEditor<SpDentalPlan> {
  protected readonly component = 'DENTAL_PLAN' as const;
  private readonly courses = inject(CourseApi);
  private readonly doc = inject(DOCUMENT);

  constructor() {
    super();
    this.registerFooterPrint(() => this.print());
  }

  protected blank(): SpDentalPlan {
    return BLANK_PLAN;
  }

  protected readonly statusLabel = STATUS_LABEL;
  protected readonly noTariff = NO_TARIFF;
  protected readonly rows = computed(() => this.val().rows);
  protected readonly tTooth = signal<number | null>(null);
  protected readonly tText = signal('');
  protected readonly addProblem = signal<string | null>(null);
  protected readonly printMessage = signal<string | null>(null);
  protected readonly starting = signal(false);
  protected readonly courseError = signal<string | null>(null);
  private readonly startedCourse = signal<string | null>(null);
  protected readonly course = computed(() => this.startedCourse() ?? this.val().courseId ?? null);
  private readonly courseKey = newClientKey();

  protected readonly summary = computed(() => planSummary(this.rows()));
  protected readonly openRows = computed(() => this.rows().filter((r) => r.status !== 'DONE').length);
  /** The server's total when nothing is unsaved; the sum of the rows on screen while typing. */
  protected readonly total = computed(() => (this.fresh() && this.view()?.dental ? this.view()!.dental!.estimateTotalPaise : estimateTotalPaise(this.rows())));
  protected readonly estimateState = computed(() => {
    const server = this.fresh() ? (this.val().estimate?.state ?? this.view()?.dental?.estimateState) : null;
    if (server) {
      return server;
    }
    return this.val().signEstimate ? 'SIGNED' : (this.val().estimate?.state === 'SIGNED' ? 'CHANGED' : 'NOT_SIGNED');
  });
  protected readonly signedTick = computed(() => this.estimateState() === 'SIGNED' || !!this.val().signEstimate);
  protected readonly estimateText = computed(() => {
    switch (this.estimateState()) {
      case 'SIGNED':
        return 'Estimate signed by patient';
      case 'CHANGED':
        return 'Estimate changed. Sign again';
      default:
        return 'Not signed';
    }
  });
  protected readonly canAddTyped = computed(() => this.tTooth() !== null && this.tText().trim() !== '');

  protected money(paise: number | null): string {
    return formatInrPaise(paise ?? 0);
  }

  protected amount(r: SpPlanRow): number | null {
    return rowAmountPaise(r);
  }

  protected shares(r: SpPlanRow): string {
    const list = r.sittingSharesPaise && r.sittingSharesPaise.length === r.sittingsPlanned ? r.sittingSharesPaise : splitShares(this.amount(r) ?? 0, r.sittingsPlanned);
    return list.map((p) => formatInrPaise(p)).join(' · ');
  }

  protected typedRupees(r: SpPlanRow): number | string {
    return r.typedEstimatePaise != null ? r.typedEstimatePaise / 100 : '';
  }

  protected num(e: Event): number | null {
    return numberOrNull(e);
  }

  protected txt(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  private replaceRows(rows: SpPlanRow[], extra: Partial<SpDentalPlan> = {}): void {
    this.replace({ ...this.val(), ...extra, rows, estimate: undefined });
  }

  protected cycle(r: SpPlanRow): void {
    this.replaceRows(this.rows().map((x) => (x.id === r.id ? { ...x, status: nextStatus(x.status) } : x)));
  }

  protected sittings(r: SpPlanRow, d: 1 | -1): void {
    const planned = Math.max(1, Math.min(6, r.sittingsPlanned + d));
    this.replaceRows(this.rows().map((x) => (x.id === r.id ? { ...x, sittingsPlanned: planned, currentSitting: Math.min(x.currentSitting, planned) } : x)));
  }

  protected remove(r: SpPlanRow): void {
    this.replaceRows(this.rows().filter((x) => x.id !== r.id));
  }

  protected typed(r: SpPlanRow, e: Event): void {
    const rupees = numberOrNull(e);
    this.replaceRows(this.rows().map((x) => (x.id === r.id ? { ...x, typedEstimatePaise: rupees === null ? null : Math.round(rupees) * 100 } : x)));
  }

  protected addTyped(): void {
    const tooth = this.tTooth();
    const text = this.tText().trim();
    if (tooth === null || !text) {
      return;
    }
    if (!isValidTooth(tooth)) {
      this.addProblem.set('Enter a tooth number from 11 to 48, or 51 to 85 for a milk tooth.');
      return;
    }
    if (isDuplicateRow(this.rows(), tooth, text)) {
      this.addProblem.set('That treatment is already on the plan for this tooth.');
      return;
    }
    this.addProblem.set(null);
    this.replaceRows([
      ...this.rows(),
      { id: newId(), tooth, procedureKey: null, procedureText: text, phase: 1, status: 'PLANNED', sittingsPlanned: 1, currentSitting: 1 },
    ]);
    this.tText.set('');
    this.tTooth.set(null);
  }

  protected sign(e: Event): void {
    this.replace({ ...this.val(), signEstimate: (e.target as HTMLInputElement).checked });
  }

  /** The estimate prints with the clinic header, the patient, the rows and the total; "Estimate only. Final charges are as billed." is an analyst addition (SAH-69-1). */
  protected print(): void {
    const lines = this.rows().map((r) => `${r.tooth} · ${r.procedureText} · Phase ${r.phase} · ${this.amount(r) !== null ? this.money(this.amount(r)) : NO_TARIFF}`);
    const ok = printSheet(this.doc, this.ctx.sheet('Treatment estimate', [{ heading: 'Treatment plan', lines }, { heading: 'Total', lines: [this.money(this.total())] }], 'Estimate only. Final charges are as billed.'));
    this.printMessage.set(ok ? 'Estimate sent to printer' : 'We could not print. Try again.');
  }

  protected startCourse(): void {
    const recordId = this.ctx.store.recordId();
    if (!recordId) {
      return;
    }
    const run = (): void => {
      this.starting.set(true);
      this.courseError.set(null);
      this.courses.create({ fromRecordId: recordId, kind: 'DENTAL', fromPlan: true }, this.courseKey).subscribe({
        next: (c) => {
          this.starting.set(false);
          this.startedCourse.set(c.id);
        },
        error: (e: unknown) => {
          this.starting.set(false);
          this.courseError.set(detailOr(e, 'We could not start the course. Try again.'));
        },
      });
    };
    if (this.ctx.store.dirty()) {
      this.ctx.store.save().subscribe((ok) => (ok ? run() : undefined));
    } else {
      run();
    }
  }
}

/** DENTAL_DONE (SAH-69-1): derived from the plan rows done on this visit's date; it stores nothing. */
@Component({
  selector: 'app-sp-dental-done',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      @for (d of done(); track d.id) {
        <div class="msg good" data-testid="done-row">
          <b>{{ d.tooth }} · {{ d.procedureText }}</b>
          <span>Marked done today. The charge is added to the bill when the visit is completed.</span>
        </div>
      } @empty {
        <p class="sp-sub">Nothing marked done today. Move a plan row to Done and it appears here.</p>
      }
      <p class="sp-sub" data-testid="done-count">{{ done().length }} procedure(s) done today will be billed.</p>
    </div>
  `,
})
export class DentalDoneComponent extends SpEditor<SpEmpty> {
  protected readonly component = 'DENTAL_DONE' as const;
  protected blank(): SpEmpty {
    return { component: 'DENTAL_DONE', schemaVersion: 1 };
  }
  protected readonly done = computed(() => doneTodayRows({ values: this.ctx.store.values(), today: istToday() }));
}

const LAB_KINDS: { key: LabItemKind; label: string }[] = [
  { key: 'CROWN', label: 'Crown' },
  { key: 'BRIDGE', label: 'Bridge' },
  { key: 'DENTURE', label: 'Denture' },
  { key: 'ALIGNER', label: 'Aligner' },
];

/** LAB_WORK (SAH-69-2): what goes to the dental lab, typed. Nothing is ordered and no message goes to a lab (OQ-45-28). */
@Component({
  selector: 'app-sp-lab-work',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      @if (items().length > 0) {
        <div class="scroll">
          <table class="tbl" aria-label="Lab work">
            <thead><tr><th>Item</th><th>Tooth</th><th>Lab</th><th>Due</th><th><span class="sr-only">Remove</span></th></tr></thead>
            <tbody>
              @for (i of items(); track i.id) {
                <tr>
                  <td>{{ label(i.item) }}</td>
                  <td>{{ i.tooth ?? '' }}</td>
                  <td>
                    <input class="input" type="text" maxlength="80" [value]="i.labName ?? ''" [disabled]="disabled()" [attr.aria-label]="'Lab name for ' + label(i.item)" (change)="setLab(i, $event)" />
                  </td>
                  <td>
                    <input class="input" type="date" [value]="i.dueDate ?? ''" [disabled]="disabled()" [class.bad]="dueBad(i)" [attr.aria-label]="'Due date for ' + label(i.item)" (change)="setDue(i, $event)" />
                    @if (dueBad(i)) {
                      <span class="note alert" role="alert">Choose a date from today.</span>
                    }
                  </td>
                  <td>
                    @if (!disabled()) {
                      <button type="button" class="link" [attr.aria-label]="'Remove ' + label(i.item)" (click)="remove(i)">×</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      } @else {
        <p class="sp-sub">No lab work yet.</p>
      }
      @if (!disabled()) {
        <div class="sp-row">
          @for (k of kinds; track k.key) {
            <button type="button" class="chip sq" [attr.data-kind]="k.key" (click)="add(k.key)">+ {{ k.label }}</button>
          }
        </div>
      }
      <p class="sp-sub">Nothing is ordered from here. The lab is not connected yet.</p>
    </div>
  `,
})
export class LabWorkComponent extends SpEditor<SpLabWork> {
  protected readonly component = 'LAB_WORK' as const;
  protected blank(): SpLabWork {
    return { component: 'LAB_WORK', schemaVersion: 1, items: [] };
  }
  protected readonly kinds = LAB_KINDS;
  protected readonly items = computed(() => this.val().items);

  protected label(k: LabItemKind): string {
    return LAB_KINDS.find((x) => x.key === k)?.label ?? k;
  }

  protected dueBad(i: SpLabItem): boolean {
    return !!i.dueDate && i.dueDate < istToday();
  }

  protected add(item: LabItemKind): void {
    const chart = this.ctx.value<SpToothChart>(this.ctx.sectionKeyOf('TOOTH_CHART') ?? '', 'TOOTH_CHART');
    const missing = chart?.teeth.find((t) => toothOf(chart, t.tooth).condition === 'MISSING')?.tooth ?? null;
    this.patch({ items: [...this.items(), { id: newId(), planRowId: null, tooth: item === 'DENTURE' || item === 'ALIGNER' ? null : missing, item, labName: null, dueDate: null }] });
  }

  protected setLab(i: SpLabItem, e: Event): void {
    this.patch({ items: this.items().map((x) => (x.id === i.id ? { ...x, labName: textOrNull(e) } : x)) });
  }

  protected setDue(i: SpLabItem, e: Event): void {
    this.patch({ items: this.items().map((x) => (x.id === i.id ? { ...x, dueDate: textOrNull(e) } : x)) });
  }

  protected remove(i: SpLabItem): void {
    this.patch({ items: this.items().filter((x) => x.id !== i.id) });
  }
}
