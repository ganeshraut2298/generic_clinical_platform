import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { newClientKey } from '../../../shared/client-key';
import { formatTimeOfInstant, istToday } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { pinProblem } from '../indent/indent.util';
import { ReasonChipsComponent } from '../indent/reason-chips.component';
import { SecondSignerComponent } from '../indent/second-signer.component';
import { PickedProduct, ProductPickerComponent } from '../inventory/product-picker.component';
import { expiryMonthYear } from '../inventory/inventory.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason, ProductStock, SecondSignerCandidate } from '../pharmacy-inventory.model';
import { AdmittedPatient, Consumption, ConsumptionType } from '../pharmacy-stockuse.model';
import { PersonRef } from '../pharmacy.model';
import { PharmacyApi } from '../pharmacy.api';
import { blindQty } from './stockuse.util';

interface Line {
  product: PickedProduct;
  stock: ProductStock | null;
  qty: string;
  error: string | null;
}

const OTHER = '__other__';
const PAGE_SIZE = 20;

/**
 * Ward consumption (story 79-16, mockup 11 tab "Ward consumption"): "Record consumption" with the type Patient-wise
 * (billable) or Ward general (non-billable), then the items taken FEFO from this store, then "Save consumption", and the
 * list "Today · <store>". Patient-wise is Integrated only: the tab appears only when the context lists the capability
 * CONSUMPTION_PATIENT (the IPD reader port is a no-op for now, so the server answers 409 integration-off and the tab
 * says so). A patient entry shows the IP number, never the name after it is saved; the name is only for verifying the
 * patient on screen. The charge to the IPD bill and the nursing chart are SAH-80; the entry shows "Charge pending".
 */
