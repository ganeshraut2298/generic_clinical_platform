import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { newClientKey } from '../../../shared/client-key';
import { formatDisplayDateOfInstant, formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { rupees } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import {
  Customer,
  CustomerAccount,
  CustomerEntryKind,
  CustomerPaymentReceipt,
  PAYMENT_MODE_LABELS,
} from '../pharmacy-sale.model';
import { PharmacyApi } from '../pharmacy.api';
import { parseRupees } from './sale.util';

const ENTRY_TEXT: Record<CustomerEntryKind, string> = {
  CREDIT_BILL: 'Credit bill',
  PAYMENT: 'Payment received',
  CANCELLED_BILL: 'Bill cancelled',
  RETURN_TO_UDHAR: 'Return adjusted in udhar',
  CREDIT_NOTE_ISSUED: 'Credit note issued',
  CREDIT_NOTE_USED: 'Credit note used',
};

const PAY_MODES = ['CASH', 'UPI', 'CARD'] as const;

/**
 * The customer's own account at the counter (SAH-80-2 and 80-4; not drawn in the mockup, the story marks it NEW): "New
 * customer", "Receive payment" against udhar with a "Payment received" slip for the browser print view, and the list of
 * credit bills and payments with the running due. The credit limit is set only by the owner or a pharmacist; the server
 * refuses it for a cashier, so the field is not shown to one.
 */
@Component({
  selector: 'app-customer-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    form {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: 10px;
    }
    label {
      display: flex;
      flex-direction: column;
      gap: 4px;
      font-size: 0.85rem;
    }
    input.field,
    select.field {
      min-height: 44px;
      padding: 6px 10px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    .err {
      color: var(--crit);
      font-size: 0.8rem;
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    table {
      min-width: 560px;
    }
    @media print {
      .noprint {
        display: none !important;
      }
    }
  `,
  template: `
    <div class="acts noprint">
      <button mat-stroked-button type="button" data-testid="cust-new" (click)="mode.set(mode() === 'new' ? 'none' : 'new')">New customer</button>
      @if (customer(); as c) {
        @if (c.credit.duePaise > 0) {
          <button mat-stroked-button type="button" data-testid="cust-pay" (click)="mode.set(mode() === 'pay' ? 'none' : 'pay')">Receive payment</button>
        }
        <button mat-stroked-button type="button" data-testid="cust-account" (click)="toggleAccount(c)">Credit account</button>
      }
    </div>

    @if (mode() === 'new') {
      <form class="noprint" (submit)="create($event)" data-testid="cust-form">
        <label>
          Name
          <input class="field" maxlength="80" data-testid="cust-name" [value]="name()" (input)="name.set($any($event.target).value)" />
        </label>
        <label>
          Phone (optional)
          <input class="field" inputmode="numeric" maxlength="10" data-testid="cust-phone" [value]="phone()" (input)="phone.set($any($event.target).value)" />
        </label>
        <label>
          Allergy note (optional, not checked)
          <input class="field" maxlength="200" data-testid="cust-allergy" [value]="allergy()" (input)="allergy.set($any($event.target).value)" />
        </label>
        @if (canSetLimit()) {
          <label>
            Credit limit (₹)
            <input class="field" inputmode="decimal" data-testid="cust-limit" [value]="limit()" (input)="limit.set($any($event.target).value)" />
          </label>
        }
        <button mat-flat-button type="submit" data-testid="cust-save" [disabled]="saving()">Save customer</button>
        @if (samePhone()) {
          <button mat-stroked-button type="button" data-testid="cust-same-phone" [disabled]="saving()" (click)="create($event, true)">Save anyway</button>
        }
      </form>
    }

    @if (mode() === 'pay' && customer(); as c) {
      <form class="noprint" (submit)="receive($event, c)" data-testid="pay-form">
        <p>Due {{ money(c.credit.duePaise) }}</p>
        <label>
          Amount (₹)
          <input class="field" inputmode="decimal" data-testid="pay-amount" [value]="amount()" (input)="amount.set($any($event.target).value)" />
        </label>
        <label>
          Mode
          <select class="field" data-testid="pay-mode" (change)="payMode.set($any($event.target).value)">
            @for (m of payModes; track m) {
              <option [value]="m" [selected]="payMode() === m">{{ modeLabel(m) }}</option>
            }
          </select>
        </label>
        <button mat-flat-button type="submit" data-testid="pay-save" [disabled]="saving()">Receive payment</button>
      </form>
    }

    @if (error(); as text) {
      <p class="err" role="alert" data-testid="cust-error">{{ text }}</p>
    }

    @if (receipt(); as r) {
      <div id="payment-slip" data-testid="payment-slip">
        <p>
          <b>Payment received</b> · {{ r.store.name }}
          @if (r.printHeader.unitGstin) {
            · GSTIN {{ r.printHeader.unitGstin }}
          }
        </p>
        <p>
          {{ r.customerName }} · {{ money(r.amountPaise) }} by {{ modeLabel(r.mode) }} · {{ dateTime(r.receivedAt) }} · received by
          {{ r.receivedBy.name }}
        </p>
        <p data-testid="slip-due">Due after this payment {{ money(r.dueAfterPaise) }}</p>
        <button class="noprint" mat-stroked-button type="button" data-testid="slip-print" (click)="print()">Print</button>
      </div>
    }

    @if (account(); as a) {
      <div class="tbl-wrap" data-testid="cust-account-table">
        @if (a.items.length === 0) {
          <p class="note">No entries yet.</p>
        } @else {
          <table>
            <thead>
              <tr>
                <th scope="col">Date</th>
                <th scope="col">Entry</th>
                <th scope="col">Number</th>
                <th scope="col" class="num">Amount</th>
                <th scope="col" class="num">Due after</th>
              </tr>
            </thead>
            <tbody>
              @for (e of a.items; track e.entryId) {
                <tr>
                  <td>{{ date(e.at) }}</td>
                  <td>{{ entryText(e.kind) }}</td>
                  <td>{{ e.reference ?? '—' }}</td>
                  <td class="num">{{ money(e.amountPaise) }}</td>
                  <td class="num">{{ money(e.dueAfterPaise) }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
        @if (isNumber(a.credit.over60DaysPaise)) {
          <p class="note" data-testid="cust-over60">{{ money(a.credit.over60DaysPaise ?? 0) }} over 60 days</p>
        }
      </div>
    }
  `,
})
export class CustomerPanel {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  /** The customer chosen at the counter, if any. */
  readonly customer = input<Customer | null>(null);
  readonly created = output<Customer>();
  readonly updated = output<Customer>();

  protected readonly mode = signal<'none' | 'new' | 'pay'>('none');
  protected readonly name = signal('');
  protected readonly phone = signal('');
  protected readonly allergy = signal('');
  protected readonly limit = signal('');
  protected readonly amount = signal('');
  protected readonly payMode = signal<'CASH' | 'UPI' | 'CARD'>('CASH');
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly samePhone = signal(false);
  protected readonly receipt = signal<CustomerPaymentReceipt | null>(null);
  protected readonly account = signal<CustomerAccount | null>(null);

  protected readonly payModes = PAY_MODES;
  protected readonly money = rupees;
  protected readonly isNumber = (v: number | null | undefined): boolean => typeof v === 'number';
  protected readonly date = (instant: string) => formatDisplayDateOfInstant(instant);
  protected readonly dateTime = (instant: string) => formatDisplayDateTimeOfInstant(instant);
  protected readonly modeLabel = (m: string) => PAYMENT_MODE_LABELS[m as keyof typeof PAYMENT_MODE_LABELS];
  protected readonly entryText = (k: CustomerEntryKind) => ENTRY_TEXT[k];

  private createKey = newClientKey();
  private payKey = newClientKey();

  /** A cashier cannot set a credit limit: the owner and a pharmacist can. */
  protected canSetLimit(): boolean {
    const access = this.context.workingStore()?.access;
    return access?.sellScheduled === true || access?.cancelBill === true;
  }

  protected create(event: Event, confirmSamePhone = false): void {
    event.preventDefault();
    const storeId = this.context.workingStoreId();
    if (!storeId || this.saving()) {
      return;
    }
    this.error.set(null);
    const name = this.name().trim();
    const phone = this.phone().trim();
    if (name === '') {
      this.error.set('Enter the name');
      return;
    }
    if (phone !== '' && !/^\d{10}$/.test(phone)) {
      this.error.set('Enter a 10-digit phone number');
      return;
    }
    let creditLimitPaise: number | null = null;
    if (this.canSetLimit() && this.limit().trim() !== '') {
      creditLimitPaise = parseRupees(this.limit());
      if (creditLimitPaise === null) {
        this.error.set('Enter the credit limit in rupees.');
        return;
      }
    }
    this.saving.set(true);
    this.api
      .createCustomer(storeId, {
        clientKey: this.createKey,
        patientId: null,
        name,
        phone: phone === '' ? null : phone,
        allergyNote: this.allergy().trim() === '' ? null : this.allergy().trim(),
        creditLimitPaise,
        confirmSamePhone,
      })
      .subscribe({
        next: (made) => {
          this.saving.set(false);
          this.createKey = newClientKey();
          this.mode.set('none');
          this.samePhone.set(false);
          this.name.set('');
          this.phone.set('');
          this.allergy.set('');
          this.limit.set('');
          this.created.emit(made);
        },
        error: (e: unknown) => {
          this.saving.set(false);
          this.samePhone.set(problemSlug(e) === 'customer-phone-exists');
          this.error.set(saveErrorMessage(e));
        },
      });
  }

  protected receive(event: Event, customer: Customer): void {
    event.preventDefault();
    const storeId = this.context.workingStoreId();
    if (!storeId || this.saving()) {
      return;
    }
    this.error.set(null);
    const paise = parseRupees(this.amount());
    if (paise === null || paise < 1) {
      this.error.set('Enter the amount in rupees.');
      return;
    }
    if (paise > customer.credit.duePaise) {
      this.error.set('The payment is more than the due.');
      return;
    }
    this.saving.set(true);
    this.api.receiveCustomerPayment(storeId, customer.id, { clientKey: this.payKey, amountPaise: paise, mode: this.payMode() }).subscribe({
      next: (slip) => {
        this.saving.set(false);
        this.payKey = newClientKey();
        this.receipt.set(slip);
        this.amount.set('');
        this.mode.set('none');
        this.account.set(null);
        this.updated.emit({ ...customer, credit: { ...customer.credit, duePaise: slip.dueAfterPaise } });
      },
      error: (e: unknown) => {
        this.saving.set(false);
        this.error.set(saveErrorMessage(e));
      },
    });
  }

  protected toggleAccount(customer: Customer): void {
    const storeId = this.context.workingStoreId();
    if (this.account()) {
      this.account.set(null);
      return;
    }
    if (!storeId) {
      return;
    }
    this.api.customerAccount(storeId, customer.id).subscribe({
      next: (a) => this.account.set(a),
      error: (e: unknown) => this.error.set(loadErrorMessage(e)),
    });
  }

  protected print(): void {
    window.print();
  }
}
