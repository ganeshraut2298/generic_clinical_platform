import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { applyFieldErrors, loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { ApiError } from '../../../core/http/problem-detail';
import { ActiveReason, SecondSignerCandidate } from '../pharmacy-inventory.model';
import {
  BalanceAction,
  Dispatch,
  FefoPick,
  Indent,
  IndentDispatchLineInput,
  ReplacementOption,
} from '../pharmacy-indent.model';
import { PharmacyApi } from '../pharmacy.api';
import { openPrintView } from '../purchase/print.util';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { COUNTING_IN_PROGRESS, batchWithExpiry, dispatchQtyProblem, indentChips, indentHeaderLine, pinProblem } from './indent.util';
import { issueSlipHtml, indentPickListHtml } from './indent-print.util';
import { ReasonChipsComponent } from './reason-chips.component';

export interface DispatchData {
  storeId: string;
  indentId: string;
  /** The caller is a pharmacist mapped here (rule 19): Schedule X lines may be dispatched, with a second signature. */
  canSignScheduleX: boolean;
}

export const DISPATCH_CONFIG = {
  panelClass: 'sheet-panel',
  width: '1100px',
  maxWidth: '100vw',
  ariaLabelledBy: 'dispatch-title',
} as const;

interface Row {
  lineId: string;
  productId: string;
  name: string;
  detail: string;
  scheduleX: boolean;
  approved: number;
  remaining: number;
  csStock: number | null;
  /** The product that goes out: the requested one ("Same") or a replacement. */
  dispatchProductId: string;
  dispatchName: string;
  replacing: boolean;
  options: ReplacementOption[] | null;
  qty: string;
  balance: BalanceAction | null;
  reasonKey: string | null;
  note: string;
  pick: FefoPick | null;
  error: string | null;
}

/**
 * "Process & dispatch" (story 79-11, mockup 10 step 3): the line "Acting as ... · Central Store · can change product and
 * quantity" and the table Approved item, Approved (remaining), CS stock, Dispatch item ("Same" or a replacement),
 * Batch (FEFO), Dispatch qty and Balance ("Back-order" or "Close balance"). The batches come from the FEFO preview and
 * are never chosen by the person; the posting picks again under locks and shows what happened. A Schedule X line needs a
 * pharmacist and a second signature (a colleague and their PIN; the PIN field is cleared after every send). After the
 * dispatch the sheet shows the dispatch reference and "Print issue slip". "Print pick list" opens the browser print view.
 */
@Component({
  selector: 'app-indent-dispatch',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, IconComponent, ReasonChipsComponent],
  styles: [
    `
      .scroll {
        overflow-x: auto;
      }
      table {
        min-width: 980px;
      }
      .num {
        text-align: right;
        white-space: nowrap;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        margin-bottom: 8px;
      }
      input.cell {
        min-height: 44px;
        width: 100%;
        min-width: 80px;
        padding: 6px 8px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
        background: var(--glass-hi);
        font: inherit;
      }
      .row-err {
        color: var(--crit);
        font-size: 0.78rem;
      }
      .opt {
        min-height: 44px;
        padding: 0 12px;
        border: 1px solid var(--line);
        border-radius: 999px;
        background: var(--glass-hi);
        font: inherit;
        cursor: pointer;
      }
      .opt[aria-pressed='true'] {
        border-color: var(--pri-text);
        color: var(--pri-text);
        font-weight: 700;
      }
      .signer-err {
        flex-basis: 100%;
      }
      .signer {
        display: flex;
        flex-wrap: wrap;
        gap: 12px;
        align-items: flex-start;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="dispatch-title">Process & dispatch</h2>
        @if (indent(); as i) {
          <p data-testid="dispatch-header">{{ header(i) }}</p>
        }
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="dispatch-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      @if (done(); as d) {
        <div class="notice good" role="status" data-testid="dispatch-done">
          <app-icon name="check" [size]="16" />
          <span>
            Dispatched as {{ d.reference }}. The stock is in transit to {{ d.toStore.name }}.
          </span>
        </div>
        <div class="scroll">
          <table data-testid="dispatch-result">
            <thead>
              <tr>
                <th scope="col">Item</th>
                <th scope="col">Batch</th>
                <th scope="col" class="num">Qty</th>
              </tr>
            </thead>
            <tbody>
              @for (line of d.lines; track line.id) {
                <tr>
                  <td>
                    {{ line.product.name }}
                    @if (line.replacedByCs) {
                      <div class="sub">replaced by Central Store{{ line.changeReason ? ' · ' + line.changeReason.label : '' }}</div>
                    }
                    @if (line.backOrdered > 0) {
                      <div class="sub">{{ line.backOrdered }} back-ordered</div>
                    }
                  </td>
                  <td>{{ batch(line.batch.batchNo, line.batch.expiryDate) }}</td>
                  <td class="num">{{ line.dispatched }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      } @else if (indent(); as i) {
        <p data-testid="dispatch-acting-as">Acting as {{ caller() }} · Central Store · can change product and quantity</p>
        <div class="chips">
          @for (chip of chips(i); track chip.text) {
            <span class="pill" [class]="chip.css">{{ chip.text }}</span>
          }
        </div>
        <div class="scroll">
          <table data-testid="dispatch-lines">
            <thead>
              <tr>
                <th scope="col">Approved item</th>
                <th scope="col" class="num">Approved</th>
                <th scope="col" class="num">CS stock</th>
                <th scope="col">Dispatch item</th>
                <th scope="col">Batch (FEFO)</th>
                <th scope="col" class="num">Dispatch qty</th>
                <th scope="col">Balance</th>
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track row.lineId; let n = $index) {
                <tr [attr.data-testid]="'dispatch-line-' + n">
                  <td>
                    {{ row.name }}
                    @if (row.scheduleX) {
                      <span class="pill p-warn">Schedule X</span>
                    }
                    <div class="sub">{{ row.detail }}</div>
                  </td>
                  <td class="num">{{ row.remaining }}</td>
                  <td class="num" [attr.data-testid]="'dispatch-stock-' + n">{{ stockText(row.csStock) }}</td>
                  <td>
                    @if (row.replacing) {
                      <mat-form-field appearance="outline" subscriptSizing="dynamic">
                        <mat-label>Replacement</mat-label>
                        <mat-select
                          [value]="row.dispatchProductId"
                          [attr.data-testid]="'dispatch-replacement-' + n"
                          (selectionChange)="chooseReplacement(n, $event.value)"
                        >
                          <mat-option [value]="row.productId">Same · {{ row.name }}</mat-option>
                          @for (o of row.options ?? []; track o.product.productId) {
                            <mat-option [value]="o.product.productId">
                              {{ o.product.name }} · {{ o.product.strength }} · CS {{ stockText(o.csAvailable) }}
                            </mat-option>
                          } @empty {
                            <mat-option disabled>No replacement is available</mat-option>
                          }
                        </mat-select>
                      </mat-form-field>
                    } @else {
                      <span data-testid="dispatch-same">Same</span>
                      <button
                        type="button"
                        class="opt"
                        [attr.data-testid]="'dispatch-change-' + n"
                        [attr.aria-label]="'Change the product of ' + row.name"
                        (click)="startReplace(n)"
                      >
                        Change
                      </button>
                    }
                  </td>
                  <td [attr.data-testid]="'dispatch-batch-' + n">{{ pickText(row) }}</td>
                  <td class="num">
                    <input
                      class="cell"
                      inputmode="numeric"
                      autocomplete="off"
                      [value]="row.qty"
                      [attr.aria-label]="'Dispatch quantity of ' + row.name"
                      [attr.data-testid]="'dispatch-qty-' + n"
                      (input)="setQty(n, $any($event.target).value)"
                      (change)="refreshPick(n)"
                    />
                    @if (row.error; as text) {
                      <div class="row-err" role="alert">{{ text }}</div>
                    }
                  </td>
                  <td>
                    @if (balanceOf(row) > 0) {
                      <div class="sub" [attr.data-testid]="'dispatch-balance-' + n">Balance {{ balanceOf(row) }}</div>
                      <div class="chips" role="group" [attr.aria-label]="'Balance of ' + row.name">
                        <button
                          type="button"
                          class="opt"
                          [attr.aria-pressed]="row.balance === 'BACK_ORDER'"
                          [attr.data-testid]="'dispatch-backorder-' + n"
                          (click)="setBalance(n, 'BACK_ORDER')"
                        >
                          Back-order
                        </button>
                        <button
                          type="button"
                          class="opt"
                          [attr.aria-pressed]="row.balance === 'CLOSE'"
                          [attr.data-testid]="'dispatch-close-' + n"
                          (click)="setBalance(n, 'CLOSE')"
                        >
                          Close balance
                        </button>
                      </div>
                    } @else {
                      <span>—</span>
                    }
                    @if (needsReason(row)) {
                      <div class="sub">Reason for the change</div>
                      <app-reason-chips
                        [reasons]="reasons()"
                        [value]="row.reasonKey"
                        label="Reason for the change"
                        [testIdPrefix]="'dispatch-reason-' + n + '-'"
                        (chosen)="setReason(n, $event)"
                      />
                      @if (reasonNeedsNote(row)) {
                        <textarea
                          maxlength="300"
                          aria-label="Note for the change"
                          [attr.data-testid]="'dispatch-note-' + n"
                          [value]="row.note"
                          (input)="setNote(n, $any($event.target).value)"
                        ></textarea>
                      }
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="sub">
          Every change of product or quantity needs a reason and is shown to the receiving store. A back-order keeps the
          remainder open on this indent. The batches are picked oldest expiry first; you cannot choose another batch.
        </p>

        @if (anyScheduleX()) {
          @if (!data.canSignScheduleX) {
            <div class="notice warn" role="status" data-testid="dispatch-x-blocked">
              <app-icon name="lock" [size]="16" />
              <span>Schedule X lines are dispatched by a pharmacist with a second signature. Set their quantity to 0 or ask a pharmacist.</span>
            </div>
          } @else {
            <div class="notice teal" data-testid="dispatch-x-note">
              <app-icon name="lock" [size]="16" />
              <span>Schedule X needs a pharmacist and a second signature by a different person.</span>
            </div>
            <div class="signer">
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label>Second signer</mat-label>
                <mat-select data-testid="f-signer" [value]="signerId()" (selectionChange)="signerId.set($event.value)">
                  @for (person of signers(); track person.membershipId) {
                    <mat-option [value]="person.membershipId">{{ person.name }} · {{ person.roleLabel }}</mat-option>
                  }
                </mat-select>
              </mat-form-field>
                @if (signerError(); as text) {
                  <span class="row-err signer-err" role="alert">{{ text }}</span>
                }
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label>Their PIN</mat-label>
                <input
                  matInput
                  type="password"
                  inputmode="numeric"
                  autocomplete="off"
                  maxlength="4"
                  data-testid="f-pin"
                  [value]="pin()"
                  (input)="pin.set($any($event.target).value)"
                />
              </mat-form-field>
                @if (pinError(); as text) {
                  <span class="row-err signer-err" role="alert">{{ text }}</span>
                }
            </div>
            @if (signersLoaded() && signers().length === 0) {
              <p class="notice" role="status" data-testid="no-second-signer">No other pharmacist or owner is available to sign.</p>
            }
          }
        }
      }

      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="dispatch-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </div>

    <div class="sheet-f">
      @if (done(); as d) {
        <button mat-stroked-button type="button" data-testid="print-issue-slip" (click)="printSlip(d)">Print issue slip</button>
        <button mat-flat-button type="button" data-testid="dispatch-finish" (click)="finish()">Close</button>
      } @else if (indent(); as i) {
        <button mat-stroked-button type="button" data-testid="print-pick-list" (click)="printPickList(i)">Print pick list</button>
        <button mat-stroked-button type="button" (click)="close()">Cancel</button>
        <button
          mat-flat-button
          type="button"
          data-testid="dispatch-submit"
          [disabled]="saving() || nothingToDispatch()"
          (click)="send(i)"
        >
          Dispatch to {{ i.fromStore.name }}
        </button>
      }
    </div>
  `,
})
export class IndentDispatchDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<IndentDispatchDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<DispatchData>(MAT_DIALOG_DATA);

  protected readonly indent = signal<Indent | null>(null);
  protected readonly rows = signal<Row[]>([]);
  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly signerId = signal('');
  protected readonly pin = signal('');
  protected readonly signerError = signal<string | null>(null);
  protected readonly pinError = signal<string | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly done = signal<Dispatch | null>(null);
  protected readonly caller = signal('');

  private readonly clientKey = newClientKey();
  private pickSeq = 0;

  protected readonly header = indentHeaderLine;
  protected readonly batch = batchWithExpiry;
  protected readonly anyScheduleX = computed(() => this.rows().some((r) => r.scheduleX && Number(r.qty) > 0));
  protected readonly nothingToDispatch = computed(() => this.rows().every((r) => !/^[1-9][0-9]*$/.test(r.qty.trim())));

  ngOnInit(): void {
    this.api.indent(this.data.storeId, this.data.indentId).subscribe({
      next: (indent) => this.opened(indent),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
    this.api.activeReasons(['cs-change']).subscribe({
      next: (answer) => this.reasons.set(answer.lists.find((l) => l.listKey === 'cs-change')?.reasons ?? []),
      error: () => this.reasons.set([]),
    });
    if (this.data.canSignScheduleX) {
      this.api.secondSigners(this.data.storeId).subscribe({
        next: (people) => {
          this.signers.set(people);
          this.signersLoaded.set(true);
        },
        error: () => {
          this.signers.set([]);
          this.signersLoaded.set(true);
        },
      });
    }
  }

  private opened(indent: Indent): void {
    this.indent.set(indent);
    this.caller.set(indent.actingAs.replace(/^Acting as /, '').split(' · ')[0] ?? '');
    const rows: Row[] = indent.lines
      .filter((l) => l.remainingQty > 0)
      .map((l) => {
        const available = l.csStock;
        const start = available === null ? l.remainingQty : Math.min(l.remainingQty, available);
        return {
          lineId: l.id,
          productId: l.product.productId,
          name: l.product.name,
          detail: `${l.product.strength} · ${l.product.form}`,
          scheduleX: l.product.schedule === 'X',
          approved: l.approvedQty ?? l.requestedQty,
          remaining: l.remainingQty,
          csStock: available,
          dispatchProductId: l.product.productId,
          dispatchName: l.product.name,
          replacing: false,
          options: null,
          qty: String(start),
          balance: start < l.remainingQty ? 'BACK_ORDER' : null,
          reasonKey: null,
          note: '',
          pick: null,
          error: null,
        };
      });
    this.rows.set(rows);
    rows.forEach((_, i) => this.refreshPick(i));
  }

  protected chips(indent: Indent) {
    return indentChips(indent);
  }

  protected stockText(value: number | null): string {
    return value === null ? COUNTING_IN_PROGRESS : String(value);
  }

  protected balanceOf(row: Row): number {
    const text = row.qty.trim();
    const qty = /^[0-9]+$/.test(text) ? Number(text) : 0;
    return Math.max(row.remaining - qty, 0);
  }

  protected pickText(row: Row): string {
    if (row.pick === null) {
      return Number(row.qty) > 0 ? '—' : 'Nothing to pick';
    }
    if (row.pick.batches.length === 0) {
      return 'No usable batch';
    }
    return row.pick.batches
      .map((b) => `${batchWithExpiry(b.batch.batchNo, b.batch.expiryDate)}${b.quantity === null ? '' : ' × ' + b.quantity}`)
      .join(', ');
  }

  protected needsReason(row: Row): boolean {
    return row.dispatchProductId !== row.productId || (this.balanceOf(row) > 0 && row.balance !== null);
  }

  protected reasonNeedsNote(row: Row): boolean {
    return this.reasons().find((r) => r.reasonKey === row.reasonKey)?.needsNote === true;
  }

  private patch(index: number, change: Partial<Row>): void {
    this.rows.update((list) => list.map((r, i) => (i === index ? { ...r, ...change } : r)));
  }

  protected setQty(index: number, value: string): void {
    this.patch(index, { qty: value, error: null });
  }

  protected setBalance(index: number, action: BalanceAction): void {
    this.patch(index, { balance: action });
  }

  protected setReason(index: number, reason: ActiveReason): void {
    this.patch(index, { reasonKey: reason.reasonKey, error: null });
  }

  protected setNote(index: number, value: string): void {
    this.patch(index, { note: value });
  }

  protected startReplace(index: number): void {
    const row = this.rows()[index];
    this.patch(index, { replacing: true });
    this.api.indentReplacementOptions(this.data.storeId, this.data.indentId, row.lineId).subscribe({
      next: (options) => this.patch(index, { options }),
      error: (error: unknown) => {
        this.patch(index, { options: [] });
        this.formError.set(loadErrorMessage(error));
      },
    });
  }

  protected chooseReplacement(index: number, productId: string): void {
    const row = this.rows()[index];
    const option = row.options?.find((o) => o.product.productId === productId);
    const name = productId === row.productId ? row.name : (option?.product.name ?? row.name);
    const stock = productId === row.productId ? row.csStock : (option?.csAvailable ?? null);
    this.patch(index, {
      dispatchProductId: productId,
      dispatchName: name,
      csStock: stock,
      qty: String(stock === null ? row.remaining : Math.min(row.remaining, stock)),
      pick: null,
    });
    this.refreshPick(index);
  }

  /** Reads the FEFO preview for the product and quantity of one row; a newer read replaces an older one. */
  protected refreshPick(index: number): void {
    const row = this.rows()[index];
    const text = row.qty.trim();
    if (!/^[1-9][0-9]*$/.test(text)) {
      this.patch(index, { pick: null });
      return;
    }
    const seq = ++this.pickSeq;
    this.api.fefoPick(this.data.storeId, row.dispatchProductId, Number(text)).subscribe({
      next: (pick) => {
        if (seq === this.pickSeq || this.rows()[index]?.dispatchProductId === pick.productId) {
          this.patch(index, { pick });
        }
      },
      error: () => this.patch(index, { pick: null }),
    });
  }

  private check(): Row[] | null {
    let bad = false;
    const checked = this.rows().map((r) => {
      let error = dispatchQtyProblem(r.qty, r.remaining, r.csStock);
      const qty = /^[0-9]+$/.test(r.qty.trim()) ? Number(r.qty.trim()) : 0;
      if (error === null && qty < r.remaining && r.balance === null) {
        error = 'Choose Back-order or Close balance';
      }
      if (error === null && this.needsReason(r) && !r.reasonKey) {
        error = 'Choose a reason for the change';
      }
      if (error === null && this.needsReason(r) && this.reasonNeedsNote(r) && r.note.trim().length < 4) {
        error = 'Write a note of at least 4 characters';
      }
      if (error === null && r.scheduleX && qty > 0 && !this.data.canSignScheduleX) {
        error = 'Only a pharmacist can dispatch Schedule X';
      }
      bad = bad || error !== null;
      return { ...r, error };
    });
    this.rows.set(checked);
    return bad ? null : checked;
  }

  protected send(indent: Indent): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    this.signerError.set(null);
    this.pinError.set(null);
    const checked = this.check();
    if (checked === null) {
      return;
    }
    const lines: IndentDispatchLineInput[] = checked.map((r) => {
      const qty = Number(r.qty.trim());
      const reasoned = this.needsReason(r);
      const note = r.note.trim();
      return {
        indentLineId: r.lineId,
        dispatchProductId: r.dispatchProductId,
        quantity: qty,
        balanceAction: qty < r.remaining ? r.balance : null,
        changeReasonKey: reasoned ? r.reasonKey : null,
        changeNote: reasoned && note !== '' ? note : null,
      };
    });
    let secondSigner: { membershipId: string; pin: string } | null = null;
    if (this.anyScheduleX() && this.data.canSignScheduleX) {
      const pinText = this.pin();
      if (!this.signerId()) {
        this.signerError.set('Choose the second signer');
      }
      const pinProblemText = pinProblem(pinText);
      if (pinProblemText) {
        this.pinError.set(pinProblemText);
      }
      if (!this.signerId() || pinProblemText) {
        return;
      }
      secondSigner = { membershipId: this.signerId(), pin: pinText };
    }
    this.saving.set(true);
    this.api
      .dispatchIndent(this.data.storeId, indent.id, {
        clientKey: this.clientKey,
        indentVersion: indent.version,
        lines,
        secondSigner,
      })
      .subscribe({
        next: (made) => {
          this.pin.set('');
          this.saving.set(false);
          this.done.set(made);
        },
        error: (error: unknown) => {
          this.pin.set('');
          this.saving.set(false);
          this.fail(error);
        },
      });
  }

  private fail(error: unknown): void {
    if (error instanceof ApiError && error.fieldErrors.length > 0) {
      const rest = applyFieldErrors({}, error.fieldErrors);
      this.formError.set(rest.length > 0 ? rest.join(' ') : saveErrorMessage(error));
      return;
    }
    this.formError.set(saveErrorMessage(error));
  }

  protected printSlip(dispatch: Dispatch): void {
    openPrintView(issueSlipHtml(dispatch));
  }

  protected printPickList(indent: Indent): void {
    const rows = this.rows().map((r) => ({
      product: r.dispatchName,
      batch: this.pickText(r),
      qty: /^[0-9]+$/.test(r.qty.trim()) ? Number(r.qty.trim()) : null,
    }));
    openPrintView(indentPickListHtml(indent.number ?? '', indent.toStore.name, indent.fromStore.name, rows));
  }

  protected finish(): void {
    this.ref.close(true);
  }

  protected close(): void {
    this.ref.close(this.done() !== null);
  }
}
