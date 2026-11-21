import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { formatDisplayDateOfInstant } from '../../../shared/format/ist-date';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { pinProblem } from '../indent/indent.util';
import { SecondSignerComponent } from '../indent/second-signer.component';
import { rupees } from '../opening-stock/opening-stock.util';
import {
  RefundModeOption,
  ReturnableBill,
  ReturnableBillRow,
  SALES_RETURN_STATUS_LABELS,
  SalesReturn,
} from '../pharmacy-compliance.model';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason, SecondSignerCandidate } from '../pharmacy-inventory.model';
import { PAYMENT_MODE_LABELS } from '../pharmacy-sale.model';
import { PharmacyApi } from '../pharmacy.api';
import { batchWithExpiry } from '../indent/indent.util';

interface ReturnRow {
  billLineId: string;
  qty: string;
  conditionKey: string;
  error: string | null;
}

const WHOLE = /^[0-9]{1,6}$/;

/**
 * Sales return (SAH-80-18, work package L; P&I mockup 04-sales-return.html): "Bill number" (scan or type), the bill card
 * "<buyer> · bill of <date> · ₹<total>" with the window chip, the table Item · batch, Sold, Already returned, Return now,
 * Condition, Allowed?, Refund, the selects "Reason" and "Refund to", and the button "Refund ₹<amount> & print credit
 * note" (or "Send for approval" above the limit). Only the same batch that was sold can be returned. The server works
 * the money and decides where the stock goes; what the screen shows for a refund is indicative.
 */
