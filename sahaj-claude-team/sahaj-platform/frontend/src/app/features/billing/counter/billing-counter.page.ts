import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/http/problem-detail';
import { ShellStore } from '../../../core/shell/shell.store';
import { formatInr } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { Service } from '../../config/opd/opd.model';
import { OpdApi } from '../../config/opd/opd.api';
import { BillingApi } from '../billing.api';
import {
  Bill,
  BillSummary,
  BillingCounter,
  DISCOUNT_REASONS,
  MESSAGE_VIEW_ONLY,
  PAYMENT_MODES,
  PaymentMode,
  REFUND_REASONS,
} from '../billing.model';
import { DayClosingDialogComponent } from '../closing/day-closing-dialog.component';

const DIALOG_CONFIG = { panelClass: 'sheet-panel', width: '480px', maxWidth: '100vw' } as const;

/**
 * Billing counter (SAH-10), mockup `vBilling`: left list (Awaiting payment / Dues / Paid
 * today), right compose panel (items, discount, totals, payment mode, collect, receipt, refund).
 * Revenue/owner view and the UPI QR are out of scope for this story.
 */
@Component({
  selector: 'app-billing-counter',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
  ],
  styleUrl: './billing-counter.page.scss',
  templateUrl: './billing-counter.page.html',
})
export class BillingCounterPage implements OnInit {
  private readonly billingApi = inject(BillingApi);
  private readonly opdApi = inject(OpdApi);
  private readonly store = inject(ShellStore);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly formatInr = formatInr;
  protected readonly paymentModes = PAYMENT_MODES;
  protected readonly discountReasons = DISCOUNT_REASONS;
  protected readonly refundReasons = REFUND_REASONS;
  protected readonly messageViewOnly = MESSAGE_VIEW_ONLY;

  protected readonly counter = signal<BillingCounter | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly selectedVisitId = signal<string | null>(null);
  protected readonly bill = signal<Bill | null>(null);
  protected readonly billLoading = signal(false);
  protected readonly billError = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly services = signal<Service[]>([]);
  protected readonly addServiceId = signal<string | null>(null);
  protected readonly addServiceQty = signal(1);

  protected readonly discountPercentInput = signal('0');
  protected readonly discountReason = signal('');

  protected readonly paymentMode = signal<PaymentMode>('CASH');
  protected readonly paymentAmountInput = signal<string | null>(null);
  protected readonly paymentReference = signal('');
  protected readonly upiMarkedReceived = signal(false);

  protected readonly refundOpen = signal(false);
  protected readonly refundAmountInput = signal('');
  protected readonly refundMode = signal<PaymentMode>('CASH');
  protected readonly refundReason = signal('');

  protected readonly role = computed(() => this.store.me()?.role);
  protected readonly isOwner = computed(() => this.role() === 'OWNER');
  protected readonly canCloseDay = computed(() => this.role() === 'OWNER' || this.role() === 'FRONT_DESK');

  /** Amount being paid now, in paise: the typed value, or the whole balance by default. */
  protected readonly paymentAmountPaise = computed(() => {
    const bill = this.bill();
    if (!bill) {
      return 0;
    }
    const typed = this.paymentAmountInput();
    if (typed === null) {
      return bill.balancePaise;
    }
    const rupees = Number(typed);
    return Number.isFinite(rupees) ? Math.round(rupees * 100) : 0;
  });

  protected readonly discountPercent = computed(() => {
    const value = Math.round(Number(this.discountPercentInput()));
    return Number.isFinite(value) ? Math.min(100, Math.max(0, value)) : 0;
  });

  ngOnInit(): void {
    this.loadCounter();
    this.opdApi.listServices().subscribe({
      next: (services) => this.services.set(services.filter((s) => s.active !== false && s.id)),
      error: () => this.services.set([]),
    });
  }

  protected selectBill(visitId: string): void {
    if (this.selectedVisitId() === visitId) {
      return;
    }
    this.selectedVisitId.set(visitId);
    this.resetDraft();
    this.loadBill(visitId);
  }

