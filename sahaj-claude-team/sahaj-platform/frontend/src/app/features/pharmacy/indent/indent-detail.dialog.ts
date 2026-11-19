import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason, ApprovalRequest } from '../pharmacy-inventory.model';
import { Indent, IndentLine } from '../pharmacy-indent.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { HistoryListComponent } from './history-list.component';
import {
  COUNTING_IN_PROGRESS,
  hodChangeText,
  indentChips,
  indentHeaderLine,
  lineDecisionText,
  qtyText,
} from './indent.util';
import { ReasonChipsComponent } from './reason-chips.component';

export interface IndentDetailData {
  /** The path store: the requesting store, or the issuing store at the Central Store. */
  storeId: string;
  indentId: string;
}

export const INDENT_DETAIL_CONFIG = {
  panelClass: 'sheet-panel',
  width: '960px',
  maxWidth: '100vw',
  ariaLabelledBy: 'indent-detail-title',
} as const;

type Step = 'view' | 'cancel' | 'reject';

/**
 * One indent (stories 79-9 and 79-10, mockup 10 steps 1 and 2): the header line, the status chips, the lines, the
 * "Stock impact" card of the selected line (only the indent's two stores), the dispatches and the "History" card.
 * For the HOD or the current delegate whom the server lists as able to decide, the card becomes "Approve indent": the
 * approved quantity of each line (starting at the requested quantity), "Change" ("+4 by HOD"), the line decision, the
 * Remark (a reason chip, and a note for "Other"), "Reject indent" and "Approve & send to Central Store". The
 * raiser may cancel a submitted indent before dispatch with a reason chip. The server checks every rule again.
 */
