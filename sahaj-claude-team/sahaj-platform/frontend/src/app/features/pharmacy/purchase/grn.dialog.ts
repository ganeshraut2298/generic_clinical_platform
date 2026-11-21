import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiError } from '../../../core/http/problem-detail';
import { newClientKey } from '../../../shared/client-key';
import { formatDisplayDate, formatDisplayDateTimeOfInstant, istToday } from '../../../shared/format/ist-date';
import { provideIsoDateAdapter } from '../../../shared/format/iso-date-adapter';
import { IconComponent } from '../../../shared/icon/icon.component';
import { actingAsText, historyEventText } from '../inventory/inventory.util';
import { PickedProduct, ProductPickerComponent } from '../inventory/product-picker.component';
import { rateText, rupees } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import {
  GoodsReceipt,
  GoodsReceiptLine,
  GoodsReceiptLineInput,
  ShortAction,
} from '../pharmacy-buying.model';
import { SecondSignerCandidate } from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import { Vendor } from '../pharmacy.model';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import {
  expiryMonthOk,
  grnStatusChip,
  lineCheckChips,
  monthInputValue,
  monthYearText,
  paiseFromRupees,
  percentRange,
  positiveRate,
  trimRate,
  wholeQty,
} from './purchase.util';

export interface GrnDialogData {
  storeId: string;
  /** Start a receipt against this sent or partly received purchase order (the lines come from its remaining quantities). */
  po?: { id: string; number: string | null; vendorName: string };
  /** Continue this receipt. */
  grnId?: string;
}

/** What the list does after the sheet closes. */
export interface GrnDialogResult {
  changed: boolean;
  next: 'approvals' | null;
}

export const GRN_DIALOG_CONFIG = {
  panelClass: 'sheet-panel',
  width: '1100px',
  maxWidth: '100vw',
  ariaLabelledBy: 'grn-title',
} as const;

interface LineMeta {
  productId: string;
  name: string;
  detail: string;
  purchaseOrderLineId: string | null;
  scheduleX: boolean;
  gstRate: string | null;
}

function lineGroup(over: Partial<Record<'batchNo' | 'expiryMonth' | 'qty' | 'billedQty' | 'freeQty' | 'ptr' | 'mrp' | 'discountPercent', string>> = {}) {
  return new FormGroup({
    batchNo: new FormControl(over.batchNo ?? '', { nonNullable: true }),
    expiryMonth: new FormControl(over.expiryMonth ?? '', { nonNullable: true }),
    qty: new FormControl(over.qty ?? '', { nonNullable: true }),
    billedQty: new FormControl(over.billedQty ?? '', { nonNullable: true }),
    freeQty: new FormControl(over.freeQty ?? '0', { nonNullable: true }),
    ptr: new FormControl(over.ptr ?? '', { nonNullable: true }),
    mrp: new FormControl(over.mrp ?? '', { nonNullable: true }),
    discountPercent: new FormControl(over.discountPercent ?? '0', { nonNullable: true }),
    claimRateDifference: new FormControl(false, { nonNullable: true }),
    shortAction: new FormControl<ShortAction | ''>('', { nonNullable: true }),
  });
}

type LineGroup = ReturnType<typeof lineGroup>;

interface GrnErrors {
  vendorId?: string;
  invoiceNo?: string;
  invoiceDate?: string;
  creditDays?: string;
  invoiceTotal?: string;
  signerId?: string;
  pin?: string;
}

/**
 * Receive stock, "Purchase invoice → GRN" (story 79-7, mockup 08): a receipt against a sent or partly received
 * purchase order, or direct. The step bar reads "1 · PO", "2 · Enter invoice", "3 · Verify", "4 · Post to stock",
 * "5 · Payable"; the OCR and e-invoice sources of the mockup are not built, so the invoice is typed. The header asks
 * Vendor, Vendor invoice number, Invoice date, Credit days and Invoice total (as printed). Each line has Batch,
 * Expiry (month and year), Qty received, Billed qty, Free, PTR, MRP, Disc %, GST (read-only) and the server's Check,
 * Landed and Margin. "Save draft" keeps a receipt with no number and no stock effect; "Post GRN & update stock"
 * (or "Send for approval (rate ↑)") posts it; a Schedule X line needs a pharmacist and a second signer.
 */