@Component({
  selector: 'app-consumption',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
    EmptyStateComponent,
    ProductPickerComponent,
    ReasonChipsComponent,
    SecondSignerComponent,
  ],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .seg {
      display: inline-flex;
    }
    .seg button {
      min-height: 44px;
      padding: 0 16px;
      border: 1px solid var(--line);
      background: var(--glass-hi);
      font: inherit;
      cursor: pointer;
    }
    .seg button:first-child {
      border-radius: 8px 0 0 8px;
    }
    .seg button:last-child {
      border-radius: 0 8px 8px 0;
    }
    .seg button[aria-pressed='true'] {
      border: 2px solid var(--pri);
      background: var(--pri-soft);
      color: var(--pri-text);
      font-weight: 700;
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
    input.cell {
      min-height: 44px;
      width: 90px;
      padding: 6px 8px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
    }
    .row-err,
    .err {
      color: var(--crit);
      font-size: 0.8rem;
    }
    .form {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="cons-h" data-testid="consumption">
      <h2 id="cons-h">Record consumption</h2>
      <p data-testid="consumption-acting">Acting as {{ callerName() }} · mapped to {{ storeName() }}</p>
      @if (notice(); as text) {
        <div class="notice good" role="status" data-testid="consumption-note">
          <app-icon name="check" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      <div class="form">
        <div class="seg" role="group" aria-label="Type of consumption">
          @if (patientTab()) {
            <button type="button" data-testid="type-patient" [attr.aria-pressed]="type() === 'PATIENT'" (click)="setType('PATIENT')">
              Patient-wise (billable)
            </button>
          }
          <button type="button" data-testid="type-general" [attr.aria-pressed]="type() === 'GENERAL'" (click)="setType('GENERAL')">
            Ward general (non-billable)
          </button>
        </div>

        @if (type() === 'PATIENT') {
          @if (patientsError(); as text) {
            <div class="notice warn" role="status" data-testid="patient-off">
              <app-icon name="alert" [size]="16" />
              <span>{{ text }}</span>
            </div>
          } @else {
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Patient (scan wristband)</mat-label>
              <input
                matInput
                autocomplete="off"
                data-testid="patient-search"
                placeholder="IP number or name"
                [value]="patientQuery()"
                (input)="searchPatients($any($event.target).value)"
              />
            </mat-form-field>
            @if (patients().length > 0) {
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label>Choose the patient</mat-label>
                <mat-select data-testid="f-patient" [value]="admissionId()" (selectionChange)="admissionId.set($event.value)">
                  @for (p of patients(); track p.admissionId) {
                    <mat-option [value]="p.admissionId">{{ p.ipNumber }} · {{ p.patientName }}{{ p.bedLabel ? ' · ' + p.bedLabel : '' }}</mat-option>
                  }
                </mat-select>
              </mat-form-field>
            }
            @if (selectedPatient(); as p) {
              <p data-testid="patient-chosen">Verify the patient: {{ p.patientName }} · {{ p.ipNumber }}</p>
            }
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Ordered by</mat-label>
              <mat-select data-testid="f-ordered-by" [value]="orderedBy()" (selectionChange)="orderedBy.set($event.value)">
                @for (o of orderedByOptions(); track o.membershipId) {
                  <mat-option [value]="o.membershipId">{{ o.name }}</mat-option>
                }
                <mat-option [value]="other">Other</mat-option>
              </mat-select>
            </mat-form-field>
            @if (orderedBy() === other) {
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label>Name of the person who ordered</mat-label>
                <input matInput maxlength="80" data-testid="f-other-name" [value]="otherName()" (input)="otherName.set($any($event.target).value)" />
              </mat-form-field>
            }
            <p class="note">Charged to the IPD running bill and shown in the nursing medication chart once SAH-80 is connected. Until then the entry shows "Charge pending".</p>
          }
        } @else {
          <span class="field-label">Purpose</span>
          <app-reason-chips
            [reasons]="purposes()"
            [value]="purposeKey()"
            label="Purpose of the consumption"
            testIdPrefix="purpose-"
            (chosen)="purposeKey.set($event.reasonKey)"
          />
          <p class="note" data-testid="cost-booked">Cost booked to {{ storeName() }}. Not billed to a patient. Cost is booked to the ward for reports.</p>
        }

        @if (stockAuditLineId()) {
          <p class="notice teal" data-testid="audit-link-note">
            <app-icon name="lock" [size]="16" />
            <span>This entry is linked to the open stock count line, so the count shows it as consumption.</span>
          </p>
        }

        <app-product-picker label="+ Scan item" (picked)="addLine($event)" />
        @if (lineMessage(); as text) {
          <p class="err" role="alert">{{ text }}</p>
        }
        @if (lines().length > 0) {
          <div class="tbl-wrap">
            <table data-testid="consumption-lines">
              <thead>
                <tr>
                  <th scope="col">Item</th>
                  <th scope="col">Batch (FEFO, ward stock)</th>
                  <th scope="col" class="num">Ward available</th>
                  <th scope="col">Qty used</th>
                </tr>
              </thead>
              <tbody>
                @for (line of lines(); track line.product.productId; let n = $index) {
                  <tr [attr.data-testid]="'cons-line-' + n">
                    <td>
                      {{ line.product.name }} {{ line.product.strength }}
                      @if (isX(line)) {
                        <span class="pill p-warn">Schedule X</span>
                      }
                    </td>
                    <td>{{ batchText(line) }}</td>
                    <td class="num" [attr.data-testid]="'cons-available-' + n">{{ availableText(line) }}</td>
                    <td>
                      <input
                        class="cell"
                        inputmode="numeric"
                        [value]="line.qty"
                        [attr.aria-label]="'Quantity used of ' + line.product.name"
                        [attr.data-testid]="'cons-qty-' + n"
                        (input)="setQty(n, $any($event.target).value)"
                      />
                      <button mat-button type="button" [attr.aria-label]="'Remove ' + line.product.name" (click)="removeLine(n)">Remove</button>
                      @if (line.error; as text) {
                        <div class="row-err" role="alert">{{ text }}</div>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
        @if (needsSigner()) {
          <p class="notice teal" data-testid="cons-x-note">
            <app-icon name="lock" [size]="16" />
            <span>Schedule X is recorded by a pharmacist, with a second signature.</span>
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
        @if (formError(); as text) {
          <div class="notice err" role="alert" data-testid="consumption-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }
        <div>
          <button mat-flat-button type="button" data-testid="consumption-save" [disabled]="saving() || lines().length === 0" (click)="save()">
            Save consumption
          </button>
        </div>
      </div>
    </section>

    <section class="panel surf" aria-labelledby="today-h" data-testid="consumption-today">
      <h2 id="today-h">Today · {{ storeName() }}</h2>
      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="consumption-list-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load(0)">Try again</button>
        </div>
      } @else if (today(); as d) {
        @if (d.items.length === 0) {
          <app-empty data-testid="consumption-empty">No consumption recorded today.</app-empty>
        } @else {
          <div class="tbl-wrap">
            <table data-testid="consumption-list">
              <thead>
                <tr>
                  <th scope="col">Time</th>
                  <th scope="col">Item</th>
                  <th scope="col" class="num">Qty</th>
                  <th scope="col">Type</th>
                </tr>
              </thead>
              <tbody>
                @for (entry of d.items; track entry.id) {
                  @for (l of entry.lines; track $index) {
                    <tr [attr.data-testid]="'entry-' + entry.id">
                      <td>{{ time(entry.recordedAt) }}</td>
                      <td>{{ l.product.name }}</td>
                      <td class="num">{{ l.quantity }}</td>
                      <td>
                        {{ entry.type === 'PATIENT' ? 'Patient' : 'General' }}
                        @if (entry.ipNumber) {
                          <span class="sub"> · {{ entry.ipNumber }}</span>
                        }
                        @if (entry.chargeStatus === 'CHARGE_PENDING') {
                          <span class="pill p-wait">Charge pending</span>
                        }
                      </td>
                    </tr>
                  }
                }
              </tbody>
            </table>
          </div>
          @if (d.total > pageSize) {
            <div class="pager" data-testid="consumption-pager">
              <span role="status">Page {{ d.page + 1 }} of {{ pages(d.total) }}</span>
              <button mat-stroked-button type="button" [disabled]="d.page === 0" (click)="load(d.page - 1)">Previous</button>
              <button mat-stroked-button type="button" [disabled]="(d.page + 1) * pageSize >= d.total" (click)="load(d.page + 1)">Next</button>
            </div>
          }
        }
      }
      <p class="note">Average daily use feeds the suggested quantity on the next indent.</p>
    </section>
  `,
})
export class ConsumptionSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  /** "Post as consumption instead" of the stock audit (79-17): the audit line this entry is linked to. */
  readonly stockAuditLineId = input<string | null>(null);

  protected readonly other = OTHER;
  protected readonly pageSize = PAGE_SIZE;
  protected readonly type = signal<ConsumptionType>('GENERAL');
  protected readonly purposes = signal<ActiveReason[]>([]);
  protected readonly purposeKey = signal<string | null>(null);
  protected readonly patients = signal<AdmittedPatient[]>([]);
  protected readonly patientQuery = signal('');
  protected readonly patientsError = signal<string | null>(null);
  protected readonly admissionId = signal<string | null>(null);
  protected readonly orderedByOptions = signal<PersonRef[]>([]);
  protected readonly orderedBy = signal<string | null>(null);
  protected readonly otherName = signal('');
  protected readonly lines = signal<Line[]>([]);
  protected readonly lineMessage = signal<string | null>(null);
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly signerId = signal('');
  protected readonly pin = signal('');
  protected readonly signerError = signal<string | null>(null);
  protected readonly pinError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly today = signal<{ items: Consumption[]; total: number; page: number } | null>(null);
  protected readonly loadError = signal<string | null>(null);

  private attemptKey = newClientKey();

  /** Patient-wise shows only when the context lists the capability (Integrated, IPD reader connected, linked ward). */
  protected readonly patientTab = this.context.canConsumePatient;
  protected readonly storeName = computed(() => this.context.workingStore()?.name ?? '');
  protected readonly callerName = computed(() => this.context.context()?.caller.name ?? '');
  protected readonly selectedPatient = computed(() => this.patients().find((p) => p.admissionId === this.admissionId()) ?? null);
  protected readonly needsSigner = computed(() => this.lines().some((l) => this.isX(l)));

  protected readonly time = (instant: string) => formatTimeOfInstant(instant);
  protected readonly pages = (total: number) => Math.max(1, Math.ceil(total / PAGE_SIZE));
  protected readonly month = expiryMonthYear;

  ngOnInit(): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.load(0);
    this.api.activeReasons(['consumption-purpose']).subscribe({
      next: (a) => this.purposes.set(a.lists.find((l) => l.listKey === 'consumption-purpose')?.reasons ?? []),
      error: () => undefined,
    });
    this.api.secondSigners(storeId).subscribe({
      next: (people) => {
        this.signers.set(people);
        this.signersLoaded.set(true);
      },
      error: () => this.signersLoaded.set(true),
    });
  }

  protected load(page: number): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.loadError.set(null);
    this.api.consumptions(storeId, { date: istToday(), page, size: PAGE_SIZE }).subscribe({
      next: (answer) => this.today.set(answer),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
  }

  protected setType(type: ConsumptionType): void {
    this.type.set(type);
    this.formError.set(null);
    if (type === 'PATIENT' && this.orderedByOptions().length === 0) {
      this.api.orderedByOptions().subscribe({
        next: (options) => this.orderedByOptions.set(options),
        error: () => this.orderedByOptions.set([]),
      });
      this.searchPatients('');
    }
  }

  protected searchPatients(query: string): void {
    const storeId = this.context.workingStoreId();
    this.patientQuery.set(query);
    if (!storeId || (query.length > 0 && query.length < 2)) {
      return;
    }
    this.patientsError.set(null);
    this.api.admittedPatients(storeId, query === '' ? undefined : query).subscribe({
      next: (list) => this.patients.set(list),
      error: (error: unknown) => {
        this.patients.set([]);
        this.patientsError.set(
          problemSlug(error) === 'integration-off'
            ? 'Not connected. Patient-wise consumption works when the ward is linked to IPD.'
            : loadErrorMessage(error),
        );
      },
    });
  }

  protected isX(line: Line): boolean {
    return line.product && line.stock?.product.schedule === 'X';
  }

  protected addLine(product: PickedProduct): void {
    const storeId = this.context.workingStoreId();
    this.lineMessage.set(null);
    if (!storeId) {
      return;
    }
    if (this.lines().some((l) => l.product.productId === product.productId)) {
      this.lineMessage.set('This item is already in the list.');
      return;
    }
    const line: Line = { product, stock: null, qty: '', error: null };
    this.lines.update((list) => [...list, line]);
    this.api.productStock(storeId, product.productId).subscribe({
      next: (stock) =>
        this.lines.update((list) => list.map((l) => (l.product.productId === product.productId ? { ...l, stock } : l))),
      error: () => undefined,
    });
  }

  protected removeLine(index: number): void {
    this.lines.update((list) => list.filter((_, i) => i !== index));
  }

  protected setQty(index: number, qty: string): void {
    this.lines.update((list) => list.map((l, i) => (i === index ? { ...l, qty, error: null } : l)));
  }

  private firstBatch(line: Line) {
    return (line.stock?.batches ?? [])
      .filter((r) => r.state === 'AVAILABLE' && r.daysToExpiry >= 0)
      .sort((a, b) => (a.fefoRank ?? 99) - (b.fefoRank ?? 99))[0];
  }

  protected batchText(line: Line): string {
    const row = this.firstBatch(line);
    return row ? `${row.batch.batchNo} · ${this.month(row.batch.expiryDate)}` : '—';
  }

  /** The sum of the Available batches; a dash when any quantity is hidden (the counter is blind, rule 24). */
  protected availableText(line: Line): string {
    const rows = (line.stock?.batches ?? []).filter((r) => r.state === 'AVAILABLE' && r.daysToExpiry >= 0);
    if (line.stock === null || rows.some((r) => r.qty === null)) {
      return blindQty(null);
    }
    return String(rows.reduce((sum, r) => sum + (r.qty ?? 0), 0));
  }

  private availableNumber(line: Line): number | null {
    const text = this.availableText(line);
    return text === '—' ? null : Number(text);
  }

  protected save(): void {
    const storeId = this.context.workingStoreId();
    if (this.saving() || !storeId) {
      return;
    }
    this.formError.set(null);
    this.signerError.set(null);
    this.pinError.set(null);
    let bad = false;
    const checked = this.lines().map((l) => {
      let error: string | null = null;
      const max = this.availableNumber(l);
      if (!/^\d{1,6}$/.test(l.qty.trim()) || Number(l.qty.trim()) < 1) {
        error = 'Enter a whole number from 1';
      } else if (max !== null && Number(l.qty.trim()) > max) {
        error = `Only ${max} available`;
      }
      bad = bad || error !== null;
      return { ...l, error };
    });
    this.lines.set(checked);
    if (this.type() === 'GENERAL' && !this.purposeKey()) {
      this.formError.set('Choose the purpose.');
      return;
    }
    if (this.type() === 'PATIENT') {
      if (!this.admissionId()) {
        this.formError.set('Choose the patient.');
        return;
      }
      if (!this.orderedBy() || (this.orderedBy() === OTHER && this.otherName().trim().length < 2)) {
        this.formError.set('Choose who ordered it, or type their name.');
        return;
      }
    }
    if (bad) {
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
    this.api
      .createConsumption(storeId, {
        clientKey: this.attemptKey,
        type: this.type(),
        purposeKey: this.type() === 'GENERAL' ? this.purposeKey() : null,
        note: null,
        admissionId: this.type() === 'PATIENT' ? this.admissionId() : null,
        orderedBy:
          this.type() === 'PATIENT'
            ? this.orderedBy() === OTHER
              ? { membershipId: null, otherName: this.otherName().trim() }
              : { membershipId: this.orderedBy(), otherName: null }
            : null,
        stockAuditLineId: this.stockAuditLineId(),
        lines: checked.map((l) => ({ productId: l.product.productId, quantity: Number(l.qty.trim()) })),
        secondSigner,
      })
      .subscribe({
        next: (done) => {
          this.pin.set('');
          this.saving.set(false);
          this.attemptKey = newClientKey();
          this.lines.set([]);
          this.notice.set(
            done.type === 'PATIENT' ? 'Consumption saved. Charge pending.' : `Consumption saved. Cost booked to ${done.costBookedTo}.`,
          );
          this.load(0);
        },
        error: (error: unknown) => {
          this.pin.set('');
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
          if (problemSlug(error) === 'insufficient-stock') {
            this.attemptKey = newClientKey();
          }
        },
      });
  }
}
