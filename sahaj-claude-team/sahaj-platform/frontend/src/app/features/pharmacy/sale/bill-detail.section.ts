import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { newClientKey } from '../../../shared/client-key';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { pinProblem } from '../indent/indent.util';
import { SecondSignerComponent } from '../indent/second-signer.component';
import { rupees } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason, SecondSignerCandidate } from '../pharmacy-inventory.model';
import { Bill, PAYMENT_MODE_LABELS, PaymentMode } from '../pharmacy-sale.model';
import { PharmacyApi } from '../pharmacy.api';
import { batchWithExpiry } from '../indent/indent.util';
import { ReasonPickComponent } from './reason-pick.component';

/**
 * A saved bill, with "Cancel bill" and the refund of a cancelled one (SAH-80-5; the cancel screen is not drawn in the
 * mockup, the story marks it NEW; the rule is the matrix row of mockup 05 "Cancel / edit saved bill"). The owner may
 * cancel any bill and a pharmacist one from the same day; the server's `canCancel` decides what is shown. A cancel asks
 * a reason and "Are the medicines back on the shelf?" (Yes: Available, No: Quarantine); a Schedule X line also needs a
 * second signer. A cancelled bill with money to give back shows "Refund due" until the refund is recorded here. No
 * bill can be edited.
 */
