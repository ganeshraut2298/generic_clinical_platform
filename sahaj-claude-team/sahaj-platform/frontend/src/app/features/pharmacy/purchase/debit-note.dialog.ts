import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { newClientKey } from '../../../shared/client-key';
import { formatDisplayDate, formatDisplayDateTimeOfInstant, istToday } from '../../../shared/format/ist-date';
import { provideIsoDateAdapter } from '../../../shared/format/iso-date-adapter';
import { IconComponent } from '../../../shared/icon/icon.component';
import { actingAsText, historyEventText } from '../inventory/inventory.util';
import { rateText, rupees } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { DebitNote, DebitNoteStepKey } from '../pharmacy-buying.model';
import { ActiveReason, SecondSignerCandidate } from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { openPrintView, pickListPrintHtml } from './print.util';
import { dnStatusChip, monthYearText, paiseFromRupees, trimPercent } from './purchase.util';

export interface DebitNoteData {
  storeId: string;
  dnId: string;
}

export interface DebitNoteResult {
  changed: boolean;
  next: 'approvals' | null;
}

export const DEBIT_NOTE_CONFIG = {
  panelClass: 'sheet-panel',
  width: '900px',
  maxWidth: '100vw',
  ariaLabelledBy: 'dn-title',
} as const;

type Panel = 'cancel' | 'hand-over' | 'credit-note' | 'discard' | null;

const STEP_LABELS: Record<DebitNoteStepKey, string> = {
  CREATED: 'Created',
  PICKED: 'Picked & packed',
  HANDED_OVER: 'Handed to vendor rep',
  CREDIT_NOTE_RECEIVED: 'Credit note received',
  ADJUSTED_IN_PAYABLE: 'Adjusted in payable',
};

/**
 * "Track DN-…" (story 79-8, mockup 09): the debit note with its lines, the steps Created, Picked & packed, Handed to
 * vendor rep, Credit note received and Adjusted in payable (who and when each), and a button for the next step. A
 * Draft (for example one prepared by a receipt) is created with "Create debit note & pick list"; a debit note not yet
 * handed over can be cancelled with a reason; the hand-over records the representative's name (no photo or signature,
 * OQ-79-25) and, for a Schedule X line, a second signer; the credit note records number, date and amount and the
 * difference from the debit note is shown. Printing is the browser print view of the pick list.
 */