@Component({
  selector: 'app-indent-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, IconComponent, HistoryListComponent, ReasonChipsComponent],
  styles: [
    `
      .scroll {
        overflow-x: auto;
      }
      table {
        min-width: 640px;
      }
      .num {
        text-align: right;
        white-space: nowrap;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        margin-bottom: 8px;
      }
      input.cell {
        min-height: 44px;
        width: 100%;
        min-width: 80px;
        padding: 6px 8px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
        background: var(--glass-hi);
        font: inherit;
      }
      textarea.note {
        width: 100%;
        min-height: 64px;
        padding: 8px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
        background: var(--glass-hi);
        font: inherit;
      }
      .row-err {
        color: var(--crit);
        font-size: 0.78rem;
      }
      .impact {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
        gap: 10px;
        margin: 0;
      }
      .impact div {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .impact dt {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      .impact dd {
        margin: 0;
        font-weight: 700;
      }
      .linkbtn {
        border: 0;
        background: transparent;
        padding: 0;
        min-height: 44px;
        text-align: left;
        font-weight: 600;
        color: var(--pri-text);
        text-decoration: underline;
        cursor: pointer;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="indent-detail-title">{{ approverMode() ? 'Approve indent' : 'Indent' }}</h2>
        @if (indent(); as i) {
          <p data-testid="indent-header">{{ header(i) }}</p>
        }
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="indent-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      @if (indent(); as i) {
        <p data-testid="indent-detail-acting-as">{{ i.actingAs }}</p>
        <div class="chips" data-testid="indent-chips">
          @for (chip of chips(i); track chip.text) {
            <span class="pill" [class]="chip.css">{{ chip.text }}</span>
          }
        </div>
        @if (i.cancelReason; as r) {
          <p class="sub" data-testid="indent-cancel-reason">Cancelled · {{ r.label }}</p>
        }
        @if (i.approval?.ruleText; as rule) {
          <p class="sub" data-testid="indent-rule">{{ rule }}</p>
        }

        <div class="scroll">
          <table data-testid="indent-detail-lines">
            <thead>
              <tr>
                <th scope="col">Item</th>
                <th scope="col" class="num">Requested</th>
                <th scope="col" class="num">Approved qty</th>
                @if (approverMode()) {
                  <th scope="col">Change</th>
                  <th scope="col">Line decision</th>
                } @else {
                  <th scope="col" class="num">Dispatched</th>
                  <th scope="col" class="num">Received</th>
                  <th scope="col" class="num">Rejected</th>
                  <th scope="col" class="num">Short</th>
                  <th scope="col">Balance</th>
                }
                <th scope="col"><span class="sr-only">Stock impact</span></th>
              </tr>
            </thead>
            <tbody>
              @for (line of i.lines; track line.id; let n = $index) {
                <tr [attr.data-testid]="'indent-row-' + n">
                  <td>
                    {{ line.product.name }}
                    @if (line.product.schedule !== 'NONE') {
                      <span class="pill p-warn">Schedule {{ line.product.schedule }}</span>
                    }
                    <div class="sub">{{ line.product.strength }} · {{ line.product.form }}</div>
                    @if (line.replacement; as r) {
                      <div class="sub" [attr.data-testid]="'indent-replaced-' + n">
                        replaced by Central Store · {{ r.product.name }} · {{ r.reason.label }}
                      </div>
                    }
                    @if (!approverMode()) {
                      <div class="sub">{{ i.fromStore.name }} stock: {{ stock(line.requestingStoreAvailable) }}</div>
                    }
                  </td>
                  <td class="num">{{ line.requestedQty }}</td>
                  @if (approverMode()) {
                    <td class="num">
                      <input
                        class="cell"
                        inputmode="numeric"
                        autocomplete="off"
                        [value]="approved()[n]"
                        [attr.aria-label]="'Approved quantity of ' + line.product.name"
                        [attr.data-testid]="'indent-approved-' + n"
                        (input)="setApproved(n, $any($event.target).value)"
                      />
                      @if (lineError()[n]; as text) {
                        <div class="row-err" role="alert">{{ text }}</div>
                      }
                    </td>
                    <td [attr.data-testid]="'indent-change-' + n">{{ changeText(line, n) }}</td>
                    <td [attr.data-testid]="'indent-decision-' + n">{{ decisionText(n) }}</td>
                  } @else {
                    <td class="num">{{ qtyText(line.approvedQty) }}</td>
                    <td class="num">{{ line.dispatchedQty }}</td>
                    <td class="num">{{ line.receivedQty }}</td>
                    <td class="num">{{ line.rejectedQty }}</td>
                    <td class="num">{{ line.shortQty }}</td>
                    <td>{{ balance(line) }}</td>
                  }
                  <td>
                    <button
                      type="button"
                      class="linkbtn"
                      [attr.data-testid]="'indent-impact-' + n"
                      [attr.aria-label]="'Stock impact of ' + line.product.name"
                      (click)="selected.set(n)"
                    >
                      Stock impact
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        @if (impactLine(); as line) {
          <h3 data-testid="indent-impact-title">Stock impact · {{ line.product.name }}</h3>
          <dl class="impact" data-testid="indent-impact">
            <div>
              <dt>Central Store · saleable</dt>
              <dd data-testid="impact-cs">{{ impactQty(line.stockImpact.csSaleable) }}</dd>
            </div>
            <div>
              <dt>In transit to {{ i.fromStore.name }}</dt>
              <dd data-testid="impact-transit">{{ line.stockImpact.inTransit }}</dd>
            </div>
            <div>
              <dt>{{ i.fromStore.name }} available</dt>
              <dd data-testid="impact-available">{{ impactQty(line.stockImpact.requestingAvailable) }}</dd>
            </div>
            <div>
              <dt>Pending return</dt>
              <dd data-testid="impact-pending">{{ impactQty(line.stockImpact.pendingReturn) }}</dd>
            </div>
            <div>
              <dt>Back-order</dt>
              <dd data-testid="impact-backorder">{{ line.stockImpact.backOrder }}</dd>
            </div>
            <div>
              <dt>Short · under investigation</dt>
              <dd data-testid="impact-short">{{ line.stockImpact.shortInvestigating }}</dd>
            </div>
          </dl>
        }

        @if (i.dispatches.length > 0) {
          <h3>Dispatches</h3>
          <ul data-testid="indent-dispatches">
            @for (d of i.dispatches; track d.dispatchId) {
              <li>{{ d.reference }} · {{ dispatchLabel(d.status) }}</li>
            }
          </ul>
        }

        @if (approverMode()) {
          <h3>Remark (required when rejecting or reducing)</h3>
          <p class="sub">Required when you reduce or reject. Choose a reason; “Other” needs a note.</p>
          <app-reason-chips
            [reasons]="reasons()"
            [value]="reasonKey()"
            label="Remark"
            testIdPrefix="remark-"
            (chosen)="chooseReason($event)"
          />
          <textarea
            class="note"
            maxlength="300"
            aria-label="Remark note"
            data-testid="indent-remark-note"
            [value]="note()"
            (input)="note.set($any($event.target).value)"
          ></textarea>
        }
        @if (step() === 'cancel') {
          <h3>Cancel this indent</h3>
          <app-reason-chips
            [reasons]="reasons()"
            [value]="reasonKey()"
            label="Reason for cancelling"
            testIdPrefix="cancel-reason-"
            (chosen)="chooseReason($event)"
          />
          <textarea
            class="note"
            maxlength="300"
            aria-label="Cancel note"
            data-testid="indent-cancel-note"
            [value]="note()"
            (input)="note.set($any($event.target).value)"
          ></textarea>
        }

        @if (formError(); as text) {
          <div class="notice err" role="alert" data-testid="indent-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }

        <h3>History</h3>
        <app-history-list [events]="i.history" />
      }
    </div>

    <div class="sheet-f">
      @if (indent(); as i) {
        @if (approverMode()) {
          <button mat-stroked-button type="button" data-testid="indent-reject" [disabled]="saving()" (click)="reject()">
            Reject indent
          </button>
          <button mat-flat-button type="button" data-testid="indent-approve" [disabled]="saving()" (click)="approve()">
            Approve & send to Central Store
          </button>
        } @else if (canCancel(i)) {
          @if (step() === 'cancel') {
            <button mat-stroked-button type="button" (click)="step.set('view')">Keep indent</button>
            <button mat-flat-button type="button" data-testid="indent-cancel-confirm" [disabled]="saving()" (click)="cancel()">
              Cancel indent
            </button>
          } @else {
            <button mat-stroked-button type="button" data-testid="indent-cancel" (click)="startCancel()">Cancel indent</button>
          }
        }
      }
      <button mat-stroked-button type="button" (click)="close()">Close</button>
    </div>
  `,
})
export class IndentDetailDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<IndentDetailDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<IndentDetailData>(MAT_DIALOG_DATA);

  protected readonly indent = signal<Indent | null>(null);
  private readonly request = signal<ApprovalRequest | null>(null);
  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly step = signal<Step>('view');
  protected readonly selected = signal(0);
  protected readonly approved = signal<string[]>([]);
  protected readonly lineError = signal<(string | null)[]>([]);
  protected readonly reasonKey = signal<string | null>(null);
  protected readonly note = signal('');

  private readonly clientKey = newClientKey();

  protected readonly qtyText = qtyText;
  protected readonly header = indentHeaderLine;

  protected readonly approverMode = computed(() => {
    const i = this.indent();
    return i !== null && i.status === 'WAITING_APPROVAL' && i.approval?.canDecide === true;
  });
  protected readonly impactLine = computed(() => this.indent()?.lines[this.selected()] ?? null);

  ngOnInit(): void {
    this.api.indent(this.data.storeId, this.data.indentId).subscribe({
      next: (indent) => this.opened(indent),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
    this.api.activeReasons(['approval-reject']).subscribe({
      next: (answer) => this.reasons.set(answer.lists.find((l) => l.listKey === 'approval-reject')?.reasons ?? []),
      error: () => this.reasons.set([]),
    });
  }

  private opened(indent: Indent): void {
    this.indent.set(indent);
    this.approved.set(indent.lines.map((l) => String(l.approvedQty ?? l.requestedQty)));
    this.lineError.set(indent.lines.map(() => null));
    const approval = indent.approval;
    if (indent.status === 'WAITING_APPROVAL' && approval?.canDecide) {
      this.api.approvalRequest(approval.requestId).subscribe({
        next: (request) => this.request.set(request),
        error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
      });
    }
  }

  protected chips(indent: Indent) {
    return indentChips(indent);
  }

  protected stock(value: number | null): string {
    return value === null ? COUNTING_IN_PROGRESS : String(value);
  }

  protected impactQty(value: number | null): string {
    return value === null ? COUNTING_IN_PROGRESS : String(value);
  }

  protected balance(line: IndentLine): string {
    if (line.balanceAction === 'BACK_ORDER') {
      return `${line.remainingQty} back-ordered`;
    }
    if (line.balanceAction === 'CLOSE') {
      return `Closed${line.closeReason ? ' · ' + line.closeReason.label : ''}`;
    }
    return '—';
  }

  protected dispatchLabel(status: string): string {
    return status === 'IN_TRANSIT' ? 'In transit' : status === 'PARTLY_RECEIVED' ? 'Partly received' : 'Received';
  }

  // ---- approver

  protected setApproved(index: number, value: string): void {
    this.approved.update((list) => list.map((v, i) => (i === index ? value : v)));
    this.lineError.update((list) => list.map((v, i) => (i === index ? null : v)));
  }

  private approvedQty(index: number): number | null {
    const text = (this.approved()[index] ?? '').trim();
    return /^[0-9]+$/.test(text) && Number(text) <= 999999 ? Number(text) : null;
  }

  protected changeText(line: IndentLine, index: number): string {
    const qty = this.approvedQty(index);
    return qty === null ? '—' : hodChangeText(qty - line.requestedQty);
  }

  protected decisionText(index: number): string {
    const qty = this.approvedQty(index);
    return qty === null ? '—' : lineDecisionText(qty);
  }

  protected chooseReason(reason: ActiveReason): void {
    this.reasonKey.set(reason.reasonKey);
    this.formError.set(null);
  }

  /** The Remark is needed when a quantity is reduced or a line is rejected, and for "Other" a note too. */
  private remarkProblem(required: boolean): string | null {
    if (!required) {
      return null;
    }
    const reason = this.reasons().find((r) => r.reasonKey === this.reasonKey());
    if (!reason) {
      return 'Choose a reason for the remark.';
    }
    if (reason.needsNote && this.note().trim().length < 4) {
      return 'Write a note of at least 4 characters.';
    }
    return null;
  }

  protected approve(): void {
    const indent = this.indent();
    const request = this.request();
    if (this.saving() || !indent || !request || request.currentLevel === null) {
      return;
    }
    this.formError.set(null);
    let bad = false;
    const errors = indent.lines.map((_, i) => {
      const qty = this.approvedQty(i);
      bad = bad || qty === null;
      return qty === null ? 'Enter a whole number, 0 or more' : null;
    });
    this.lineError.set(errors);
    if (bad) {
      return;
    }
    const quantities = indent.lines.map((_, i) => this.approvedQty(i) ?? 0);
    if (quantities.every((q) => q === 0)) {
      this.formError.set('Every line is 0, so this is a rejection. Use “Reject indent” and give a remark.');
      return;
    }
    const reduced = indent.lines.some((l, i) => quantities[i] < l.requestedQty);
    const problem = this.remarkProblem(reduced);
    if (problem) {
      this.formError.set(problem);
      return;
    }
    this.decide(request, 'APPROVED', quantities, indent);
  }

  protected reject(): void {
    const indent = this.indent();
    const request = this.request();
    if (this.saving() || !indent || !request || request.currentLevel === null) {
      return;
    }
    this.formError.set(null);
    const problem = this.remarkProblem(true);
    if (problem) {
      this.formError.set(problem);
      return;
    }
    this.decide(request, 'REJECTED', null, indent);
  }

  private decide(request: ApprovalRequest, decision: 'APPROVED' | 'REJECTED', quantities: number[] | null, indent: Indent): void {
    if (request.currentLevel === null) {
      return;
    }
    const note = this.note().trim();
    this.saving.set(true);
    this.api
      .decideApproval(request.id, {
        clientKey: this.clientKey,
        level: request.currentLevel,
        requestVersion: request.version,
        decision,
        reasonKey: this.reasonKey(),
        note: note === '' ? null : note,
        ...(quantities
          ? { indentLines: indent.lines.map((l, i) => ({ indentLineId: l.id, approvedQty: quantities[i] })) }
          : {}),
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.ref.close(true);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }

  // ---- raiser

  protected canCancel(indent: Indent): boolean {
    return (indent.status === 'WAITING_APPROVAL' || indent.status === 'APPROVED') && indent.dispatches.length === 0;
  }

  protected startCancel(): void {
    this.reasonKey.set(null);
    this.note.set('');
    this.formError.set(null);
    this.step.set('cancel');
  }

  protected cancel(): void {
    const indent = this.indent();
    if (this.saving() || !indent) {
      return;
    }
    const problem = this.remarkProblem(true);
    if (problem) {
      this.formError.set(problem);
      return;
    }
    const note = this.note().trim();
    this.saving.set(true);
    this.api
      .cancelIndent(this.data.storeId, indent.id, {
        version: indent.version,
        reasonKey: this.reasonKey() ?? '',
        note: note === '' ? null : note,
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.ref.close(true);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }

  protected close(): void {
    this.ref.close(false);
  }
}
