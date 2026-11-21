import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { newClientKey } from '../../../shared/client-key';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { pinProblem } from '../indent/indent.util';
import { ReasonChipsComponent } from '../indent/reason-chips.component';
import { RECEIPT_CONFIG, ReceiptData, ReceiptDialog } from '../indent/receipt.dialog';
import { SecondSignerComponent } from '../indent/second-signer.component';
import { expiryMonthYear } from '../inventory/inventory.util';
import { rupees } from '../opening-stock/opening-stock.util';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason, SecondSignerCandidate } from '../pharmacy-inventory.model';
import { Transfer } from '../pharmacy-stockuse.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { blindQty, transferChip, transferSteps } from '../stockuse/stockuse.util';

export interface TransferDetailData {
  /** The store the person works in. */
  storeId: string;
  transferId: string;
  /** dispatchTransfer at the working store. */
  canDispatch: boolean;
  /** receive at the working store (any mapped user at the receiving store). */
  canReceive: boolean;
  isPharmacist: boolean;
}

export const TRANSFER_DETAIL_CONFIG = {
  panelClass: 'sheet-panel',
  width: '900px',
  maxWidth: '100vw',
  ariaLabelledBy: 'transfer-title',
} as const;

/**
 * One transfer (story 79-15, mockup 12): "TRF-… · <from> → <to>", Reason, the table Item · batch, Expiry, Available at
 * <sender>, Transfer qty and Value, the step bar Requested, Approved, Dispatched, Received, and the note "Receiving
 * works exactly like an indent". The sending store dispatches an approved transfer (FEFO, all lines; a Schedule X line
 * needs a second signer). The receiving store opens "Receive items", the same sheet as an indent dispatch. A transfer
 * can be cancelled before dispatch, with a reason.
 */
