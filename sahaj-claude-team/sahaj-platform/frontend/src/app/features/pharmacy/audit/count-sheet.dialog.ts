import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { pinProblem } from '../indent/indent.util';
import { SecondSignerComponent } from '../indent/second-signer.component';
import { expiryMonthYear } from '../inventory/inventory.util';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason, SecondSignerCandidate } from '../pharmacy-inventory.model';
import { CountLineInput, CountSheetLine, CountSheetPage, StockAudit } from '../pharmacy-stockcount.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';

export interface CountSheetData {
  storeId: string;
  auditId: string;
}

export const COUNT_SHEET_CONFIG = {
  panelClass: 'sheet-panel',
  width: '900px',
  maxWidth: '100vw',
  ariaLabelledBy: 'count-title',
} as const;

interface Row {
  line: CountSheetLine;
  /** What the counter typed; '' is not counted yet. */
  value: string;
  notCounted: boolean;
  reasonKey: string;
  error: string | null;
  /** Changed since the last save. */
  dirty: boolean;
}

/**
 * "Count sheet · <counter>" (story 79-17, mockup 13): Item · batch, Rack and Counted, with "Scan next item" and "Submit
 * count". The count is BLIND: this screen is built from a line type that has no book quantity, difference or value, so
 * nobody sees one here, the owner included (BR-30, rule 24). Counted values are saved as the counter goes, so a page
 * reload keeps them. A line may be marked "Not counted" with a reason. A recounted line is marked "Recount". A Schedule X
 * line needs a pharmacist; the submit then asks for a second signer.
 */
