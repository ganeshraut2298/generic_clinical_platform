import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { saveErrorMessage } from '../pharmacy-display.util';
import { SecondSignerCandidate } from '../pharmacy-inventory.model';
import { Shortage, ShortageOutcome } from '../pharmacy-indent.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { HistoryListComponent } from './history-list.component';
import { batchWithExpiry, pinProblem, shortageBanner, shortageStatusChip } from './indent.util';
import { SecondSignerComponent } from './second-signer.component';

export interface ShortageData {
  storeId: string;
  shortage: Shortage;
  canSignScheduleX: boolean;
}

export const SHORTAGE_CONFIG = {
  panelClass: 'sheet-panel',
  width: '720px',
  maxWidth: '100vw',
  ariaLabelledBy: 'shortage-title',
} as const;

const OUTCOMES: readonly { value: ShortageOutcome; label: string }[] = [
  { value: 'FOUND', label: 'Found in Central Store' },
  { value: 'TRANSIT_LOSS', label: 'Transit loss · write off' },
  { value: 'CARRIER_CLAIM', label: 'Claim from carrier' },
];

/**
 * "Shortage, not a return" (story 79-13, mockup 10 step 5): the banner, Item · batch, the receiving store, the quantity
 * and "Settle as" with "Found in Central Store", "Transit loss · write off" and "Claim from carrier"; the button reads
 * "Settle shortage of <n>". A shortage is never accepted as a physical return. A transit loss goes through the write-off
 * rule: it may wait for an approver. A Schedule X product found again needs a pharmacist and a second signature.
 */