  protected addItem(): void {
    const visitId = this.selectedVisitId();
    const serviceId = this.addServiceId();
    if (!visitId || !serviceId || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.billingApi.addItem(visitId, serviceId, this.addServiceQty()).subscribe({
      next: (bill) => {
        this.busy.set(false);
        this.applyBill(bill);
        this.addServiceId.set(null);
        this.addServiceQty.set(1);
      },
      error: (error: unknown) => this.fail(error, 'We could not add that service. Try again.'),
    });
  }

  protected removeItem(itemId: string): void {
    const visitId = this.selectedVisitId();
    if (!visitId || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.billingApi.removeItem(visitId, itemId).subscribe({
      next: (bill) => {
        this.busy.set(false);
        this.applyBill(bill);
      },
      error: (error: unknown) => this.fail(error, 'We could not remove that item. Try again.'),
    });
  }

  protected setDiscountPercent(value: string): void {
    this.discountPercentInput.set(value);
    if (Math.round(Number(value)) <= 0) {
      this.discountReason.set('');
    }
  }

  protected saveDiscount(): void {
    const visitId = this.selectedVisitId();
    if (!visitId || this.busy()) {
      return;
    }
    const percent = this.discountPercent();
    this.busy.set(true);
    this.billingApi.setDiscount(visitId, percent, percent > 0 ? this.discountReason() || null : null).subscribe({
      next: (bill) => {
        this.busy.set(false);
        this.applyBill(bill);
      },
      error: (error: unknown) => this.fail(error, 'We could not save the discount. Try again.'),
    });
  }

  protected setPaymentMode(mode: PaymentMode): void {
    this.paymentMode.set(mode);
    this.upiMarkedReceived.set(false);
  }

  protected markUpiReceived(): void {
    this.upiMarkedReceived.set(true);
  }

  /** `billErrors` from the mockup: why the collect button is disabled right now. */
  protected readonly collectBlockedReason = computed(() => {
    const bill = this.bill();
    if (!bill || bill.balancePaise <= 0) {
      return null;
    }
    const amount = this.paymentAmountPaise();
    if (!(amount > 0)) {
      return 'Enter the amount being paid now.';
    }
    if (amount > bill.balancePaise) {
      return `The amount can't be more than the balance of ${formatInr(bill.balancePaise / 100)}.`;
    }
    if (this.paymentMode() === 'UPI' && !this.upiMarkedReceived()) {
      return 'Wait until the UPI payment shows as received.';
    }
    return null;
  });

  protected collect(): void {
    const visitId = this.selectedVisitId();
    const bill = this.bill();
    if (!visitId || !bill || this.busy() || this.collectBlockedReason()) {
      return;
    }
    this.busy.set(true);
    this.billingApi
      .collectPayment(visitId, {
        mode: this.paymentMode(),
        amountPaise: this.paymentAmountPaise(),
        reference: this.paymentReference().trim() || null,
      })
      .subscribe({
        next: (updated) => {
          this.busy.set(false);
          this.applyBill(updated);
          this.resetPaymentDraft();
          this.toast('Payment collected.');
        },
        error: (error: unknown) => this.fail(error, 'We could not collect this payment. Try again.'),
      });
  }

  protected openRefund(): void {
    const bill = this.bill();
    this.refundAmountInput.set(bill ? String(bill.paidPaise / 100) : '');
    this.refundMode.set('CASH');
    this.refundReason.set('');
    this.refundOpen.set(true);
  }

  protected cancelRefund(): void {
    this.refundOpen.set(false);
  }

  protected submitRefund(): void {
    const visitId = this.selectedVisitId();
    const bill = this.bill();
    if (!visitId || !bill || this.busy()) {
      return;
    }
    const amountPaise = Math.round(Number(this.refundAmountInput()) * 100);
    if (!Number.isFinite(amountPaise) || amountPaise <= 0 || !this.refundReason()) {
      this.toast('Enter a refund amount and choose a reason.');
      return;
    }
    this.busy.set(true);
    this.billingApi
      .refund(visitId, { amountPaise, mode: this.refundMode(), reason: this.refundReason() })
      .subscribe({
        next: (updated) => {
          this.busy.set(false);
          this.applyBill(updated);
          this.refundOpen.set(false);
          this.toast('Refund recorded.');
        },
        error: (error: unknown) => this.fail(error, 'We could not record this refund. Try again.'),
      });
  }

  protected openClosing(): void {
    this.dialog.open(DayClosingDialogComponent, DIALOG_CONFIG);
  }

  protected rowsOf(list: BillSummary[] | undefined): BillSummary[] {
    return list ?? [];
  }

  protected sumBalance(list: BillSummary[]): number {
    return list.reduce((total, p) => total + p.balancePaise, 0);
  }

  protected sumTotal(list: BillSummary[]): number {
    return list.reduce((total, p) => total + p.totalPaise, 0);
  }

  private loadCounter(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.billingApi.counter().subscribe({
      next: (counter) => {
        this.counter.set(counter);
        this.loading.set(false);
        if (!this.selectedVisitId()) {
          const first = counter.awaiting[0] ?? counter.dues[0] ?? counter.paidToday[0];
          if (first) {
            this.selectBill(first.visitId);
          }
        }
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(
          error instanceof ApiError ? error.problem.detail ?? 'We could not load the billing counter. Try again.' : 'We could not load the billing counter. Try again.',
        );
      },
    });
  }

  private loadBill(visitId: string): void {
    this.billLoading.set(true);
    this.billError.set(null);
    this.billingApi.getBill(visitId).subscribe({
      next: (bill) => {
        this.billLoading.set(false);
        this.applyBill(bill);
      },
      error: (error: unknown) => {
        this.billLoading.set(false);
        this.billError.set(
          error instanceof ApiError ? error.problem.detail ?? 'We could not open this bill. Try again.' : 'We could not open this bill. Try again.',
        );
      },
    });
  }

  private applyBill(bill: Bill): void {
    this.bill.set(bill);
    this.discountPercentInput.set(String(bill.discountPercent));
    this.discountReason.set(bill.discountReason ?? '');
    this.loadCounterQuietly();
  }

  /** Refreshes the three lists (a payment can move a bill between them) without disturbing the
   * selection or showing a full-page loading state. */
  private loadCounterQuietly(): void {
    this.billingApi.counter().subscribe({ next: (counter) => this.counter.set(counter) });
  }

  private resetDraft(): void {
    this.resetPaymentDraft();
    this.refundOpen.set(false);
    this.addServiceId.set(null);
    this.addServiceQty.set(1);
  }

  private resetPaymentDraft(): void {
    this.paymentMode.set('CASH');
    this.paymentAmountInput.set(null);
    this.paymentReference.set('');
    this.upiMarkedReceived.set(false);
  }

  private fail(error: unknown, fallback: string): void {
    this.busy.set(false);
    this.toast(error instanceof ApiError ? error.problem.detail ?? fallback : fallback);
  }

  private toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }
}
