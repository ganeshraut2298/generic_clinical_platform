import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ShellStore } from '../../../core/shell/shell.store';
import { newClientKey } from '../../../shared/client-key';
import { formatDisplayDate, istToday } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { AdmitDialogComponent, AdmitDialogData } from '../../ipd/admit/admit-dialog.component';
import { AdmissionDetail } from '../../ipd/ipd.model';
import { detailOr, problemSlug } from '../emr-errors';
import { CourseApi } from './course.api';
import {
  CourseAccount,
  CoursePaymentCreate,
  CoursePaymentType,
  CourseSitting,
  CourseSittingAction,
  PaymentMode,
  TreatmentCourseKind,
  TreatmentCourseSummary,
  TreatmentCourseView,
} from './course.model';
import {
  AMOUNT_STEP_PAISE,
  ENCOUNTER_WORD,
  MODES,
  NOTE_ACCOUNT,
  PayChoice,
  KIND_ACCENT,
  STATUS_WORD,
  TYPE_WORD,
  UNIT_WORD,
  actionLabel,
  amountHint,
  amountProblem,
  apiType,
  chargeText,
  collectLabel,
  dateProblem,
  doneToast,
  encounterText,
  numberOf,
  parseRupees,
  payNote,
  payOptions,
  receiptNumber,
  rupees,
  sittingLabel,
  stats,
  suggestedPaise,
} from './course.logic';
import { accentVars } from '../specialty/specialty-registry';
import { ReceiptDialogComponent, ReceiptDialogData } from './receipt-dialog.component';

type CourseView = 'timeline' | 'table' | 'payments';

const VIEWS: readonly { key: CourseView; label: string }[] = [
  { key: 'timeline', label: 'Visits' },
  { key: 'table', label: 'Table' },
  { key: 'payments', label: 'Payments' },
];

const LOAD_ERROR = 'We could not open this course. Try again.';
const PLAN_ERROR = 'We could not save the change. Try again.';
const PAY_ERROR = 'We could not record the payment. Nothing was taken twice. Try again.';
const TOAST_MS = 3500;
const SITTING_CHANGED = 'This sitting changed. Reload.';

/** The date choice of "Rebook" and "Reschedule" (today or later); one open at a time. */
interface DateChoice {
  sittingId: string;
  action: 'REBOOK' | 'RESCHEDULE';
  date: string;
}

interface EditState {
  sittingId: string;
  date: string;
  items: string;
  charge: string;
  note: string;
}

/**
 * Multi-visit treatment course and payments (SAH-58; mockup #TreatmentCourse): the course header
 * and estimate, the timeline ("Visits"), the "Table", and "Payments" with the collect panel and the
 * list of receipts and adjustments. The plan is the doctor's (opd); the money is billing's: one
 * running account whose figures come from the server. Payment is collected by the owner and front
 * desk only (DEC-042 H2); a doctor sees the figures. A retry of a payment sends the same
 * Idempotency-Key, so one payment is recorded. Nothing here is logged or stored in the browser.
 *
 * SAH-75-3 and SAH-75-10: the same component is the panel embedded in the Dental, Gynae (ANC), Physiotherapy,
 * Surgery and Oncology screens (`embedded`, `courseId`, `visitId`, `kind` inputs); each sitting's buttons are
 * drawn from the server's `allowedActions` (mark done, patient did not come, rebook, reschedule, remove, move
 * to day care / IPD or request admission, admit now). The browser adds no charge and decides no state.
 */
@Component({
  selector: 'app-course',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, RouterLink],
  host: { '[style]': 'accent()' },
  styleUrl: './course.page.scss',
  templateUrl: './course.page.html',
})
export class CoursePage implements OnInit {
  private readonly api = inject(CourseApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);
  private readonly shell = inject(ShellStore);

  /** Embedded in a doctor screen: the course to show, the visit of that screen, and the kind of the department. */
  readonly courseId = input<string | null>(null);
  readonly embedded = input(false);
  readonly visitId = input<string | null>(null);
  readonly kind = input<TreatmentCourseKind | null>(null);

  protected readonly views = VIEWS;
  protected readonly modes = MODES;
  protected readonly accountNote = NOTE_ACCOUNT;
  protected readonly stepAmount = AMOUNT_STEP_PAISE;

