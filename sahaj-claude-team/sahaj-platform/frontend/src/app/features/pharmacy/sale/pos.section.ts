import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ApiError } from '../../../core/http/problem-detail';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { pinProblem } from '../indent/indent.util';
import { SecondSignerComponent } from '../indent/second-signer.component';
import { expiryMonthYear } from '../inventory/inventory.util';
import { rateText, rupees } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason, ProductRef, SecondSignerCandidate } from '../pharmacy-inventory.model';
import {
  Bill,
  BuyerInput,
  CartInput,
  CartLineInput,
  Customer,
  DiscountRequest,
  HeldBill,
  HeldBillRow,
  PAYMENT_MODE_LABELS,
  PaymentInput,
  PaymentMode,
  PrescriptionInput,
  ReasonInput,
  SalePatientRow,
  SaleProductRow,
  SaleQuote,
} from '../pharmacy-sale.model';
import { PharmacyApi } from '../pharmacy.api';
import { CustomerPanel } from './customer.panel';
import { DiscountApprovalCard } from './discount-approval.card';
import { ReasonPickComponent } from './reason-pick.component';
import { SubstituteCard, SubstitutePicked } from './substitute.card';
import { blockerText, expiredBlockedText, expiresText, parseRupees, paymentProblem, shortText } from './sale.util';

type BuyerMode = 'NONE' | 'WALK_IN' | 'CUSTOMER' | 'PATIENT';

interface CartRow {
  product: ProductRef;
  qty: string;
  /** The product asked for, when a substitute is supplied (80-7). */
  prescribed?: ProductRef | null;
  substitution?: SubstitutePicked['substitution'] | null;
  allergyOverride?: ReasonInput | null;
  h1Override?: ReasonInput | null;
}

interface CheckFor {
  index: number;
  kind: 'allergy' | 'h1';
}

const MODES: PaymentMode[] = ['CASH', 'UPI', 'CARD', 'UDHAR', 'CREDIT_NOTE'];

/**
 * POS billing (stories 80-2 to 80-5, mockup 02): the buyer (a registered patient in Integrated mode, a customer, a typed
 * walk-in, or none), "Scan or search medicine" with Salt search, the cart priced by the server after every change (FEFO
 * batches, expiry, quantity, rate, GST, amount, "Only n in stock."), the Summary (Items / units, MRP total, Discount,
 * CGST and SGST, Round off, Net payable), Split payment (Cash, UPI, Card, Udhar, Credit note), and the buttons Save &
 * print (F9), Hold (F8) and Cancel. Held bills resume into the cart and are priced again. In Standalone mode everything
 * works with typed customers; the patient pick shows only when the context lists PATIENT_LINK. Not in this package: the
 * IPD queue (M). Work package J adds the discount approval card, the "Safe substitute" card, and the allergy and
 * Schedule H1 override with a reason; the quote's checks still block the save until they are cleared.
 */