@Component({
  selector: 'app-sales-return',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, SecondSignerComponent],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .find {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: 10px;
    }
    .find label,
    .opts label {
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
    input.cell {
      width: 72px;
    }
    table {
      min-width: 760px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    .opts {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
    }
    .err {
      color: var(--crit);
      font-size: 0.8rem;
    }
    ul.rows {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    ul.rows button {
      min-height: 44px;
      width: 100%;
      text-align: left;
      padding: 0 12px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
      cursor: pointer;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="sr-h" data-testid="sales-return">
      <h2 id="sr-h">Sales return</h2>
      @if (result(); as done) {
        <div class="notice teal" role="status" data-testid="sr-result">
          <app-icon name="check" [size]="16" />
          <span>
            {{ statusText(done) }}
            @if (done.creditNoteNo) {
              · Credit note {{ done.creditNoteNo }}
            }
            · Refund {{ money(done.refundTotalPaise) }}
          </span>
        </div>
        @if (done.status === 'COMPLETED') {
          <p class="note" data-testid="sr-stock-note">Stock goes back to {{ done.store.name }} as the return rules decide.</p>
        }
        <div class="acts">
          <button mat-stroked-button type="button" data-testid="sr-new" (click)="reset()">New return</button>
        </div>
      } @else {
        <form class="find" (submit)="find($event)">
          <label>
            Bill number
            <input
              class="field"
              type="text"
              maxlength="16"
              autocomplete="off"
              placeholder="Scan the bill QR or type the bill number"
              data-testid="sr-number"
              [value]="number()"
              (input)="number.set($any($event.target).value)"
            />
          </label>
          <button mat-flat-button type="submit" data-testid="sr-find" [disabled]="searching()">Find bill</button>
        </form>
        @if (findError(); as text) {
          <div class="notice err" role="alert" data-testid="sr-find-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }
        @if (found().length > 1 && !bill()) {
          <ul class="rows" data-testid="sr-found">
            @for (row of found(); track row.billId) {
              <li>
                <button type="button" [attr.data-testid]="'sr-pick-' + row.billNo" (click)="open(row.billId)">
                  {{ row.billNo }} · {{ row.buyerLabel }} · {{ date(row.savedAt) }} · {{ money(row.netPayablePaise) }}
                </button>
              </li>
            }
          </ul>
        }
        @if (bill(); as b) {
          <p data-testid="sr-card">
            <b>{{ b.buyerLabel }}</b> · bill of {{ date(b.savedAt) }} · {{ money(b.netPayablePaise) }}
            <span class="pill" [class.p-ok]="b.window.state === 'WITHIN'" [class.p-warn]="b.window.state !== 'WITHIN'" data-testid="sr-window">
              {{ windowText(b) }}
            </span>
          </p>
          @if (b.window.state === 'WITHIN') {
            <div class="tbl-wrap">
              <table data-testid="sr-table">
                <thead>
                  <tr>
                    <th scope="col">Item · batch</th>
                    <th scope="col" class="num">Sold</th>
                    <th scope="col" class="num">Already returned</th>
                    <th scope="col">Return now</th>
                    <th scope="col">Condition</th>
                    <th scope="col">Allowed?</th>
                    <th scope="col" class="num">Refund</th>
                  </tr>
                </thead>
                <tbody>
                  @for (line of b.lines; track line.billLineId; let n = $index) {
                    <tr [attr.data-testid]="'sr-row-' + n">
                      <td>
                        {{ line.product.name }} · {{ batchText(line.batch.batchNo, line.batch.expiryDate) }}
                        @if (line.schedule !== 'NONE') {
                          <span class="pill p-warn">Sch. {{ line.schedule }}</span>
                        }
                      </td>
                      <td class="num">{{ line.soldQty }}</td>
                      <td class="num">{{ line.returnedQty }}</td>
                      <td>
                        @if (line.returnable && line.returnableQty > 0) {
                          <input
                            class="field cell"
                            inputmode="numeric"
                            autocomplete="off"
                            [value]="rows()[n].qty"
                            [attr.aria-label]="'Return quantity of ' + line.product.name"
                            [attr.data-testid]="'sr-qty-' + n"
                            (input)="setQty(n, $any($event.target).value)"
                          />
                          @if (rows()[n].error; as text) {
                            <div class="err" role="alert">{{ text }}</div>
                          }
                        } @else {
                          <span>0</span>
                        }
                      </td>
                      <td>
                        @if (line.returnable && line.returnableQty > 0) {
                          <select
                            class="field"
                            [attr.aria-label]="'Condition of ' + line.product.name"
                            [attr.data-testid]="'sr-condition-' + n"
                            (change)="setCondition(n, $any($event.target).value)"
                          >
                            @for (c of conditions(); track c.reasonKey) {
                              <option [value]="c.reasonKey" [selected]="c.reasonKey === rows()[n].conditionKey">{{ c.label }}</option>
                            }
                          </select>
                        } @else {
                          <span>—</span>
                        }
                      </td>
                      <td [attr.data-testid]="'sr-allowed-' + n">{{ allowedText(n) }}</td>
                      <td class="num" [attr.data-testid]="'sr-refund-' + n">{{ lineRefundText(n) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <p class="note">
              Only the same batch that was sold can be returned. Cold-chain items, cut strips and Schedule X items follow the return rules
              set in Setup.
            </p>
            <div class="opts">
              <label>
                Reason
                <select class="field" data-testid="sr-reason" (change)="reasonKey.set($any($event.target).value)">
                  <option value="" [selected]="reasonKey() === ''">Choose a reason</option>
                  @for (r of reasons(); track r.reasonKey) {
                    <option [value]="r.reasonKey" [selected]="r.reasonKey === reasonKey()">{{ r.label }}</option>
                  }
                </select>
              </label>
              <label>
                Refund to
                <select class="field" data-testid="sr-refund-to" (change)="setMode($any($event.target).value)">
                  @for (m of b.refundModes; track $index) {
                    <option [value]="$index" [selected]="$index === modeIndex()">{{ modeText(m) }}</option>
                  }
                </select>
              </label>
            </div>
            @if (anyScheduleX()) {
              <p class="notice warn" role="status" data-testid="sr-schedule-x">
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
            @if (b.limitPaise !== null) {
              <p class="note" data-testid="sr-limit">Returns above {{ money(b.limitPaise) }} need approval.</p>
            }
            @if (formError(); as text) {
              <div class="notice err" role="alert" data-testid="sr-error">
                <app-icon name="alert" [size]="16" />
                <span>{{ text }}</span>
              </div>
            }
            <div class="acts">
              <button mat-flat-button type="button" data-testid="sr-submit" [disabled]="saving()" (click)="submit(b)">
                {{ needsApproval(b) ? 'Send for approval' : 'Refund ' + money(total()) + ' & print credit note' }}
              </button>
            </div>
          }
        }
      }
    </section>
  `,
})
export class SalesReturnSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  protected readonly number = signal('');
  protected readonly searching = signal(false);
  protected readonly found = signal<ReturnableBillRow[]>([]);
  protected readonly findError = signal<string | null>(null);
  protected readonly bill = signal<ReturnableBill | null>(null);
  protected readonly rows = signal<ReturnRow[]>([]);
  protected readonly reasonKey = signal('');
  protected readonly modeIndex = signal(0);
  protected readonly conditions = signal<ActiveReason[]>([]);
  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly signerId = signal('');
  protected readonly pin = signal('');
  protected readonly signerError = signal<string | null>(null);
  protected readonly pinError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly result = signal<SalesReturn | null>(null);

  protected readonly money = rupees;
  protected readonly date = (instant: string) => formatDisplayDateOfInstant(instant);
  protected readonly batchText = batchWithExpiry;

  private saveKey = newClientKey();

  protected readonly total = computed(() => {
    const b = this.bill();
    if (!b) {
      return 0;
    }
    return b.lines.reduce((sum, line, i) => sum + this.lineQty(i) * line.refundPerUnitPaise, 0);
  });

  protected readonly anyScheduleX = computed(() => {
    const b = this.bill();
    return !!b && b.lines.some((line, i) => line.schedule === 'X' && this.lineQty(i) > 0);
  });

  ngOnInit(): void {
    this.api.activeReasons(['return-condition', 'sales-return']).subscribe({
      next: (answer) => {
        this.conditions.set(answer.lists.find((l) => l.listKey === 'return-condition')?.reasons ?? []);
        this.reasons.set(answer.lists.find((l) => l.listKey === 'sales-return')?.reasons ?? []);
      },
      error: () => this.findError.set('Could not load the reasons. Try again.'),
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

  protected statusText(done: SalesReturn): string {
    return SALES_RETURN_STATUS_LABELS[done.status];
  }

  protected windowText(b: ReturnableBill): string {
    switch (b.window.state) {
      case 'WITHIN':
        return `Within ${b.window.days}-day return window`;
      case 'OVER':
        return 'Return window is over';
      default:
        return 'This bill was cancelled.';
    }
  }

  protected modeText(m: RefundModeOption): string {
    return m.original ? `Original mode (${PAYMENT_MODE_LABELS[m.mode]})` : PAYMENT_MODE_LABELS[m.mode];
  }

  protected needsApproval(b: ReturnableBill): boolean {
    return b.limitPaise !== null && this.total() > b.limitPaise;
  }

  private lineQty(index: number): number {
    const text = this.rows()[index]?.qty.trim() ?? '';
    return WHOLE.test(text) ? Number(text) : 0;
  }

  protected allowedText(index: number): string {
    const line = this.bill()?.lines[index];
    const row = this.rows()[index];
    if (!line || !row) {
      return '';
    }
    if (!line.returnable) {
      return 'Not returnable';
    }
    if (line.returnableQty === 0) {
      return 'Nothing left to return';
    }
    return row.conditionKey.endsWith('.sealed') ? 'Yes' : 'Pharmacist decides';
  }

  protected lineRefundText(index: number): string {
    const line = this.bill()?.lines[index];
    const qty = this.lineQty(index);
    return line && qty > 0 && line.returnable ? this.money(qty * line.refundPerUnitPaise) : '—';
  }

  protected find(event: Event): void {
    event.preventDefault();
    const storeId = this.context.workingStoreId();
    const text = this.number().trim();
    if (!storeId || this.searching()) {
      return;
    }
    this.findError.set(null);
    this.found.set([]);
    this.bill.set(null);
    if (text === '') {
      this.findError.set('Type the bill number.');
      return;
    }
    this.searching.set(true);
    this.api.findReturnableBills(storeId, { number: text }).subscribe({
      next: (items) => {
        this.searching.set(false);
        this.found.set(items);
        if (items.length === 0) {
          this.findError.set('No bill found. Check the number.');
        } else if (items.length === 1) {
          this.open(items[0].billId);
        }
      },
      error: (error: unknown) => {
        this.searching.set(false);
        this.findError.set(saveErrorMessage(error));
      },
    });
  }

  protected open(billId: string): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.api.returnableBill(storeId, billId).subscribe({
      next: (b) => {
        const sealed = this.conditions().find((c) => c.reasonKey.endsWith('.sealed'))?.reasonKey ?? this.conditions()[0]?.reasonKey ?? '';
        this.rows.set(b.lines.map((l) => ({ billLineId: l.billLineId, qty: '', conditionKey: sealed, error: null })));
        this.modeIndex.set(0);
        this.reasonKey.set('');
        this.formError.set(null);
        this.bill.set(b);
      },
      error: (error: unknown) => this.findError.set(loadErrorMessage(error)),
    });
  }

  private patch(index: number, change: Partial<ReturnRow>): void {
    this.rows.update((list) => list.map((r, i) => (i === index ? { ...r, ...change } : r)));
  }

  protected setQty(index: number, value: string): void {
    this.patch(index, { qty: value, error: null });
  }

  protected setCondition(index: number, key: string): void {
    this.patch(index, { conditionKey: key });
  }

  protected setMode(value: string): void {
    this.modeIndex.set(Number(value));
  }

  protected reset(): void {
    this.result.set(null);
    this.bill.set(null);
    this.found.set([]);
    this.number.set('');
    this.pin.set('');
    this.signerId.set('');
    this.formError.set(null);
    this.saveKey = newClientKey();
  }

  protected submit(b: ReturnableBill): void {
    const storeId = this.context.workingStoreId();
    if (!storeId || this.saving()) {
      return;
    }
    this.formError.set(null);
    this.signerError.set(null);
    this.pinError.set(null);
    let bad = false;
    const checked = this.rows().map((r, i) => {
      const line = b.lines[i];
      const text = r.qty.trim();
      let error: string | null = null;
      if (text !== '') {
        if (!WHOLE.test(text) || Number(text) < 1) {
          error = 'Enter a whole number from 1';
        } else if (!line.returnable) {
          error = 'Not returnable';
        } else if (Number(text) > line.returnableQty) {
          error = `You can return at most ${line.returnableQty}.`;
        }
      }
      bad = bad || error !== null;
      return { ...r, error };
    });
    this.rows.set(checked);
    if (bad) {
      return;
    }
    const lines = checked
      .filter((r) => r.qty.trim() !== '')
      .map((r) => ({ billLineId: r.billLineId, qty: Number(r.qty.trim()), conditionKey: r.conditionKey }));
    if (lines.length === 0) {
      this.formError.set('Enter a quantity to return.');
      return;
    }
    if (this.reasonKey() === '') {
      this.formError.set('Choose a reason.');
      return;
    }
    const mode = b.refundModes[this.modeIndex()];
    if (!mode) {
      this.formError.set('Choose where to refund.');
      return;
    }
    const total = this.total();
    if (mode.maxPaise !== null && total > mode.maxPaise) {
      this.formError.set(`This mode can refund at most ${this.money(mode.maxPaise)}.`);
      return;
    }
    let secondSigner: { membershipId: string; pin: string } | null = null;
    if (this.anyScheduleX()) {
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
      .createSalesReturn(storeId, {
        clientKey: this.saveKey,
        billId: b.billId,
        lines,
        reasonKey: this.reasonKey(),
        note: null,
        refunds: [{ mode: mode.mode, amountPaise: total }],
        secondSigner,
      })
      .subscribe({
        next: (made) => {
          this.pin.set('');
          this.saving.set(false);
          this.result.set(made);
        },
        error: (error: unknown) => {
          this.pin.set('');
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
          if (problemSlug(error) === 'changed') {
            this.open(b.billId);
          }
        },
      });
  }
}