@Component({
  selector: 'app-grn',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDatepickerModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
    ProductPickerComponent,
  ],
  providers: [provideIsoDateAdapter()],
  styles: [
    `
      .steps {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        list-style: none;
        margin: 0;
        padding: 0;
        font-size: 0.82rem;
      }
      .steps li {
        padding: 4px 10px;
        border: 1px solid var(--line);
        border-radius: 999px;
      }
      .steps li[aria-current='step'] {
        border: 2px solid var(--pri);
        background: var(--pri-soft);
        color: var(--pri-text);
        font-weight: 700;
      }
      .headgrid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
        gap: 8px;
      }
      .scroll {
        overflow-x: auto;
      }
      table {
        min-width: 1280px;
      }
      td,
      th {
        vertical-align: top;
      }
      input.cell,
      select.cell {
        min-height: 44px;
        width: 100%;
        min-width: 70px;
        padding: 6px 8px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
        background: var(--glass-hi);
        font: inherit;
      }
      .num {
        text-align: right;
        white-space: nowrap;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      .err-text {
        color: var(--crit);
      }
      .row-err {
        color: var(--crit);
        font-size: 0.78rem;
      }
      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
      }
      ol.hist {
        margin: 4px 0;
        padding-left: 18px;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="grn-title" data-testid="grn-title">
          Purchase invoice → GRN
          @if (grn(); as g) {
            @if (g.number) {
              · {{ g.number }}
            }
            <span class="pill" [class]="statusChip().css" data-testid="grn-status">{{ statusChip().text }}</span>
          }
        </h2>
        <p>Central Store</p>
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <form class="sheet-b" [formGroup]="form" novalidate aria-labelledby="grn-title" (ngSubmit)="saveDraft()">
      <ol class="steps" aria-label="Receiving steps" data-testid="grn-steps">
        @for (s of stepList; track s.key) {
          <li [attr.aria-current]="step() === s.key ? 'step' : null">{{ s.label }}</li>
        }
      </ol>

      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="grn-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }

      @if (grn()?.warnings?.includes('VENDOR_LICENCE_EXPIRED')) {
        <div class="notice warn" role="status" data-testid="grn-licence-warning">
          <app-icon name="alert" [size]="16" />
          <span>Drug licence expired</span>
        </div>
      }

      <div class="headgrid">
        @if (data.po || grn()?.purchaseOrder) {
          <p data-testid="grn-matched">
            Matched to <b>{{ grn()?.purchaseOrder?.number ?? data.po?.number ?? 'the purchase order' }}</b>
            · {{ grn()?.vendor?.name ?? data.po?.vendorName }}
          </p>
        } @else if (!grn()) {
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Vendor</mat-label>
            <mat-select formControlName="vendorId" data-testid="f-grn-vendor" (selectionChange)="vendorChosen($event.value)">
              @for (vendor of vendors(); track vendor.id) {
                <mat-option [value]="vendor.id">{{ vendor.name }}</mat-option>
              }
            </mat-select>
            @if (errors().vendorId; as text) {
              <mat-hint align="end" class="err-text" role="alert">{{ text }}</mat-hint>
            }
          </mat-form-field>
        } @else {
          <p data-testid="grn-vendor">
            Vendor <b>{{ grn()?.vendor?.name }}</b>
          </p>
        }
        @if (grn()?.vendor?.gstin ?? selectedGstin(); as gstin) {
          <p class="sub" data-testid="grn-gstin">GSTIN {{ gstin }}</p>
        }
      </div>

      <div class="headgrid">
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Vendor invoice number</mat-label>
          <input matInput formControlName="invoiceNo" maxlength="30" autocomplete="off" data-testid="f-grn-invoice-no" />
          @if (errors().invoiceNo; as text) {
            <mat-hint align="end" class="err-text" role="alert">{{ text }}</mat-hint>
          }
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Invoice date</mat-label>
          <input
            matInput
            [matDatepicker]="invoicePicker"
            formControlName="invoiceDate"
            placeholder="24 Oct 2026"
            autocomplete="off"
            data-testid="f-grn-invoice-date"
          />
          <mat-datepicker-toggle matIconSuffix [for]="invoicePicker" aria-label="Choose the invoice date" />
          <mat-datepicker #invoicePicker />
          @if (errors().invoiceDate; as text) {
            <mat-hint align="end" class="err-text" role="alert">{{ text }}</mat-hint>
          }
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Credit days</mat-label>
          <input matInput formControlName="creditDays" inputmode="numeric" autocomplete="off" data-testid="f-grn-credit" />
          @if (errors().creditDays; as text) {
            <mat-hint align="end" class="err-text" role="alert">{{ text }}</mat-hint>
          }
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Invoice total</mat-label>
          <input
            matInput
            formControlName="invoiceTotal"
            inputmode="decimal"
            autocomplete="off"
            placeholder="₹ 0.00"
            data-testid="f-grn-total"
          />
          <mat-hint>As printed on the paper invoice</mat-hint>
          @if (errors().invoiceTotal; as text) {
            <mat-hint align="end" class="err-text" role="alert">{{ text }}</mat-hint>
          }
        </mat-form-field>
      </div>

      @if (!isPoReceipt() && editable()) {
        <app-product-picker label="Add a product" (picked)="addProduct($event)" />
      }

      @if (lines.length > 0) {
        <div class="scroll">
          <table data-testid="grn-lines">
            <thead>
              <tr>
                <th scope="col">Item</th>
                <th scope="col">Batch</th>
                <th scope="col">Expiry</th>
                <th scope="col" class="num">Qty</th>
                <th scope="col" class="num">Billed qty</th>
                <th scope="col" class="num">Free</th>
                <th scope="col" class="num">PTR</th>
                <th scope="col" class="num">MRP</th>
                <th scope="col" class="num">Disc %</th>
                <th scope="col" class="num">GST %</th>
                <th scope="col" class="num">Landed</th>
                <th scope="col" class="num">Margin</th>
                <th scope="col">Check</th>
                @if (editable()) {
                  <th scope="col"><span class="sr-only">Remove</span></th>
                }
              </tr>
            </thead>
            <tbody formArrayName="lines">
              @for (group of lines.controls; track group; let i = $index) {
                <tr [formGroupName]="i" [attr.data-testid]="'grn-line-' + i">
                  <td>
                    <b>{{ meta()[i]?.name }}</b>
                    <div class="sub">{{ meta()[i]?.detail }}</div>
                    @if (meta()[i]?.scheduleX) {
                      <div class="sub" data-testid="grn-schedule-x">Pharmacist and second signature needed</div>
                    }
                  </td>
                  <td>
                    <input class="cell" formControlName="batchNo" maxlength="20" autocomplete="off" [attr.aria-label]="'Batch, line ' + (i + 1)" [attr.data-testid]="'grn-batch-' + i" />
                  </td>
                  <td>
                    <input class="cell" type="month" formControlName="expiryMonth" [attr.aria-label]="'Expiry month and year, line ' + (i + 1)" [attr.data-testid]="'grn-expiry-' + i" />
                    @if (group.controls.expiryMonth.value) {
                      <div class="sub">{{ monthText(group.controls.expiryMonth.value) }}</div>
                    }
                  </td>
                  <td>
                    <input class="cell" formControlName="qty" inputmode="numeric" autocomplete="off" [attr.aria-label]="'Quantity received, line ' + (i + 1)" [attr.data-testid]="'grn-qty-' + i" (input)="qtyTyped(i)" />
                  </td>
                  <td>
                    <input class="cell" formControlName="billedQty" inputmode="numeric" autocomplete="off" [attr.aria-label]="'Billed quantity, line ' + (i + 1)" [attr.data-testid]="'grn-billed-' + i" />
                  </td>
                  <td>
                    <input class="cell" formControlName="freeQty" inputmode="numeric" autocomplete="off" [attr.aria-label]="'Free quantity, line ' + (i + 1)" [attr.data-testid]="'grn-free-' + i" />
                  </td>
                  <td>
                    <input class="cell" formControlName="ptr" inputmode="decimal" autocomplete="off" [attr.aria-label]="'PTR per purchase unit, line ' + (i + 1)" [attr.data-testid]="'grn-ptr-' + i" />
                  </td>
                  <td>
                    <input class="cell" formControlName="mrp" inputmode="decimal" autocomplete="off" [attr.aria-label]="'MRP per issue unit, line ' + (i + 1)" [attr.data-testid]="'grn-mrp-' + i" />
                  </td>
                  <td>
                    <input class="cell" formControlName="discountPercent" inputmode="decimal" autocomplete="off" [attr.aria-label]="'Discount percent, line ' + (i + 1)" [attr.data-testid]="'grn-disc-' + i" />
                  </td>
                  <td class="num" [attr.data-testid]="'grn-gst-' + i">{{ serverLine(i)?.gstRate ?? meta()[i]?.gstRate ?? '—' }}</td>
                  <td class="num" [attr.data-testid]="'grn-landed-' + i">{{ serverLine(i) ? rate(serverLine(i)!.landed) : '—' }}</td>
                  <td class="num" [attr.data-testid]="'grn-margin-' + i">{{ serverLine(i)?.marginPercent ? serverLine(i)!.marginPercent + '%' : '—' }}</td>
                  <td>
                    <div class="chips" [attr.data-testid]="'grn-check-' + i">
                      @for (chip of checks(i); track chip.text) {
                        <span class="pill" [class]="chip.css">{{ chip.text }}</span>
                      } @empty {
                        <span class="sub">Checked when saved</span>
                      }
                    </div>
                    @if (isPoReceipt() && hasShort(i) && editable()) {
                      <select class="cell" formControlName="shortAction" [attr.aria-label]="'Short supply, line ' + (i + 1)" [attr.data-testid]="'grn-short-' + i">
                        <option value="">Choose what happens to the balance</option>
                        <option value="BALANCE_TO_COME">Balance to come</option>
                        <option value="CLOSE_SHORT">Close short</option>
                      </select>
                    }
                    @if (isPoReceipt() && hasRateUp(i) && editable()) {
                      <label class="sub">
                        <input type="checkbox" formControlName="claimRateDifference" [attr.data-testid]="'grn-claim-' + i" />
                        Claim rate difference
                      </label>
                    }
                    @if (lineErrors()[i]; as text) {
                      <div class="row-err" role="alert">{{ text }}</div>
                    }
                  </td>
                  @if (editable()) {
                    <td>
                      @if (!isPoReceipt()) {
                        <button mat-icon-button type="button" [attr.aria-label]="'Remove ' + meta()[i]?.name" [attr.data-testid]="'grn-remove-' + i" (click)="removeLine(i)">
                          <app-icon name="x" />
                        </button>
                      }
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="sub">
          Landed cost spreads free quantity and discount across units, so margin is true margin. Landed, margin and the
          checks are worked by the server when you save.
        </p>
      } @else if (!grn() && !isPoReceipt()) {
        <p class="sub" data-testid="grn-no-lines">No items yet. Search for a product to add it.</p>
      }

      @if (grn(); as g) {
        <p data-testid="grn-totals">
          Computed total {{ money(g.computedTotalPaise) }} · Invoice total {{ money(g.invoiceTotalPaise) }} ·
          <b [class.err-text]="differenceTooBig()" data-testid="grn-difference">Difference {{ money(g.totalDifferencePaise) }}</b>
        </p>
        @if (g.threeWayMatch.length > 0) {
          <div class="panel" data-testid="grn-three-way">
            <h3>3-way match</h3>
            <div class="scroll">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Item</th>
                    <th scope="col" class="num">PO qty</th>
                    <th scope="col" class="num">Billed qty</th>
                    <th scope="col" class="num">Received qty</th>
                    <th scope="col" class="num">PO rate</th>
                    <th scope="col" class="num">Billed rate</th>
                  </tr>
                </thead>
                <tbody>
                  @for (m of g.threeWayMatch; track m.lineId) {
                    <tr>
                      <td>{{ m.product }}</td>
                      <td class="num">{{ m.poQty ?? '—' }}</td>
                      <td class="num">{{ m.billedQty }}</td>
                      <td class="num">{{ m.receivedQty }}</td>
                      <td class="num">{{ rate(m.poRate) }}</td>
                      <td class="num">{{ rate(m.billedRate) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
        @if (g.approval; as a) {
          <div class="notice teal" role="status" data-testid="grn-approval">
            <app-icon name="lock" [size]="16" />
            <span>
              {{ a.ruleText }}
              @if (a.status === 'PENDING') {
                · Waiting for {{ a.waitingFor === 'OWNER' ? 'owner' : 'HOD' }} approval. Nothing is posted until it is approved.
              } @else if (a.status === 'REJECTED' && a.rejectReason) {
                · Rejected: {{ a.rejectReason.label }}. Correct the rate and save again.
              }
            </span>
            @if (a.status === 'PENDING') {
              <button mat-stroked-button type="button" data-testid="grn-open-approvals" (click)="finish('approvals')">
                Open in approvals
              </button>
            }
          </div>
        }
        @if (g.payable; as p) {
          <div class="notice good" role="status" data-testid="grn-payable">
            <app-icon name="check" [size]="16" />
            <span>Payable · due {{ date(p.dueDate) }} · {{ money(p.amountPaise) }}</span>
          </div>
        }
        @if (g.debitNoteDraft; as d) {
          <div class="notice teal" role="status" data-testid="grn-debit-note">
            <app-icon name="lock" [size]="16" />
            <span>Debit note prepared: {{ d.lineCount }} {{ d.lineCount === 1 ? 'line' : 'lines' }}, {{ money(d.amountPaise) }}</span>
          </div>
        }
        <h3>History</h3>
        <ol class="hist" data-testid="grn-history">
          @for (h of g.history; track h.at + h.event) {
            <li>
              {{ eventText(h.event) }} · {{ h.by?.name ?? 'System' }}{{ h.actingAs ? ' (' + acting(h.actingAs) + ')' : '' }} ·
              {{ when(h.at) }}{{ h.reason ? ' · ' + h.reason.label : '' }}
            </li>
          }
        </ol>
      }

      @if (needsSigner() && canPost()) {
        <div class="headgrid" data-testid="grn-signer-block">
          <p class="sub">Schedule X needs a pharmacist and a second signature by a different person.</p>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Second signer</mat-label>
            <mat-select formControlName="signerId" placeholder="Choose a person" data-testid="f-grn-signer">
              @for (person of signers(); track person.membershipId) {
                <mat-option [value]="person.membershipId">{{ person.name }} · {{ person.roleLabel }}</mat-option>
              }
            </mat-select>
            @if (errors().signerId; as text) {
              <mat-hint align="end" class="err-text" role="alert">{{ text }}</mat-hint>
            }
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Their PIN</mat-label>
            <input matInput type="password" inputmode="numeric" autocomplete="off" maxlength="12" formControlName="pin" data-testid="f-grn-pin" />
            @if (errors().pin; as text) {
              <mat-hint align="end" class="err-text" role="alert">{{ text }}</mat-hint>
            }
          </mat-form-field>
          @if (signersLoaded() && signers().length === 0) {
            <p class="notice" role="status" data-testid="grn-no-second-signer">
              No other pharmacist or owner is available to sign.
            </p>
          }
        </div>
      } @else if (needsSigner() && !canPost()) {
        <div class="notice warn" role="status" data-testid="grn-pharmacist-needed">
          <app-icon name="lock" [size]="16" />
          <span>Schedule X lines need a pharmacist and a second signature. Ask a pharmacist to post this receipt.</span>
        </div>
      }

      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="grn-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </form>

    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close()">{{ grn()?.status === 'POSTED' ? 'Close' : 'Cancel' }}</button>
      @if (grn(); as g) {
        @if (g.status === 'DRAFT' || g.status === 'APPROVED') {
          <button mat-stroked-button type="button" data-testid="grn-discard" [disabled]="busy()" (click)="discard()">Discard</button>
        }
        @if (g.status === 'APPROVED') {
          <button mat-stroked-button type="button" data-testid="grn-reopen" [disabled]="busy()" (click)="reopen()">Change it</button>
        }
      }
      @if (editable()) {
        <button mat-stroked-button type="button" data-testid="grn-save-draft" [disabled]="busy()" (click)="saveDraft()">Save draft</button>
      }
      @if (grn(); as g) {
        @if ((g.status === 'DRAFT' || g.status === 'APPROVED') && canPost()) {
          <button mat-flat-button type="button" data-testid="grn-post" [disabled]="busy() || !g.lines.length" (click)="post()">
            {{ postLabel() }}
          </button>
        }
      }
    </div>
  `,
})
export class GrnDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);
  private readonly ref = inject<MatDialogRef<GrnDialog, GrnDialogResult>>(MatDialogRef);
  protected readonly data = inject<GrnDialogData>(MAT_DIALOG_DATA);

  protected readonly stepList = [
    { key: 'PO', label: '1 · PO' },
    { key: 'ENTER_INVOICE', label: '2 · Enter invoice' },
    { key: 'VERIFY', label: '3 · Verify' },
    { key: 'POST', label: '4 · Post to stock' },
    { key: 'PAYABLE', label: '5 · Payable' },
  ] as const;

  protected readonly grn = signal<GoodsReceipt | null>(null);
  protected readonly meta = signal<LineMeta[]>([]);
  protected readonly vendors = signal<Vendor[]>([]);
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly errors = signal<GrnErrors>({});
  protected readonly lineErrors = signal<Record<number, string>>({});

  private readonly createKey = newClientKey();
  private postKey = newClientKey();
  private changed = false;
  private poLineIds = new Map<string, string>();

  protected readonly form = new FormGroup({
    vendorId: new FormControl('', { nonNullable: true }),
    invoiceNo: new FormControl('', { nonNullable: true }),
    invoiceDate: new FormControl<string | null>(null),
    creditDays: new FormControl('30', { nonNullable: true }),
    invoiceTotal: new FormControl('', { nonNullable: true }),
    lines: new FormArray<LineGroup>([]),
    signerId: new FormControl('', { nonNullable: true }),
    pin: new FormControl('', { nonNullable: true }),
  });

  protected get lines(): FormArray<LineGroup> {
    return this.form.controls.lines;
  }

  protected readonly money = rupees;
  protected readonly rate = rateText;
  protected readonly date = formatDisplayDate;
  protected readonly monthText = monthYearText;
  protected readonly when = (instant: string) => formatDisplayDateTimeOfInstant(instant);
  protected readonly eventText = historyEventText;
  protected readonly acting = actingAsText;

  protected readonly isPoReceipt = computed(() => !!this.data.po || !!this.grn()?.purchaseOrder);
  protected readonly step = computed(() => this.grn()?.step ?? 'ENTER_INVOICE');
  protected readonly editable = computed(() => {
    const g = this.grn();
    return g === null || g.status === 'DRAFT';
  });
  protected readonly statusChip = computed(() => grnStatusChip(this.grn()?.status ?? 'DRAFT'));
  protected readonly selectedGstin = signal<string | null>(null);
  protected readonly differenceTooBig = computed(() => Math.abs(this.grn()?.totalDifferencePaise ?? 0) > 100);

  /** A Schedule X line needs the pharmacist and a second signer (rule 19). */
  protected readonly needsSigner = computed(
    () => this.meta().some((m) => m.scheduleX) || (this.grn()?.lines.some((l) => l.scheduleX) ?? false),
  );
  /** Only a person with the Schedule X capability can post such a receipt; others see why. */
  protected readonly canPost = computed(() => !this.needsSigner() || this.context.canReceiveScheduleX());

  protected readonly postLabel = computed(() => {
    const g = this.grn();
    const rateUp = g?.status === 'DRAFT' && g.lines.some((l) => l.checks.includes('RATE_UP'));
    return rateUp ? 'Send for approval (rate ↑)' : 'Post GRN & update stock';
  });

  ngOnInit(): void {
    if (!this.data.grnId) {
      this.form.controls.invoiceDate.setValue(istToday());
      if (!this.data.po) {
        this.loadVendors();
      }
      return;
    }
    this.loadGrn(this.data.grnId);
  }

  private loadVendors(): void {
    this.api.vendors({ status: 'ACTIVE', suppliesStoreId: this.data.storeId, size: 100 }).subscribe({
      next: (page) =>
        this.vendors.set(
          page.items.filter((v) => !v.blocked),
        ),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
  }

  private loadGrn(id: string): void {
    this.api.goodsReceipt(this.data.storeId, id).subscribe({
      next: (g) => this.apply(g),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
  }

  /** Puts a receipt from the server into the sheet: the header, the lines and the line meta. */
  private apply(g: GoodsReceipt): void {
    this.grn.set(g);
    this.form.patchValue({
      invoiceNo: g.invoiceNo,
      invoiceDate: g.invoiceDate,
      creditDays: String(g.creditDays),
      invoiceTotal: (g.invoiceTotalPaise / 100).toFixed(2),
    });
    const previous = this.lines.controls.map((c) => c.getRawValue());
    this.lines.clear();
    this.meta.set([]);
    g.lines.forEach((line, i) => {
      const keep = previous[i];
      const group = lineGroup({
        batchNo: line.batchNo,
        expiryMonth: monthInputValue(line.expiryDate),
        qty: String(line.qty),
        billedQty: String(line.billedQty),
        freeQty: String(line.freeQty),
        ptr: trimRate(line.ptr),
        mrp: trimRate(line.mrp),
        discountPercent: trimRate(line.discountPercent),
      });
      group.patchValue({
        claimRateDifference: line.claimRateDifference,
        shortAction: line.shortAction ?? keep?.shortAction ?? '',
      });
      this.lines.push(group);
      this.meta.update((m) => [...m, this.metaOf(line)]);
    });
    if (g.purchaseOrder && this.poLineIds.size === 0) {
      this.loadPoLines(g.purchaseOrder.poId);
    }
    if (this.needsSigner()) {
      this.loadSigners();
    }
    if (g.status === 'POSTED') {
      this.changed = true;
    }
  }

  private metaOf(line: GoodsReceiptLine): LineMeta {
    return {
      productId: line.product.productId,
      name: line.product.name,
      detail: `${line.product.strength} · ${line.product.form} · per ${line.product.purchaseUnit}`,
      purchaseOrderLineId: this.poLineIds.get(line.product.productId) ?? null,
      scheduleX: line.scheduleX || line.product.schedule === 'X',
      gstRate: line.gstRate,
    };
  }

  /** The receipt answer does not name the purchase order line of a line, so it is found by product (contract gap). */
  private loadPoLines(poId: string): void {
    this.api.purchaseOrder(this.data.storeId, poId).subscribe({
      next: (po) => {
        this.poLineIds = new Map(po.lines.map((l) => [l.product.productId, l.id]));
        this.meta.update((list) =>
          list.map((m) => ({ ...m, purchaseOrderLineId: this.poLineIds.get(m.productId) ?? null })),
        );
      },
      error: () => undefined,
    });
  }

  private loadSigners(): void {
    if (this.signersLoaded()) {
      return;
    }
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

  protected serverLine(index: number): GoodsReceiptLine | null {
    return this.grn()?.lines[index] ?? null;
  }

  protected checks(index: number) {
    const line = this.serverLine(index);
    return line ? lineCheckChips(line) : [];
  }

  protected hasShort(index: number): boolean {
    return this.serverLine(index)?.checks.includes('SHORT') ?? false;
  }

  protected hasRateUp(index: number): boolean {
    return this.serverLine(index)?.checks.includes('RATE_UP') ?? false;
  }

  protected vendorChosen(vendorId: string): void {
    const vendor = this.vendors().find((v) => v.id === vendorId);
    if (vendor) {
      this.form.controls.creditDays.setValue(vendor.creditDays === null ? '' : String(vendor.creditDays));
      this.selectedGstin.set(vendor.gstin);
    }
  }

  protected addProduct(picked: PickedProduct): void {
    if (this.meta().some((m) => m.productId === picked.productId)) {
      this.formError.set('Already on this receipt. Add another batch of it on the same line, or use a new receipt.');
      return;
    }
    this.formError.set(null);
    this.meta.update((m) => [
      ...m,
      {
        productId: picked.productId,
        name: picked.name,
        detail: `${picked.strength} · ${picked.form}`,
        purchaseOrderLineId: null,
        scheduleX: false,
        gstRate: null,
      },
    ]);
    this.lines.push(lineGroup());
  }

  protected removeLine(index: number): void {
    this.lines.removeAt(index);
    this.meta.update((m) => m.filter((_, i) => i !== index));
  }

  /** Billed quantity follows the received quantity until the person changes it (OQ-79-9a). */
  protected qtyTyped(index: number): void {
    const group = this.lines.at(index);
    if (group && !group.controls.billedQty.dirty) {
      group.controls.billedQty.setValue(group.controls.qty.value);
    }
  }

  protected close(): void {
    this.ref.close({ changed: this.changed, next: null });
  }

  protected finish(next: 'approvals'): void {
    this.ref.close({ changed: this.changed, next });
  }

  // ------------------------------------------------------------ checks (the server checks again)

  private checkHeader(): { invoiceTotalPaise: number; creditDays: number } | null {
    const e: GrnErrors = {};
    const f = this.form.controls;
    if (!this.data.po && !this.grn()?.purchaseOrder && !this.grn() && f.vendorId.value === '') {
      e['vendorId'] = 'Choose a vendor';
    }
    const invoiceNo = f.invoiceNo.value.trim();
    if (invoiceNo.length < 1 || invoiceNo.length > 30) {
      e['invoiceNo'] = 'Enter the vendor invoice number, up to 30 characters';
    }
    const invoiceDate = f.invoiceDate.value;
    if (!invoiceDate) {
      e['invoiceDate'] = 'Enter the invoice date';
    } else if (invoiceDate > istToday()) {
      e['invoiceDate'] = 'The invoice date cannot be in the future';
    }
    const credit = f.creditDays.value.trim();
    if (!/^\d+$/.test(credit) || Number(credit) > 365) {
      e['creditDays'] = 'Enter 0 to 365 days';
    }
    const total = paiseFromRupees(f.invoiceTotal.value);
    if (total === null || total > 99_999_999_900) {
      e['invoiceTotal'] = 'Enter the invoice total as printed, for example 1,25,000.50';
    }
    this.errors.set(e);
    return Object.keys(e).length === 0 && total !== null
      ? { invoiceTotalPaise: total, creditDays: Number(credit) }
      : null;
  }

  private checkLines(): GoodsReceiptLineInput[] | null {
    const out: GoodsReceiptLineInput[] = [];
    const lineErrors: Record<number, string> = {};
    this.lines.controls.forEach((group, i) => {
      const v = group.getRawValue();
      const m = this.meta()[i];
      const message = this.lineProblem(v);
      if (message) {
        lineErrors[i] = message;
      }
      out.push({
        purchaseOrderLineId: m.purchaseOrderLineId,
        productId: m.productId,
        batchNo: v.batchNo.trim(),
        expiryMonth: v.expiryMonth,
        qty: Number(v.qty),
        billedQty: Number(v.billedQty === '' ? v.qty : v.billedQty),
        freeQty: Number(v.freeQty === '' ? '0' : v.freeQty),
        ptr: v.ptr.trim(),
        mrp: v.mrp.trim(),
        discountPercent: v.discountPercent.trim() === '' ? '0' : v.discountPercent.trim(),
        claimRateDifference: v.claimRateDifference,
        shortAction: v.shortAction === '' ? null : v.shortAction,
      });
    });
    this.lineErrors.set(lineErrors);
    return Object.keys(lineErrors).length === 0 ? out : null;
  }

  private lineProblem(v: ReturnType<LineGroup['getRawValue']>): string | null {
    const batch = v.batchNo.trim();
    if (batch.length < 1 || batch.length > 20) {
      return 'Batch is 1 to 20 characters';
    }
    if (!expiryMonthOk(v.expiryMonth)) {
      return 'Expiry must be in the future';
    }
    const whole = wholeQty(0, 999999);
    const qtyBad = whole(new FormControl(v.qty)) !== null;
    const billedBad = v.billedQty !== '' && whole(new FormControl(v.billedQty)) !== null;
    const freeBad = v.freeQty !== '' && whole(new FormControl(v.freeQty)) !== null;
    if (qtyBad || billedBad || freeBad) {
      return 'Quantities are whole numbers';
    }
    if (Number(v.qty) + Number(v.freeQty === '' ? '0' : v.freeQty) < 1) {
      return 'Enter a received or free quantity';
    }
    if (positiveRate(new FormControl(v.ptr)) !== null || positiveRate(new FormControl(v.mrp)) !== null) {
      return 'PTR and MRP are above 0, up to 4 decimals';
    }
    if (v.discountPercent.trim() !== '' && percentRange(new FormControl(v.discountPercent)) !== null) {
      return 'Disc % is 0 to 100';
    }
    return null;
  }

  // ------------------------------------------------------------ save and post

  protected saveDraft(): void {
    this.save(() => undefined);
  }

  private save(after: (g: GoodsReceipt) => void): void {
    if (this.busy() || !this.editable()) {
      return;
    }
    this.formError.set(null);
    const header = this.checkHeader();
    const lines = this.checkLines();
    if (header === null || lines === null) {
      return;
    }
    if (lines.length === 0 && !this.isPoReceipt()) {
      this.formError.set('Add at least one item.');
      return;
    }
    const f = this.form.controls;
    const existing = this.grn();
    this.busy.set(true);
    const request = existing
      ? this.api.updateGoodsReceipt(this.data.storeId, existing.id, {
          version: existing.version,
          invoiceNo: f.invoiceNo.value.trim(),
          invoiceDate: f.invoiceDate.value ?? '',
          creditDays: header.creditDays,
          invoiceTotalPaise: header.invoiceTotalPaise,
          lines,
        })
      : this.api.createGoodsReceipt(this.data.storeId, {
          clientKey: this.createKey,
          purchaseOrderId: this.data.po?.id ?? null,
          vendorId: this.data.po ? null : f.vendorId.value,
          invoiceNo: f.invoiceNo.value.trim(),
          invoiceDate: f.invoiceDate.value ?? '',
          creditDays: header.creditDays,
          invoiceTotalPaise: header.invoiceTotalPaise,
          lines: this.data.po ? [] : lines,
        });
    request.subscribe({
      next: (g) => {
        this.busy.set(false);
        this.changed = true;
        this.apply(g);
        after(g);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.onError(error);
      },
    });
  }

  protected post(): void {
    const g = this.grn();
    if (!g || this.busy()) {
      return;
    }
    this.formError.set(null);
    const f = this.form.controls;
    const needs = this.needsSigner();
    if (needs) {
      this.errors.update((e) => ({
        ...e,
        signerId: f.signerId.value ? undefined : 'Choose the second signer',
        pin: f.pin.value ? undefined : 'Enter their PIN',
      }));
      if (!f.signerId.value || !f.pin.value) {
        return;
      }
    }
    const run = (current: GoodsReceipt) => {
      this.busy.set(true);
      this.api
        .postGoodsReceipt(this.data.storeId, current.id, {
          clientKey: this.postKey,
          version: current.version,
          secondSigner: needs ? { membershipId: f.signerId.value, pin: f.pin.value } : null,
        })
        .subscribe({
          next: (done) => {
            this.busy.set(false);
            f.pin.setValue('');
            this.postKey = newClientKey();
            this.changed = true;
            this.apply(done);
          },
          error: (error: unknown) => {
            this.busy.set(false);
            f.pin.setValue('');
            this.onError(error);
          },
        });
    };
    if (g.status === 'DRAFT') {
      // A draft is saved with what is on the sheet first, so that the post uses the same lines (rule 10).
      this.save((saved) => run(saved));
    } else {
      run(g);
    }
  }

  protected discard(): void {
    const g = this.grn();
    if (!g || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api.discardGoodsReceipt(this.data.storeId, g.id, { version: g.version }).subscribe({
      next: () => {
        this.busy.set(false);
        this.ref.close({ changed: true, next: null });
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.formError.set(saveErrorMessage(error));
      },
    });
  }

  protected reopen(): void {
    const g = this.grn();
    if (!g || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api.reopenGoodsReceipt(this.data.storeId, g.id, { version: g.version }).subscribe({
      next: (reopened) => {
        this.busy.set(false);
        this.apply(reopened);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.formError.set(saveErrorMessage(error));
      },
    });
  }

  private onError(error: unknown): void {
    if (problemSlug(error) === 'duplicate-invoice') {
      this.errors.update((e) => ({ ...e, invoiceNo: 'That invoice was already received.' }));
    }
    if (error instanceof ApiError && error.fieldErrors.length > 0) {
      const map: Record<string, string> = {};
      const shown: GrnErrors = {};
      for (const { field, message } of error.fieldErrors) {
        const lineMatch = /^lines\[(\d+)\]/.exec(field);
        if (lineMatch) {
          this.lineErrors.update((l) => ({ ...l, [Number(lineMatch[1])]: message }));
        } else if (field === 'vendorId' || field === 'invoiceNo' || field === 'invoiceDate' || field === 'creditDays') {
          shown[field] = message;
        } else if (field === 'invoiceTotalPaise') {
          shown['invoiceTotal'] = message;
        } else if (field === 'secondSigner.membershipId') {
          shown['signerId'] = message;
        } else if (field === 'secondSigner.pin') {
          shown['pin'] = message;
        } else {
          map[field] = message;
        }
      }
      this.errors.update((e) => ({ ...e, ...shown }));
      const rest = Object.values(map);
      this.formError.set(rest.length > 0 ? rest.join(' ') : null);
      return;
    }
    this.formError.set(saveErrorMessage(error));
  }
}
