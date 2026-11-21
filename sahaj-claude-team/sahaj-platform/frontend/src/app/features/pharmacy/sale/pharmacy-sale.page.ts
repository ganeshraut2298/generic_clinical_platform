import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { formatTimeOfInstant, istToday } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { PageHeadComponent } from '../../../shared/ui/page-head.component';
import { rupees } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { BillMark, BillPage } from '../pharmacy-sale.model';
import { PharmacyApi } from '../pharmacy.api';
import { BillDetailSection } from './bill-detail.section';
import { CommandCentreSection } from './command-centre.section';
import { OpdQueueSection } from './opd-queue.section';
import { PosSection } from './pos.section';
import { ReturnsWaitingSection } from './returns-waiting.section';
import { RegistersSection } from './registers.section';
import { SalesReturnSection } from './sales-return.section';

type SaleSectionKey = 'home' | 'pos' | 'dispensing' | 'return' | 'registers';

const MARK_TEXT: Record<BillMark, string> = {
  LINK_PENDING: 'Link to patient pending',
  ALLERGY_NOT_CHECKED: 'Allergy not checked',
  REFUND_DUE: 'Refund due',
  ALLERGY_OVERRIDE: 'Allergy override',
  H1_OVERRIDE: 'Schedule H1 override',
  SUBSTITUTED: 'Substituted',
  HAS_RETURNS: 'Has returns',
};

/**
 * The Pharmacy tab (SAH-80-1, work package I; mockup 01 and 02). Its sections are drawn from what the server's context
 * lists: "Command Center" (the home: today's bills and "+ New bill"), "POS billing" for a person who can sell at the
 * working store, and "OPD / IPD dispensing", which says "Not connected" while the OPD and IPD ports are not connected.
 * The Command Center shows the tiles of the command-centre operation (work package N). A saved bill opens from "Today's
 * bills" or by its number, with "Cancel bill" and the refund of a cancelled one.
 */
