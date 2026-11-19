import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason, SecondSignerCandidate } from '../pharmacy-inventory.model';
import { PutBackInto, ReturnItem, ReturnSide } from '../pharmacy-indent.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { HistoryListComponent } from './history-list.component';
import { acceptedResultText, batchWithExpiry, pinProblem, returnStatusChip, returnTypeText, whenText } from './indent.util';
import { ReasonChipsComponent } from './reason-chips.component';
import { SecondSignerComponent } from './second-signer.component';

export interface ReturnItemData {
  storeId: string;
  itemId: string;
  side: ReturnSide;
  /** A pharmacist mapped to the store (rule 19): needed to accept a Schedule X item. */
  canSignScheduleX: boolean;
}

export const RETURN_ITEM_CONFIG = {
  panelClass: 'sheet-panel',
  width: '760px',
  maxWidth: '100vw',
  ariaLabelledBy: 'return-item-title',
} as const;

type Step = 'accept' | 'dispute';

/**
 * "Accept returned / rejected items" (story 79-13, mockup 10 step 5): Item · batch, From, Rejected by, Qty, Reason and
 * "Put back into" with "Saleable stock" and "Damaged / quarantine bin". The default comes from the server (damaged in
 * transit and near expiry go to quarantine; an expired batch can only go to quarantine). "Accept <n> units back" posts
 * the units; "Dispute · ask <store> with reason" needs a reason chip and keeps the units pending at the returning store.
 * The returning store sees the item with the dispute reason and may add a reply note. A Schedule X item needs a
 * pharmacist and a second signature.
 */
