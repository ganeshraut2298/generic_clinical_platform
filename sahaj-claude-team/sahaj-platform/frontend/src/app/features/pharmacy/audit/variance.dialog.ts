import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { ReasonChipsComponent } from '../indent/reason-chips.component';
import { SecondSignerComponent } from '../indent/second-signer.component';
import { pinProblem } from '../indent/indent.util';
import { expiryMonthYear } from '../inventory/inventory.util';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason, SecondSignerCandidate } from '../pharmacy-inventory.model';
import { StockAudit, VarianceReview } from '../pharmacy-stockcount.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { signedQty, signedRupees } from '../stockuse/stockuse.util';

export interface VarianceData {
  storeId: string;
  auditId: string;
}

/** The dialog answers the audit it changed, or the "post as consumption instead" line the reviewer chose. */
export type VarianceResult = { audit: StockAudit } | { consumptionLineId: string } | null;

export const VARIANCE_CONFIG = {
  panelClass: 'sheet-panel',
  width: '1000px',
  maxWidth: '100vw',
  ariaLabelledBy: 'variance-title',
} as const;

const CONSUMPTION_REASON = 'stock-adjustment.consumption-not-entered';

/**
 * "Variance review · <store> HOD" (story 79-17, mockup 13): the reviewer sees the book quantity here and only here.
 * Item, System, Counted, Diff (a loss in red), Reason chips, with "Recount" and "Approve & post adjustment". The note
 * "Consumption not entered can be fixed by posting the missed consumption against a patient instead of a loss" becomes
 * the button "Post as consumption instead" on a line of that reason with a loss. A line that moved since the count
 * must be recounted ("Stock moved since the count. Recount."). Whether the adjustment posts or waits for approval is
 * the server's rule; the person who submitted the count cannot approve it. A Schedule X line needs a second signer.
 */
