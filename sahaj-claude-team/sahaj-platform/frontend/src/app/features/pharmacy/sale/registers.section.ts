import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { formatDisplayDate, formatDisplayDateTimeOfInstant, istToday } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { batchWithExpiry } from '../indent/indent.util';
import { H1RegisterPage, H1RegisterRow, Licences, ScheduleXRegister, ScheduleXRow } from '../pharmacy-compliance.model';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { ComplianceCardsSection } from './compliance-cards.section';

const PAGE_SIZE = 25;

const LICENCE_STATE_TEXT = {
  VALID: 'Valid',
  DUE: 'Due soon',
  EXPIRED: 'Expired',
  NO_DATE: 'No date',
  NONE: 'Not entered',
} as const;

/**
 * Registers & compliance (SAH-80-9, 80-10, 80-19, work packages J and L; P&I mockup 05-compliance-roles-and-audit.html):
 * the cards "Schedule H1 register · auto-generated from bills" (chip "<n> entries today · <n> missing prescriber"), the
 * "Schedule X / NDPS balance register" (Drug, Opening, Received, Issued, Book, Physical) and "Licences & renewals"
 * (Document, Number, Valid till, Status). The register cards need readRegisters at the working store and the licences card
 * needs COMPLIANCE, as do the cards "Role-based permissions & approvals" and "Audit log" (compliance-cards.section.ts).
 * Not built, by the story (OQ-80-2, OQ-80-3): the header buttons "Export GST (GSTR-1 / 3B)" and "Download inspection
 * pack (PDF)", the tiles of "Data protection & trust", and "+ Custom role". The GST sales register is the CSV report of
 * Reports; the register CSVs are there too.
 */
