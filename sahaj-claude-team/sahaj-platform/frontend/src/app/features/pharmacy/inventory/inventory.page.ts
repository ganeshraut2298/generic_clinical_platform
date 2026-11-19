import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { PageHeadComponent } from '../../../shared/ui/page-head.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { ApprovalsInboxSection } from './approvals-inbox.section';
import { BatchFocus, BatchInventorySection } from './batch-inventory.section';
import { IndentsSection } from '../indent/indents.section';
import { WaitingForActionSection } from '../indent/waiting-for-action.section';
import { PurchaseOrdersSection } from '../purchase/purchase-orders.section';
import { ReceivingSection } from '../purchase/receiving.section';
import { VendorReturnsSection } from '../purchase/vendor-returns.section';
import { StockAdjustmentsSection } from './stock-adjustments.section';
import { StockLedgerSection } from './stock-ledger.section';
import { StoreStockSection } from './store-stock.section';

export type InventorySectionKey =
  | 'batches'
  | 'indents'
  | 'waiting'
  | 'store-stock'
  | 'adjustments'
  | 'purchase-orders'
  | 'receiving'
  | 'vendor-returns'
  | 'approvals'
  | 'ledger';

interface InventorySection {
  key: InventorySectionKey;
  label: string;
}

/**
 * Inventory (SAH-79 work package D; mockup 06, 11 and 14 name the screens, the menu of the mockup names the sections).
 * The section tabs are drawn from what the server's context lists, in the order of the mockup menu: "Batch inventory
 * & expiry" and "Store stock" for a person who can read stock, "Stock adjustments" for a person who can adjust the
 * working store, "Approvals inbox" for an owner, HOD or current delegate, and "Stock ledger" for a person with stock
 * reports. Nothing the person cannot use is drawn, and nothing is greyed out. Sections of the later work packages
 * (purchase orders, receive stock, indents, transfers, audit, reports) are added here when they are released.
 */
@Component({
  selector: 'app-inventory',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    EmptyStateComponent,
    PageHeadComponent,
    BatchInventorySection,
    StoreStockSection,
    StockAdjustmentsSection,
    ApprovalsInboxSection,
    StockLedgerSection,
    PurchaseOrdersSection,
    ReceivingSection,
    VendorReturnsSection,
    IndentsSection,
    WaitingForActionSection,
  ],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .tabpanel {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .sectab {
      min-height: 44px;
    }
  `,
  template: `
    @if (!store.canInventory()) {
      <app-page-head title="Inventory" />
      <app-empty data-testid="inventory-no-access">You do not have access to Inventory. Ask your owner.</app-empty>
    } @else {
      <app-page-head title="Inventory" [lead]="'Unit: ' + store.unitName()" />

      <nav class="tabs" role="tablist" aria-label="Inventory sections">
        @for (section of sections(); track section.key) {
          <button
            type="button"
            role="tab"
            class="sectab"
            [id]="'inv-tab-' + section.key"
            [attr.aria-controls]="'inv-panel-' + section.key"
            [attr.aria-selected]="active() === section.key"
            [attr.tabindex]="active() === section.key ? 0 : -1"
            [attr.data-testid]="'inv-section-' + section.key"
            (click)="picked.set(section.key)"
            (keydown)="onTabKeydown($event)"
          >
            {{ section.label }}
          </button>
        }
      </nav>

      @for (section of sections(); track section.key) {
        @if (active() === section.key) {
          <div
            role="tabpanel"
            class="tabpanel"
            [id]="'inv-panel-' + section.key"
            [attr.aria-labelledby]="'inv-tab-' + section.key"
          >
            @switch (section.key) {
              @case ('batches') {
                <app-batch-inventory [focus]="focus()" />
              }
              @case ('store-stock') {
                <app-store-stock (batchesRequested)="openBatches($event)" />
              }
              @case ('adjustments') {
                <app-stock-adjustments />
              }
              @case ('purchase-orders') {
                <app-purchase-orders (approvalsRequested)="picked.set('approvals')" />
              }
              @case ('receiving') {
                <app-receiving (approvalsRequested)="picked.set('approvals')" />
              }
              @case ('vendor-returns') {
                <app-vendor-returns (approvalsRequested)="picked.set('approvals')" />
              }
              @case ('indents') {
                <app-indents (approvalsRequested)="picked.set('approvals')" />
              }
              @case ('waiting') {
                <app-waiting-for-action />
              }
              @case ('approvals') {
                <app-approvals-inbox />
              }
              @case ('ledger') {
                <app-stock-ledger />
              }
            }
          </div>
        }
      }
    }
  `,
})
export class InventoryPage {
  protected readonly store = inject(PharmacyContextStore);

  /** The order of the mockup menu; a section is listed only when the context lists what it needs. */
  protected readonly sections = computed<readonly InventorySection[]>(() => [
    ...(this.store.canReadStock() ? [{ key: 'batches' as const, label: 'Batch inventory & expiry' }] : []),
    ...(this.store.canReadStock() ? [{ key: 'store-stock' as const, label: 'Store stock' }] : []),
    ...(this.store.canAdjustStock() ? [{ key: 'adjustments' as const, label: 'Stock adjustments' }] : []),
    ...(this.store.canPurchase() ? [{ key: 'purchase-orders' as const, label: 'Purchase orders' }] : []),
    ...(this.store.canPurchase() ? [{ key: 'receiving' as const, label: 'Receive stock (GRN)' }] : []),
    ...(this.store.canPurchase() ? [{ key: 'vendor-returns' as const, label: 'Vendor return & debit note' }] : []),
    ...(this.store.canRaiseIndent() || this.store.canDispatch() || this.store.canReceive()
      ? [{ key: 'indents' as const, label: 'Ward / store indents' }]
      : []),
    ...(this.store.canAcceptReturn() || this.store.canReceive()
      ? [{ key: 'waiting' as const, label: 'Waiting for action' }]
      : []),
    ...(this.store.canApprovalsInbox() ? [{ key: 'approvals' as const, label: 'Approvals inbox' }] : []),
    ...(this.store.canStockReports() ? [{ key: 'ledger' as const, label: 'Stock ledger' }] : []),
  ]);

  protected readonly focus = signal<BatchFocus | null>(null);
  protected readonly picked = signal<InventorySectionKey | null>(null);

  /** The first listed section until the person picks one; a person with no stock access lands on the inbox. */
  protected readonly active = computed<InventorySectionKey>(
    () => this.picked() ?? this.sections()[0]?.key ?? 'approvals',
  );

  /** A store name of "Store stock" opens that store's batches for the product (79-3). */
  protected openBatches(focus: BatchFocus): void {
    this.focus.set(focus);
    this.picked.set('batches');
  }

  protected onTabKeydown(event: KeyboardEvent): void {
    const keys = this.sections().map((s) => s.key);
    const index = keys.indexOf(this.active());
    let next = index;
    if (event.key === 'ArrowRight') {
      next = (index + 1) % keys.length;
    } else if (event.key === 'ArrowLeft') {
      next = (index - 1 + keys.length) % keys.length;
    } else if (event.key === 'Home') {
      next = 0;
    } else if (event.key === 'End') {
      next = keys.length - 1;
    } else {
      return;
    }
    event.preventDefault();
    this.picked.set(keys[next]);
    queueMicrotask(() => document.getElementById('inv-tab-' + keys[next])?.focus());
  }
}
