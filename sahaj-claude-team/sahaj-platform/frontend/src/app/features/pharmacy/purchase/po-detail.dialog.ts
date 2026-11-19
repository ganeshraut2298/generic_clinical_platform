import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { newClientKey } from '../../../shared/client-key';
import { formatDisplayDate, formatDisplayDateTimeOfInstant, istToday } from '../../../shared/format/ist-date';
import { provideIsoDateAdapter } from '../../../shared/format/iso-date-adapter';
import { IconComponent } from '../../../shared/icon/icon.component';
import { actingAsText, historyEventText } from '../inventory/inventory.util';
import { rateText, rupees } from '../opening-stock/opening-stock.util';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { PurchaseOrder } from '../pharmacy-buying.model';
import { ActiveReason } from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { openPrintView, purchaseOrderPrintHtml } from './print.util';
import { overdueText, poStatusChip } from './purchase.util';

export interface PoDetailData {
  storeId: string;
  poId: string;
}

/** What the list does after the sheet closes. */
export interface PoDetailResult {
  changed: boolean;
  /** `edit` opens the draft form; `approvals` opens the Approvals inbox tab; `open` shows another purchase order. */
  next: 'edit' | 'approvals' | { open: string } | null;
}

export const PO_DETAIL_CONFIG = {
  panelClass: 'sheet-panel',
  width: '820px',
  maxWidth: '100vw',
  ariaLabelledBy: 'po-detail-title',
} as const;

type Panel = 'cancel' | 'close-short' | 'sent' | 'discard' | null;

/**
 * The purchase order card (story 79-6, mockup 07 "PO detail"): "<PO number> · <vendor> <status chip>", Item, Qty,
 * Rate, Amount, "Total incl. GST", the approval rule note, "Delivery expected <date> · credit <n> days", the receipts
 * and the History card. The actions follow the status: a Draft is edited, submitted or discarded; an approved order
 * is printed (the browser print view) and marked as sent; a submitted order that has received nothing is cancelled
 * with a reason; a partly received order is closed short; a rejected order is copied into a new draft. There is no
 * WhatsApp or e-mail button (OQ-79-32). The server decides every status change; nothing here changes a status alone.
 */