@Component({
  selector: 'app-debit-note',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDatepickerModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
  ],
  providers: [provideIsoDateAdapter()],
  styles: [
    `
      .scroll {
        overflow-x: auto;
      }
      table {
        min-width: 760px;
      }
      .num {
        text-align: right;
        white-space: nowrap;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      ol.track {
        list-style: none;
        margin: 8px 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      ol.track li[data-done='true'] {
        font-weight: 600;
      }
      ol.hist {
        margin: 4px 0;
        padding-left: 18px;
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
        @if (dn(); as d) {
          <h2 id="dn-title" data-testid="dn-title">
            Debit note {{ d.number ?? '(draft)' }} · {{ d.lines.length }} {{ d.lines.length === 1 ? 'line' : 'lines' }} ·
            {{ money(d.creditTotalPaise) }} + GST reversal {{ money(d.gstReversalPaise) }}
            <span class="pill" [class]="chip().css" data-testid="dn-status">{{ chip().text }}</span>
          </h2>
          <p>{{ d.vendor.name }}</p>
        } @else {
          <h2 id="dn-title">Debit note</h2>
        }
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      @if (loading()) {
        <p class="loading" role="status">Loading debit note</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="dn-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else if (dn(); as d) {
        <div class="scroll">
          <table data-testid="dn-lines">
            <thead>
              <tr>
                <th scope="col">Item · batch</th>
                <th scope="col">Type</th>
                <th scope="col" class="num">Qty</th>
                <th scope="col" class="num">Rate</th>
                <th scope="col" class="num">Credit %</th>
                <th scope="col" class="num">Credit</th>
                <th scope="col" class="num">GST reversal</th>
              </tr>
            </thead>
            <tbody>
              @for (line of d.lines; track line.id) {
                <tr>
                  <td>
                    {{ line.product.name }}
                    <div class="sub">
                      {{ line.batch ? line.batch.batchNo + ' · ' + month(line.batch.expiryDate) : '—' }}
                      @if (line.scheduleX) {
                        · Schedule X
                      }
                    </div>
                  </td>
                  <td>{{ line.type === 'SHORT_SUPPLY' ? 'Short supply' : 'Stock return' }}</td>
                  <td class="num">{{ line.qty }}</td>
                  <td class="num">{{ rate(line.rate) }}</td>
                  <td class="num">
                    {{ percent(line.creditPercent) }}%
                    @if (line.creditPercentChanged) {
                      <div class="sub">Changed from policy</div>
                    }
                  </td>
                  <td class="num">{{ money(line.creditPaise) }}</td>
                  <td class="num">{{ money(line.gstReversalPaise) }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        @if (d.reason) {
          <p class="sub">Reason: {{ d.reason.label }}</p>
        }

        @if (d.approval; as a) {
          <div class="notice teal" role="status" data-testid="dn-approval">
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
              <button mat-stroked-button type="button" data-testid="dn-open-approvals" (click)="finish('approvals')">
                Open in approvals
              </button>
            }
          </div>
        }

        <h3>Track {{ d.number ?? 'debit note' }}</h3>
        <ol class="track" data-testid="dn-steps">
          @for (step of d.steps; track step.step) {
            <li [attr.data-done]="step.at !== null" [attr.data-testid]="'dn-step-' + step.step">
              {{ stepLabel(step.step) }}
              @if (step.at) {
                · {{ step.by?.name ?? 'System' }} · {{ when(step.at) }}
              } @else {
                <span class="sub"> · not yet</span>
              }
            </li>
          }
        </ol>

        @if (d.creditNote; as c) {
          <p data-testid="dn-credit-note">
            Credit note {{ c.creditNoteNo }} · {{ date(c.creditNoteDate) }} · {{ money(c.amountPaise) }}
            @if (c.differencePaise !== 0) {
              <br /><span class="sub" data-testid="dn-difference">
                Difference from the debit note {{ signed(c.differencePaise) }}
              </span>
            }
          </p>
        }
        @if (d.payableEffect; as p) {
          <p class="sub" data-testid="dn-payable">
            Adjusted in payable {{ money(p.appliedPaise) }}
            @if (p.unappliedCreditPaise > 0) {
              · {{ money(p.unappliedCreditPaise) }} of credit is not yet applied
            }
          </p>
        }

        @switch (panel()) {
          @case ('cancel') {
            <div class="notice" data-testid="dn-cancel-panel">
              <span>Cancel this debit note. Held units go back to where they came from. It keeps its number and shows Cancelled.</span>
            </div>
            <div class="reasons" role="group" aria-label="Reason">
              @for (r of reasons(); track r.reasonKey) {
                <button
                  type="button"
                  [attr.aria-pressed]="reasonKey() === r.reasonKey"
                  [attr.data-testid]="'dn-reason-' + r.reasonKey"
                  (click)="reasonKey.set(r.reasonKey)"
                >
                  {{ r.label }}
                </button>
              }
            </div>
          }
          @case ('hand-over') {
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Vendor representative's name</mat-label>
              <input matInput [formControl]="repName" maxlength="80" autocomplete="off" data-testid="f-dn-rep" />
            </mat-form-field>
            @if (hasScheduleX()) {
              <div class="notice teal" data-testid="dn-signer-note">
                <app-icon name="lock" [size]="16" />
                <span>Schedule X needs a pharmacist and a second signature by a different person.</span>
              </div>
              @if (!canSign()) {
                <div class="notice warn" role="status" data-testid="dn-pharmacist-needed">
                  <span>A pharmacist must hand over a Schedule X item.</span>
                </div>
              } @else {
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>Second signer</mat-label>
                  <mat-select [formControl]="signerId" placeholder="Choose a person" data-testid="f-dn-signer">
                    @for (person of signers(); track person.membershipId) {
                      <mat-option [value]="person.membershipId">{{ person.name }} · {{ person.roleLabel }}</mat-option>
                    }
                  </mat-select>
                </mat-form-field>
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>Their PIN</mat-label>
                  <input matInput type="password" inputmode="numeric" autocomplete="off" maxlength="12" [formControl]="pin" data-testid="f-dn-pin" />
                </mat-form-field>
                @if (signersLoaded() && signers().length === 0) {
                  <p class="notice" role="status" data-testid="dn-no-second-signer">
                    No other pharmacist or owner is available to sign.
                  </p>
                }
              }
            }
          }
          @case ('credit-note') {
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Credit note number</mat-label>
              <input matInput [formControl]="cnNo" maxlength="30" autocomplete="off" data-testid="f-cn-no" />
            </mat-form-field>
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Credit note date</mat-label>
              <input matInput [matDatepicker]="cnPicker" [formControl]="cnDate" placeholder="24 Oct 2026" autocomplete="off" data-testid="f-cn-date" />
              <mat-datepicker-toggle matIconSuffix [for]="cnPicker" aria-label="Choose the credit note date" />
              <mat-datepicker #cnPicker />
            </mat-form-field>
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Credit note amount</mat-label>
              <input matInput [formControl]="cnAmount" inputmode="decimal" autocomplete="off" placeholder="₹ 0.00" data-testid="f-cn-amount" />
            </mat-form-field>
          }
          @case ('discard') {
            <div class="notice warn" data-testid="dn-discard-panel">
              <span>Discard this draft? Short supply on a receipt becomes eligible again.</span>
            </div>
          }
        }

        @if (panelError(); as text) {
          <div class="notice err" role="alert" data-testid="dn-action-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }

        <h3>History</h3>
        <ol class="hist" data-testid="dn-history">
          @for (h of d.history; track h.at + h.event) {
            <li>
              {{ eventText(h.event) }} · {{ h.by?.name ?? 'System' }}{{ h.actingAs ? ' (' + acting(h.actingAs) + ')' : '' }}
              · {{ when(h.at) }}{{ h.qtyAfter !== null ? ' · ' + h.qtyAfter : '' }}{{ h.reason ? ' · ' + h.reason.label : '' }}
            </li>
          }
        </ol>
      }
    </div>

    <div class="sheet-f">
      @if (dn(); as d) {
        <div class="actions">
          @if (panel() !== null) {
            <button mat-stroked-button type="button" data-testid="dn-panel-back" (click)="closePanel()">Back</button>
            <button mat-flat-button type="button" data-testid="dn-panel-confirm" [disabled]="busy() || !panelReady()" (click)="confirmPanel()">
              {{ confirmLabel() }}
            </button>
          } @else {
            @if (d.status === 'DRAFT') {
              <button mat-stroked-button type="button" data-testid="dn-discard" (click)="open('discard')">Discard</button>
              <button mat-flat-button type="button" data-testid="dn-submit" [disabled]="busy()" (click)="submit()">
                Create debit note & pick list
              </button>
            }
            @if (d.status === 'CREATED' || d.status === 'PICKED') {
              <button mat-stroked-button type="button" data-testid="dn-print" (click)="print()">Print pick list</button>
            }
            @if (d.status === 'CREATED') {
              <button mat-stroked-button type="button" data-testid="dn-picked" [disabled]="busy()" (click)="picked()">
                Mark picked & packed
              </button>
            }
            @if (d.status === 'CREATED' || d.status === 'PICKED') {
              @if (!shortOnly()) {
                <button mat-flat-button type="button" data-testid="dn-hand-over" (click)="open('hand-over')">
                  Handed to vendor rep
                </button>
              }
            }
            @if (d.status === 'HANDED_OVER' || ((d.status === 'CREATED' || d.status === 'PICKED') && shortOnly())) {
              <button mat-flat-button type="button" data-testid="dn-credit" (click)="open('credit-note')">
                Credit note received
              </button>
            }
            @if (d.status === 'WAITING_APPROVAL' || d.status === 'CREATED' || d.status === 'PICKED') {
              <button mat-stroked-button type="button" data-testid="dn-cancel" (click)="open('cancel')">
                Cancel debit note
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
export class DebitNoteDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);
  private readonly ref = inject<MatDialogRef<DebitNoteDialog, DebitNoteResult>>(MatDialogRef);
  protected readonly data = inject<DebitNoteData>(MAT_DIALOG_DATA);

  protected readonly dn = signal<DebitNote | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly panel = signal<Panel>(null);
  protected readonly panelError = signal<string | null>(null);
  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly reasonKey = signal('');
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);

  protected readonly repName = new FormControl('', { nonNullable: true });
  protected readonly signerId = new FormControl('', { nonNullable: true });
  protected readonly pin = new FormControl('', { nonNullable: true });
  protected readonly cnNo = new FormControl('', { nonNullable: true });
  protected readonly cnDate = new FormControl<string | null>(istToday());
  protected readonly cnAmount = new FormControl('', { nonNullable: true });

  private changed = false;
  private submitKey = newClientKey();
  private handOverKey = newClientKey();
  private creditKey = newClientKey();

  protected readonly chip = computed(() => dnStatusChip(this.dn()?.status ?? 'DRAFT'));
  protected readonly hasScheduleX = computed(() => this.dn()?.lines.some((l) => l.scheduleX) ?? false);
  /** Only a person with the Schedule X capability can hand over such a line (rule 19). */
  protected readonly canSign = computed(() => this.context.canHandleScheduleX());
  /** A debit note of short supply only has no stock to pick or hand over (OQ-79-9a). */
  protected readonly shortOnly = computed(() => {
    const lines = this.dn()?.lines ?? [];
    return lines.length > 0 && lines.every((l) => l.type === 'SHORT_SUPPLY');
  });

  protected readonly money = rupees;
  protected readonly rate = rateText;
  protected readonly month = monthYearText;
  protected readonly percent = trimPercent;
  protected readonly date = formatDisplayDate;
  protected readonly when = (instant: string) => formatDisplayDateTimeOfInstant(instant);
  protected readonly eventText = historyEventText;
  protected readonly acting = actingAsText;
  protected readonly stepLabel = (step: DebitNoteStepKey) => STEP_LABELS[step];
  protected readonly signed = (paise: number) => (paise > 0 ? '+' : '−') + rupees(Math.abs(paise));

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.debitNote(this.data.storeId, this.data.dnId).subscribe({
      next: (dn) => {
        this.dn.set(dn);
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

  protected finish(next: 'approvals'): void {
    this.ref.close({ changed: this.changed, next });
  }

  protected print(): void {
    const d = this.dn();
    if (d) {
      openPrintView(pickListPrintHtml(d));
    }
  }

  protected open(panel: Panel): void {
    this.panelError.set(null);
    this.reasonKey.set('');
    this.panel.set(panel);
    if (panel === 'cancel' && this.reasons().length === 0) {
      this.api.activeReasons(['approval-reject']).subscribe({
        next: (answer) => this.reasons.set(answer.lists.find((l) => l.listKey === 'approval-reject')?.reasons ?? []),
        error: (error: unknown) => this.panelError.set(loadErrorMessage(error)),
      });
    }
    if (panel === 'hand-over' && this.hasScheduleX() && !this.signersLoaded()) {
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

  protected closePanel(): void {
    this.panel.set(null);
    this.panelError.set(null);
  }

  protected panelReady(): boolean {
    switch (this.panel()) {
      case 'cancel':
        return this.reasonKey() !== '';
      case 'hand-over':
        return (
          this.repName.value.trim().length > 0 &&
          (!this.hasScheduleX() || (this.canSign() && this.signerId.value !== '' && this.pin.value !== ''))
        );
      case 'credit-note':
        return this.cnNo.value.trim().length > 0 && !!this.cnDate.value && paiseFromRupees(this.cnAmount.value) !== null;
      default:
        return true;
    }
  }

  protected confirmLabel(): string {
    switch (this.panel()) {
      case 'cancel':
        return 'Cancel debit note';
      case 'hand-over':
        return 'Confirm hand-over';
      case 'credit-note':
        return 'Record credit note';
      default:
        return 'Discard draft';
    }
  }

  protected submit(): void {
    const d = this.dn();
    if (d) {
      this.run(this.api.submitDebitNote(this.data.storeId, d.id, { clientKey: this.submitKey, version: d.version }));
    }
  }

  protected picked(): void {
    const d = this.dn();
    if (d) {
      this.run(this.api.markDebitNotePicked(this.data.storeId, d.id, { version: d.version }));
    }
  }

  protected confirmPanel(): void {
    const d = this.dn();
    const panel = this.panel();
    if (!d || panel === null || !this.panelReady()) {
      return;
    }
    const storeId = this.data.storeId;
    switch (panel) {
      case 'cancel':
        this.run(this.api.cancelDebitNote(storeId, d.id, { version: d.version, reasonKey: this.reasonKey() }));
        break;
      case 'hand-over':
        this.run(
          this.api.handOverDebitNote(storeId, d.id, {
            clientKey: this.handOverKey,
            version: d.version,
            representativeName: this.repName.value.trim(),
            secondSigner: this.hasScheduleX()
              ? { membershipId: this.signerId.value, pin: this.pin.value }
              : null,
          }),
        );
        break;
      case 'credit-note':
        this.run(
          this.api.recordCreditNote(storeId, d.id, {
            clientKey: this.creditKey,
            version: d.version,
            creditNoteNo: this.cnNo.value.trim(),
            creditNoteDate: this.cnDate.value ?? '',
            amountPaise: paiseFromRupees(this.cnAmount.value) ?? 0,
          }),
        );
        break;
      case 'discard':
        this.run(this.api.discardDebitNote(storeId, d.id, { version: d.version }), true);
        break;
    }
  }

  private run(request: ReturnType<PharmacyApi['debitNote']>, closeAfter = false): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.panelError.set(null);
    request.subscribe({
      next: (dn) => {
        this.busy.set(false);
        this.pin.setValue('');
        this.changed = true;
        this.panel.set(null);
        this.submitKey = newClientKey();
        this.handOverKey = newClientKey();
        this.creditKey = newClientKey();
        if (closeAfter) {
          this.ref.close({ changed: true, next: null });
          return;
        }
        this.dn.set(dn);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.pin.setValue('');
        this.panelError.set(saveErrorMessage(error));
      },
    });
  }
}