@Component({
  selector: 'app-registers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, ComplianceCardsSection],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
    }
    .filters {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: 10px;
    }
    .filters label {
      display: flex;
      flex-direction: column;
      gap: 4px;
      font-size: 0.85rem;
    }
    input.field {
      min-height: 44px;
      padding: 6px 10px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
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
  `,
  template: `
    <header data-testid="compliance-head">
      <div class="note">Inspection-ready at any time</div>
      <h2>Compliance, roles &amp; audit</h2>
    </header>
    @if (store.canRegisters()) {
      <section class="panel surf" aria-labelledby="h1-h" data-testid="h1-register">
        <div class="head">
          <h2 id="h1-h">Schedule H1 register · auto-generated from bills</h2>
          @if (h1(); as page) {
            <span class="pill p-wait" data-testid="h1-chip">
              {{ page.entriesToday }} {{ page.entriesToday === 1 ? 'entry' : 'entries' }} today · {{ page.missingPrescriberToday }} missing prescriber
            </span>
          }
        </div>
        <form class="filters" (submit)="searchH1($event)">
          <label>
            From
            <input class="field" type="date" data-testid="h1-from" [value]="from()" (input)="from.set($any($event.target).value)" />
          </label>
          <label>
            To
            <input class="field" type="date" data-testid="h1-to" [value]="to()" (input)="to.set($any($event.target).value)" />
          </label>
          <label>
            Prescriber
            <input
              class="field"
              type="text"
              maxlength="80"
              data-testid="h1-prescriber"
              [value]="prescriber()"
              (input)="prescriber.set($any($event.target).value)"
            />
          </label>
          <button mat-stroked-button type="submit" data-testid="h1-search">Show</button>
        </form>
        @if (h1Error(); as text) {
          <div class="notice err" role="alert" data-testid="h1-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="loadH1(0)">Try again</button>
          </div>
        } @else if (h1(); as page) {
          @if (page.items.length === 0) {
            <p class="note" data-testid="h1-empty">No Schedule H1 entries in this range.</p>
          } @else {
            <div class="tbl-wrap">
              <table data-testid="h1-table">
                <thead>
                  <tr>
                    <th scope="col">Date</th>
                    <th scope="col">Bill no.</th>
                    <th scope="col">Patient</th>
                    <th scope="col">Prescriber &amp; reg. no.</th>
                    <th scope="col">Drug</th>
                    <th scope="col">Batch</th>
                    <th scope="col" class="num">Qty</th>
                    <th scope="col">Rx copy</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of page.items; track row.rowId) {
                    <tr [attr.data-testid]="'h1-row-' + row.rowId">
                      <td>{{ dateTime(row.at) }}</td>
                      <td>{{ row.billNo }}</td>
                      <td>
                        {{ row.patientName }}
                        <div class="sub">{{ patientLine(row) }}</div>
                      </td>
                      <td>{{ prescriberText(row) }}</td>
                      <td>
                        {{ row.drug.name }}
                        @if (row.prescribedDrug; as prescribed) {
                          <div class="sub">Prescribed: {{ prescribed.name }}</div>
                        }
                        @if (row.kind === 'REVERSAL') {
                          <span class="pill p-warn">{{ row.reversalReason === 'RETURNED' ? 'Returned' : 'Cancelled' }}</span>
                        }
                        @if (row.override; as why) {
                          <div class="sub">Override: {{ why.label }}</div>
                        }
                      </td>
                      <td>{{ batch(row.batch.batchNo, row.batch.expiryDate) }}</td>
                      <td class="num">{{ row.qty }}</td>
                      <td>{{ row.rxCopy.source === 'E_RX' ? 'e-Rx' : 'Typed' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <div class="acts">
              <button mat-stroked-button type="button" data-testid="h1-prev" [disabled]="page.page === 0" (click)="loadH1(page.page - 1)">
                Previous
              </button>
              <button
                mat-stroked-button
                type="button"
                data-testid="h1-next"
                [disabled]="(page.page + 1) * page.size >= page.total"
                (click)="loadH1(page.page + 1)"
              >
                Next
              </button>
            </div>
          }
        }
      </section>

      <section class="panel surf" aria-labelledby="x-h" data-testid="x-register">
        <div class="head">
          <h2 id="x-h">Schedule X / NDPS balance register</h2>
          @if (x(); as reg) {
            @if (reg.toReview > 0) {
              <span class="pill p-warn" data-testid="x-chip">{{ reg.toReview }} to review</span>
            }
          }
        </div>
        @if (xError(); as text) {
          <div class="notice err" role="alert" data-testid="x-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="loadX()">Try again</button>
          </div>
        } @else if (x(); as reg) {
          @if (reg.items.length === 0) {
            <p class="note" data-testid="x-empty">No Schedule X stock or movement in this range.</p>
          } @else {
            <div class="tbl-wrap">
              <table data-testid="x-table">
                <thead>
                  <tr>
                    <th scope="col">Drug</th>
                    <th scope="col" class="num">Opening</th>
                    <th scope="col" class="num">Received</th>
                    <th scope="col" class="num">Issued</th>
                    <th scope="col" class="num">Book</th>
                    <th scope="col" class="num">Physical</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of reg.items; track row.product.productId) {
                    <tr [attr.data-testid]="'x-row-' + row.product.productId">
                      <td>{{ row.product.name }}@if (row.ndps) { <span class="pill p-warn">NDPS</span> }</td>
                      <td class="num">{{ row.opening }}</td>
                      <td class="num">{{ row.received }}</td>
                      <td class="num">{{ row.issued }}</td>
                      <td class="num">{{ row.book }}</td>
                      <td class="num" [attr.data-testid]="'x-physical-' + row.product.productId">{{ physicalText(row) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        }
        <p class="note">Two-person sign-off on issue. The register is kept as per applicable state rules.</p>
      </section>
    } @else {
      <p class="note" data-testid="registers-no-access">You cannot read the registers at this store. Pick a store where you can.</p>
    }

    @if (store.canCompliance()) {
      <section class="panel surf" aria-labelledby="lic-h" data-testid="licences">
        <h2 id="lic-h">Licences &amp; renewals</h2>
        @if (licenceError(); as text) {
          <div class="notice err" role="alert" data-testid="licences-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="loadLicences()">Try again</button>
          </div>
        } @else if (licences(); as lic) {
          @for (document of lic.expired; track document) {
            <p class="notice err" role="alert" data-testid="licence-expired">
              <app-icon name="alert" [size]="16" />
              <span>{{ document }} has expired.</span>
            </p>
          }
          <div class="tbl-wrap">
            <table data-testid="licences-table">
              <thead>
                <tr>
                  <th scope="col">Document</th>
                  <th scope="col">Number</th>
                  <th scope="col">Valid till</th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                @for (row of lic.rows; track $index) {
                  <tr [attr.data-testid]="'licence-' + row.kind + '-' + $index">
                    <td>
                      {{ row.document }}
                      @if (row.pharmacist; as p) {
                        <div class="sub">{{ p.member.name }}</div>
                      }
                    </td>
                    <td>{{ row.number ?? row.pharmacist?.registrationNo ?? '—' }}</td>
                    <td>{{ validTill(row.validTill ?? row.pharmacist?.validTill ?? null) }}</td>
                    <td>
                      <span class="pill" [class.p-ok]="row.status.state === 'VALID'" [class.p-warn]="row.status.state !== 'VALID'">
                        {{ statusText(row.status.state, row.status.daysLeft) }}
                      </span>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
      <app-compliance-cards />
    }
  `,
})
export class RegistersSection implements OnInit {
  protected readonly store = inject(PharmacyContextStore);
  private readonly api = inject(PharmacyApi);

  protected readonly from = signal(istToday());
  protected readonly to = signal(istToday());
  protected readonly prescriber = signal('');
  protected readonly h1 = signal<H1RegisterPage | null>(null);
  protected readonly h1Error = signal<string | null>(null);
  protected readonly x = signal<ScheduleXRegister | null>(null);
  protected readonly xError = signal<string | null>(null);
  protected readonly licences = signal<Licences | null>(null);
  protected readonly licenceError = signal<string | null>(null);

  protected readonly dateTime = (instant: string) => formatDisplayDateTimeOfInstant(instant);
  protected readonly batch = batchWithExpiry;

  ngOnInit(): void {
    if (this.store.canRegisters()) {
      this.loadH1(0);
      this.loadX();
    }
    if (this.store.canCompliance()) {
      this.loadLicences();
    }
  }

  protected searchH1(event: Event): void {
    event.preventDefault();
    this.loadH1(0);
  }

  protected loadH1(page: number): void {
    const storeId = this.store.workingStoreId();
    if (!storeId) {
      return;
    }
    this.h1Error.set(null);
    this.api
      .h1Register(storeId, { from: this.from(), to: this.to(), prescriber: this.prescriber().trim(), page, size: PAGE_SIZE })
      .subscribe({
        next: (answer) => this.h1.set(answer),
        error: (error: unknown) => {
          this.h1.set(null);
          this.h1Error.set(loadErrorMessage(error));
        },
      });
  }

  protected loadX(): void {
    const storeId = this.store.workingStoreId();
    if (!storeId) {
      return;
    }
    this.xError.set(null);
    this.api.scheduleXRegister(storeId, {}).subscribe({
      next: (answer) => this.x.set(answer),
      error: (error: unknown) => {
        this.x.set(null);
        this.xError.set(loadErrorMessage(error));
      },
    });
  }

  protected loadLicences(): void {
    this.licenceError.set(null);
    this.api.licences().subscribe({
      next: (answer) => this.licences.set(answer),
      error: (error: unknown) => {
        this.licences.set(null);
        this.licenceError.set(loadErrorMessage(error));
      },
    });
  }

  protected patientLine(row: H1RegisterRow): string {
    return [row.patientAge === null ? null : `${row.patientAge} years`, row.patientAddress].filter((p) => p).join(' · ');
  }

  protected prescriberText(row: H1RegisterRow): string {
    if (!row.prescriberName) {
      return 'Missing prescriber';
    }
    return row.prescriberRegNo ? `${row.prescriberName} · ${row.prescriberRegNo}` : row.prescriberName;
  }

  /** "34 ✓" when the count matches the book, "101 · review" when it does not, "Not counted" before any count. */
  protected physicalText(row: ScheduleXRow): string {
    if (row.status === 'NOT_COUNTED' || row.physical === null) {
      return 'Not counted';
    }
    return row.status === 'MATCH' ? `${row.physical} ✓` : `${row.physical} · review`;
  }

  protected validTill(date: string | null): string {
    return date ? formatDisplayDate(date) : '—';
  }

  protected statusText(state: keyof typeof LICENCE_STATE_TEXT, daysLeft: number | null): string {
    return state === 'DUE' && daysLeft !== null ? `${daysLeft} days` : LICENCE_STATE_TEXT[state];
  }
}