@Component({
  selector: 'app-po-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDatepickerModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    IconComponent,
  ],
  providers: [provideIsoDateAdapter()],
  styles: [
    `
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      .num {
        text-align: right;
        white-space: nowrap;
      }
      .scroll {
        overflow-x: auto;
      }
      table {
        min-width: 560px;
      }
      .total {
        font-weight: 700;
      }
      .reasons {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
      }
      .reasons button {
        min-height: 44px;
        padding: 8px 12px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
        background: var(--glass-hi);
        font-weight: 600;
        font-size: 0.86rem;
      }
      .reasons button[aria-pressed='true'] {
        border: 2px solid var(--pri);
        padding: 7px 11px;
        background: var(--pri-soft);
        color: var(--pri-text);
      }
      ol.steps {
        margin: 4px 0;
        padding-left: 18px;
      }
      .actions {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        @if (po(); as p) {
          <h2 id="po-detail-title" data-testid="po-title">
            {{ p.number ?? 'Draft purchase order' }} · {{ p.vendor.name }}
            <span class="pill" [class]="chip().css" data-testid="po-status">{{ chip().text }}</span>
          </h2>
        } @else {
          <h2 id="po-detail-title">Purchase order</h2>
        }
        <p>Central Store</p>
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      @if (loading()) {
        <p class="loading" role="status">Loading purchase order</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="po-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else if (po(); as p) {
        @if (p.vendor.blocked) {
          <div class="notice warn" role="status" data-testid="po-vendor-blocked">
            <app-icon name="alert" [size]="16" />
            <span>Blocked · licence expired. This vendor cannot get a new purchase order.</span>
          </div>
        }

        <div class="scroll">
          <table data-testid="po-lines">
            <thead>
              <tr>
                <th scope="col">Item</th>
                <th scope="col" class="num">Qty</th>
                <th scope="col" class="num">Rate</th>
                <th scope="col" class="num">Amount</th>
                <th scope="col" class="num">Received</th>
              </tr>
            </thead>
            <tbody>
              @for (line of p.lines; track line.id) {
                <tr>
                  <td>
                    {{ line.product.name }}
                    <div class="sub">{{ line.product.strength }} · {{ line.product.form }}</div>
                  </td>
                  <td class="num">{{ line.qty }} {{ line.product.purchaseUnit }}</td>
                  <td class="num">{{ rate(line.rate) }}</td>
                  <td class="num">{{ money(line.amountPaise) }}</td>
                  <td class="num">
                    {{ line.receivedQty }}
                    @if (line.closedShort) {
                      <div class="sub">Closed short</div>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="total" data-testid="po-total">Total incl. GST {{ money(p.totalPaise) }}</p>
        <p class="sub">Subtotal {{ money(p.subtotalPaise) }} · GST {{ money(p.gstPaise) }}</p>
        @if (p.ruleNote; as note) {
          <p class="sub" data-testid="po-rule-note">{{ note }}</p>
        }
        <p data-testid="po-delivery">
          Delivery expected {{ p.deliveryExpected ? date(p.deliveryExpected) : 'not set' }} · credit
          {{ p.creditDays }} days
          @if (overdue(); as text) {
            <span class="pill p-crit" data-testid="po-overdue">{{ text }}</span>
          }
        </p>
        @if (p.sentOn) {
          <p class="sub" data-testid="po-sent-on">Sent to vendor on {{ date(p.sentOn) }}</p>
        }

        @if (p.approval; as a) {
          <div class="notice teal" role="status" data-testid="po-approval">
            <app-icon name="lock" [size]="16" />
            <span>
              {{ a.ruleText }}
              @if (a.status === 'PENDING') {
                · Waiting for {{ a.waitingFor === 'OWNER' ? 'owner' : 'HOD' }} approval
              } @else if (a.status === 'REJECTED' && a.rejectReason) {
                · Rejected: {{ a.rejectReason.label }}
              }
            </span>
            @if (a.status === 'PENDING') {
              <button mat-stroked-button type="button" data-testid="po-open-approvals" (click)="finish('approvals')">
                Open in approvals
              </button>
            }
          </div>
        }

        @if (p.receipts.length > 0) {
          <h3>Receipts</h3>
          <ul data-testid="po-receipts">
            @for (r of p.receipts; track r.grnId) {
              <li>{{ r.number }} · {{ when(r.postedAt) }}</li>
            }
          </ul>
        }

        @switch (panel()) {
          @case ('cancel') {
            <div class="notice" data-testid="po-cancel-panel">
              <span>Cancel this purchase order. It keeps its number and shows Cancelled. Choose a reason.</span>
            </div>
            <div class="reasons" role="group" aria-label="Reason">
              @for (r of reasons(); track r.reasonKey) {
                <button
                  type="button"
                  [attr.aria-pressed]="reasonKey() === r.reasonKey"
                  [attr.data-testid]="'po-reason-' + r.reasonKey"
                  (click)="reasonKey.set(r.reasonKey)"
                >
                  {{ r.label }}
                </button>
              }
            </div>
          }
          @case ('close-short') {
            <div class="notice" data-testid="po-close-short-panel">
              <span>Close the open lines short. The order then shows Received with the number of short lines. Choose a reason.</span>
            </div>
            <div class="reasons" role="group" aria-label="Reason">
              @for (r of reasons(); track r.reasonKey) {
                <button
                  type="button"
                  [attr.aria-pressed]="reasonKey() === r.reasonKey"
                  [attr.data-testid]="'po-reason-' + r.reasonKey"
                  (click)="reasonKey.set(r.reasonKey)"
                >
                  {{ r.label }}
                </button>
              }
            </div>
          }
          @case ('sent') {
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Sent on</mat-label>
              <input
                matInput
                [matDatepicker]="sentPicker"
                [formControl]="sentOn"
                placeholder="24 Oct 2026"
                autocomplete="off"
                data-testid="po-sent-date"
              />
              <mat-datepicker-toggle matIconSuffix [for]="sentPicker" aria-label="Choose the date sent" />
              <mat-datepicker #sentPicker />
            </mat-form-field>
          }
          @case ('discard') {
            <div class="notice warn" data-testid="po-discard-panel">
              <span>Discard this draft? It has no number and no effect, and it leaves the list.</span>
            </div>
          }
        }

        @if (panelError(); as text) {
          <div class="notice err" role="alert" data-testid="po-action-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }

        <h3>History</h3>
        <ol class="steps" data-testid="po-history">
          @for (h of p.history; track h.at + h.event) {
            <li>
              {{ eventText(h.event) }} · {{ h.by?.name ?? 'System' }}{{ h.actingAs ? ' (' + acting(h.actingAs) + ')' : '' }}
              · {{ when(h.at) }}{{ h.reason ? ' · ' + h.reason.label : '' }}
            </li>
          }
        </ol>
      }
    </div>

    <div class="sheet-f">
      @if (po(); as p) {
        <div class="actions">
          @if (panel() !== null) {
            <button mat-stroked-button type="button" data-testid="po-panel-back" (click)="closePanel()">Back</button>
            <button
              mat-flat-button
              type="button"
              data-testid="po-panel-confirm"
              [disabled]="busy() || !panelReady()"
              (click)="confirmPanel()"
            >
              {{ confirmLabel() }}
            </button>
          } @else {
            @if (p.status === 'DRAFT') {
              <button mat-stroked-button type="button" data-testid="po-edit" (click)="finish('edit')">Edit</button>
              <button mat-stroked-button type="button" data-testid="po-discard" (click)="open('discard')">Discard</button>
              <button mat-flat-button type="button" data-testid="po-submit" [disabled]="busy()" (click)="submit()">
                Submit
              </button>
            }
            @if (p.status === 'APPROVED' || p.status === 'SENT') {
              <button mat-stroked-button type="button" data-testid="po-print" (click)="print()">Print purchase order</button>
            }
            @if (p.status === 'APPROVED') {
              <button mat-flat-button type="button" data-testid="po-mark-sent" (click)="open('sent')">Mark as sent</button>
            }
            @if (canCancel()) {
              <button mat-stroked-button type="button" data-testid="po-cancel" (click)="open('cancel')">
                Cancel purchase order
              </button>
            }
            @if (p.status === 'PARTLY_RECEIVED') {
              <button mat-stroked-button type="button" data-testid="po-close-short" (click)="open('close-short')">
                Close short
              </button>
            }
            @if (p.status === 'REJECTED') {
              <button mat-flat-button type="button" data-testid="po-copy" [disabled]="busy()" (click)="copy()">
                Copy into a new draft
              </button>
            }
            <button mat-stroked-button type="button" (click)="close()">Close</button>
          }
        </div>
      } @else {
        <button mat-stroked-button type="button" (click)="close()">Close</button>
      }
    </div>
  `,
})
export class PoDetailDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<PoDetailDialog, PoDetailResult>>(MatDialogRef);
  protected readonly data = inject<PoDetailData>(MAT_DIALOG_DATA);

  protected readonly po = signal<PurchaseOrder | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly panel = signal<Panel>(null);
  protected readonly panelError = signal<string | null>(null);
  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly reasonKey = signal<string>('');
  protected readonly sentOn = new FormControl<string | null>(istToday());

  private changed = false;
  private submitKey = newClientKey();
  private copyKey = newClientKey();

  protected readonly chip = computed(() => {
    const p = this.po();
    return p ? poStatusChip(p.status, p.shortLineCount ?? 0) : { text: '', css: '' };
  });
  protected readonly overdue = computed(() => overdueText(this.po()?.overdueDays ?? null));

  /** Cancel is offered for a submitted order that has received nothing; the server checks again. */
  protected readonly canCancel = computed(() => {
    const p = this.po();
    return (
      p !== null &&
      (p.status === 'WAITING_APPROVAL' || p.status === 'APPROVED' || p.status === 'SENT') &&
      p.lines.every((l) => l.receivedQty === 0)
    );
  });

  protected readonly money = rupees;
  protected readonly rate = rateText;
  protected readonly date = formatDisplayDate;
  protected readonly when = (instant: string) => formatDisplayDateTimeOfInstant(instant);
  protected readonly eventText = historyEventText;
  protected readonly acting = actingAsText;

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.purchaseOrder(this.data.storeId, this.data.poId).subscribe({
      next: (po) => {
        this.po.set(po);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  protected close(): void {
    this.ref.close({ changed: this.changed, next: null });
  }

  protected finish(next: 'edit' | 'approvals'): void {
    this.ref.close({ changed: this.changed, next });
  }

  protected print(): void {
    const p = this.po();
    if (p) {
      openPrintView(purchaseOrderPrintHtml(p));
    }
  }

  protected open(panel: Panel): void {
    this.panelError.set(null);
    this.reasonKey.set('');
    this.panel.set(panel);
    if ((panel === 'cancel' || panel === 'close-short') && this.reasons().length === 0) {
      this.api.activeReasons(['approval-reject']).subscribe({
        next: (answer) =>
          this.reasons.set(answer.lists.find((l) => l.listKey === 'approval-reject')?.reasons ?? []),
        error: (error: unknown) => this.panelError.set(loadErrorMessage(error)),
      });
    }
  }

  protected closePanel(): void {
    this.panel.set(null);
    this.panelError.set(null);
  }

  protected panelReady(): boolean {
    switch (this.panel()) {
      case 'cancel':
      case 'close-short':
        return this.reasonKey() !== '';
      case 'sent':
        return !!this.sentOn.value;
      default:
        return true;
    }
  }

  protected confirmLabel(): string {
    switch (this.panel()) {
      case 'cancel':
        return 'Cancel purchase order';
      case 'close-short':
        return 'Close short';
      case 'sent':
        return 'Mark as sent';
      default:
        return 'Discard draft';
    }
  }

  protected submit(): void {
    const p = this.po();
    if (!p) {
      return;
    }
    this.run(this.api.submitPurchaseOrder(this.data.storeId, p.id, { clientKey: this.submitKey, version: p.version }));
  }

  protected copy(): void {
    const p = this.po();
    if (!p || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.panelError.set(null);
    this.api.copyPurchaseOrder(this.data.storeId, p.id, { clientKey: this.copyKey }).subscribe({
      next: (draft) => {
        this.busy.set(false);
        this.ref.close({ changed: true, next: { open: draft.id } });
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.panelError.set(saveErrorMessage(error));
      },
    });
  }

  protected confirmPanel(): void {
    const p = this.po();
    const panel = this.panel();
    if (!p || panel === null || !this.panelReady()) {
      return;
    }
    const storeId = this.data.storeId;
    switch (panel) {
      case 'cancel':
        this.run(this.api.cancelPurchaseOrder(storeId, p.id, { version: p.version, reasonKey: this.reasonKey() }));
        break;
      case 'close-short':
        this.run(this.api.closePurchaseOrderShort(storeId, p.id, { version: p.version, reasonKey: this.reasonKey() }));
        break;
      case 'sent':
        this.run(this.api.markPurchaseOrderSent(storeId, p.id, { version: p.version, sentOn: this.sentOn.value ?? '' }));
        break;
      case 'discard':
        this.run(this.api.discardPurchaseOrder(storeId, p.id, { version: p.version }), true);
        break;
    }
  }

  private run(request: ReturnType<PharmacyApi['purchaseOrder']>, closeAfter = false): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.panelError.set(null);
    request.subscribe({
      next: (po) => {
        this.busy.set(false);
        this.changed = true;
        this.panel.set(null);
        this.submitKey = newClientKey();
        if (closeAfter) {
          this.ref.close({ changed: true, next: null });
          return;
        }
        this.po.set(po);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.panelError.set(saveErrorMessage(error));
      },
    });
  }
}
