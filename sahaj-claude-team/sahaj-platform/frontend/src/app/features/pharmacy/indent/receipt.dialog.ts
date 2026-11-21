import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { newClientKey } from '../../../shared/client-key';
import { istToday } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason, SecondSignerCandidate } from '../pharmacy-inventory.model';
import { Dispatch, DispatchLine, DispatchReceiptLineInput } from '../pharmacy-indent.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { batchWithExpiry, dispatchStatusChip, pinProblem, receiptNotes } from './indent.util';
import { ReasonChipsComponent } from './reason-chips.component';

export interface ReceiptData {
  storeId: string;
  dispatchId: string;
  /** The caller is a pharmacist mapped to the store: Schedule X lines may be received, with a second signature. */
  isPharmacist: boolean;
}

export const RECEIPT_CONFIG = {
  panelClass: 'sheet-panel',
  width: '1000px',
  maxWidth: '100vw',
  ariaLabelledBy: 'receipt-title',
} as const;

const SHORT_KEY = 'receipt-reject.short';
const EXPIRED_KEY = 'receipt-reject.near-expiry';

interface Row {
  line: DispatchLine;
  accepted: number;
  reasonKey: string | null;
  note: string;
  expired: boolean;
  /** A Schedule X line this person cannot receive: it is sent as skipped and stays in transit. */
  skipped: boolean;
  error: string | null;
}

/**
 * "Receive items" (story 79-12, mockup 10 step 4): the line "Acting as ... assigned to <store>" and the table Item · batch,
 * Dispatched, Accepted (a number with − and + buttons), Rejected / damaged (worked out), Reason (required when anything is
 * rejected, from the `receipt-reject` list), and the line "Check: Accepted + Rejected = Dispatched" for every line. A
 * replaced product shows "replaced by Central Store" and a back-ordered line "<n> back-ordered". A Schedule X line waits
 * for a pharmacist, who also needs a second signer and their PIN (cleared after every send). "Add photo of damage" is not
 * built (OQ-79-25). Lines already received are shown with what was received and are not sent again.
 */