@Component({
  selector: 'app-shortage-settle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, IconComponent, HistoryListComponent, SecondSignerComponent],
  styles: [
    `
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      .row {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        margin: 8px 0;
      }
      .opt {
        min-height: 44px;
        padding: 0 14px;
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
      textarea {
        width: 100%;
        min-height: 64px;
        padding: 8px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
        background: var(--glass-hi);
        font: inherit;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="shortage-title">Settle shortage</h2>
        <p data-testid="shortage-header">
          {{ s().dispatchReference }} · {{ s().product.name }} · {{ batch() }}
          <span class="pill" [class]="statusChip().css" data-testid="shortage-status">{{ statusChip().text }}</span>
        </p>
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      <div class="notice warn" role="status" data-testid="shortage-banner">
        <app-icon name="alert" [size]="16" />
        <span>{{ banner() }}</span>
      </div>
      @if (result(); as text) {
        <div class="notice good" role="status" data-testid="shortage-result">
          <app-icon name="check" [size]="16" />
          <span>{{ text }}</span>
        </div>
      } @else {
        <h3>Settle as</h3>
        <div class="row" role="group" aria-label="Settle as">
          @for (o of outcomes; track o.value) {
            <button
              type="button"
              class="opt"
              [attr.aria-pressed]="outcome() === o.value"
              [attr.data-testid]="'settle-' + o.value"
              (click)="outcome.set(o.value)"
            >
              {{ o.label }}
            </button>
          }
        </div>
        <textarea
          maxlength="300"
          aria-label="Note"
          data-testid="settle-note"
          [value]="note()"
          (input)="note.set($any($event.target).value)"
        ></textarea>
        <p class="sub">Optional. Do not add patient details.</p>
        @if (needsSigner()) {
          @if (!data.canSignScheduleX) {
            <div class="notice warn" role="status" data-testid="shortage-x-blocked">
              <app-icon name="lock" [size]="16" />
              <span>A Schedule X shortage is settled as found by a pharmacist, with a second signature.</span>
            </div>
          } @else {
            <app-second-signer
              [signers]="signers()"
              [loaded]="signersLoaded()"
              [signerError]="signerError()"
              [pinError]="pinError()"
              [(signerId)]="signerId"
              [(pin)]="pin"
            />
          }
        }
      }
      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="shortage-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      <h3>History</h3>
      <app-history-list [events]="s().history" emptyText="Nothing yet." />
    </div>

    <div class="sheet-f">
      @if (result()) {
        <button mat-flat-button type="button" data-testid="shortage-finish" (click)="finish()">Close</button>
      } @else {
        <button mat-stroked-button type="button" (click)="close()">Cancel</button>
        <button mat-flat-button type="button" data-testid="shortage-settle" [disabled]="saving() || !outcome()" (click)="settle()">
          Settle shortage of {{ s().qty }}
        </button>
      }
    </div>
  `,
})
export class ShortageSettleDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<ShortageSettleDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<ShortageData>(MAT_DIALOG_DATA);

  protected readonly outcomes = OUTCOMES;
  protected readonly s = signal<Shortage>(this.data.shortage);
  protected readonly outcome = signal<ShortageOutcome | null>(null);
  protected readonly note = signal('');
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly signerId = signal('');
  protected readonly pin = signal('');
  protected readonly signerError = signal<string | null>(null);
  protected readonly pinError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly result = signal<string | null>(null);

  private changed = false;
  private readonly clientKey = newClientKey();

  protected readonly statusChip = () => shortageStatusChip(this.s().status);
  protected readonly batch = () => batchWithExpiry(this.s().batch.batchNo, this.s().batch.expiryDate);
  protected readonly banner = () =>
    this.s().banner && this.s().banner !== 'Shortage, not a return.'
      ? (this.s().banner as string)
      : shortageBanner(this.s().qty, this.s().receivingStore.name);

  ngOnInit(): void {
    if (this.data.canSignScheduleX && this.data.shortage.product.schedule === 'X') {
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

  protected needsSigner(): boolean {
    return this.s().product.schedule === 'X' && this.outcome() === 'FOUND';
  }

  protected settle(): void {
    const outcome = this.outcome();
    if (this.saving() || outcome === null) {
      return;
    }
    this.formError.set(null);
    this.signerError.set(null);
    this.pinError.set(null);
    let secondSigner: { membershipId: string; pin: string } | null = null;
    if (this.needsSigner()) {
      if (!this.data.canSignScheduleX) {
        this.formError.set('A Schedule X shortage is settled as found by a pharmacist, with a second signature.');
        return;
      }
      const pinText = this.pin();
      const pinProblemText = pinProblem(pinText);
      if (!this.signerId()) {
        this.signerError.set('Choose the second signer');
      }
      if (pinProblemText) {
        this.pinError.set(pinProblemText);
      }
      if (!this.signerId() || pinProblemText) {
        return;
      }
      secondSigner = { membershipId: this.signerId(), pin: pinText };
    }
    const note = this.note().trim();
    this.saving.set(true);
    this.api
      .settleShortage(this.data.storeId, this.s().id, {
        clientKey: this.clientKey,
        version: this.s().version,
        outcome,
        note: note === '' ? null : note,
        secondSigner,
      })
      .subscribe({
        next: (done) => {
          this.pin.set('');
          this.saving.set(false);
          this.changed = true;
          this.s.set(done);
          this.result.set(done.resultNote ?? this.resultText(done));
        },
        error: (error: unknown) => {
          this.pin.set('');
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }

  private resultText(done: Shortage): string {
    switch (done.status) {
      case 'FOUND':
        return `Shortage settled. ${done.qty} units found and added to Central Store.`;
      case 'WRITTEN_OFF':
        return `Shortage settled. ${done.qty} units written off as transit loss. No stock added back.`;
      case 'WAITING_WRITE_OFF':
        return 'The write-off is waiting for approval. It shows in the Approvals inbox.';
      case 'CLAIM_OPEN':
        return 'Claim open. It must be settled later as found or written off.';
      default:
        return 'Saved.';
    }
  }

  protected finish(): void {
    this.ref.close(true);
  }

  protected close(): void {
    this.ref.close(this.changed);
  }
}
