import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { newClientKey } from '../../../shared/client-key';
import { formatDisplayDateOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { pinProblem } from '../indent/indent.util';
import { SecondSignerComponent } from '../indent/second-signer.component';
import { batchWithExpiry } from '../indent/indent.util';
import { rupees } from '../opening-stock/opening-stock.util';
import {
  AcceptSalesReturnInput,
  SALES_RETURN_STATUS_LABELS,
  SalesReturn,
  SalesReturnPage,
} from '../pharmacy-compliance.model';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { SecondSignerCandidate } from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';

type Decision = 'ACCEPT' | 'ACCEPT_QUARANTINE' | 'REFUSE';

/**
 * Returns waiting for the pharmacist (SAH-80-15 and 80-18; P&I mockup 03, tab "Returns", card "Returns waiting to be
 * accepted", with "Accept"; and mockup 04, a cashier's return "Waiting for the pharmacist"). A cashier's return, or one
 * with a damaged or cut strip, waits here; no money moves until a pharmacist, the store approver or the owner accepts the
 * goods. "Accept return" shows each line: sealed goods go back to Available, doubtful goods to Quarantine or are
 * refused, and a refused line lowers the refund. A Schedule X line needs the second signature. Above the unit's limit the
 * return then goes for approval; the server decides and this screen says which.
 */
@Component({
  selector: 'app-returns-waiting',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, SecondSignerComponent],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    table {
      min-width: 640px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    fieldset {
      border: 0;
      padding: 0;
      margin: 0;
      display: flex;
      gap: 12px;
    }
    fieldset label {
      display: flex;
      align-items: center;
      gap: 6px;
      min-height: 44px;
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="rw-h" data-testid="returns-waiting">
      <h2 id="rw-h">Waiting for the pharmacist</h2>
      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="rw-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else if (page(); as p) {
        @if (p.items.length === 0) {
          <p class="note" data-testid="rw-empty">No returns are waiting.</p>
        } @else {
          <div class="tbl-wrap">
            <table data-testid="rw-table">
              <thead>
                <tr>
                  <th scope="col">Bill</th>
                  <th scope="col">Buyer</th>
                  <th scope="col">Date</th>
                  <th scope="col">Started by</th>
                  <th scope="col" class="num">Refund</th>
                  <th scope="col"><span class="sr-only">Action</span></th>
                </tr>
              </thead>
              <tbody>
                @for (row of p.items; track row.id) {
                  <tr [attr.data-testid]="'rw-row-' + row.billNo">
                    <td>{{ row.billNo }}</td>
                    <td>{{ row.buyerLabel }}</td>
                    <td>{{ date(row.at) }}</td>
                    <td>{{ row.startedBy.name }}</td>
                    <td class="num">{{ money(row.refundTotalPaise) }}</td>
                    <td>
                      @if (canAccept()) {
                        <button mat-stroked-button type="button" [attr.data-testid]="'rw-open-' + row.billNo" (click)="open(row.id)">Accept return</button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      } @else {
        <p class="loading" role="status">Loading</p>
      }

      @if (done(); as d) {
        <div class="notice teal" role="status" data-testid="rw-done">
          <app-icon name="check" [size]="16" />
          <span>{{ statusText(d) }}@if (d.creditNoteNo) { · Credit note {{ d.creditNoteNo }} } · Refund {{ money(d.refundTotalPaise) }}</span>
        </div>
      }

      @if (current(); as r) {
        <h3 data-testid="rw-detail">Return against bill {{ r.bill.billNo }}</h3>
        <div class="tbl-wrap">
          <table data-testid="rw-lines">
            <thead>
              <tr>
                <th scope="col">Item · batch</th>
                <th scope="col" class="num">Qty</th>
                <th scope="col">Condition</th>
                <th scope="col" class="num">Refund</th>
                <th scope="col">Decision</th>
              </tr>
            </thead>
            <tbody>
              @for (line of r.lines; track line.returnLineId) {
                <tr [attr.data-testid]="'rw-line-' + line.returnLineId">
                  <td>
                    {{ line.product.name }} · {{ batchText(line.batch.batchNo, line.batch.expiryDate) }}
                    @if (line.product.schedule === 'X') {
                      <span class="pill p-warn">Sch. X</span>
                    }
                  </td>
                  <td class="num">{{ line.qty }}</td>
                  <td>{{ line.condition.label }}</td>
                  <td class="num">{{ money(line.refundPaise) }}</td>
                  <td>
                    @if (isSealed(line.condition.reasonKey)) {
                      <span>Back to Available</span>
                    } @else {
                      <fieldset [attr.aria-label]="'Decision for ' + line.product.name">
                        <label>
                          <input type="radio" [name]="'d-' + line.returnLineId" [attr.data-testid]="'rw-quarantine-' + line.returnLineId" [checked]="decisionOf(line.returnLineId) === 'ACCEPT_QUARANTINE'" (change)="decide(line.returnLineId, 'ACCEPT_QUARANTINE')" />
                          Accept to Quarantine
                        </label>
                        <label>
                          <input type="radio" [name]="'d-' + line.returnLineId" [attr.data-testid]="'rw-refuse-' + line.returnLineId" [checked]="decisionOf(line.returnLineId) === 'REFUSE'" (change)="decide(line.returnLineId, 'REFUSE')" />
                          Refuse
                        </label>
                      </fieldset>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p data-testid="rw-refund">Refund {{ money(refundAfter()) }} by {{ refundModes() }}.</p>
        @if (hasScheduleX()) {
          <p class="notice warn" role="status" data-testid="rw-x-note">
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
        @if (formError(); as text) {
          <div class="notice err" role="alert" data-testid="rw-form-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }
        <div class="acts">
          <button mat-flat-button type="button" data-testid="rw-accept" [disabled]="saving() || !r.canAccept" (click)="accept(r)">Accept return</button>
          <button mat-stroked-button type="button" data-testid="rw-close" (click)="current.set(null)">Close</button>
        </div>
      }
    </section>
  `,
})
export class ReturnsWaitingSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  protected readonly page = signal<SalesReturnPage | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly current = signal<SalesReturn | null>(null);
  protected readonly done = signal<SalesReturn | null>(null);
  protected readonly decisions = signal<Record<string, Decision>>({});
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly signerId = signal('');
  protected readonly pin = signal('');
  protected readonly signerError = signal<string | null>(null);
  protected readonly pinError = signal<string | null>(null);

  protected readonly money = rupees;
  protected readonly batchText = batchWithExpiry;
  protected readonly date = (instant: string) => formatDisplayDateOfInstant(instant);
  protected readonly statusText = (r: SalesReturn) => SALES_RETURN_STATUS_LABELS[r.status];

  /** A pharmacist, the store approver or delegate, or the owner (acceptSalesReturn); a cashier only watches. */
  protected readonly canAccept = computed(() => this.context.workingStore()?.access.acceptSalesReturn === true);
  protected readonly hasScheduleX = computed(() => this.current()?.lines.some((l) => l.product.schedule === 'X') === true);

  private acceptKey = newClientKey();

  ngOnInit(): void {
    this.load();
    const storeId = this.context.workingStoreId();
    if (storeId && this.canAccept()) {
      this.api.secondSigners(storeId).subscribe({
        next: (people) => {
          this.signers.set(people);
          this.signersLoaded.set(true);
        },
        error: () => this.signersLoaded.set(true),
      });
    }
  }

  protected load(): void {
    const storeId = this.context.workingStoreId();
    this.loadError.set(null);
    if (!storeId) {
      return;
    }
    this.api.salesReturns(storeId, { status: 'WAITING_PHARMACIST', size: 25 }).subscribe({
      next: (p) => this.page.set(p),
      error: (e: unknown) => this.loadError.set(loadErrorMessage(e)),
    });
  }

  protected isSealed(key: string): boolean {
    return key.endsWith('.sealed');
  }

  protected decisionOf(lineId: string): Decision {
    return this.decisions()[lineId] ?? 'ACCEPT_QUARANTINE';
  }

  protected decide(lineId: string, decision: Decision): void {
    this.decisions.update((d) => ({ ...d, [lineId]: decision }));
  }

  /** The refund once the refused lines are taken out. */
  protected readonly refundAfter = computed(() => {
    const r = this.current();
    if (!r) {
      return 0;
    }
    return r.refundTotalPaise - this.refusedPaise(r);
  });

  protected readonly refundModes = computed(() => {
    const r = this.current();
    return r ? r.refunds.map((x) => x.mode.toLowerCase().replace('_', ' ')).join(', ') : '';
  });

  private refusedPaise(r: SalesReturn): number {
    return r.lines.filter((l) => !this.isSealed(l.condition.reasonKey) && this.decisionOf(l.returnLineId) === 'REFUSE').reduce((sum, l) => sum + l.refundPaise, 0);
  }

  /** Lowers the refunds from the last mode backwards until the refused amount is taken out. */
  private refundsAfter(r: SalesReturn): AcceptSalesReturnInput['refunds'] {
    let cut = this.refusedPaise(r);
    const out = r.refunds.map((x) => ({ ...x }));
    for (let i = out.length - 1; i >= 0 && cut > 0; i--) {
      const take = Math.min(out[i].amountPaise, cut);
      out[i].amountPaise -= take;
      cut -= take;
    }
    return out.filter((x) => x.amountPaise > 0);
  }

  protected open(id: string): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.done.set(null);
    this.formError.set(null);
    this.api.salesReturn(storeId, id).subscribe({
      next: (r) => {
        this.decisions.set({});
        this.current.set(r);
      },
      error: (e: unknown) => this.loadError.set(loadErrorMessage(e)),
    });
  }

  protected accept(r: SalesReturn): void {
    const storeId = this.context.workingStoreId();
    if (!storeId || this.saving()) {
      return;
    }
    this.formError.set(null);
    this.signerError.set(null);
    this.pinError.set(null);
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
      .acceptSalesReturn(storeId, r.id, {
        clientKey: this.acceptKey,
        version: r.version,
        decisions: r.lines.map((l) => ({
          returnLineId: l.returnLineId,
          decision: this.isSealed(l.condition.reasonKey) ? 'ACCEPT' : this.decisionOf(l.returnLineId),
        })),
        refunds: this.refundsAfter(r),
        secondSigner,
      })
      .subscribe({
        next: (answer) => {
          this.pin.set('');
          this.saving.set(false);
          this.acceptKey = newClientKey();
          this.current.set(null);
          this.done.set(answer);
          this.load();
        },
        error: (e: unknown) => {
          this.pin.set('');
          this.saving.set(false);
          this.formError.set(saveErrorMessage(e));
          if (problemSlug(e) === 'return-state' || problemSlug(e) === 'changed') {
            this.load();
          }
        },
      });
  }
}
