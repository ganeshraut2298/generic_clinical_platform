import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ApiError } from '../../../core/http/problem-detail';
import { isOutsideScope } from '../ipd-refusal';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { formatInrPaise } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { formatDate, formatWhen, problemMessage } from '../../config/config-format';
import { AdmissionsApi } from '../admissions.api';
import {
  DISCHARGE_ROLE_HINT,
  DischargeInput,
  DischargeSheet,
  DischargeTemplateKey,
  DischargeType,
  balanceLabel,
  dischargeButtonText,
  dischargeNotice,
  dischargeSteps,
  followUpText,
  openEscalationWarning,
  payModeLabel,
  signatureApplies,
  signedByText,
  stayText,
} from '../discharge.model';
import { AdmissionDetail, PayMode } from '../ipd.model';

const LOAD_ERROR = 'We could not load the discharge sheet. Try again.';
const SIGN_ERROR = 'We could not record the signature. Try again.';
const DISCHARGE_ERROR = 'We could not discharge the patient. Try again.';
const DISCHARGE_LOST = 'We could not discharge the patient. Check the admission before trying again.';

/** No answer at all (status 0, or not an API error) or a server error: the discharge may have happened. */
function isLostAnswer(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status === 0 || error.status >= 500;
}

/**
 * Discharge sheet (SAH-27), mockup `openDis`: numbered steps, discharge type, summary template,
 * summary preview, the doctor's sign box, the Final bill block, Collect by / Refund by and the
 * discharge button. It is shown inside the admission sheet; Back returns there. Opening it changes
 * nothing. The amounts are always the server's: the sheet only sends the total it showed
 * and the deposits (`expectedTotalPaise`, `expectedDepositsPaise`) so the server can refuse a bill
 * that moved (409 `bill-changed`).
 *
 * Roles: the sign box is disabled when `canSign` is false, and the discharge button when
 * `canDischarge` is false (doctor, nurse), with a hint beside it. The refund rule stays on the
 * server: `canRefund` disables nothing, and a 403 (for example `refund-needs-owner`) shows the
 * server's message. Once signed, the sheet shows the server's signer and time (`signature.signedByName`
 * and `signedAt`), because any doctor or the owner can sign; this line is new text beyond the mockup.
 * When the answer to the discharge call is lost (no answer, or a 5xx) the patient may already be
 * discharged: the sheet says so and asks the admission sheet to read the admission again.
 */
@Component({
  selector: 'app-discharge-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrls: ['../ipd-bill.scss', './discharge-sheet.component.scss'],
  templateUrl: './discharge-sheet.component.html',
})
export class DischargeSheetComponent implements OnInit {
  private readonly api = inject(AdmissionsApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly scopeRefusals = inject(ScopeRefusals);

  readonly admissionId = input.required<string>();
  /** Back to the admission sheet. */
  readonly back = output<void>();
  /** Close the whole dialog. */
  readonly closed = output<void>();
  /** The discharge went through; carries the discharged admission for the toast. */
  readonly discharged = output<AdmissionDetail>();
  /** Something was written (a signature), so the lists behind the dialog need a refresh. */
  readonly changed = output<void>();
  /** The admission moved on (not admitted any more, read-only): the admission sheet should reload. */
  readonly outdated = output<void>();

  /** Mockup order: UPI (the default), Cash, Card. */
  protected readonly modes: readonly PayMode[] = ['UPI', 'Cash', 'Card'];
  protected readonly formatInrPaise = formatInrPaise;
  protected readonly balanceLabel = balanceLabel;
  protected readonly payModeLabel = payModeLabel;
  protected readonly stay = stayText;
  protected readonly roleHint = DISCHARGE_ROLE_HINT;

  protected readonly sheet = signal<DischargeSheet | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly chosenType = signal<DischargeType | null>(null);
  protected readonly chosenTemplate = signal<DischargeTemplateKey | null>(null);
  protected readonly mode = signal<PayMode>('UPI');
  protected readonly signing = signal(false);
  protected readonly running = signal(false);
  protected readonly actionError = signal<string | null>(null);
  /** True once the server said the patient is not admitted (or IPD is read-only): nothing more to do here. */
  protected readonly stale = signal(false);

  protected readonly type = computed<DischargeType>(
    () => this.chosenType() ?? this.sheet()?.defaultType ?? 'normal',
  );
  protected readonly template = computed<DischargeTemplateKey>(
    () => this.chosenTemplate() ?? this.sheet()?.defaultTemplateKey ?? 'general',
  );
  /** A sign-off older than the open escalation does not count (SAH-33 review fix, ADR-007 R2): unticked. */
  protected readonly signed = computed(
    () =>
      !this.sheet()?.signoffBeforeEscalation &&
      signatureApplies(this.sheet()?.signature, this.type(), this.template()),
  );
  /** "Signed by Dr. A · 24 Oct 2026, 11:05 AM IST" while a signature applies to the choice, else ''. */
  protected readonly signedLine = computed(() =>
    this.signed() ? signedByText(this.sheet()?.signature, formatWhen) : '',
  );
  protected readonly steps = computed(() => dischargeSteps(this.sheet()?.approvalChain ?? true, this.signed()));
  protected readonly balance = computed(() => this.sheet()?.bill.balancePaise ?? 0);
  protected readonly buttonText = computed(() => dischargeButtonText(this.balance(), formatInrPaise));
  protected readonly death = computed(() => this.type() === 'death');
  protected readonly followUp = computed(() => {
    const s = this.sheet();
    return s ? followUpText(s.preview, this.type(), s.followUpDate, formatDate) : '';
  });
  /** SAH-33: the warning for an escalation not closed; hidden while the same text shows as the error. */
  protected readonly escalationWarning = computed(() => {
    const text = openEscalationWarning(
      this.sheet()?.openEscalation,
      this.type(),
      this.sheet()?.signoffBeforeEscalation,
    );
    return text !== '' && text === this.actionError() ? '' : text;
  });
  protected readonly notice = computed(() => {
    const s = this.sheet();
    return s ? dischargeNotice(this.type(), s.followUpDate, formatDate) : '';
  });

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loadError.set(null);
    this.fetch((error) => {
      if (this.outsideScope(error, false)) {
        return;
      }
      this.loadError.set(problemMessage(error, LOAD_ERROR));
    });
  }