@Component({
  selector: 'app-bill-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, SecondSignerComponent, ReasonPickComponent],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 10px;
      min-width: 0;
    }
    table {
      min-width: 640px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    fieldset {
      border: 0;
      padding: 0;
      margin: 0;
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
    }
    fieldset label,
    label.sel {
      display: flex;
      align-items: center;
      gap: 6px;
      min-height: 44px;
    }
    select.field {
      min-height: 44px;
      padding: 6px 10px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
    }
  `,
  template: `
    @if (loadError(); as text) {
      <div class="notice err" role="alert" data-testid="bill-load-error">
        <app-icon name="alert" [size]="16" />
        <span>{{ text }}</span>
      </div>
    } @else if (bill(); as b) {
      <h3 data-testid="bill-title">
        Bill {{ b.billNo }}
        <span class="pill" [class.p-warn]="b.status === 'CANCELLED'" [class.p-good]="b.status === 'SAVED'" data-testid="bill-status">
          {{ b.status === 'CANCELLED' ? 'Cancelled' : 'Saved' }}
        </span>
      </h3>
      <p data-testid="bill-meta">
        {{ b.buyer.label }} · {{ dateTime(b.savedAt) }} · saved by {{ b.savedBy.name }} · {{ money(b.summary.netPayablePaise) }}
      </p>
      <div class="tbl-wrap">
        <table data-testid="bill-lines">
          <thead>
            <tr>
              <th scope="col">#</th>
              <th scope="col">Medicine</th>
              <th scope="col">Batch</th>
              <th scope="col" class="num">Qty</th>
              <th scope="col" class="num">Amount</th>
            </tr>
          </thead>
          <tbody>
            @for (l of b.lines; track l.lineId) {
              <tr>
                <td>{{ l.lineNo }}</td>
                <td>{{ l.product.name }} {{ l.product.strength }}</td>
                <td>{{ batchText(l.batch.batchNo, l.batch.expiryDate) }}</td>
                <td class="num">{{ l.qty }}</td>
                <td class="num">{{ money(l.netPaise) }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      @if (b.status === 'SAVED') {
        <p class="note" data-testid="bill-no-edit">A saved bill cannot be edited. Cancel it and make a new bill.</p>
      }
      @if (b.status === 'CANCELLED' && b.refundDuePaise > 0) {
        <p class="notice warn" role="status" data-testid="bill-refund-due">
          <app-icon name="alert" [size]="16" />
          <span>Refund due {{ money(b.refundDuePaise) }}</span>
        </p>
        @if (canRefund()) {
          <label class="sel">
            Refund to
            <select class="field" data-testid="refund-mode" (change)="refundMode.set($any($event.target).value)">
              @for (m of refundModes(); track m) {
                <option [value]="m" [selected]="refundMode() === m">{{ modeLabel(m) }}</option>
              }
            </select>
          </label>
          <div class="acts">
            <button mat-flat-button type="button" data-testid="refund-save" [disabled]="saving()" (click)="refund(b)">
              Record refund of {{ money(b.refundDuePaise) }}
            </button>
          </div>
          <p class="note">Refund by {{ modeLabel(refundMode()) }} from the counter.</p>
        }
      }
      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="bill-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      @if (b.canCancel && !cancelling()) {
        <div class="acts">
          <button mat-stroked-button type="button" data-testid="cancel-open" (click)="cancelling.set(true)">Cancel bill</button>
        </div>
      }
      @if (cancelling()) {
        <app-reason-pick heading="Cancel bill reason" confirmLabel="Choose this reason" [reasons]="reasons()" (chosen)="reasonChosen($event)" (dismissed)="cancelling.set(false)" />
        @if (reason(); as chosen) {
          <p class="note" data-testid="cancel-reason">Reason chosen.</p>
          <fieldset>
            <legend>Are the medicines back on the shelf?</legend>
            <label><input type="radio" name="shelf" data-testid="shelf-yes" [checked]="backOnShelf() === true" (change)="backOnShelf.set(true)" /> Yes</label>
            <label><input type="radio" name="shelf" data-testid="shelf-no" [checked]="backOnShelf() === false" (change)="backOnShelf.set(false)" /> No</label>
          </fieldset>
          @if (hasScheduleX()) {
            <p class="notice warn" role="status" data-testid="cancel-x-note">
              <app-icon name="alert" [size]="16" />
              <span>Schedule X needs a pharmacist and a second signature by a different person.</span>
            </p>
            <app-second-signer
              [signers]="signers()"
              [loaded]="signersLoaded()"
              [signerError]="signerError()"
              [pinError]="pinError()"
              [(signerId)]="signerId"
              [(pin)]="pin"
            />
          }
          <div class="acts">
            <button mat-flat-button type="button" data-testid="cancel-save" [disabled]="saving()" (click)="cancel(b, chosen.reasonKey, chosen.note ?? null)">
              Cancel this bill
            </button>
          </div>
        }
      }
      @if (b.history.length > 0) {
        <h4>History</h4>
        <ul data-testid="bill-history">
          @for (h of b.history; track $index) {
            <li>{{ h.event }} · {{ dateTime(h.at) }}{{ h.by ? ' · ' + h.by.name : '' }}</li>
          }
        </ul>
      }
    } @else {
      <p class="loading" role="status">Loading the bill</p>
    }
  `,
})
export class BillDetailSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  readonly billId = input.required<string>();
  /** Told to the list so that it shows the new status. */
  readonly changed = output<Bill>();

  protected readonly bill = signal<Bill | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly cancelling = signal(false);
  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly reason = signal<{ reasonKey: string; note?: string | null } | null>(null);
  protected readonly backOnShelf = signal<boolean | null>(null);
  protected readonly refundMode = signal<PaymentMode>('CASH');
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly signerId = signal('');
  protected readonly pin = signal('');
  protected readonly signerError = signal<string | null>(null);
  protected readonly pinError = signal<string | null>(null);

  protected readonly money = rupees;
  protected readonly batchText = batchWithExpiry;
  protected readonly dateTime = (instant: string) => formatDisplayDateTimeOfInstant(instant);
  protected readonly modeLabel = (m: PaymentMode) => PAYMENT_MODE_LABELS[m];

  /** The person who records a refund is a pharmacist or a cashier or the owner: any seller (80-5). */
  protected readonly canRefund = computed(() => this.context.canSell());
  protected readonly hasScheduleX = computed(() => this.bill()?.lines.some((l) => l.schedule === 'X') === true);
  /** The modes the bill was paid in, and cash; the server checks the unit's refund modes. */
  protected readonly refundModes = computed<PaymentMode[]>(() => {
    const paid = (this.bill()?.payments ?? []).map((p) => p.mode).filter((m) => m !== 'UDHAR');
    return Array.from(new Set<PaymentMode>([...paid, 'CASH']));
  });

  private cancelKey = newClientKey();
  private refundKey = newClientKey();

  ngOnInit(): void {
    this.load();
    this.api.activeReasons(['cancel-bill']).subscribe({
      next: (answer) => this.reasons.set(answer.lists.find((l) => l.listKey === 'cancel-bill')?.reasons ?? []),
      error: () => this.reasons.set([]),
    });
    const storeId = this.context.workingStoreId();
    if (storeId) {
      this.api.secondSigners(storeId).subscribe({
        next: (people) => {
          this.signers.set(people);
          this.signersLoaded.set(true);
        },
        error: () => this.signersLoaded.set(true),
      });
    }
  }

  private load(): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.api.bill(storeId, this.billId()).subscribe({
      next: (b) => {
        this.bill.set(b);
        this.refundMode.set(this.refundModes()[0] ?? 'CASH');
      },
      error: (e: unknown) => this.loadError.set(loadErrorMessage(e)),
    });
  }

  protected reasonChosen(chosen: { reasonKey: string; note?: string | null }): void {
    this.reason.set(chosen);
  }

  protected cancel(b: Bill, reasonKey: string, note: string | null): void {
    const storeId = this.context.workingStoreId();
    if (!storeId || this.saving()) {
      return;
    }
    this.formError.set(null);
    this.signerError.set(null);
    this.pinError.set(null);
    if (this.backOnShelf() === null) {
      this.formError.set('Say whether the medicines are back on the shelf.');
      return;
    }
    let secondSigner: { membershipId: string; pin: string } | null = null;
    if (this.hasScheduleX()) {
      const problem = pinProblem(this.pin());
      if (!this.signerId()) {
        this.signerError.set('Choose the second signer');
      }
      if (problem) {
        this.pinError.set(problem);
      }
      if (!this.signerId() || problem) {
        return;
      }
      secondSigner = { membershipId: this.signerId(), pin: this.pin() };
    }
    this.saving.set(true);
    this.api
      .cancelBill(storeId, b.id, {
        clientKey: this.cancelKey,
        version: b.version,
        reasonKey,
        note,
        backOnShelf: this.backOnShelf() === true,
        secondSigner,
      })
      .subscribe({
        next: (done) => {
          this.pin.set('');
          this.saving.set(false);
          this.cancelKey = newClientKey();
          this.cancelling.set(false);
          this.reason.set(null);
          this.bill.set(done);
          this.changed.emit(done);
        },
        error: (e: unknown) => {
          this.pin.set('');
          this.saving.set(false);
          this.formError.set(
            problemSlug(e) === 'cancel-not-allowed' ? 'Only the owner can cancel a bill from an earlier day.' : saveErrorMessage(e),
          );
          if (problemSlug(e) === 'changed') {
            this.load();
          }
        },
      });
  }

  protected refund(b: Bill): void {
    const storeId = this.context.workingStoreId();
    if (!storeId || this.saving()) {
      return;
    }
    this.formError.set(null);
    this.saving.set(true);
    this.api
      .recordCancelledBillRefund(storeId, b.id, {
        clientKey: this.refundKey,
        version: b.version,
        refunds: [{ mode: this.refundMode(), amountPaise: b.refundDuePaise }],
      })
      .subscribe({
        next: (done) => {
          this.saving.set(false);
          this.refundKey = newClientKey();
          this.bill.set(done);
          this.changed.emit(done);
        },
        error: (e: unknown) => {
          this.saving.set(false);
          this.formError.set(saveErrorMessage(e));
          if (problemSlug(e) === 'changed') {
            this.load();
          }
        },
      });
  }
}