@Component({
  selector: 'app-pharmacy-sale',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    IconComponent,
    EmptyStateComponent,
    PageHeadComponent,
    BillDetailSection,
    CommandCentreSection,
    OpdQueueSection,
    PosSection,
    RegistersSection,
    ReturnsWaitingSection,
    SalesReturnSection,
  ],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .sectab {
      min-height: 44px;
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
    }
    table {
      min-width: 640px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
  `,
  template: `
    <app-page-head title="Pharmacy" [lead]="'Unit: ' + unit()" />
    <nav class="tabs" role="tablist" aria-label="Pharmacy sections">
      @for (s of sections(); track s.key) {
        <button
          type="button"
          role="tab"
          class="sectab"
          [attr.aria-selected]="active() === s.key"
          [attr.data-testid]="'sale-section-' + s.key"
          (click)="picked.set(s.key)"
        >
          {{ s.label }}
        </button>
      }
    </nav>

    @switch (active()) {
      @case ('pos') {
        @if (store.canSell()) {
          <app-pos />
        } @else {
          <app-empty data-testid="pos-no-access">You cannot sell at this store. Pick a store where you can.</app-empty>
        }
      }
      @case ('return') {
        @if (store.canSalesReturn()) {
          <app-returns-waiting />
          <app-sales-return />
        } @else {
          <app-empty data-testid="return-no-access">You cannot take returns at this store. Pick a store where you can.</app-empty>
        }
      }
      @case ('registers') {
        <app-registers />
      }
      @case ('dispensing') {
        <app-opd-queue />
      }
      @default {
        @if (store.canCommandCentre()) {
          <app-command-centre (newBill)="picked.set('pos')" (openRegisters)="picked.set('registers')" (openDispensing)="picked.set('dispensing')" />
        } @else if (store.canPos() && store.canSell()) {
          <div class="head">
            <h2>Pharmacy Command Center</h2>
            <button mat-flat-button type="button" data-testid="new-bill" (click)="picked.set('pos')">+ New bill</button>
          </div>
        }
        <section class="panel surf" aria-labelledby="bills-h" data-testid="command-centre">
          <h3 id="bills-h">Today's bills</h3>
          <form class="head" (submit)="findBill($event)">
            <label>
              Find a bill by number
              <input class="field" maxlength="20" data-testid="bill-number" [value]="billNumber()" (input)="billNumber.set($any($event.target).value)" />
            </label>
            <button mat-stroked-button type="submit" data-testid="bill-find">Find bill</button>
          </form>
          @if (findNote(); as text) {
            <p class="note" role="alert" data-testid="bill-find-note">{{ text }}</p>
          }
          @if (openBillId(); as id) {
            <app-bill-detail [billId]="id" (changed)="ngOnInit()" />
            <button mat-stroked-button type="button" data-testid="bill-close" (click)="openBillId.set(null)">Close the bill</button>
          }
          @if (loadError(); as text) {
            <div class="notice err" role="alert" data-testid="bills-error">
              <app-icon name="alert" [size]="16" />
              <span>{{ text }}</span>
              <button mat-stroked-button type="button" (click)="ngOnInit()">Try again</button>
            </div>
          } @else if (bills(); as b) {
            @if (b.items.length === 0) {
              <app-empty data-testid="bills-empty">No bills today.</app-empty>
            } @else {
              <div class="tbl-wrap">
                <table data-testid="bills-table">
                  <thead>
                    <tr>
                      <th scope="col">Bill</th>
                      <th scope="col">Time</th>
                      <th scope="col">Buyer</th>
                      <th scope="col" class="num">Items</th>
                      <th scope="col" class="num">Net payable</th>
                      <th scope="col">Status</th>
                      <th scope="col"><span class="sr-only">Open</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (row of b.items; track row.id) {
                      <tr [attr.data-testid]="'bill-' + row.billNo">
                        <td>{{ row.billNo }}</td>
                        <td>{{ time(row.savedAt) }}</td>
                        <td>{{ row.buyerLabel }}</td>
                        <td class="num">{{ row.itemCount }}</td>
                        <td class="num">{{ money(row.netPayablePaise) }}</td>
                        <td>
                          {{ row.status === 'CANCELLED' ? 'Cancelled' : 'Saved' }}
                          @for (m of row.marks; track m) {
                            <span class="pill p-wait">{{ marks[m] }}</span>
                          }
                        </td>
                        <td>
                          <button mat-button type="button" [attr.data-testid]="'open-' + row.billNo" (click)="openBillId.set(row.id)">Open</button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          }
        </section>
      }
    }
  `,
})
export class PharmacySalePage implements OnInit {
  protected readonly store = inject(PharmacyContextStore);
  private readonly api = inject(PharmacyApi);

  protected readonly picked = signal<SaleSectionKey | null>(null);
  protected readonly bills = signal<BillPage | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly openBillId = signal<string | null>(null);
  protected readonly billNumber = signal('');
  protected readonly findNote = signal<string | null>(null);

  protected readonly marks = MARK_TEXT;
  protected readonly money = rupees;
  protected readonly time = (instant: string) => formatTimeOfInstant(instant);
  protected readonly unit = computed(() => this.store.unitName());

  protected readonly sections = computed<{ key: SaleSectionKey; label: string }[]>(() => [
    { key: 'home', label: 'Command Center' },
    ...(this.store.canPos() && this.store.canSell() ? [{ key: 'pos' as const, label: 'POS billing' }] : []),
    ...(this.store.canOpdQueue() || this.store.canIpdQueue() ? [{ key: 'dispensing' as const, label: 'OPD / IPD dispensing' }] : []),
    ...(this.store.canSalesReturn() ? [{ key: 'return' as const, label: 'Sales return' }] : []),
    ...(this.store.canRegisters() || this.store.canCompliance() ? [{ key: 'registers' as const, label: 'Registers & compliance' }] : []),
  ]);
  protected readonly active = computed<SaleSectionKey>(() => this.picked() ?? 'home');

  protected findBill(event: Event): void {
    event.preventDefault();
    const storeId = this.store.workingStoreId();
    const number = this.billNumber().trim();
    this.findNote.set(null);
    if (!storeId || number === '') {
      return;
    }
    this.api.bills(storeId, { number, size: 1 }).subscribe({
      next: (page) => {
        if (page.items.length === 0) {
          this.findNote.set('No bill found. Check the number.');
          return;
        }
        this.openBillId.set(page.items[0].id);
      },
      error: (error: unknown) => this.findNote.set(loadErrorMessage(error)),
    });
  }

  ngOnInit(): void {
    const storeId = this.store.workingStoreId();
    this.loadError.set(null);
    if (!storeId || !this.store.canSell()) {
      this.bills.set({ items: [], total: 0, page: 0, size: 25 });
      return;
    }
    this.api.bills(storeId, { date: istToday(), size: 25 }).subscribe({
      next: (page) => this.bills.set(page),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
  }
}