@Component({
  selector: 'app-pos',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    IconComponent,
    SecondSignerComponent,
    CustomerPanel,
    DiscountApprovalCard,
    ReasonPickComponent,
    SubstituteCard,
  ],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .grid {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr);
      gap: 12px;
    }
    @media (max-width: 900px) {
      .grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    .seg {
      display: inline-flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .seg button,
    .hits button.hit {
      min-height: 44px;
      padding: 0 14px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
      cursor: pointer;
    }
    .seg button[aria-pressed='true'] {
      border: 2px solid var(--pri);
      background: var(--pri-soft);
      color: var(--pri-text);
      font-weight: 700;
    }
    .hits {
      list-style: none;
      margin: 6px 0 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .hits button.hit {
      width: 100%;
      text-align: left;
    }
    .hits button.hit:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
    table {
      min-width: 720px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    .sub {
      font-size: 0.78rem;
      color: var(--ink-3);
    }
    .err {
      color: var(--crit);
      font-size: 0.8rem;
    }
    input.cell {
      min-height: 44px;
      width: 80px;
      padding: 6px 8px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
    }
    dl.summary {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 4px 12px;
      margin: 0;
    }
    dl.summary dt,
    dl.summary dd {
      margin: 0;
    }
    dl.summary dd {
      text-align: right;
    }
    dl.summary .net {
      font-weight: 800;
      font-size: 1.1rem;
    }
    .pay {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .pay label {
      display: flex;
      align-items: center;
      gap: 8px;
      min-height: 44px;
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    #invoice {
      min-width: 0;
      overflow-wrap: anywhere;
    }
    @media print {
      .tbl-wrap {
        overflow: visible;
      }
      .noprint {
        display: none !important;
      }
    }
  `,
  template: `
    @if (saved(); as bill) {
      <section class="panel surf" data-testid="pos-saved" aria-labelledby="pos-saved-h">
        <h2 id="pos-saved-h">Bill {{ bill.billNo }} saved</h2>
        <div id="invoice" data-testid="invoice">
          <p>
            <b>{{ bill.print.unitName }}</b>
            @if (bill.print.drugLicenceNo) {
              · Drug licence {{ bill.print.drugLicenceNo }}
            }
            @if (bill.print.gstin) {
              · GSTIN {{ bill.print.gstin }}
            }
          </p>
          <p data-testid="invoice-buyer">{{ bill.billNo }} · {{ bill.buyer.label }}</p>
          <div class="tbl-wrap" data-testid="invoice-table">
          <table>
            <thead>
              <tr>
                <th scope="col">#</th>
                <th scope="col">Medicine</th>
                <th scope="col">HSN</th>
                <th scope="col">Batch</th>
                <th scope="col">Expiry</th>
                <th scope="col" class="num">Qty</th>
                <th scope="col" class="num">MRP</th>
                <th scope="col" class="num">GST</th>
                <th scope="col" class="num">Amount</th>
              </tr>
            </thead>
            <tbody>
              @for (l of bill.lines; track l.lineId) {
                <tr>
                  <td>{{ l.lineNo }}</td>
                  <td>{{ l.product.name }}</td>
                  <td>{{ l.hsn }}</td>
                  <td>{{ l.batch.batchNo }}</td>
                  <td>{{ month(l.batch.expiryDate) }}</td>
                  <td class="num">{{ l.qty }}</td>
                  <td class="num">{{ rate(l.mrp) }}</td>
                  <td class="num">{{ l.gstRate }}%</td>
                  <td class="num">{{ money(l.netPaise) }}</td>
                </tr>
              }
            </tbody>
          </table>
          </div>
          <p data-testid="invoice-net">Net payable {{ money(bill.summary.netPayablePaise) }}</p>
          @if (bill.pharmacist; as ph) {
            <p>Pharmacist: {{ ph.name }}{{ ph.registrationNo ? ' · Reg. ' + ph.registrationNo : '' }}</p>
          }
          @if (bill.print.footerNote) {
            <p class="sub">{{ bill.print.footerNote }}</p>
          }
        </div>
        <div class="acts noprint">
          <button mat-flat-button type="button" data-testid="pos-print" (click)="print()">Print</button>
          <button mat-stroked-button type="button" data-testid="pos-new" (click)="reset()">New sale</button>
        </div>
      </section>
    } @else {
      <section class="panel surf" aria-labelledby="pos-h" data-testid="pos">
        <h2 id="pos-h">POS billing</h2>
        <p data-testid="pos-bar">New sale · Cashier: {{ callerName() }} · {{ storeName() }}</p>
        <p class="note">F2 Search · F4 Patient · F8 Hold · F9 Save & print</p>
        @if (notice(); as text) {
          <div class="notice good" role="status" data-testid="pos-note">
            <app-icon name="check" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }

        <h3>Buyer</h3>
        <div class="seg" role="group" aria-label="Buyer">
          <button type="button" data-testid="buyer-none" [attr.aria-pressed]="buyerMode() === 'NONE'" (click)="setBuyer('NONE')">No name</button>
          <button type="button" data-testid="buyer-walkin" [attr.aria-pressed]="buyerMode() === 'WALK_IN'" (click)="setBuyer('WALK_IN')">Walk-in</button>
          <button type="button" data-testid="buyer-customer" [attr.aria-pressed]="buyerMode() === 'CUSTOMER'" (click)="setBuyer('CUSTOMER')">Customer</button>
          @if (patientPick()) {
            <button type="button" data-testid="buyer-patient" [attr.aria-pressed]="buyerMode() === 'PATIENT'" (click)="setBuyer('PATIENT')">Patient</button>
          }
        </div>
        @if (buyerMode() === 'WALK_IN') {
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Name</mat-label>
            <input matInput maxlength="80" data-testid="walkin-name" [value]="walkInName()" (input)="walkInName.set($any($event.target).value); requote()" />
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Phone (optional)</mat-label>
            <input matInput inputmode="numeric" maxlength="10" data-testid="walkin-phone" [value]="walkInPhone()" (input)="walkInPhone.set($any($event.target).value)" />
          </mat-form-field>
        }
        @if (buyerMode() === 'CUSTOMER') {
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Search customers by name or phone</mat-label>
            <input matInput data-testid="customer-search" [value]="customerQuery()" (input)="searchCustomers($any($event.target).value)" />
          </mat-form-field>
          <ul class="hits">
            @for (c of customers(); track c.id) {
              <li>
                <button type="button" class="hit" [attr.data-testid]="'customer-' + c.id" (click)="pickCustomer(c)">
                  {{ c.name }} <span class="sub">{{ c.phone ?? '' }}</span>
                </button>
              </li>
            }
          </ul>
          @if (customer(); as c) {
            <p data-testid="customer-chosen">
              {{ c.name }}
              @if ((c.credit.creditLimitPaise ?? 0) > 0) {
                · Credit limit {{ money(c.credit.creditLimitPaise ?? 0) }} · current due {{ money(c.credit.duePaise) }}
              }
            </p>
            @if (c.allergyNote) {
              <p class="sub" data-testid="customer-allergy">Allergy note (not checked): {{ c.allergyNote }}</p>
            }
          }
          <app-customer-panel [customer]="customer()" (created)="pickCustomer($event)" (updated)="customer.set($event)" />
        }
        @if (buyerMode() === 'PATIENT') {
          @if (patientError(); as text) {
            <div class="notice warn" role="status" data-testid="patient-error">
              <app-icon name="alert" [size]="16" />
              <span>{{ text }}</span>
            </div>
          }
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Scan or type UHID, name or mobile</mat-label>
            <input matInput data-testid="patient-search" [value]="patientQuery()" (input)="searchPatients($any($event.target).value)" />
          </mat-form-field>
          <ul class="hits">
            @for (p of patients(); track p.patientId) {
              <li>
                <button type="button" class="hit" [attr.data-testid]="'patient-' + p.patientId" (click)="pickPatient(p)">
                  {{ p.name }} · {{ p.age }} {{ p.sex }} <span class="sub">{{ p.uhid ?? '' }} · {{ p.maskedMobile ?? '' }}</span>
                </button>
              </li>
            }
          </ul>
          @if (patient(); as p) {
            <p data-testid="patient-chosen">{{ p.name }} · {{ p.age }} {{ p.sex }} · UHID {{ p.uhid }} · {{ p.maskedMobile }}</p>
          }
        }

        <h3>Medicines</h3>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Scan barcode, or type brand / salt / composition</mat-label>
          <input
            matInput
            autocomplete="off"
            data-testid="pos-search"
            [value]="searchText()"
            (input)="search($any($event.target).value)"
            (keydown.enter)="searchBarcode($event)"
          />
        </mat-form-field>
        @if (searchNote(); as text) {
          <p class="err" role="alert" data-testid="pos-search-note">{{ text }}</p>
        }
        <ul class="hits" data-testid="pos-hits">
          @for (hit of hits(); track hit.product.productId) {
            <li>
              <button type="button" class="hit" [attr.data-testid]="'hit-' + hit.product.productId" [disabled]="hit.outOfStock" (click)="add(hit)">
                {{ hit.product.name }} {{ hit.product.strength }}
                <span class="sub">{{ hit.packText }} · {{ hit.outOfStock ? 'Out of stock' : hit.availableQty === null ? 'Counting in progress' : 'In stock ' + stockText(hit.availableQty) }}</span>
                @if (hit.matchedOnSalt) {
                  <span class="pill p-blue">Salt search</span>
                }
                @if (hit.product.schedule !== 'NONE') {
                  <span class="pill p-warn">Sch. {{ hit.product.schedule }}</span>
                }
              </button>
            </li>
          }
        </ul>
      </section>

      <div class="grid">
        <section class="panel surf" aria-labelledby="cart-h" data-testid="pos-cart">
          <h3 id="cart-h">Cart</h3>
          @if (cart().length === 0) {
            <p class="note" data-testid="cart-empty">No items yet. Scan or search a medicine.</p>
          } @else {
            <div class="tbl-wrap">
              <table data-testid="cart-table">
                <thead>
                  <tr>
                    <th scope="col">#</th>
                    <th scope="col">Medicine</th>
                    <th scope="col">Batch (FEFO)</th>
                    <th scope="col">Expiry</th>
                    <th scope="col">Qty</th>
                    <th scope="col" class="num">Rate</th>
                    <th scope="col" class="num">GST</th>
                    <th scope="col" class="num">Amount</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of cart(); track row.product.productId; let n = $index) {
                    <tr [attr.data-testid]="'cart-row-' + n">
                      <td>{{ n + 1 }}</td>
                      <td>
                        {{ row.product.name }}
                        @if (row.product.schedule !== 'NONE') {
                          <span class="pill p-warn">Sch. {{ row.product.schedule }}</span>
                        }
                        <div class="sub">{{ row.product.strength }} · {{ row.product.form }}</div>
                        @if (row.prescribed; as asked) {
                          <span class="pill p-blue" data-testid="chip-substituted">Substituted</span>
                          <div class="sub" data-testid="rx-asked">Rx: {{ asked.name }}</div>
                        }
                        @if (row.allergyOverride) {
                          <span class="pill p-warn" data-testid="chip-allergy-override">Allergy override</span>
                        }
                        @if (row.h1Override) {
                          <span class="pill p-warn" data-testid="chip-h1-override">Schedule H1 override</span>
                        }
                      </td>
                      <td colspan="1">
                        @for (q of linesOf(n); track q.lineNo) {
                          <div [attr.data-testid]="'cart-batch-' + n">
                            {{ q.batch ? q.batch.batchNo : '—' }} <span class="sub">FEFO</span>
                            @if (expiredBlocked(q); as t) {
                              <div class="err">{{ t }}</div>
                            }
                            @if (short(q); as t) {
                              <div class="err" role="alert" [attr.data-testid]="'cart-short-' + n">{{ t }}</div>
                            }
                          </div>
                        } @empty {
                          —
                        }
                      </td>
                      <td>
                        @for (q of linesOf(n); track q.lineNo) {
                          <div>
                            {{ q.batch ? month(q.batch.expiryDate) : '—' }}
                            @if (expires(q); as t) {
                              <div class="sub">{{ t }}</div>
                            }
                          </div>
                        }
                      </td>
                      <td>
                        <input
                          class="cell"
                          inputmode="numeric"
                          [value]="row.qty"
                          [attr.aria-label]="'Quantity of ' + row.product.name"
                          [attr.data-testid]="'cart-qty-' + n"
                          (input)="setQty(n, $any($event.target).value)"
                        />
                        <button mat-button type="button" [attr.aria-label]="'Remove ' + row.product.name" [attr.data-testid]="'cart-remove-' + n" (click)="remove(n)">Remove</button>
                        @if (canSubstitute()) {
                          <button mat-button type="button" [attr.aria-label]="'Substitute ' + row.product.name" [attr.data-testid]="'cart-substitute-' + n" (click)="openSubstitute(n)">
                            Substitute (F6)
                          </button>
                        }
                      </td>
                      <td class="num">
                        @for (q of linesOf(n); track q.lineNo) {
                          <div>{{ rate(q.mrp) }}</div>
                        }
                      </td>
                      <td class="num">
                        @for (q of linesOf(n); track q.lineNo) {
                          <div>{{ q.gstRate }}%</div>
                        }
                      </td>
                      <td class="num">
                        @for (q of linesOf(n); track q.lineNo) {
                          <div>{{ money(q.amountPaise) }}</div>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
          @if (substituteFor(); as sf) {
            <app-substitute-card [product]="sf.product" [reasons]="substitutionReasons()" (picked)="substitute(sf.index, $event)" (closed)="substituteIndex.set(null)" />
          }
          @if (quoteError(); as text) {
            <div class="notice err" role="alert" data-testid="quote-error">
              <app-icon name="alert" [size]="16" />
              <span>{{ text }}</span>
            </div>
          }
          @if (quote(); as qt) {
            <div data-testid="quote-checks">
              <p data-testid="quote-allergy">{{ allergyText(qt) }}</p>
              @for (b of qt.blockers; track b) {
                <p class="err" role="alert" [attr.data-testid]="'blocker-' + b">{{ blocker(b) }}</p>
              }
              @for (l of qt.lines; track l.lineNo) {
                @if (l.allergyBlock; as a) {
                  @if (!a.overridden) {
                    <p class="err" role="alert" data-testid="line-allergy">
                      Blocked: {{ l.product.name }} — patient has a {{ a.allergyText }} allergy recorded in EMR.
                    </p>
                    @if (canOverride()) {
                      <button mat-stroked-button type="button" data-testid="allergy-override" (click)="openCheck(l.inputIndex ?? 0, 'allergy')">
                        Override with a reason
                      </button>
                    } @else {
                      <p class="note" data-testid="allergy-decide">A pharmacist must decide.</p>
                    }
                  }
                }
                @if (l.h1PrescriberMissing && l.schedule === 'H1' && !l.h1Overridden && canOverride()) {
                  <button mat-stroked-button type="button" data-testid="h1-override" (click)="openCheck(l.inputIndex ?? 0, 'h1')">
                    Override the prescriber with a reason
                  </button>
                }
              }
              @if (checkFor(); as cf) {
                <app-reason-pick
                  [heading]="cf.kind === 'allergy' ? 'Allergy override reason' : 'Schedule H1 override reason'"
                  [reasons]="cf.kind === 'allergy' ? allergyReasons() : h1Reasons()"
                  confirmLabel="Override"
                  (chosen)="override(cf, $event)"
                  (dismissed)="checkFor.set(null)"
                />
              }
            </div>
          }
        </section>

        <section class="panel surf" aria-labelledby="sum-h" data-testid="pos-summary">
          <h3 id="sum-h">Summary</h3>
          @if (quote(); as qt) {
            <dl class="summary">
              <dt>Items / units</dt>
              <dd data-testid="sum-items">{{ qt.summary.itemCount }} / {{ qt.summary.unitCount }}</dd>
              <dt>MRP total</dt>
              <dd data-testid="sum-mrp">{{ money(qt.summary.mrpTotalPaise) }}</dd>
              <dt>Discount</dt>
              <dd data-testid="sum-discount">− {{ money(qt.summary.discountPaise) }}</dd>
              @for (t of qt.summary.taxByRate; track t.gstRate) {
                <dt>CGST {{ half(t.gstRate) }}% (incl.)</dt>
                <dd>{{ money(t.cgstPaise) }}</dd>
                <dt>SGST {{ half(t.gstRate) }}% (incl.)</dt>
                <dd>{{ money(t.sgstPaise) }}</dd>
              }
              <dt>Round off</dt>
              <dd data-testid="sum-round">{{ qt.summary.roundOffPaise < 0 ? '−' : '' }} {{ money(abs(qt.summary.roundOffPaise)) }}</dd>
              <dt class="net">Net payable</dt>
              <dd class="net" data-testid="sum-net">{{ money(qt.summary.netPayablePaise) }}</dd>
            </dl>
          } @else {
            <p class="note" data-testid="sum-empty">The summary shows when the cart has an item.</p>
          }
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Discount % of the MRP total</mat-label>
            <input
              matInput
              inputmode="decimal"
              data-testid="pos-discount"
              [value]="discount()"
              (input)="setDiscount($any($event.target).value)"
            />
          </mat-form-field>
          @if (quote(); as qt) {
            @if (qt.discount.limitNotSet && discountNumber() > 0) {
              <p class="err" role="alert" data-testid="discount-not-set">The discount limit is not set. Ask your owner.</p>
            } @else if (qt.discount.approvalNeeded || discountRequest()) {
              <app-discount-approval
                [request]="qt.discount.approval ?? discountRequest()"
                [busy]="discountBusy()"
                [error]="discountError()"
                (send)="sendDiscount()"
                (refresh)="refreshDiscount()"
                (withdraw)="withdrawDiscount()"
              />
            }
          }

          <h3>Split payment</h3>
          <div class="pay" data-testid="pos-payments">
            @for (m of availableModes(); track m) {
              <label>
                <span>{{ modeLabel(m) }}</span>
                <input
                  class="cell"
                  inputmode="decimal"
                  [value]="amounts()[m] ?? ''"
                  [attr.aria-label]="modeLabel(m) + ' amount in rupees'"
                  [attr.data-testid]="'pay-' + m"
                  (input)="setAmount(m, $any($event.target).value)"
                />
              </label>
            }
            @if (creditLine(); as c) {
              <p class="sub" data-testid="credit-line">Credit limit {{ money(c.creditLimitPaise ?? 0) }} · current due {{ money(c.duePaise) }}</p>
            }
            <button mat-stroked-button type="button" data-testid="pay-fill-cash" (click)="fillCash()">Whole amount in cash</button>
            @if (payError(); as text) {
              <p class="err" role="alert" data-testid="pay-error">{{ text }}</p>
            }
          </div>

          @if (quote()?.needsPrescription) {
            <h3>Prescription</h3>
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Prescriber name</mat-label>
              <input matInput maxlength="80" data-testid="rx-name" [value]="rxName()" (input)="rxName.set($any($event.target).value)" />
            </mat-form-field>
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Registration number</mat-label>
              <input matInput maxlength="30" data-testid="rx-reg" [value]="rxReg()" (input)="rxReg.set($any($event.target).value)" />
            </mat-form-field>
            @if (quote()?.needsH1PatientDetails) {
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label>Patient age</mat-label>
                <input matInput inputmode="numeric" data-testid="rx-age" [value]="rxAge()" (input)="rxAge.set($any($event.target).value)" />
              </mat-form-field>
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label>Patient address</mat-label>
                <input matInput maxlength="200" data-testid="rx-address" [value]="rxAddress()" (input)="rxAddress.set($any($event.target).value)" />
              </mat-form-field>
            }
          }
          @if (quote()?.needsSecondSigner) {
            <p class="notice teal" data-testid="pos-x-note">
              <app-icon name="lock" [size]="16" />
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
          @if (formError(); as text) {
            <div class="notice err" role="alert" data-testid="pos-error">
              <app-icon name="alert" [size]="16" />
              <span>{{ text }}</span>
            </div>
          }
          <div class="acts">
            <button mat-flat-button type="button" data-testid="pos-save" [disabled]="saving() || !canSave()" (click)="save()">
              Save & print (F9)
            </button>
            <button mat-stroked-button type="button" data-testid="pos-hold" [disabled]="saving() || cart().length === 0" (click)="hold()">Hold (F8)</button>
            <button mat-stroked-button type="button" data-testid="pos-cancel" (click)="reset()">Cancel</button>
          </div>
          <p class="note">Invoice shows drug licence no., GSTIN, HSN, batch, expiry and pharmacist signature.</p>
        </section>
      </div>

      <section class="panel surf" aria-labelledby="held-h" data-testid="pos-held">
        <h3 id="held-h">Held bills</h3>
        @if (held().length === 0) {
          <p class="note" data-testid="held-empty">No held bills.</p>
        } @else {
          <ul class="hits">
            @for (h of held(); track h.id) {
              <li [attr.data-testid]="'held-' + h.id">
                {{ h.buyerLabel }} · {{ h.itemCount }} {{ h.itemCount === 1 ? 'item' : 'items' }} · held by {{ h.heldBy.name }} · {{ h.ageMinutes }} min
                <button mat-stroked-button type="button" [attr.data-testid]="'resume-' + h.id" (click)="resume(h)">Resume</button>
                <button mat-button type="button" [attr.data-testid]="'discard-' + h.id" (click)="discard(h)">Discard</button>
              </li>
            }
          </ul>
        }
      </section>
    }
  `,
})
export class PosSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  protected readonly buyerMode = signal<BuyerMode>('NONE');
  protected readonly walkInName = signal('');
  protected readonly walkInPhone = signal('');
  protected readonly customerQuery = signal('');
  protected readonly customers = signal<Customer[]>([]);
  protected readonly customer = signal<Customer | null>(null);
  protected readonly patientQuery = signal('');
  protected readonly patients = signal<SalePatientRow[]>([]);
  protected readonly patient = signal<SalePatientRow | null>(null);
  protected readonly patientError = signal<string | null>(null);

  protected readonly searchText = signal('');
  protected readonly hits = signal<SaleProductRow[]>([]);
  protected readonly searchNote = signal<string | null>(null);

  protected readonly cart = signal<CartRow[]>([]);
  protected readonly discount = signal('');
  protected readonly quote = signal<SaleQuote | null>(null);
  protected readonly quoteError = signal<string | null>(null);
  protected readonly amounts = signal<Partial<Record<PaymentMode, string>>>({});
  protected readonly payError = signal<string | null>(null);
  protected readonly rxName = signal('');
  protected readonly rxReg = signal('');
  protected readonly rxAge = signal('');
  protected readonly rxAddress = signal('');
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly signerId = signal('');
  protected readonly pin = signal('');
  protected readonly signerError = signal<string | null>(null);
  protected readonly pinError = signal<string | null>(null);
  protected readonly held = signal<HeldBillRow[]>([]);
  protected readonly heldBillId = signal<string | null>(null);
  protected readonly heldNet = signal<number | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly saved = signal<Bill | null>(null);
  protected readonly substitutionReasons = signal<ActiveReason[]>([]);
  protected readonly allergyReasons = signal<ActiveReason[]>([]);
  protected readonly h1Reasons = signal<ActiveReason[]>([]);
  protected readonly substituteIndex = signal<number | null>(null);
  protected readonly checkFor = signal<CheckFor | null>(null);
  protected readonly discountRequest = signal<DiscountRequest | null>(null);
  protected readonly discountBusy = signal(false);
  protected readonly discountError = signal<string | null>(null);

  private quoteSeq = 0;
  private saveKey = newClientKey();
  private holdKey = newClientKey();

  protected readonly storeName = computed(() => this.context.workingStore()?.name ?? '');
  protected readonly callerName = computed(() => this.context.context()?.caller.name ?? '');
  /** Substitution and the two overrides are the pharmacist's alone (the server says so in the store's access flags). */
  protected readonly canSubstitute = computed(() => this.context.workingStore()?.access.substitute === true);
  protected readonly canOverride = computed(() => this.context.workingStore()?.access.overrideBlock === true);
  protected readonly substituteFor = computed(() => {
    const index = this.substituteIndex();
    const row = index === null ? undefined : this.cart()[index];
    return row && index !== null ? { index, product: row.product } : null;
  });
  /** The patient pick is Integrated only, and only when the context lists the patient port (PATIENT_LINK). */
  protected readonly patientPick = this.context.canPatientLink;

  protected readonly money = rupees;
  protected readonly rate = rateText;
  protected readonly month = expiryMonthYear;
  protected readonly short = shortText;
  protected readonly expires = expiresText;
  protected readonly expiredBlocked = expiredBlockedText;
  protected readonly blocker = blockerText;
  protected readonly modeLabel = (m: PaymentMode) => PAYMENT_MODE_LABELS[m];
  protected readonly abs = Math.abs;

  protected readonly discountNumber = computed(() => {
    const n = Number(this.discount());
    return Number.isFinite(n) ? n : 0;
  });
  /** Udhar is offered only for a named customer with a credit limit above 0; Credit note for a customer. */
  protected readonly creditLine = computed(() => this.customer()?.credit ?? this.quote()?.credit ?? null);
  protected readonly availableModes = computed<PaymentMode[]>(() => {
    const credit = this.creditLine();
    const customer = this.buyerMode() === 'CUSTOMER' || this.buyerMode() === 'PATIENT';
    return MODES.filter((m) => {
      if (m === 'UDHAR') {
        return customer && (credit?.creditLimitPaise ?? 0) > 0;
      }
      if (m === 'CREDIT_NOTE') {
        return customer && (credit?.creditNoteBalancePaise ?? 0) > 0;
      }
      return true;
    });
  });
  protected readonly canSave = computed(() => {
    const q = this.quote();
    return q !== null && q.canSave && this.cart().length > 0 && this.buyerReady();
  });

  ngOnInit(): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.loadHeld();
    if (this.canSubstitute() || this.canOverride()) {
      this.api.activeReasons(['substitution', 'allergy-override', 'h1-override']).subscribe({
        next: (answer) => {
          const list = (key: string) => answer.lists.find((l) => l.listKey === key)?.reasons ?? [];
          this.substitutionReasons.set(list('substitution'));
          this.allergyReasons.set(list('allergy-override'));
          this.h1Reasons.set(list('h1-override'));
        },
        error: () => this.formError.set('Could not load the reasons. Try again.'),
      });
    }
    this.api.secondSigners(storeId).subscribe({
      next: (people) => {
        this.signers.set(people);
        this.signersLoaded.set(true);
      },
      error: () => this.signersLoaded.set(true),
    });
  }

  private loadHeld(): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.api.heldBills(storeId).subscribe({
      next: (rows) => this.held.set(rows),
      error: () => this.held.set([]),
    });
  }

  // ---- buyer

  protected setBuyer(mode: BuyerMode): void {
    this.buyerMode.set(mode);
    this.customer.set(null);
    this.patient.set(null);
    this.patientError.set(null);
    this.requote();
  }

  private buyerReady(): boolean {
    switch (this.buyerMode()) {
      case 'WALK_IN':
        return this.walkInName().trim().length > 0;
      case 'CUSTOMER':
        return this.customer() !== null;
      case 'PATIENT':
        return this.patient() !== null;
      default:
        return true;
    }
  }

  private buyer(): BuyerInput {
    switch (this.buyerMode()) {
      case 'WALK_IN':
        return {
          kind: 'WALK_IN',
          walkInName: this.walkInName().trim(),
          walkInPhone: this.walkInPhone().trim() === '' ? null : this.walkInPhone().trim(),
        };
      case 'CUSTOMER':
        return { kind: 'CUSTOMER', customerId: this.customer()?.id ?? null };
      case 'PATIENT':
        return { kind: 'PATIENT', patientId: this.patient()?.patientId ?? null };
      default:
        return { kind: 'NONE' };
    }
  }

  protected searchCustomers(query: string): void {
    const storeId = this.context.workingStoreId();
    this.customerQuery.set(query);
    if (!storeId || query.trim().length < 2) {
      this.customers.set([]);
      return;
    }
    this.api.searchCustomers(storeId, query.trim()).subscribe({
      next: (page) => this.customers.set(page.items),
      error: () => this.customers.set([]),
    });
  }

  protected pickCustomer(customer: Customer): void {
    this.customer.set(customer);
    this.customers.set([]);
    this.requote();
  }

  protected searchPatients(query: string): void {
    const storeId = this.context.workingStoreId();
    this.patientQuery.set(query);
    this.patientError.set(null);
    if (!storeId || query.trim().length < 2) {
      this.patients.set([]);
      return;
    }
    this.api.searchSalePatients(storeId, query.trim()).subscribe({
      next: (rows) => this.patients.set(rows),
      error: (error: unknown) => {
        this.patients.set([]);
        const slug = problemSlug(error);
        this.patientError.set(
          slug === 'integration-off'
            ? 'Not connected. Patient pick works when OPD and IPD are connected. Use a customer or a walk-in.'
            : slug === 'patients-unreachable'
              ? 'Patient records are not reachable. You can continue as a walk-in and link the patient later.'
              : loadErrorMessage(error),
        );
      },
    });
  }

  protected pickPatient(patient: SalePatientRow): void {
    this.patient.set(patient);
    this.patients.set([]);
    this.requote();
  }

  // ---- medicines

  protected stockText(qty: number | null): string {
    return qty === null ? '—' : String(qty);
  }

  protected search(text: string): void {
    const storeId = this.context.workingStoreId();
    this.searchText.set(text);
    this.searchNote.set(null);
    if (!storeId || text.trim().length < 2) {
      this.hits.set([]);
      return;
    }
    this.api.saleProducts(storeId, { q: text.trim() }).subscribe({
      next: (rows) => {
        this.hits.set(rows);
        if (rows.length === 0) {
          this.searchNote.set('No medicine matches.');
        }
      },
      error: (error: unknown) => {
        this.hits.set([]);
        this.searchNote.set(loadErrorMessage(error));
      },
    });
  }

  /** A keyboard-style scanner types the barcode and presses Enter (OQ-79-24a). */
  protected searchBarcode(event: Event): void {
    event.preventDefault();
    const storeId = this.context.workingStoreId();
    const code = this.searchText().trim();
    if (!storeId || !/^[A-Za-z0-9-]{1,40}$/.test(code)) {
      return;
    }
    this.api.saleProducts(storeId, { barcode: code }).subscribe({
      next: (rows) => {
        if (rows.length === 0) {
          this.searchNote.set('No product has this barcode.');
          return;
        }
        this.searchText.set('');
        this.hits.set([]);
        if (!rows[0].outOfStock) {
          this.add(rows[0]);
        }
      },
      error: (error: unknown) => this.searchNote.set(loadErrorMessage(error)),
    });
  }

  protected add(hit: SaleProductRow): void {
    if (hit.outOfStock) {
      return;
    }
    this.hits.set([]);
    this.searchText.set('');
    const existing = this.cart().findIndex((r) => r.product.productId === hit.product.productId);
    if (existing >= 0) {
      this.cart.update((list) => list.map((r, i) => (i === existing ? { ...r, qty: String(Number(r.qty || 0) + 1) } : r)));
    } else {
      this.cart.update((list) => [...list, { product: hit.product, qty: '1' }]);
    }
    this.requote();
  }

  protected setQty(index: number, qty: string): void {
    this.cart.update((list) => list.map((r, i) => (i === index ? { ...r, qty } : r)));
    this.requote();
  }

  protected remove(index: number): void {
    this.cart.update((list) => list.filter((_, i) => i !== index));
    this.requote();
  }

  protected setDiscount(value: string): void {
    this.discount.set(value);
    this.requote();
  }

  // ---- work package J: substitution, overrides and the discount approval

  protected openSubstitute(index: number): void {
    this.checkFor.set(null);
    this.substituteIndex.set(index);
  }

  protected substitute(index: number, picked: SubstitutePicked): void {
    this.cart.update((list) =>
      list.map((r, i) =>
        i === index
          ? { ...r, prescribed: r.prescribed ?? r.product, product: picked.product, substitution: picked.substitution, allergyOverride: null, h1Override: null }
          : r,
      ),
    );
    this.substituteIndex.set(null);
    this.requote();
  }

  protected openCheck(index: number, kind: CheckFor['kind']): void {
    this.substituteIndex.set(null);
    this.checkFor.set({ index, kind });
  }

  protected override(check: CheckFor, reason: ReasonInput): void {
    this.cart.update((list) =>
      list.map((r, i) =>
        i === check.index ? (check.kind === 'allergy' ? { ...r, allergyOverride: reason } : { ...r, h1Override: reason }) : r,
      ),
    );
    this.checkFor.set(null);
    this.requote();
  }

  protected sendDiscount(): void {
    const storeId = this.context.workingStoreId();
    const cart = this.cartInput();
    if (!storeId || cart === null || this.discountBusy()) {
      return;
    }
    this.discountError.set(null);
    this.discountBusy.set(true);
    this.api
      .requestDiscountApproval(storeId, { ...cart, discountRequestId: null, clientKey: newClientKey(), heldBillId: this.heldBillId() })
      .subscribe({
        next: (answer) => {
          this.discountBusy.set(false);
          this.discountRequest.set(answer.required ? answer : null);
          if (!answer.required) {
            this.notice.set('The discount applies at once.');
          }
          this.requote();
        },
        error: (e: unknown) => {
          this.discountBusy.set(false);
          this.discountError.set(saveErrorMessage(e));
        },
      });
  }

  protected refreshDiscount(): void {
    const storeId = this.context.workingStoreId();
    const id = this.discountRequest()?.requestId;
    if (!storeId || !id) {
      return;
    }
    this.discountBusy.set(true);
    this.api.discountRequest(storeId, id).subscribe({
      next: (answer) => {
        this.discountBusy.set(false);
        this.discountRequest.set(answer);
        this.requote();
      },
      error: (e: unknown) => {
        this.discountBusy.set(false);
        this.discountError.set(loadErrorMessage(e));
      },
    });
  }

  protected withdrawDiscount(): void {
    const storeId = this.context.workingStoreId();
    const id = this.discountRequest()?.requestId;
    if (!storeId || !id) {
      return;
    }
    this.discountBusy.set(true);
    this.api.withdrawDiscountRequest(storeId, id).subscribe({
      next: () => {
        this.discountBusy.set(false);
        this.discountRequest.set(null);
        this.requote();
      },
      error: (e: unknown) => {
        this.discountBusy.set(false);
        this.discountError.set(saveErrorMessage(e));
      },
    });
  }

  // ---- the quote: after every change; the server prices by FEFO

  protected linesOf(index: number): SaleQuote['lines'] {
    return (this.quote()?.lines ?? []).filter((l) => (l.inputIndex ?? 0) === index);
  }

  protected allergyText(q: SaleQuote): string {
    switch (q.allergy.status) {
      case 'RECORDED':
        return `Allergy on EMR: ${q.allergy.text ?? ''}`;
      case 'NONE_RECORDED':
        return 'No allergy recorded';
      case 'NOT_CHECKED':
        return 'Allergy not checked';
      case 'NO_PATIENT':
        return 'No patient selected. Allergy not checked.';
      default:
        return q.allergy.text ? `Allergy note (not checked): ${q.allergy.text}` : 'Allergy not checked in Standalone mode';
    }
  }

  protected half(rate: string): string {
    const n = Number(rate) / 2;
    return Number.isInteger(n) ? String(n) : n.toFixed(1);
  }

  private cartInput(): CartInput | null {
    const lines: CartLineInput[] = this.cart().map((r) => ({
      productId: r.product.productId,
      qty: Number(r.qty),
      ...(r.prescribed ? { prescribedProductId: r.prescribed.productId, substitution: r.substitution ?? null } : {}),
      ...(r.allergyOverride ? { allergyOverride: r.allergyOverride } : {}),
      ...(r.h1Override ? { h1Override: r.h1Override } : {}),
    }));
    if (lines.length === 0 || lines.some((l) => !Number.isInteger(l.qty) || l.qty < 1 || l.qty > 999999)) {
      return null;
    }
    const d = this.discount().trim();
    return {
      buyer: this.buyer(),
      lines,
      discountPercent: d === '' || Number(d) === 0 ? null : d,
      discountRequestId: d === '' || Number(d) === 0 ? null : (this.discountRequest()?.requestId ?? null),
      prescription: this.prescription(),
    };
  }

  private prescription(): PrescriptionInput | null {
    if (!this.quote()?.needsPrescription) {
      return null;
    }
    const age = this.rxAge().trim();
    return {
      source: 'TYPED',
      prescriberName: this.rxName().trim() || null,
      prescriberRegNo: this.rxReg().trim() || null,
      patientAge: /^\d{1,3}$/.test(age) ? Number(age) : null,
      patientAddress: this.rxAddress().trim() || null,
    };
  }

  protected requote(): void {
    const storeId = this.context.workingStoreId();
    const cart = this.cartInput();
    this.quoteError.set(null);
    if (!storeId || cart === null) {
      this.quote.set(null);
      return;
    }
    const seq = ++this.quoteSeq;
    this.api.quoteSale(storeId, cart).subscribe({
      next: (answer) => {
        if (seq === this.quoteSeq) {
          this.quote.set(answer);
          this.fillIfSingle(answer);
        }
      },
      error: (error: unknown) => {
        if (seq === this.quoteSeq) {
          this.quote.set(null);
          this.quoteError.set(saveErrorMessage(error));
        }
      },
    });
  }

  /** With one payment mode typed, the amount follows the net payable. */
  private fillIfSingle(q: SaleQuote): void {
    const keys = Object.keys(this.amounts());
    if (keys.length === 1) {
      const mode = keys[0] as PaymentMode;
      this.amounts.set({ [mode]: String(q.summary.netPayablePaise / 100) });
    }
  }

  // ---- payment

  protected setAmount(mode: PaymentMode, text: string): void {
    this.amounts.update((a) => {
      const next = { ...a };
      if (text.trim() === '') {
        delete next[mode];
      } else {
        next[mode] = text;
      }
      return next;
    });
    this.payError.set(null);
  }

  protected fillCash(): void {
    const q = this.quote();
    if (q) {
      this.amounts.set({ CASH: String(q.summary.netPayablePaise / 100) });
      this.payError.set(null);
    }
  }

  private payments(): PaymentInput[] | null {
    const out: PaymentInput[] = [];
    for (const mode of MODES) {
      const text = this.amounts()[mode];
      if (text === undefined) {
        continue;
      }
      const paise = parseRupees(text);
      if (paise === null || paise < 1) {
        this.payError.set(`Enter the ${PAYMENT_MODE_LABELS[mode]} amount in rupees.`);
        return null;
      }
      out.push({ mode, amountPaise: paise });
    }
    return out;
  }

  // ---- save, hold, resume

  protected save(): void {
    const storeId = this.context.workingStoreId();
    const q = this.quote();
    const cart = this.cartInput();
    if (this.saving() || !storeId || !q || cart === null) {
      return;
    }
    this.formError.set(null);
    this.signerError.set(null);
    this.pinError.set(null);
    this.payError.set(null);
    const payments = this.payments();
    if (payments === null) {
      return;
    }
    const mismatch = paymentProblem(q.summary.netPayablePaise, payments);
    if (mismatch) {
      this.payError.set(mismatch);
      return;
    }
    let secondSigner: { membershipId: string; pin: string } | null = null;
    if (q.needsSecondSigner) {
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
      .createBill(storeId, {
        ...cart,
        clientKey: this.saveKey,
        quoteVersion: q.quoteVersion,
        expectedNetPaise: q.summary.netPayablePaise,
        payments,
        heldBillId: this.heldBillId(),
        secondSigner,
      })
      .subscribe({
        next: (bill) => {
          this.pin.set('');
          this.saving.set(false);
          this.saved.set(bill);
          this.saveKey = newClientKey();
          this.loadHeld();
        },
        error: (error: unknown) => {
          this.pin.set('');
          this.saving.set(false);
          if (problemSlug(error) === 'sale-changed' && error instanceof ApiError && error.problem.quote) {
            // The server priced the cart again under its locks: this quote replaces ours and nothing was saved.
            this.quote.set(error.problem.quote as SaleQuote);
            this.formError.set('Prices or batches changed. Check the bill and save again.');
            return;
          }
          this.formError.set(saveErrorMessage(error));
          if (problemSlug(error) === 'sale-changed' || problemSlug(error) === 'insufficient-stock') {
            this.requote();
          }
        },
      });
  }

  protected hold(): void {
    const storeId = this.context.workingStoreId();
    const cart = this.cartInput();
    if (this.saving() || !storeId || cart === null) {
      return;
    }
    this.formError.set(null);
    this.saving.set(true);
    this.api
      .holdBill(storeId, { ...cart, clientKey: this.holdKey, replacesHeldBillId: this.heldBillId() })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.holdKey = newClientKey();
          this.reset();
          this.notice.set('Bill held. Resume it from Held bills.');
          this.loadHeld();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }

  protected resume(row: HeldBillRow): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.api.heldBill(storeId, row.id).subscribe({
      next: (held: HeldBill) => this.loadCart(held),
      error: (error: unknown) => this.formError.set(loadErrorMessage(error)),
    });
  }

  /** Batches and prices are not kept in a held bill: the cart is priced again, and a different net is said aloud. */
  private loadCart(held: HeldBill): void {
    const cart = held.cart;
    this.heldBillId.set(held.id);
    this.heldNet.set(held.heldNetPaise);
    this.buyerMode.set(cart.buyer.kind === 'PATIENT' && !this.patientPick() ? 'NONE' : cart.buyer.kind);
    this.walkInName.set(cart.buyer.walkInName ?? '');
    this.walkInPhone.set(cart.buyer.walkInPhone ?? '');
    this.discount.set(cart.discountPercent ?? '');
    const named = (id: string): ProductRef =>
      held.products.find((p) => p.productId === id) ?? { productId: id, code: '', name: 'Medicine', strength: '', form: '', schedule: 'NONE', purchaseUnit: '', issueUnit: '', conversion: 1 };
    this.cart.set(
      cart.lines.map((l) => ({
        product: named(l.productId),
        qty: String(l.qty),
        prescribed: l.prescribedProductId ? named(l.prescribedProductId) : null,
        substitution: l.substitution ?? null,
        allergyOverride: l.allergyOverride ?? null,
        h1Override: l.h1Override ?? null,
      })),
    );
    this.discountRequest.set(null);
    if (cart.discountRequestId) {
      const resumedStore = this.context.workingStoreId();
      if (resumedStore) {
        this.api.discountRequest(resumedStore, cart.discountRequestId).subscribe({
          next: (answer) => this.discountRequest.set(answer),
          error: () => this.discountRequest.set(null),
        });
      }
    }
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    const seq = ++this.quoteSeq;
    this.api.quoteSale(storeId, cart).subscribe({
      next: (answer) => {
        if (seq !== this.quoteSeq) {
          return;
        }
        this.quote.set(answer);
        // The quote knows the product names; the cart rows take them from it.
        this.cart.update((rows) =>
          rows.map((r, i) => {
            const line = answer.lines.find((l) => (l.inputIndex ?? 0) === i);
            return line ? { ...r, product: line.product } : r;
          }),
        );
        this.notice.set(
          answer.summary.netPayablePaise !== held.heldNetPaise
            ? 'Prices or batches changed. Check the bill and save again.'
            : 'Held bill resumed.',
        );
      },
      error: (error: unknown) => this.quoteError.set(saveErrorMessage(error)),
    });
  }

  protected discard(row: HeldBillRow): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.api.heldBill(storeId, row.id).subscribe({
      next: (held) =>
        this.api.discardHeldBill(storeId, row.id, { version: held.version }).subscribe({
          next: () => {
            this.notice.set('Held bill discarded.');
            this.loadHeld();
          },
          error: (error: unknown) => this.formError.set(saveErrorMessage(error)),
        }),
      error: (error: unknown) => this.formError.set(loadErrorMessage(error)),
    });
  }

  protected print(): void {
    window.print();
  }

  /** Cancel or "New sale": nothing is saved; the cart and the payment amounts are cleared. */
  protected reset(): void {
    const pending = this.discountRequest();
    const storeId = this.context.workingStoreId();
    if (pending?.status === 'PENDING' && pending.requestId && storeId && this.saved() === null) {
      this.api.withdrawDiscountRequest(storeId, pending.requestId).subscribe({ error: () => undefined });
    }
    this.discountRequest.set(null);
    this.discountError.set(null);
    this.substituteIndex.set(null);
    this.checkFor.set(null);
    this.cart.set([]);
    this.quote.set(null);
    this.discount.set('');
    this.amounts.set({});
    this.heldBillId.set(null);
    this.heldNet.set(null);
    this.saved.set(null);
    this.customer.set(null);
    this.patient.set(null);
    this.buyerMode.set('NONE');
    this.walkInName.set('');
    this.walkInPhone.set('');
    this.formError.set(null);
    this.payError.set(null);
    this.quoteError.set(null);
    this.notice.set(null);
    this.saveKey = newClientKey();
  }
}