@Component({
  selector: 'app-variance',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, IconComponent, ReasonChipsComponent, SecondSignerComponent],
  styles: [
    `
      .scroll {
        overflow-x: auto;
      }
      table {
        min-width: 860px;
      }
      .num {
        text-align: right;
        white-space: nowrap;
      }
      .loss {
        color: var(--crit);
        font-weight: 700;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      .row-err {
        color: var(--crit);
        font-size: 0.78rem;
      }
      .tiles {
        display: flex;
        flex-wrap: wrap;
        gap: 12px;
        font-weight: 600;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="variance-title">Variance review</h2>
        @if (review(); as r) {
          <p data-testid="variance-header">{{ r.store.name }} · audit {{ r.auditNo }}</p>
        }
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="variance-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      @if (done(); as audit) {
        <div class="notice good" role="status" data-testid="variance-done">
          <app-icon name="check" [size]="16" />
          <span>{{ doneText(audit) }}</span>
        </div>
      }
      @if (review(); as r) {
        <p class="tiles" data-testid="variance-tiles">
          <span>With variance {{ r.lines.length }}</span>
          <span>Variance value {{ money(r.varianceValuePaise) }}</span>
        </p>
        @if (r.lines.length === 0) {
          <p data-testid="variance-none">Every counted line matches the book. Nothing to adjust.</p>
        } @else {
          <div class="scroll">
            <table data-testid="variance-table">
              <thead>
                <tr>
                  <th scope="col"><span class="sr-only">Recount</span></th>
                  <th scope="col">Item · batch</th>
                  <th scope="col" class="num">System</th>
                  <th scope="col" class="num">Counted</th>
                  <th scope="col" class="num">Diff</th>
                  <th scope="col" class="num">Value</th>
                  <th scope="col">Reason</th>
                </tr>
              </thead>
              <tbody>
                @for (line of r.lines; track line.auditLineId; let n = $index) {
                  <tr [attr.data-testid]="'variance-line-' + n">
                    <td>
                      <input
                        type="checkbox"
                        [checked]="recountIds().includes(line.auditLineId)"
                        [attr.aria-label]="'Recount ' + line.product.name"
                        [attr.data-testid]="'variance-recount-' + n"
                        [disabled]="!!done()"
                        (change)="toggleRecount(line.auditLineId)"
                      />
                    </td>
                    <td>
                      {{ line.product.name }}
                      @if (line.scheduleX) {
                        <span class="pill p-warn">Schedule X</span>
                      }
                      <div class="sub">{{ line.batch.batchNo }} · {{ month(line.batch.expiryDate) }} · counted by {{ line.counter.name }}</div>
                      @if (line.stockMoved) {
                        <div class="row-err" role="alert" [attr.data-testid]="'variance-moved-' + n">
                          Stock moved since the count. Recount.
                        </div>
                      }
                    </td>
                    <td class="num" [attr.data-testid]="'variance-system-' + n">{{ line.system }}</td>
                    <td class="num">{{ line.counted }}</td>
                    <td class="num" [class.loss]="line.diff < 0" [attr.data-testid]="'variance-diff-' + n">{{ qty(line.diff) }}</td>
                    <td class="num">{{ money(line.valuePaise) }}</td>
                    <td>
                      <app-reason-chips
                        [reasons]="reasons()"
                        [value]="reasonOf(line.auditLineId)"
                        label="Reason for the difference"
                        [testIdPrefix]="'variance-reason-' + n + '-'"
                        (chosen)="setReason(line.auditLineId, $event.reasonKey)"
                      />
                      @if (line.suggestConsumption || (reasonOf(line.auditLineId) === consumptionReason && line.diff < 0)) {
                        <button
                          mat-stroked-button
                          type="button"
                          [attr.data-testid]="'variance-consumption-' + n"
                          (click)="postAsConsumption(line.auditLineId)"
                        >
                          Post as consumption instead
                        </button>
                      }
                      @if (lineError(line.auditLineId); as text) {
                        <div class="row-err" role="alert">{{ text }}</div>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <p class="sub">
            "Consumption not entered" can be fixed by posting the missed consumption against a patient instead of a loss.
            Whether an adjustment waits for approval is decided by your approval rule.
          </p>
        }
        @if (needsSigner()) {
          <p class="notice teal" data-testid="variance-x-note">
            <app-icon name="lock" [size]="16" />
            <span>A Schedule X line differs. It needs a pharmacist and a second signature by a different person.</span>
          </p>
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
      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="variance-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </div>

    <div class="sheet-f">
      @if (done()) {
        <button mat-flat-button type="button" data-testid="variance-finish" (click)="finish()">Close</button>
      } @else {
        <button mat-stroked-button type="button" (click)="close()">Cancel</button>
        <button
          mat-stroked-button
          type="button"
          data-testid="variance-recount"
          [disabled]="saving() || recountIds().length === 0"
          (click)="recount()"
        >
          Recount
        </button>
        <button
          mat-flat-button
          type="button"
          data-testid="variance-approve"
          [disabled]="saving() || !review()"
          (click)="approve()"
        >
          Approve & post adjustment
        </button>
      }
    </div>
  `,
})
export class VarianceDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<VarianceDialog, VarianceResult>>(MatDialogRef);
  protected readonly data = inject<VarianceData>(MAT_DIALOG_DATA);

  protected readonly review = signal<VarianceReview | null>(null);
  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly chosen = signal<Record<string, string>>({});
  protected readonly recountIds = signal<string[]>([]);
  protected readonly lineErrors = signal<Record<string, string>>({});
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly signerId = signal('');
  protected readonly pin = signal('');
  protected readonly signerError = signal<string | null>(null);
  protected readonly pinError = signal<string | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly done = signal<StockAudit | null>(null);

  private clientKey = newClientKey();

  protected readonly consumptionReason = CONSUMPTION_REASON;
  protected readonly money = signedRupees;
  protected readonly qty = signedQty;
  protected readonly month = expiryMonthYear;

  protected readonly needsSigner = computed(() => this.review()?.lines.some((l) => l.scheduleX === true) === true && !this.done());

  ngOnInit(): void {
    this.load();
    this.api.activeReasons(['stock-adjustment']).subscribe({
      next: (answer) => this.reasons.set(answer.lists.find((l) => l.listKey === 'stock-adjustment')?.reasons ?? []),
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

  private load(): void {
    this.api.varianceReview(this.data.storeId, this.data.auditId).subscribe({
      next: (review) => {
        this.review.set(review);
        const picked: Record<string, string> = {};
        for (const line of review.lines) {
          if (line.reason) {
            picked[line.auditLineId] = line.reason.reasonKey;
          }
        }
        this.chosen.set(picked);
      },
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
  }

  protected reasonOf(lineId: string): string | null {
    return this.chosen()[lineId] ?? null;
  }

  protected lineError(lineId: string): string | null {
    return this.lineErrors()[lineId] ?? null;
  }

  protected setReason(lineId: string, reasonKey: string): void {
    this.chosen.update((c) => ({ ...c, [lineId]: reasonKey }));
    this.lineErrors.update((e) => ({ ...e, [lineId]: '' }));
  }

  protected toggleRecount(lineId: string): void {
    this.recountIds.update((ids) => (ids.includes(lineId) ? ids.filter((i) => i !== lineId) : [...ids, lineId]));
  }

  protected postAsConsumption(lineId: string): void {
    this.ref.close({ consumptionLineId: lineId });
  }

  protected doneText(audit: StockAudit): string {
    if (audit.status === 'CLOSED') {
      return `Adjustment posted. Audit ${audit.number} is closed.`;
    }
    if (audit.status === 'RECOUNT') {
      return 'Sent back to the counter for a recount.';
    }
    return audit.approval?.waitingFor === 'OWNER'
      ? 'Waiting for owner approval. The stock changes when it is approved.'
      : 'Waiting for approval. The stock changes when it is approved.';
  }

  protected recount(): void {
    const review = this.review();
    if (this.saving() || !review || this.recountIds().length === 0) {
      return;
    }
    this.formError.set(null);
    this.saving.set(true);
    this.api
      .requestRecount(this.data.storeId, this.data.auditId, { version: review.version, auditLineIds: this.recountIds() })
      .subscribe({
        next: (audit) => {
          this.saving.set(false);
          this.done.set(audit);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }

  protected approve(): void {
    const review = this.review();
    if (this.saving() || !review) {
      return;
    }
    this.formError.set(null);
    this.signerError.set(null);
    this.pinError.set(null);
    const missing: Record<string, string> = {};
    for (const line of review.lines) {
      if (!this.chosen()[line.auditLineId]) {
        missing[line.auditLineId] = 'Choose a reason';
      }
    }
    this.lineErrors.set(missing);
    if (Object.keys(missing).length > 0) {
      return;
    }
    let secondSigner: { membershipId: string; pin: string } | null = null;
    if (this.needsSigner()) {
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
    const lines = review.lines.map((l) => ({ auditLineId: l.auditLineId, reasonKey: this.chosen()[l.auditLineId] }));
    const reasons$ =
      lines.length > 0
        ? this.api.saveVarianceReasons(this.data.storeId, this.data.auditId, { version: review.version, lines })
        : null;
    if (reasons$ === null) {
      this.post(review.version, secondSigner);
      return;
    }
    reasons$.subscribe({
      next: (saved) => {
        this.review.set(saved);
        this.post(saved.version, secondSigner);
      },
      error: (error: unknown) => this.failed(error),
    });
  }

  private post(version: number, secondSigner: { membershipId: string; pin: string } | null): void {
    this.api
      .approveAndPostAudit(this.data.storeId, this.data.auditId, { clientKey: this.clientKey, version, secondSigner })
      .subscribe({
        next: (audit) => {
          this.pin.set('');
          this.saving.set(false);
          this.done.set(audit);
        },
        error: (error: unknown) => this.failed(error),
      });
  }

  private failed(error: unknown): void {
    this.pin.set('');
    this.saving.set(false);
    this.formError.set(saveErrorMessage(error));
    // A line moved: the screen reloads so that the reviewer sees which line to recount.
    if (problemSlug(error) === 'stock-moved' || problemSlug(error) === 'changed') {
      this.clientKey = newClientKey();
      this.load();
    }
  }

  protected finish(): void {
    const audit = this.done();
    this.ref.close(audit ? { audit } : null);
  }

  protected close(): void {
    const audit = this.done();
    this.ref.close(audit ? { audit } : null);
  }
}