@Component({
  selector: 'app-count-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
    SecondSignerComponent,
  ],
  styles: [
    `
      .scroll {
        overflow-x: auto;
      }
      table {
        min-width: 700px;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      input.cell {
        min-height: 44px;
        width: 110px;
        padding: 6px 8px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
        background: var(--glass-hi);
        font: inherit;
      }
      .row-err {
        color: var(--crit);
        font-size: 0.78rem;
      }
      .tiles {
        display: flex;
        flex-wrap: wrap;
        gap: 14px;
        font-weight: 600;
      }
      .scan {
        display: flex;
        gap: 8px;
        align-items: center;
        flex-wrap: wrap;
      }
      tr.hit {
        background: var(--pri-soft);
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="count-title">Count sheet</h2>
        @if (sheet(); as s) {
          <p data-testid="count-header">{{ s.storeName }} · audit {{ s.auditNo }}</p>
        }
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      <p class="notice teal" data-testid="count-blind-note">
        <app-icon name="lock" [size]="16" />
        <span>Blind count · the counter does not see the system quantity, so the count is honest.</span>
      </p>
      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="count-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      @if (submitted(); as audit) {
        <div class="notice good" role="status" data-testid="count-done">
          <app-icon name="check" [size]="16" />
          <span>Count submitted. Audit {{ audit.number }} is waiting for review.</span>
        </div>
      }
      @if (sheet(); as s) {
        <p class="tiles" data-testid="count-tiles">
          <span>Items in this count {{ s.itemsInCount }}</span>
          <span>Counted {{ countedNow() }}</span>
        </p>
        <div class="scan">
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Scan next item</mat-label>
            <input
              matInput
              autocomplete="off"
              data-testid="count-scan"
              [value]="scan()"
              (input)="scan.set($any($event.target).value)"
              (keydown.enter)="scanNext($event)"
            />
          </mat-form-field>
          <button mat-stroked-button type="button" data-testid="count-scan-go" (click)="scanNext()">Find</button>
          @if (scanNote(); as text) {
            <span class="row-err" role="alert" data-testid="count-scan-note">{{ text }}</span>
          }
        </div>
        <div class="scroll">
          <table data-testid="count-table">
            <thead>
              <tr>
                <th scope="col">Item · batch</th>
                <th scope="col">Rack</th>
                <th scope="col">Counted</th>
                <th scope="col">Not counted</th>
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track row.line.auditLineId; let n = $index) {
                <tr [attr.data-testid]="'count-line-' + n" [class.hit]="hit() === n">
                  <td>
                    {{ row.line.product.name }}
                    @if (row.line.product.schedule === 'X') {
                      <span class="pill p-warn">Schedule X</span>
                    }
                    @if (row.line.recount) {
                      <span class="pill p-wait" [attr.data-testid]="'count-recount-' + n">Recount</span>
                    }
                    <div class="sub">{{ row.line.batch.batchNo }} · {{ month(row.line.batch.expiryDate) }}</div>
                  </td>
                  <td>{{ row.line.rack ?? '—' }}</td>
                  <td>
                    <input
                      class="cell"
                      inputmode="numeric"
                      [id]="'count-input-' + n"
                      [value]="row.value"
                      [disabled]="row.notCounted || saving() || !!submitted()"
                      [attr.aria-label]="'Counted quantity of ' + row.line.product.name + ' batch ' + row.line.batch.batchNo"
                      [attr.data-testid]="'count-input-' + n"
                      (input)="setValue(n, $any($event.target).value)"
                      (change)="saveRow(n)"
                    />
                    @if (row.error; as text) {
                      <div class="row-err" role="alert">{{ text }}</div>
                    }
                  </td>
                  <td>
                    <label>
                      <input
                        type="checkbox"
                        [checked]="row.notCounted"
                        [disabled]="saving() || !!submitted()"
                        [attr.data-testid]="'count-notcounted-' + n"
                        (change)="toggleNotCounted(n)"
                      />
                      Not counted
                    </label>
                    @if (row.notCounted) {
                      <mat-form-field appearance="outline" subscriptSizing="dynamic">
                        <mat-label>Reason</mat-label>
                        <mat-select
                          [value]="row.reasonKey"
                          [attr.data-testid]="'count-reason-' + n"
                          (selectionChange)="setReason(n, $event.value)"
                        >
                          @for (reason of reasons(); track reason.reasonKey) {
                            <mat-option [value]="reason.reasonKey">{{ reason.label }}</mat-option>
                          }
                        </mat-select>
                      </mat-form-field>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        @if (needsSigner()) {
          <p class="notice teal" data-testid="count-x-note">
            <app-icon name="lock" [size]="16" />
            <span>A Schedule X line is in this count. A pharmacist submits it, with a second signature.</span>
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
        <div class="notice err" role="alert" data-testid="count-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </div>

    <div class="sheet-f">
      @if (submitted()) {
        <button mat-flat-button type="button" data-testid="count-finish" (click)="close()">Close</button>
      } @else {
        <button mat-stroked-button type="button" data-testid="count-save" [disabled]="saving() || !sheet()" (click)="saveAll()">
          Save progress
        </button>
        <button mat-flat-button type="button" data-testid="count-submit" [disabled]="saving() || !sheet()" (click)="submit()">
          Submit count
        </button>
      }
    </div>
  `,
})
export class CountSheetDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<CountSheetDialog, StockAudit | null>>(MatDialogRef);
  protected readonly data = inject<CountSheetData>(MAT_DIALOG_DATA);

  protected readonly sheet = signal<CountSheetPage | null>(null);
  protected readonly rows = signal<Row[]>([]);
  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly signerId = signal('');
  protected readonly pin = signal('');
  protected readonly signerError = signal<string | null>(null);
  protected readonly pinError = signal<string | null>(null);
  protected readonly scan = signal('');
  protected readonly scanNote = signal<string | null>(null);
  protected readonly hit = signal<number | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly submitted = signal<StockAudit | null>(null);

  private readonly clientKey = newClientKey();
  protected readonly month = expiryMonthYear;

  /** Counted or marked not counted, as the counter sees it now. */
  protected readonly countedNow = computed(
    () => this.rows().filter((r) => r.notCounted || r.value.trim() !== '').length,
  );
  protected readonly needsSigner = computed(() => this.rows().some((r) => r.line.product.schedule === 'X'));

  ngOnInit(): void {
    this.api.countSheet(this.data.storeId, this.data.auditId).subscribe({
      next: (sheet) => this.opened(sheet),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
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

  private opened(sheet: CountSheetPage): void {
    this.sheet.set(sheet);
    this.rows.set(
      sheet.items.map((line) => ({
        line,
        value: line.counted === null ? '' : String(line.counted),
        notCounted: line.notCounted,
        reasonKey: line.notCountedReason?.reasonKey ?? '',
        error: null,
        dirty: false,
      })),
    );
  }

  private patch(index: number, change: Partial<Row>): void {
    this.rows.update((list) => list.map((r, i) => (i === index ? { ...r, ...change } : r)));
  }

  protected setValue(index: number, value: string): void {
    this.patch(index, { value, dirty: true, error: null });
  }

  protected setReason(index: number, reasonKey: string): void {
    this.patch(index, { reasonKey, dirty: true, error: null });
    this.saveRow(index);
  }

  protected toggleNotCounted(index: number): void {
    const row = this.rows()[index];
    this.patch(index, { notCounted: !row.notCounted, dirty: true, error: null });
  }

  /** A typed or scanned barcode jumps to the row of that batch and puts the cursor in its Counted box (OQ-79-24a). */
  protected scanNext(event?: Event): void {
    event?.preventDefault();
    const code = this.scan().trim();
    this.scanNote.set(null);
    if (code === '') {
      return;
    }
    const index = this.rows().findIndex((r) => r.line.barcode === code);
    if (index < 0) {
      this.scanNote.set('This item is not on the count sheet.');
      this.hit.set(null);
      return;
    }
    this.hit.set(index);
    this.scan.set('');
    queueMicrotask(() => document.getElementById('count-input-' + index)?.focus());
  }

  private problem(row: Row): string | null {
    if (row.notCounted) {
      return row.reasonKey ? null : 'Choose a reason';
    }
    if (row.value.trim() === '') {
      return null;
    }
    return /^\d{1,6}$/.test(row.value.trim()) ? null : 'Enter a whole number from 0';
  }

  private input(row: Row): CountLineInput | null {
    if (row.notCounted) {
      return { auditLineId: row.line.auditLineId, counted: null, notCounted: true, notCountedReasonKey: row.reasonKey };
    }
    if (row.value.trim() === '') {
      return null;
    }
    return { auditLineId: row.line.auditLineId, counted: Number(row.value.trim()), notCounted: false };
  }

  /** Saves one row as the counter leaves its box. */
  protected saveRow(index: number): void {
    const row = this.rows()[index];
    const problem = this.problem(row);
    if (problem) {
      this.patch(index, { error: problem });
      return;
    }
    const input = this.input(row);
    if (input === null || this.saving() || this.submitted()) {
      return;
    }
    this.persist([input], () => this.patch(index, { dirty: false }));
  }

  private persist(lines: CountLineInput[], after: () => void, then?: () => void): void {
    const sheet = this.sheet();
    if (!sheet) {
      return;
    }
    this.formError.set(null);
    this.saving.set(true);
    this.api.saveCounts(this.data.storeId, this.data.auditId, { version: sheet.version, lines }).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.sheet.update((s) => (s ? { ...s, version: saved.version, counted: saved.counted } : s));
        after();
        then?.();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.formError.set(saveErrorMessage(error));
        if (problemSlug(error) === 'changed') {
          this.ngOnInit();
        }
      },
    });
  }

  private validateAll(): boolean {
    let bad = false;
    this.rows.update((list) =>
      list.map((r) => {
        const error = this.problem(r);
        bad = bad || error !== null;
        return { ...r, error };
      }),
    );
    return !bad;
  }

  protected saveAll(then?: () => void): void {
    if (!this.validateAll()) {
      return;
    }
    const lines = this.rows()
      .filter((r) => r.dirty)
      .map((r) => this.input(r))
      .filter((l): l is CountLineInput => l !== null);
    if (lines.length === 0) {
      then?.();
      return;
    }
    this.persist(lines, () => this.rows.update((list) => list.map((r) => ({ ...r, dirty: false }))), then);
  }

  protected submit(): void {
    if (this.saving()) {
      return;
    }
    this.signerError.set(null);
    this.pinError.set(null);
    const missing = this.rows().filter((r) => !r.notCounted && r.value.trim() === '').length;
    if (missing > 0) {
      this.formError.set(`${missing} ${missing === 1 ? 'line is' : 'lines are'} not counted yet. Count them or mark them not counted.`);
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
    this.saveAll(() => this.send(secondSigner));
  }

  private send(secondSigner: { membershipId: string; pin: string } | null): void {
    const sheet = this.sheet();
    if (!sheet) {
      return;
    }
    this.saving.set(true);
    this.api
      .submitCount(this.data.storeId, this.data.auditId, { clientKey: this.clientKey, version: sheet.version, secondSigner })
      .subscribe({
        next: (audit) => {
          this.pin.set('');
          this.saving.set(false);
          this.submitted.set(audit);
        },
        error: (error: unknown) => {
          this.pin.set('');
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }

  protected close(): void {
    this.ref.close(this.submitted());
  }
}