@Component({
  selector: 'app-return-item',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, IconComponent, ReasonChipsComponent, HistoryListComponent, SecondSignerComponent],
  styles: [
    `
      dl.facts {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
        gap: 10px;
        margin: 0 0 12px;
      }
      dl.facts div {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      dl.facts dt {
        font-size: 0.78rem;
        font-weight: 600;
        color: var(--ink-3);
      }
      dl.facts dd {
        margin: 0;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
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
      .opt:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }
      .row {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        margin: 8px 0;
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
        <h2 id="return-item-title">Accept returned / rejected items</h2>
        @if (item(); as i) {
          <p data-testid="return-header">{{ i.docNo }} · {{ typeText(i) }}</p>
        }
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="return-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      @if (item(); as i) {
        <dl class="facts" data-testid="return-facts">
          <div>
            <dt>Item · batch</dt>
            <dd>{{ i.product.name }} · {{ batch(i) }}</dd>
          </div>
          <div>
            <dt>From</dt>
            <dd>{{ i.holdingStore.name }}</dd>
          </div>
          <div>
            <dt>Rejected by</dt>
            <dd>{{ i.raisedBy.name }} · {{ when(i.raisedAt) }}</dd>
          </div>
          <div>
            <dt>Qty</dt>
            <dd data-testid="return-qty">{{ i.qty }} {{ i.product.issueUnit }}</dd>
          </div>
          <div>
            <dt>Reason</dt>
            <dd>{{ i.reason.label }}</dd>
          </div>
          <div>
            <dt>Status</dt>
            <dd>
              <span class="pill" [class]="statusChip(i).css" data-testid="return-status">{{ statusChip(i).text }}</span>
            </dd>
          </div>
        </dl>

        @if (i.dispute; as d) {
          <div class="notice warn" role="status" data-testid="return-dispute">
            <app-icon name="alert" [size]="16" />
            <span>Disputed by {{ d.by.name }} · {{ d.reason.label }}{{ d.reason.note ? ' · ' + d.reason.note : '' }}</span>
          </div>
        }
        @for (n of i.notes; track $index) {
          <p class="sub" data-testid="return-note">Reply from {{ n.by.name }} · {{ n.note }}</p>
        }

        @if (result(); as text) {
          <div class="notice good" role="status" data-testid="return-result">
            <app-icon name="check" [size]="16" />
            <span>{{ text }}</span>
          </div>
        } @else if (data.side === 'ACCEPTING' && canAct(i)) {
          @if (step() === 'accept') {
            <h3>Put back into</h3>
            <div class="row" role="group" aria-label="Put back into">
              <button
                type="button"
                class="opt"
                data-testid="put-saleable"
                [attr.aria-pressed]="putBack() === 'SALEABLE'"
                [disabled]="i.expired"
                (click)="putBack.set('SALEABLE')"
              >
                Saleable stock
              </button>
              <button
                type="button"
                class="opt"
                data-testid="put-quarantine"
                [attr.aria-pressed]="putBack() === 'QUARANTINE'"
                (click)="putBack.set('QUARANTINE')"
              >
                Damaged / quarantine bin
              </button>
            </div>
            @if (i.expired) {
              <p class="sub" data-testid="return-expired">This batch has expired. It can only go to quarantine.</p>
            }
            @if (i.scheduleX) {
              @if (!data.canSignScheduleX) {
                <div class="notice warn" role="status" data-testid="return-x-blocked">
                  <app-icon name="lock" [size]="16" />
                  <span>Schedule X is accepted back by a pharmacist, with a second signature.</span>
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
          } @else {
            <h3>Dispute · ask {{ i.holdingStore.name }}</h3>
            <app-reason-chips
              [reasons]="disputeReasons()"
              [value]="reasonKey()"
              label="Reason for the dispute"
              testIdPrefix="dispute-reason-"
              (chosen)="chooseReason($event)"
            />
            <textarea
              maxlength="300"
              aria-label="Dispute note"
              data-testid="dispute-note"
              [value]="note()"
              (input)="note.set($any($event.target).value)"
            ></textarea>
          }
        } @else if (data.side === 'RETURNING' && (i.status === 'WAITING' || i.status === 'DISPUTED')) {
          <h3>Reply to Central Store</h3>
          <textarea
            maxlength="300"
            aria-label="Reply note"
            data-testid="reply-note"
            [value]="note()"
            (input)="note.set($any($event.target).value)"
          ></textarea>
          <p class="sub">At least 4 characters. Do not add patient details.</p>
        }

        @if (formError(); as text) {
          <div class="notice err" role="alert" data-testid="return-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }
        <h3>History</h3>
        <app-history-list [events]="i.history" emptyText="Nothing yet." />
      }
    </div>

    <div class="sheet-f">
      @if (item(); as i) {
        @if (result()) {
          <button mat-flat-button type="button" data-testid="return-finish" (click)="finish()">Close</button>
        } @else if (data.side === 'ACCEPTING' && canAct(i)) {
          @if (step() === 'accept') {
            <button mat-stroked-button type="button" data-testid="return-dispute-start" (click)="step.set('dispute')">
              Dispute · ask {{ i.holdingStore.name }} with reason
            </button>
            <button mat-flat-button type="button" data-testid="return-accept" [disabled]="saving()" (click)="accept(i)">
              Accept {{ i.qty }} units back
            </button>
          } @else {
            <button mat-stroked-button type="button" (click)="step.set('accept')">Back</button>
            <button mat-flat-button type="button" data-testid="return-dispute-send" [disabled]="saving()" (click)="dispute(i)">
              Dispute · ask {{ i.holdingStore.name }}
            </button>
          }
        } @else if (data.side === 'RETURNING' && (i.status === 'WAITING' || i.status === 'DISPUTED')) {
          <button mat-flat-button type="button" data-testid="return-reply" [disabled]="saving()" (click)="reply(i)">Send reply</button>
        }
      }
      <button mat-stroked-button type="button" (click)="close()">Close</button>
    </div>
  `,
})
export class ReturnItemDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<ReturnItemDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<ReturnItemData>(MAT_DIALOG_DATA);

  protected readonly item = signal<ReturnItem | null>(null);
  protected readonly step = signal<Step>('accept');
  protected readonly putBack = signal<PutBackInto>('QUARANTINE');
  protected readonly disputeReasons = signal<ActiveReason[]>([]);
  protected readonly reasonKey = signal<string | null>(null);
  protected readonly note = signal('');
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly signerId = signal('');
  protected readonly pin = signal('');
  protected readonly signerError = signal<string | null>(null);
  protected readonly pinError = signal<string | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly result = signal<string | null>(null);

  private changed = false;
  private readonly clientKey = newClientKey();

  protected readonly typeText = (i: ReturnItem) => returnTypeText(i.type);
  protected readonly statusChip = (i: ReturnItem) => returnStatusChip(i.status);
  protected readonly batch = (i: ReturnItem) => batchWithExpiry(i.batch.batchNo, i.batch.expiryDate);
  protected readonly when = whenText;
  protected readonly accepting = computed(() => this.data.side === 'ACCEPTING');

  ngOnInit(): void {
    this.api.returnItem(this.data.storeId, this.data.itemId).subscribe({
      next: (item) => {
        this.item.set(item);
        this.putBack.set(item.expired ? 'QUARANTINE' : item.defaultPutBackInto);
      },
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
    if (this.data.side === 'ACCEPTING') {
      this.api.activeReasons(['dispute']).subscribe({
        next: (answer) => this.disputeReasons.set(answer.lists.find((l) => l.listKey === 'dispute')?.reasons ?? []),
        error: () => this.disputeReasons.set([]),
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
  }

  protected canAct(item: ReturnItem): boolean {
    return item.status === 'WAITING' || item.status === 'DISPUTED';
  }

  protected chooseReason(reason: ActiveReason): void {
    this.reasonKey.set(reason.reasonKey);
    this.formError.set(null);
  }

  protected accept(item: ReturnItem): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    this.signerError.set(null);
    this.pinError.set(null);
    let secondSigner: { membershipId: string; pin: string } | null = null;
    if (item.scheduleX) {
      if (!this.data.canSignScheduleX) {
        this.formError.set('Schedule X is accepted back by a pharmacist, with a second signature.');
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
    this.saving.set(true);
    this.api
      .acceptReturnItem(this.data.storeId, item.id, {
        clientKey: this.clientKey,
        version: item.version,
        putBackInto: this.putBack(),
        secondSigner,
      })
      .subscribe({
        next: (done) => {
          this.pin.set('');
          this.saving.set(false);
          this.changed = true;
          this.item.set(done);
          this.result.set(done.resultNote ?? acceptedResultText(done));
        },
        error: (error: unknown) => {
          this.pin.set('');
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }

  protected dispute(item: ReturnItem): void {
    if (this.saving()) {
      return;
    }
    const reason = this.disputeReasons().find((r) => r.reasonKey === this.reasonKey());
    if (!reason) {
      this.formError.set('Choose a reason for the dispute.');
      return;
    }
    const note = this.note().trim();
    if (reason.needsNote && note.length < 4) {
      this.formError.set('Write a note of at least 4 characters.');
      return;
    }
    this.formError.set(null);
    this.saving.set(true);
    this.api
      .disputeReturnItem(this.data.storeId, item.id, {
        version: item.version,
        reasonKey: reason.reasonKey,
        note: note === '' ? null : note,
      })
      .subscribe({
        next: (done) => {
          this.saving.set(false);
          this.changed = true;
          this.item.set(done);
          this.result.set(`Disputed. ${done.holdingStore.name} sees the reason. The units stay pending return there.`);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }

  protected reply(item: ReturnItem): void {
    if (this.saving()) {
      return;
    }
    const note = this.note().trim();
    if (note.length < 4) {
      this.formError.set('Write a note of at least 4 characters.');
      return;
    }
    this.formError.set(null);
    this.saving.set(true);
    this.api.replyReturnItem(this.data.storeId, item.id, { clientKey: this.clientKey, note }).subscribe({
      next: (done) => {
        this.saving.set(false);
        this.changed = true;
        this.item.set(done);
        this.note.set('');
        this.result.set('Your reply was added.');
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.formError.set(saveErrorMessage(error));
      },
    });
  }

  protected finish(): void {
    this.ref.close(true);
  }

  protected close(): void {
    this.ref.close(this.changed);
  }
}