  /**
   * SAH-34 AC 38, 39: 403 `outside-scope` is reported once; the admission sheet around this one clears and closes,
   * and the page says so. Nothing is shown as saved.
   */
  private outsideScope(error: unknown, write: boolean): boolean {
    if (!isOutsideScope(error)) {
      return false;
    }
    this.scopeRefusals.report(this.admissionId(), write);
    return true;
  }

  /** Reads the sheet again and keeps the choices still on offer (after bill-changed and the like). */
  private reload(): void {
    this.fetch((error) => {
      this.outsideScope(error, false);
    });
  }

  private fetch(failed: (error: unknown) => void): void {
    this.api
      .dischargeSheet(this.admissionId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sheet) => this.accept(sheet),
        error: failed,
      });
  }

  /** Takes a fresh sheet; a choice that is no longer on offer falls back to the server default. */
  private accept(sheet: DischargeSheet): void {
    this.sheet.set(sheet);
    if (!sheet.types.some((t) => t.key === this.chosenType())) {
      this.chosenType.set(null);
    }
    if (!sheet.templates.some((t) => t.key === this.chosenTemplate())) {
      this.chosenTemplate.set(null);
    }
  }

  protected setType(type: DischargeType): void {
    this.chosenType.set(type);
  }

  protected setTemplate(key: string): void {
    this.chosenTemplate.set(key as DischargeTemplateKey);
  }

  protected setMode(mode: PayMode): void {
    this.mode.set(mode);
  }

  /** Ticking records the signature at once; a signature cannot be taken back, so unticking does nothing. */
  protected sign(box: HTMLInputElement): void {
    const sheet = this.sheet();
    if (!sheet || !sheet.canSign || this.signing() || this.stale()) {
      box.checked = this.signed();
      return;
    }
    if (!box.checked) {
      box.checked = this.signed();
      return;
    }
    this.signing.set(true);
    this.actionError.set(null);
    this.api
      .signDischarge(sheet.admissionId, { type: this.type(), templateKey: this.template() })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (fresh) => {
          this.signing.set(false);
          this.accept(fresh);
          box.checked = this.signed();
          this.changed.emit();
        },
        error: (error: unknown) => {
          this.signing.set(false);
          box.checked = false;
          if (this.outsideScope(error, true)) {
            return;
          }
          this.fail(error, SIGN_ERROR);
        },
      });
  }

  protected discharge(): void {
    const sheet = this.sheet();
    if (!sheet || !sheet.canDischarge || !this.signed() || this.running() || this.stale()) {
      return;
    }
    const body: DischargeInput = {
      type: this.type(),
      templateKey: this.template(),
      expectedTotalPaise: sheet.bill.totalPaise,
      expectedDepositsPaise: sheet.bill.depositsPaise,
      ...(sheet.bill.balancePaise !== 0 ? { mode: this.mode() } : {}),
    };
    this.running.set(true);
    this.actionError.set(null);
    this.api
      .discharge(sheet.admissionId, body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (admission) => {
          this.running.set(false);
          this.discharged.emit(admission);
        },
        error: (error: unknown) => {
          this.running.set(false);
          if (this.outsideScope(error, true)) {
            return;
          }
          if (isLostAnswer(error)) {
            this.actionError.set(DISCHARGE_LOST);
            this.outdated.emit();
            return;
          }
          this.fail(error, DISCHARGE_ERROR);
        },
      });
  }

  /**
   * Shows the server's message. Some answers also change the sheet: a moved bill or an unsigned
   * summary reloads it; "not admitted" and "read-only" end the work here and ask the admission
   * sheet to reload. A 409 `escalation-open` (SAH-33) also reloads, so the warning above the footer shows. A 403 only shows its message.
   */
  private fail(error: unknown, fallback: string): void {
    this.actionError.set(problemMessage(error, fallback));
    if (!(error instanceof ApiError)) {
      return;
    }
    const slug = error.problem.type;
    if (
      error.status === 409 &&
      (slug.endsWith('bill-changed') ||
        slug.endsWith('summary-not-signed') ||
        slug.endsWith('escalation-open'))
    ) {
      this.reload();
    } else if (error.status === 409 && (slug.endsWith('not-admitted') || slug.endsWith('ipd-readonly'))) {
      this.stale.set(true);
      this.outdated.emit();
    }
  }
}