  private readonly currentId = signal('');
  protected readonly course = signal<TreatmentCourseView | null>(null);
  protected readonly account = signal<CourseAccount | null>(null);
  protected readonly others = signal<TreatmentCourseSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly accountError = signal<string | null>(null);
  protected readonly view = signal<CourseView>('timeline');
  protected readonly busy = signal(false);
  protected readonly planError = signal<string | null>(null);
  protected readonly toast = signal<string | null>(null);
  protected readonly editing = signal<EditState | null>(null);
  protected readonly dateChoice = signal<DateChoice | null>(null);
  protected readonly dateError = signal<string | null>(null);
  protected readonly accent = computed(() => {
    const kind = this.course()?.kind;
    return kind ? accentVars(KIND_ACCENT[kind]) : null;
  });
  protected readonly today = istToday;

  // payment panel
  protected readonly choice = signal<PayChoice>('today');
  protected readonly useCredit = signal(true);
  protected readonly mode = signal<PaymentMode>('UPI');
  private readonly customPaise = signal<number | null>(null);
  protected readonly paying = signal(false);
  protected readonly payError = signal<string | null>(null);

  /** The visit to link to a planned sitting ("This is today's <unit>"), when opened from today's EMR. */
  protected readonly activeVisitId = computed(() => this.visitId() ?? this.routeVisitId());
  private readonly routeVisitId = signal<string | null>(null);

  protected readonly abs = Math.abs;
  protected readonly unitWord = computed(() => UNIT_WORD[this.course()?.unit ?? 'SITTING']);
  protected readonly unitLower = computed(() => this.unitWord().toLowerCase());
  protected readonly stats = computed(() => {
    const a = this.account();
    return a ? stats(a) : [];
  });
  protected readonly options = computed(() => {
    const a = this.account();
    return a ? payOptions(a, this.useCredit()) : [];
  });
  protected readonly amountPaise = computed(() => {
    const a = this.account();
    if (!a) {
      return 0;
    }
    return this.customPaise() ?? suggestedPaise(this.choice(), a, this.useCredit());
  });
  protected readonly type = computed<CoursePaymentType>(() => {
    const a = this.account();
    return a ? apiType(this.choice(), a) : 'VISIT_PAYMENT';
  });
  protected readonly problem = computed(() => {
    const a = this.account();
    return a ? amountProblem(this.amountPaise(), this.type(), a) : null;
  });
  protected readonly hint = computed(() => {
    const a = this.account();
    return a ? amountHint(this.choice(), a, this.amountPaise()) : '';
  });
  protected readonly collectText = computed(() => collectLabel(this.amountPaise(), this.mode()));
  protected readonly note = computed(() => {
    const a = this.account();
    return a ? payNote(a) : '';
  });
  protected readonly creditText = computed(() => {
    const a = this.account();
    return a && a.creditPaise > 0 ? `${rupees(a.creditPaise)} credit` : 'none available';
  });
  protected readonly canCollect = computed(() => this.account()?.canCollect ?? false);
  /** Receipts are for the counter roles: owner, front desk, billing. A doctor sees the table but cannot open one (403). */
  protected readonly canReceipt = computed(() => {
    const role = this.shell.me()?.role;
    return role === 'OWNER' || role === 'FRONT_DESK' || role === 'BILLING';
  });