@Component({
  selector: 'app-receipt',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, IconComponent, ReasonChipsComponent],
  styles: [
    `
      .scroll {
        overflow-x: auto;
      }
      table {
        min-width: 900px;
      }
      .num {
        text-align: right;
        white-space: nowrap;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      .stepper {
        display: inline-flex;
        align-items: center;
        gap: 6px;
      }
      .stepper button {
        min-width: 44px;
        min-height: 44px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
        background: var(--glass-hi);
        font: inherit;
        font-size: 1.2rem;
        cursor: pointer;
      }
      .stepper button:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }
      .stepper output {
        min-width: 36px;
        text-align: center;
        font-weight: 700;
      }
      .row-err {
        color: var(--crit);
        font-size: 0.78rem;
      }
      .signer-err {
        flex-basis: 100%;
      }
      .signer {
        display: flex;
        flex-wrap: wrap;
        gap: 12px;
        align-items: flex-start;
      }
      .notes p {
        margin: 4px 0;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="receipt-title">Receive items</h2>
        @if (dispatch(); as d) {
          <p data-testid="receipt-header">
            {{ d.reference }} · {{ d.fromStore.name }} → {{ d.toStore.name }}
            <span class="pill" [class]="statusChip(d).css" data-testid="receipt-status">{{ statusChip(d).text }}</span>
          </p>
        }
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="receipt-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      @if (dispatch(); as d) {
        <p data-testid="receipt-acting-as">{{ d.actingAs }}</p>
        @if (isDone()) {
          <div class="notice good" role="status" data-testid="receipt-done">
            <app-icon name="check" [size]="16" />
            @if (waitingCount() > 0) {
              <span data-testid="receipt-partly">
                Receipt confirmed for the lines you could receive. Schedule X lines are still waiting for a pharmacist.
              </span>
            } @else {
              <span>Receipt confirmed. The accepted units are now in {{ d.toStore.name }}.</span>
            }
          </div>
        }
        <div class="scroll">
          <table data-testid="receipt-table">
            <thead>
              <tr>
                <th scope="col">Item · batch</th>
                <th scope="col" class="num">Dispatched</th>
                <th scope="col">Accepted</th>
                <th scope="col" class="num">Rejected / damaged</th>
                <th scope="col">Reason (required)</th>
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track row.line.id; let n = $index) {
                <tr [attr.data-testid]="'receipt-line-' + n">
                  <td>
                    {{ row.line.product.name }}
                    @if (row.line.scheduleX) {
                      <span class="pill p-warn">Schedule X</span>
                    }
                    <div class="sub">{{ batch(row.line) }}</div>
                    @if (row.line.replacedByCs) {
                      <div class="sub" [attr.data-testid]="'receipt-replaced-' + n">
                        replaced by Central Store{{ row.line.changeReason ? ' · ' + row.line.changeReason.label : '' }}
                      </div>
                    }
                    @if (row.line.backOrdered > 0) {
                      <div class="sub" [attr.data-testid]="'receipt-backordered-' + n">{{ row.line.backOrdered }} back-ordered</div>
                    }
                  </td>
                  <td class="num">{{ row.line.dispatched }}</td>
                  @if (row.line.received) {
                    <td data-testid="receipt-received">{{ row.line.accepted ?? '—' }} received</td>
                    <td class="num">{{ row.line.rejected ?? '—' }}</td>
                    <td>{{ row.line.rejectReason?.label ?? '—' }}</td>
                  } @else if (row.skipped) {
                    <td colspan="3" [attr.data-testid]="'receipt-skipped-' + n">
                      Waits for a pharmacist. It stays in transit.
                    </td>
                  } @else {
                    <td>
                      <span class="stepper">
                        <button
                          type="button"
                          aria-label="One less accepted"
                          [attr.data-testid]="'receipt-minus-' + n"
                          [disabled]="row.accepted <= 0 || saving() || isDone()"
                          (click)="step(n, -1)"
                        >
                          −
                        </button>
                        <output [attr.data-testid]="'receipt-accepted-' + n" aria-live="polite">{{ row.accepted }}</output>
                        <button
                          type="button"
                          aria-label="One more accepted"
                          [attr.data-testid]="'receipt-plus-' + n"
                          [disabled]="row.accepted >= row.line.dispatched || row.expired || saving() || isDone()"
                          (click)="step(n, 1)"
                        >
                          +
                        </button>
                      </span>
                    </td>
                    <td class="num" [attr.data-testid]="'receipt-rejected-' + n">{{ rejectedOf(row) }}</td>
                    <td>
                      @if (rejectedOf(row) > 0) {
                        <app-reason-chips
                          [reasons]="reasons()"
                          [value]="row.reasonKey"
                          label="Reason for the rejected quantity"
                          [testIdPrefix]="'receipt-reason-' + n + '-'"
                          (chosen)="setReason(n, $event)"
                        />
                        @if (needsNote(row)) {
                          <textarea
                            maxlength="300"
                            aria-label="Note for the rejected quantity"
                            [attr.data-testid]="'receipt-note-' + n"
                            [value]="row.note"
                            (input)="setNote(n, $any($event.target).value)"
                          ></textarea>
                        }
                        @if (row.expired) {
                          <div class="sub">This batch has expired. It can only be rejected.</div>
                        }
                      } @else {
                        <span>—</span>
                      }
                      @if (row.error; as text) {
                        <div class="row-err" role="alert">{{ text }}</div>
                      }
                    </td>
                  }
                </tr>
                @if (!row.line.received && !row.skipped) {
                  <tr class="check">
                    <td colspan="5" class="sub" [attr.data-testid]="'receipt-check-' + n">
                      Check: Accepted {{ row.accepted }} + Rejected {{ rejectedOf(row) }} = Dispatched {{ row.line.dispatched }}
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>

        <div class="notes" data-testid="receipt-notes">
          @for (text of notes(); track text) {
            <p>{{ text }}</p>
          }
        </div>
        @if (hasScheduleX()) {
          <p class="sub" data-testid="receipt-x-note">
            Schedule X / NDPS lines can only be received by a mapped pharmacist, with a second signature.
          </p>
        }

        @if (needsSigner()) {
          <div class="signer">
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Second signer</mat-label>
              <mat-select data-testid="f-signer" [value]="signerId()" (selectionChange)="signerId.set($event.value)">
                @for (person of signers(); track person.membershipId) {
                  <mat-option [value]="person.membershipId">{{ person.name }} · {{ person.roleLabel }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
              @if (signerError(); as text) {
                <span class="row-err signer-err" role="alert">{{ text }}</span>
              }
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Their PIN</mat-label>
              <input
                matInput
                type="password"
                inputmode="numeric"
                autocomplete="off"
                maxlength="4"
                data-testid="f-pin"
                [value]="pin()"
                (input)="pin.set($any($event.target).value)"
              />
            </mat-form-field>
              @if (pinError(); as text) {
                <span class="row-err signer-err" role="alert">{{ text }}</span>
              }
          </div>
          @if (signersLoaded() && signers().length === 0) {
            <p class="notice" role="status" data-testid="no-second-signer">No other pharmacist or owner is available to sign.</p>
          }
        }
      }

      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="receipt-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </div>

    <div class="sheet-f">
      @if (isDone()) {
        <button mat-flat-button type="button" data-testid="receipt-finish" (click)="finish()">Close</button>
      } @else {
        <button mat-stroked-button type="button" (click)="close()">Cancel</button>
        <button
          mat-flat-button
          type="button"
          data-testid="receipt-confirm"
          [disabled]="saving() || nothingToReceive()"
          (click)="confirm()"
        >
          Confirm receipt
        </button>
      }
    </div>
  `,
})
export class ReceiptDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<ReceiptDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<ReceiptData>(MAT_DIALOG_DATA);

  protected readonly dispatch = signal<Dispatch | null>(null);
  protected readonly rows = signal<Row[]>([]);
  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly signerId = signal('');
  protected readonly pin = signal('');
  protected readonly signerError = signal<string | null>(null);
  protected readonly pinError = signal<string | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly isDone = signal(false);

  private readonly clientKey = newClientKey();

  protected readonly batch = (line: DispatchLine) => batchWithExpiry(line.batch.batchNo, line.batch.expiryDate);
  protected readonly statusChip = (d: Dispatch) => dispatchStatusChip(d.status);

  protected readonly hasScheduleX = computed(() => this.rows().some((r) => r.line.scheduleX && !r.line.received));
  /** A pharmacist who receives a Schedule X line needs the second signer. */
  protected readonly needsSigner = computed(
    () => this.data.isPharmacist && this.rows().some((r) => r.line.scheduleX && !r.line.received && !r.skipped),
  );
  protected readonly waitingCount = computed(() => this.rows().filter((r) => !r.line.received).length);
  protected readonly nothingToReceive = computed(() => this.rows().every((r) => r.line.received || r.skipped));
  protected readonly notes = computed(() => {
    const d = this.dispatch();
    if (d === null) {
      return [];
    }
    let rejected = 0;
    let short = 0;
    for (const r of this.rows()) {
      if (r.line.received || r.skipped) {
        continue;
      }
      const qty = r.line.dispatched - r.accepted;
      if (r.reasonKey === SHORT_KEY) {
        short += qty;
      } else {
        rejected += qty;
      }
    }
    return receiptNotes(rejected, short, d.toStore.name);
  });

  ngOnInit(): void {
    this.api.dispatch(this.data.storeId, this.data.dispatchId).subscribe({
      next: (dispatch) => this.opened(dispatch),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
    this.api.activeReasons(['receipt-reject']).subscribe({
      next: (answer) => this.reasons.set(answer.lists.find((l) => l.listKey === 'receipt-reject')?.reasons ?? []),
      error: () => this.reasons.set([]),
    });
    if (this.data.isPharmacist) {
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

  private opened(dispatch: Dispatch): void {
    this.dispatch.set(dispatch);
    const today = istToday();
    this.rows.set(
      dispatch.lines.map((line) => {
        const expired = line.batch.expiryDate < today;
        return {
          line,
          accepted: expired ? 0 : line.dispatched,
          reasonKey: expired ? EXPIRED_KEY : null,
          note: '',
          expired,
          skipped: line.scheduleX && !this.data.isPharmacist && !line.received,
          error: null,
        };
      }),
    );
  }

  protected rejectedOf(row: Row): number {
    return row.line.dispatched - row.accepted;
  }

  protected needsNote(row: Row): boolean {
    return this.reasons().find((r) => r.reasonKey === row.reasonKey)?.needsNote === true;
  }

  private patch(index: number, change: Partial<Row>): void {
    this.rows.update((list) => list.map((r, i) => (i === index ? { ...r, ...change } : r)));
  }

  protected step(index: number, delta: number): void {
    const row = this.rows()[index];
    const next = Math.min(Math.max(row.accepted + delta, 0), row.line.dispatched);
    this.patch(index, { accepted: next, reasonKey: next === row.line.dispatched ? null : row.reasonKey, error: null });
  }

  protected setReason(index: number, reason: ActiveReason): void {
    this.patch(index, { reasonKey: reason.reasonKey, error: null });
  }

  protected setNote(index: number, value: string): void {
    this.patch(index, { note: value });
  }

  protected confirm(): void {
    const dispatch = this.dispatch();
    if (this.saving() || !dispatch) {
      return;
    }
    this.formError.set(null);
    this.signerError.set(null);
    this.pinError.set(null);
    let bad = false;
    const checked = this.rows().map((r) => {
      let error: string | null = null;
      if (!r.line.received && !r.skipped) {
        const rejected = r.line.dispatched - r.accepted;
        if (rejected > 0 && !r.reasonKey) {
          error = 'Choose a reason for the rejected quantity';
        } else if (rejected > 0 && this.needsNote(r) && r.note.trim().length < 4) {
          error = 'Write a note of at least 4 characters';
        } else if (r.expired && r.accepted > 0) {
          error = 'This batch has expired. Reject it as near expiry or expired';
        }
      }
      bad = bad || error !== null;
      return { ...r, error };
    });
    this.rows.set(checked);
    if (bad) {
      return;
    }
    let secondSigner: { membershipId: string; pin: string } | null = null;
    if (this.needsSigner()) {
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
    const lines: DispatchReceiptLineInput[] = checked
      .filter((r) => !r.line.received)
      .map((r) => {
        const rejected = r.line.dispatched - r.accepted;
        const note = r.note.trim();
        return {
          dispatchLineId: r.line.id,
          accepted: r.skipped ? 0 : r.accepted,
          rejected: r.skipped ? 0 : rejected,
          rejectReasonKey: !r.skipped && rejected > 0 ? r.reasonKey : null,
          note: !r.skipped && rejected > 0 && note !== '' ? note : null,
          skipped: r.skipped,
        };
      });
    this.saving.set(true);
    this.api
      .receiveDispatch(this.data.storeId, dispatch.id, {
        clientKey: this.clientKey,
        dispatchVersion: dispatch.version,
        lines,
        secondSigner,
      })
      .subscribe({
        next: (answer) => {
          this.pin.set('');
          this.saving.set(false);
          this.dispatch.set(answer);
          this.rows.set(
            answer.lines.map((line) => ({
              line,
              accepted: line.accepted ?? line.dispatched,
              reasonKey: line.rejectReason?.reasonKey ?? null,
              note: '',
              expired: false,
              skipped: !line.received,
              error: null,
            })),
          );
          this.isDone.set(true);
        },
        error: (error: unknown) => {
          this.pin.set('');
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }

  protected finish(): void {
    this.ref.close(true);
  }

  protected close(): void {
    this.ref.close(this.isDone());
  }
}