@Component({
  selector: 'app-transfer-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, IconComponent, ReasonChipsComponent, SecondSignerComponent],
  styles: [
    `
      .scroll {
        overflow-x: auto;
      }
      table {
        min-width: 700px;
      }
      .num {
        text-align: right;
        white-space: nowrap;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      ol.steps {
        display: flex;
        flex-wrap: wrap;
        gap: 12px;
        list-style: none;
        padding: 0;
      }
      ol.steps li {
        padding: 8px 12px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
      }
      ol.steps li[data-done='true'] {
        border-color: var(--pri);
        background: var(--pri-soft);
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="transfer-title">Stock transfer</h2>
        @if (transfer(); as t) {
          <p data-testid="transfer-header">
            {{ t.number }} · {{ t.fromStore.name }} → {{ t.toStore.name }}
            <span class="pill" [class]="chip(t).css" data-testid="transfer-status">{{ chip(t).text }}</span>
          </p>
        }
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>
    <div class="sheet-b">
      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="transfer-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      @if (transfer(); as t) {
        <p data-testid="transfer-reason">Reason: {{ t.reason.label }}</p>
        <div class="scroll">
          <table data-testid="transfer-lines">
            <thead>
              <tr>
                <th scope="col">Item · batch</th>
                <th scope="col">Expiry</th>
                <th scope="col" class="num">Available at {{ t.fromStore.name }}</th>
                <th scope="col" class="num">Transfer qty</th>
                <th scope="col" class="num">Value</th>
              </tr>
            </thead>
            <tbody>
              @for (line of t.lines; track line.id; let n = $index) {
                <tr [attr.data-testid]="'transfer-line-' + n">
                  <td>
                    {{ line.product.name }}
                    @if (line.product.schedule === 'X') {
                      <span class="pill p-warn">Schedule X</span>
                    }
                    @for (b of line.previewBatches; track b.batch.batchId) {
                      <div class="sub">{{ b.batch.batchNo }} · {{ b.quantity }}</div>
                    }
                  </td>
                  <td>{{ firstExpiry(n) }}</td>
                  <td class="num" [attr.data-testid]="'transfer-available-' + n">{{ qty(line.availableAtSender) }}</td>
                  <td class="num">{{ line.quantity }}</td>
                  <td class="num">{{ money(line.valuePaise) }}</td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <td colspan="4">Total value</td>
                <td class="num" data-testid="transfer-total">{{ money(t.totalValuePaise) }}</td>
              </tr>
            </tfoot>
          </table>
        </div>
        @if (anyBlind()) {
          <p class="note" data-testid="transfer-counting">Counting in progress at {{ t.fromStore.name }}.</p>
        }
        <ol class="steps" aria-label="Steps" data-testid="transfer-steps">
          @for (s of steps(); track s.label) {
            <li [attr.data-done]="s.done" [attr.data-testid]="'step-' + s.label">
              <b>{{ s.label }}</b>
              <div class="sub">{{ s.hint }}</div>
            </li>
          }
        </ol>
        <p class="note" data-testid="transfer-note">
          Receiving works exactly like an indent: any mapped user at {{ t.toStore.name }} accepts or rejects with a
          reason; rejected units go back to {{ t.fromStore.name }} as Pending return.
        </p>
        @if (t.approval; as a) {
          <p data-testid="transfer-approval">{{ a.waitingFor === 'OWNER' ? 'Waiting for owner approval' : a.status === 'PENDING' ? 'Waiting for HOD approval' : '' }}</p>
        }
        <p class="sub">Raised by {{ t.raisedBy.name }} · {{ when(t.raisedAt) }}</p>

        @if (step() === 'dispatch' && needsSigner()) {
          <app-second-signer
            [signers]="signers()"
            [loaded]="signersLoaded()"
            [signerError]="signerError()"
            [pinError]="pinError()"
            [(signerId)]="signerId"
            [(pin)]="pin"
          />
        }
        @if (step() === 'cancel') {
          <span class="field-label">Reason for cancelling</span>
          <app-reason-chips
            [reasons]="reasons()"
            [value]="reasonKey()"
            label="Reason for cancelling"
            testIdPrefix="transfer-cancel-reason-"
            (chosen)="reasonKey.set($event.reasonKey)"
          />
        }
      }
      @if (done(); as text) {
        <div class="notice good" role="status" data-testid="transfer-done">
          <app-icon name="check" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="transfer-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </div>
    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close()">Close</button>
      @if (transfer(); as t) {
        @if (canCancelNow(t) && step() !== 'cancel') {
          <button mat-stroked-button type="button" data-testid="transfer-cancel" (click)="step.set('cancel')">Cancel transfer</button>
        }
        @if (step() === 'cancel') {
          <button mat-flat-button type="button" data-testid="transfer-cancel-confirm" [disabled]="saving()" (click)="cancel(t)">
            Confirm cancel
          </button>
        }
        @if (canDispatchNow(t)) {
          <button mat-flat-button type="button" data-testid="transfer-dispatch" [disabled]="saving()" (click)="dispatch(t)">Dispatch</button>
        }
        @if (canReceiveNow(t)) {
          <button mat-flat-button type="button" data-testid="transfer-receive" (click)="receive(t)">Receive items</button>
        }
      }
    </div>
  `,
})
export class TransferDetailDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly ref = inject<MatDialogRef<TransferDetailDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<TransferDetailData>(MAT_DIALOG_DATA);

  protected readonly transfer = signal<Transfer | null>(null);
  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly reasonKey = signal<string | null>(null);
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly signerId = signal('');
  protected readonly pin = signal('');
  protected readonly signerError = signal<string | null>(null);
  protected readonly pinError = signal<string | null>(null);
  protected readonly step = signal<'view' | 'dispatch' | 'cancel'>('view');
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);
  protected readonly saving = signal(false);
  private changed = false;

  private readonly clientKey = newClientKey();

  protected readonly money = rupees;
  protected readonly qty = blindQty;
  protected readonly when = (instant: string) => formatDisplayDateTimeOfInstant(instant);
  protected readonly chip = (t: Transfer) => transferChip(t.status, t.notReceivedHours);
  protected readonly steps = computed(() => {
    const t = this.transfer();
    return t ? transferSteps(t.step, t.fromStore.name, t.toStore.name) : [];
  });
  protected readonly anyBlind = computed(() => this.transfer()?.lines.some((l) => l.availableAtSender === null) === true);
  protected readonly needsSigner = computed(() => this.transfer()?.lines.some((l) => l.product.schedule === 'X') === true);

  ngOnInit(): void {
    this.api.transfer(this.data.storeId, this.data.transferId).subscribe({
      next: (t) => this.transfer.set(t),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
    this.api.activeReasons(['approval-reject']).subscribe({
      next: (answer) => this.reasons.set(answer.lists.find((l) => l.listKey === 'approval-reject')?.reasons ?? []),
      error: () => this.reasons.set([]),
    });
    this.api.secondSigners(this.data.storeId).subscribe({
      next: (people) => {
        this.signers.set(people);
        this.signersLoaded.set(true);
      },
      error: () => this.signersLoaded.set(true),
    });
  }

  protected firstExpiry(index: number): string {
    const batch = this.transfer()?.lines[index]?.previewBatches[0]?.batch;
    return batch ? expiryMonthYear(batch.expiryDate) : '—';
  }

  protected canCancelNow(t: Transfer): boolean {
    return t.status === 'WAITING_APPROVAL' || t.status === 'APPROVED';
  }

  /** Only the sending store dispatches, and only an approved transfer (the server checks both). */
  protected canDispatchNow(t: Transfer): boolean {
    return this.data.canDispatch && t.fromStore.storeId === this.data.storeId && t.status === 'APPROVED' && !this.done();
  }

  protected canReceiveNow(t: Transfer): boolean {
    return this.data.canReceive && t.toStore.storeId === this.data.storeId && t.status === 'IN_TRANSIT' && t.dispatch !== null;
  }

  protected dispatch(t: Transfer): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    this.signerError.set(null);
    this.pinError.set(null);
    let secondSigner: { membershipId: string; pin: string } | null = null;
    if (this.needsSigner()) {
      this.step.set('dispatch');
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
      .dispatchTransfer(this.data.storeId, t.id, { clientKey: this.clientKey, version: t.version, secondSigner })
      .subscribe({
        next: (answer) => {
          this.pin.set('');
          this.saving.set(false);
          this.changed = true;
          this.transfer.set(answer);
          this.done.set(`Dispatched. ${answer.toStore.name} can now receive it.`);
        },
        error: (error: unknown) => {
          this.pin.set('');
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }

  protected cancel(t: Transfer): void {
    const reason = this.reasonKey();
    if (this.saving()) {
      return;
    }
    if (!reason) {
      this.formError.set('Choose a reason.');
      return;
    }
    this.formError.set(null);
    this.saving.set(true);
    this.api.cancelTransfer(this.data.storeId, t.id, { version: t.version, reasonKey: reason }).subscribe({
      next: (answer) => {
        this.saving.set(false);
        this.changed = true;
        this.step.set('view');
        this.transfer.set(answer);
        this.done.set(`${answer.number} cancelled.`);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.formError.set(saveErrorMessage(error));
      },
    });
  }

  /** "Receiving works like an indent": the same Receive items sheet, on the transfer's dispatch. */
  protected receive(t: Transfer): void {
    if (!t.dispatch) {
      return;
    }
    const data: ReceiptData = { storeId: this.data.storeId, dispatchId: t.dispatch.dispatchId, isPharmacist: this.data.isPharmacist };
    this.dialog
      .open(ReceiptDialog, { ...RECEIPT_CONFIG, data })
      .afterClosed()
      .subscribe((received: boolean | undefined) => {
        if (received) {
          this.changed = true;
          this.ref.close(true);
        }
      });
  }

  protected close(): void {
    this.ref.close(this.changed);
  }
}