  private pendingKey: { key: string; body: string } | null = null;
  private readonly addKeys = new Map<string, string>();
  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => {
      if (this.toastTimer) {
        clearTimeout(this.toastTimer);
      }
    });
    // Embedded: the panel hands the course in; a change of the course loads it.
    effect(() => {
      const id = this.courseId();
      if (id) {
        untracked(() => this.load(id));
      }
    });
  }

  ngOnInit(): void {
    if (this.courseId()) {
      return;
    }
    this.routeVisitId.set(this.route.snapshot.queryParamMap.get('visitId'));
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const id = params.get('courseId');
      if (id) {
        this.load(id);
      }
    });
  }

  protected load(courseId: string = this.currentId()): void {
    if (!courseId) {
      return;
    }
    this.currentId.set(courseId);
    this.loading.set(true);
    this.error.set(null);
    this.api.get(courseId).subscribe({
      next: (course) => {
        this.course.set(course);
        this.loading.set(false);
        this.loadAccount(course.id);
        this.api.listForPatient(course.patientId, this.kind() ?? undefined).subscribe({
          next: (list) => this.others.set(list),
          error: () => this.others.set([]),
        });
      },
      error: (e: unknown) => {
        this.loading.set(false);
        this.error.set(detailOr(e, LOAD_ERROR));
      },
    });
  }

  protected loadAccount(courseId: string = this.course()?.id ?? ''): void {
    this.accountError.set(null);
    this.api.account(courseId).subscribe({
      next: (a) => {
        this.account.set(a);
        this.customPaise.set(null);
      },
      error: (e: unknown) => this.accountError.set(detailOr(e, 'We could not load the money figures. Try again.')),
    });
  }

  // ---- words ----

  protected statusWord = (s: CourseSitting): string => STATUS_WORD[s.status];
  protected numberOf = numberOf;
  protected actionText = (a: CourseSittingAction, s: CourseSitting): string => actionLabel(a, this.course()?.unit ?? 'SITTING', s);
  protected readonly estimateSignedText = 'estimate signed by patient';
  protected encounterWord = (s: CourseSitting): string => encounterText(s);
  protected chargeWord = (s: CourseSitting): string => chargeText(s);
  protected label = (s: CourseSitting): string => sittingLabel(s, this.course()?.unit ?? 'SITTING');
  protected typeWord = (t: string | null): string => (t ? (TYPE_WORD[t as CoursePaymentType] ?? t) : 'Adjustment');
  protected dateOf(s: CourseSitting): string {
    return s.plannedDate ? formatDisplayDate(s.plannedDate) : 'Date not set';
  }
  protected ledgerDate = (iso: string): string => formatDisplayDate(iso);
  protected money = rupees;
  protected encounterKindWord = (k: CourseSitting['encounterKind']): string => ENCOUNTER_WORD[k];

  protected setView(view: CourseView): void {
    this.view.set(view);
  }

  /** The switcher: the page goes to the other course; the embedded panel loads it in place. */
  protected openCourse(id: string): void {
    if (this.embedded()) {
      this.load(id);
      return;
    }
    void this.router.navigate(['/app/courses', id]);
  }

  // ---- the plan (doctor) ----

  private applyCourse(next: TreatmentCourseView, message: string | null): void {
    this.course.set(next);
    this.busy.set(false);
    this.planError.set(null);
    this.loadAccount(next.id);
    if (message) {
      this.say(message);
    }
  }

  private planFailed(e: unknown): void {
    this.busy.set(false);
    const slug = problemSlug(e);
    if (slug === 'course-changed') {
      // A second press or another device changed the sitting: the answer is "This sitting changed. Reload." and the course is read again.
      this.planError.set(SITTING_CHANGED);
      this.reloadCourse();
      return;
    }
    this.planError.set(detailOr(e, PLAN_ERROR));
  }

  /** "Add": the choices of the course kind. The same tap twice makes one sitting (same Idempotency-Key). */
  protected add(optionKey: string, label: string): void {
    const course = this.course();
    if (!course || !course.canPlan || this.busy()) {
      return;
    }
    const key = this.addKeys.get(optionKey) ?? newClientKey();
    this.addKeys.set(optionKey, key);
    this.busy.set(true);
    this.api.addSitting(course.id, { revision: course.revision, addOptionKey: optionKey }, key).subscribe({
      next: (next) => {
        this.addKeys.delete(optionKey);
        this.applyCourse(next, `${label} added to the course`);
      },
      error: (e: unknown) => {
        if (problemSlug(e) === 'course-changed') {
          this.addKeys.delete(optionKey);
        }
        this.planFailed(e);
      },
    });
  }

  /** The server's list of what the caller may do on this sitting now (the buttons come from it). */
  protected actionsOf(s: CourseSitting): CourseSittingAction[] {
    return s.allowedActions ?? [];
  }

  protected isPrimary(a: CourseSittingAction): boolean {
    return a === 'MARK_DONE' || a === 'ADMIT';
  }

  private reloadCourse(): void {
    const id = this.course()?.id;
    if (id) {
      this.api.get(id).subscribe({
        next: (c) => {
          this.course.set(c);
          this.loadAccount(c.id);
        },
        error: () => undefined,
      });
    }
  }

  /** One press of an action button. Rebook and Reschedule first ask for a date; Admit now opens the admission sheet. */
  protected run(action: CourseSittingAction, s: CourseSitting): void {
    const course = this.course();
    if (!course || this.busy()) {
      return;
    }
    const revision = course.revision;
    const unit = UNIT_WORD[course.unit];
    switch (action) {
      case 'MARK_DONE':
        this.busy.set(true);
        this.api.markDone(course.id, s.id, { revision, visitId: this.activeVisitId() }).subscribe({
          next: (next) => this.applyCourse(next, doneToast(course.unit, next.sittings.find((x) => x.id === s.id) ?? s)),
          error: (e: unknown) => this.planFailed(e),
        });
        return;
      case 'MISSED':
        this.busy.set(true);
        this.api.markMissed(course.id, s.id, { revision }).subscribe({
          next: (next) => this.applyCourse(next, 'Marked missed.'),
          error: (e: unknown) => this.planFailed(e),
        });
        return;
      case 'REMOVE':
        this.busy.set(true);
        this.api.remove(course.id, s.id, { revision }).subscribe({
          next: (next) => this.applyCourse(next, `${unit} removed from plan`),
          error: (e: unknown) => this.planFailed(e),
        });
        return;
      case 'REBOOK':
      case 'RESCHEDULE':
        this.dateError.set(null);
        this.dateChoice.set({ sittingId: s.id, action, date: s.plannedDate && s.plannedDate >= istToday() ? s.plannedDate : istToday() });
        return;
      case 'REQUEST_ADMISSION': {
        const opd = s.encounterKind === 'OPD';
        this.busy.set(true);
        this.api.requestAdmission(course.id, s.id, { revision, encounterKind: opd ? this.admissionKind() : null }).subscribe({
          next: (next) =>
            this.applyCourse(
              next,
              opd
                ? this.admissionKind() === 'IPD'
                  ? 'Will be done as an IPD admission. Admission request created.'
                  : 'Will be done as a day-care admission. Admission request created.'
                : 'Admission request sent',
            ),
          error: (e: unknown) => this.planFailed(e),
        });
        return;
      }
      case 'ADMIT':
        this.admit(s);
        return;
    }
  }

  protected setChoiceDate(event: Event): void {
    const date = (event.target as HTMLInputElement).value;
    this.dateChoice.update((c) => (c ? { ...c, date } : c));
    this.dateError.set(null);
  }

  protected cancelDate(): void {
    this.dateChoice.set(null);
    this.dateError.set(null);
  }

  /** Confirms the chosen date: today or later, checked here for the message and again by the server. */
  protected confirmDate(s: CourseSitting): void {
    const choice = this.dateChoice();
    const course = this.course();
    if (!choice || !course || this.busy()) {
      return;
    }
    const problem = dateProblem(choice.date, istToday());
    if (problem) {
      this.dateError.set(problem);
      return;
    }
    const call = choice.action === 'REBOOK' ? this.api.rebook : this.api.reschedule;
    this.busy.set(true);
    call.call(this.api, course.id, s.id, { revision: course.revision, plannedDate: choice.date }).subscribe({
      next: (next) => {
        this.dateChoice.set(null);
        this.applyCourse(next, choice.action === 'REBOOK' ? `Rebooked for ${formatDisplayDate(choice.date)}` : `${this.label(s)} moved to ${formatDisplayDate(choice.date)}`);
      },
      error: (e: unknown) => this.planFailed(e),
    });
  }

  /** The kind an OPD sitting moves to: day care when the course offers it, else IPD (null when IPD is off). */
  private admissionKind(): 'DAY_CARE' | 'IPD' | null {
    const kinds = this.course()?.addOptions.map((o) => o.encounterKind) ?? [];
    return kinds.includes('DAY_CARE') ? 'DAY_CARE' : kinds.includes('IPD') ? 'IPD' : null;
  }

  protected canEdit(s: CourseSitting): boolean {
    return !!this.course()?.canPlan && s.status !== 'DONE' && s.status !== 'MISSED';
  }

  protected startEdit(s: CourseSitting): void {
    this.editing.set({
      sittingId: s.id,
      date: s.plannedDate ?? '',
      items: s.items.join('\n'),
      charge: String(s.chargePaise / 100),
      note: s.note ?? '',
    });
    this.planError.set(null);
  }

  protected cancelEdit(): void {
    this.editing.set(null);
  }

  protected setEdit(field: 'date' | 'items' | 'charge' | 'note', event: Event): void {
    const value = (event.target as HTMLInputElement | HTMLTextAreaElement).value;
    this.editing.update((e) => (e ? { ...e, [field]: value } : e));
  }

  protected saveEdit(s: CourseSitting): void {
    const e = this.editing();
    const course = this.course();
    if (!e || !course || this.busy()) {
      return;
    }
    const charge = parseRupees(e.charge);
    if (charge === null) {
      this.planError.set('Enter the charge in whole rupees.');
      return;
    }
    const items = e.items
      .split('\n')
      .map((x) => x.trim())
      .filter((x) => x);
    this.busy.set(true);
    this.api
      .changeSitting(course.id, s.id, {
        revision: course.revision,
        encounterKind: s.encounterKind,
        plannedDate: e.date || null,
        items,
        chargePaise: charge,
        note: e.note.trim() || null,
      })
      .subscribe({
        next: (next) => {
          this.editing.set(null);
          this.applyCourse(next, `${this.label(s)} updated`);
        },
        error: (err: unknown) => this.planFailed(err),
      });
  }

  /** "This is today's <unit>": links today's OPD visit to a planned OPD sitting (ADR-022 H5). */
  protected canLink(s: CourseSitting): boolean {
    return !!this.course()?.canPlan && !!this.activeVisitId() && s.status === 'PLANNED' && s.encounterKind === 'OPD' && !s.opdVisitId;
  }

  protected link(s: CourseSitting): void {
    const course = this.course();
    const visitId = this.activeVisitId();
    if (!course || !visitId || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api.linkVisit(course.id, s.id, { revision: course.revision, visitId }).subscribe({
      next: (next) => this.applyCourse(next, `${this.label(s)} is today’s ${this.unitLower()}`),
      error: (e: unknown) => this.planFailed(e),
    });
  }

  /** "Admit now": the existing admission sheet, with the sitting; the course credit is carried to the IPD bill. */
  protected admit(s: CourseSitting): void {
    const course = this.course();
    if (!course) {
      return;
    }
    this.dialog
      .open<AdmitDialogComponent, AdmitDialogData, AdmissionDetail | null>(AdmitDialogComponent, {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        data: { patientId: course.patientId, treatmentCourseSittingId: s.id },
      })
      .afterClosed()
      .subscribe((admission) => {
        if (admission) {
          this.say(`Admitted · ${admission.ipNumber}. Advance from this course is carried to the IPD bill.`);
          this.load(course.id);
        }
      });
  }

  // ---- payment ----

  protected pickChoice(choice: PayChoice): void {
    this.choice.set(choice);
    this.customPaise.set(null);
    this.payError.set(null);
  }

  protected toggleCredit(): void {
    this.useCredit.update((v) => !v);
    this.customPaise.set(null);
  }

  protected setMode(mode: PaymentMode): void {
    this.mode.set(mode);
  }

  protected stepBy(direction: 1 | -1): void {
    this.customPaise.set(Math.max(0, this.amountPaise() + direction * AMOUNT_STEP_PAISE));
    this.payError.set(null);
  }

  protected collect(): void {
    const course = this.course();
    const account = this.account();
    if (!course || !account || !account.canCollect || this.paying() || this.problem()) {
      return;
    }
    const todaySitting = course.sittings.find((s) => s.status === 'TODAY' && !s.settledInIpd);
    const type = this.type();
    const body: CoursePaymentCreate = {
      type,
      amountPaise: this.amountPaise(),
      mode: this.mode(),
      useCredit: this.useCredit(),
      againstSittingId:
        type === 'VISIT_PAYMENT' ? (todaySitting?.id ?? null) : type === 'IPD_DEPOSIT' ? (account.nextPlannedAdmission?.sittingId ?? null) : null,
    };
    // A retry of the same payment (lost network) sends the same key, so one payment results.
    const fingerprint = JSON.stringify(body);
    if (!this.pendingKey || this.pendingKey.body !== fingerprint) {
      this.pendingKey = { key: newClientKey(), body: fingerprint };
    }
    this.paying.set(true);
    this.payError.set(null);
    this.api.collect(course.id, body, this.pendingKey.key).subscribe({
      next: (result) => {
        this.pendingKey = null;
        this.paying.set(false);
        this.account.set(result.account);
        this.customPaise.set(null);
        this.say(`Payment collected · Receipt ${result.receiptNo}`);
      },
      error: (e: unknown) => {
        this.paying.set(false);
        const slug = problemSlug(e);
        if (slug === 'idempotency-conflict') {
          this.pendingKey = null;
        }
        this.payError.set(detailOr(e, PAY_ERROR));
      },
    });
  }

  protected openReceipt(receiptNo: string | null): void {
    const course = this.course();
    const n = receiptNumber(receiptNo);
    if (!course || n === null) {
      return;
    }
    this.dialog.open<ReceiptDialogComponent, ReceiptDialogData>(ReceiptDialogComponent, {
      panelClass: 'sheet-panel',
      width: '560px',
      maxWidth: '100vw',
      data: { courseId: course.id, receiptNo: n },
    });
  }

  private say(message: string): void {
    this.toast.set(message);
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
    this.toastTimer = setTimeout(() => this.toast.set(null), TOAST_MS);
  }
}
