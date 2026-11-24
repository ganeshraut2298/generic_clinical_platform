import { ChangeDetectionStrategy, Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { formatDisplayDate, formatTimeOfInstant, istToday } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { rupees } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { CardState, CommandCentre } from '../pharmacy-compliance.model';
import { PharmacyApi } from '../pharmacy.api';

const BAND_TEXT = {
  EXPIRED: 'Expired',
  D0_30: '0–30 days',
  D31_60: '31–60 days',
  D61_90: '61–90 days',
} as const;
const BAND_COLOUR = {
  EXPIRED: 'var(--crit)',
  D0_30: 'var(--crit)',
  D31_60: 'var(--warn)',
  D61_90: 'var(--warn)',
} as const;

const COULD_NOT_LOAD = 'Could not load. Try again.';

function weekday(date: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-IN', { timeZone: 'Asia/Kolkata', weekday: 'long' }).format(date);
}

/**
 * The Pharmacy Command Center (SAH-80-20; P&I mockup 01-pharmacy-command-center.html): the date line, the title, the
 * buttons "+ New bill" and "+ Purchase entry", the tiles TODAY'S SALES, GROSS MARGIN, NEAR-EXPIRY (90 D), REORDER,
 * RX FROM OPD / IPD, UDHAR OUTSTANDING, the bars "Expiry risk", "Live prescriptions from Sahaj OPD / IPD" and "Compliance
 * health". Every figure is worked by the server when the page is read ("As of <time> IST", "Refresh"); a tile the server
 * leaves out for this person is not drawn, and a tile that failed says "Could not load. Try again." while the others still
 * show. Not built (the story leaves them out): the Action queue, the expiry-return calendar note, "Branches today" and
 * the cold-chain tile.
 */
@Component({
  selector: 'app-command-centre',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink, IconComponent],
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
      align-items: flex-end;
      justify-content: space-between;
      gap: 10px;
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }
    .tiles {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 12px;
    }
    .tile {
      padding: 12px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
    }
    .tile .k {
      font-size: 0.75rem;
      font-weight: 600;
      text-transform: uppercase;
    }
    .tile .v {
      font-size: 1.5rem;
      font-weight: 700;
      margin-top: 6px;
    }
    .tile .s {
      font-size: 0.8rem;
      margin-top: 4px;
      color: var(--ink-3);
    }
    .two {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      gap: 12px;
    }
    .bar {
      height: 8px;
      border-radius: 4px;
      background: var(--line);
      margin-top: 4px;
    }
    .bar span {
      display: block;
      height: 100%;
      border-radius: 4px;
    }
    .bandrow {
      display: flex;
      justify-content: space-between;
      gap: 8px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    table {
      min-width: 480px;
    }
    .err {
      color: var(--crit);
    }
  `,
  template: `
    <div class="head">
      <div>
        <div class="note" data-testid="cc-dateline">{{ dateLine() }}</div>
        <h2 id="cc-h">Pharmacy Command Center</h2>
      </div>
      <div class="acts">
        <span class="note" data-testid="cc-asof">{{ asOf() }}</span>
        <button mat-stroked-button type="button" data-testid="cc-refresh" (click)="load()">Refresh</button>
        @if (canNewBill()) {
          <button mat-flat-button type="button" data-testid="new-bill" (click)="newBill.emit()">+ New bill</button>
        }
        @if (canPurchaseEntry()) {
          <a mat-stroked-button routerLink="/app/pharmacy/inventory" data-testid="purchase-entry">+ Purchase entry</a>
        }
      </div>
    </div>

    @if (owner()) {
      <label class="note">
        Store
        <select data-testid="cc-store" (change)="pickStore($any($event.target).value)">
          <option value="" [selected]="storeChoice() === ''">{{ workingName() }}</option>
          <option value="ALL" [selected]="storeChoice() === 'ALL'">All selling stores</option>
        </select>
      </label>
    }

    @if (loadError(); as text) {
      <div class="notice err" role="alert" data-testid="cc-error">
        <app-icon name="alert" [size]="16" />
        <span>{{ text }}</span>
        <button mat-stroked-button type="button" (click)="load()">Try again</button>
      </div>
    } @else if (data(); as d) {
      <section class="tiles" aria-label="Today at a glance" data-testid="cc-tiles">
        <div class="tile" data-testid="tile-sales">
          <div class="k">Today's sales</div>
          @if (failed(d.todaysSales.state)) {
            <div class="s err" data-testid="tile-sales-error">{{ couldNot }}</div>
          } @else if (d.todaysSales.state === 'EMPTY') {
            <div class="s" data-testid="tile-sales-empty">No sales yet today.</div>
          } @else {
            <div class="v">{{ money(d.todaysSales.netPaise ?? 0) }}</div>
            <div class="s">{{ d.todaysSales.billCount ?? 0 }} bills · avg {{ money(average(d.todaysSales.netPaise, d.todaysSales.billCount)) }}</div>
          }
        </div>
        @if (d.grossMargin; as g) {
          <div class="tile" data-testid="tile-margin">
            <div class="k">Gross margin</div>
            @if (failed(g.state)) {
              <div class="s err">{{ couldNot }}</div>
            } @else {
              <div class="v">{{ g.percent }}%</div>
              <div class="s">On landed cost, not PTR</div>
            }
          </div>
        }
        @if (d.nearExpiry90; as n) {
          <div class="tile" data-testid="tile-expiry">
            <div class="k">Near-expiry (90 d)</div>
            @if (failed(n.state)) {
              <div class="s err">{{ couldNot }}</div>
            } @else if (n.state === 'EMPTY') {
              <div class="s">{{ noStock }}</div>
            } @else {
              <div class="v">{{ orDash(n.valuePaise) }}</div>
              <div class="s">
                {{ n.batchCount ?? 0 }} batches
                @if (isNumber(n.returnablePaise)) {
                  · {{ money(n.returnablePaise ?? 0) }} returnable
                }
              </div>
            }
          </div>
        }
        @if (d.reorder; as r) {
          <div class="tile" data-testid="tile-reorder">
            <div class="k">Reorder</div>
            @if (failed(r.state)) {
              <div class="s err">{{ couldNot }}</div>
            } @else if (r.state === 'EMPTY') {
              <div class="s">{{ noStock }}</div>
            } @else {
              <div class="v">{{ r.productCount ?? 0 }} SKUs</div>
              <div class="s">below reorder level</div>
            }
          </div>
        }
        @if (d.rxWaiting; as x) {
          <div class="tile" data-testid="tile-rx">
            <div class="k">Rx from OPD / IPD</div>
            @if (failed(x.state)) {
              <div class="s err">{{ couldNot }}</div>
            } @else {
              <div class="v">{{ x.opdConnected ? (x.opdWaiting ?? 0) : '—' }} · {{ x.ipdConnected ? (x.ipdWaiting ?? 0) : '—' }}</div>
              <div class="s" data-testid="tile-rx-sub">
                @if (!x.opdConnected || !x.ipdConnected) {
                  Not connected ·
                }
                {{ x.indentsWaiting ?? 0 }} indents waiting
              </div>
            }
          </div>
        }
        @if (d.udhar; as u) {
          <div class="tile" data-testid="tile-udhar">
            <div class="k">Udhar outstanding</div>
            @if (failed(u.state)) {
              <div class="s err">{{ couldNot }}</div>
            } @else {
              <div class="v">{{ money(u.duePaise ?? 0) }}</div>
              <div class="s">{{ money(u.over60DaysPaise ?? 0) }} over 60 days</div>
            }
          </div>
        }
      </section>

      <div class="two">
        @if (d.expiryRisk; as e) {
          <section class="panel surf" aria-labelledby="er-h" data-testid="cc-expiry-risk">
            <h3 id="er-h">Expiry risk</h3>
            <p class="note">Stock value by days to expiry</p>
            @if (failed(e.state)) {
              <p class="err">{{ couldNot }}</p>
            } @else if (e.state === 'EMPTY' || (e.bars ?? []).length === 0) {
              <p class="note">{{ noStock }}</p>
            } @else {
              @for (bar of e.bars; track bar.band) {
                <div [attr.data-testid]="'band-' + bar.band">
                  <div class="bandrow">
                    <span>
                      <b>{{ band(bar.band) }}</b> · {{ bar.batchCount }} batches
                      @if (bar.band === 'EXPIRED') {
                        · blocked at POS
                      }
                    </span>
                    <span>{{ orDash(bar.valuePaise) }}</span>
                  </div>
                  <div class="bar"><span [style.width.%]="width(e.bars ?? [], bar.valuePaise)" [style.background]="colour(bar.band)"></span></div>
                </div>
              }
            }
          </section>
        }
        @if (d.livePrescriptions; as lp) {
          <section class="panel surf" aria-labelledby="lp-h" data-testid="cc-live-rx">
            <h3 id="lp-h">Live prescriptions from Sahaj OPD / IPD</h3>
            @if (failed(lp.state)) {
              <p class="err">{{ couldNot }}</p>
            } @else if ((lp.items ?? []).length === 0) {
              <p class="note" data-testid="cc-live-rx-empty">No prescriptions waiting.</p>
            } @else {
              <div class="tbl-wrap">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Time</th>
                      <th scope="col">Patient</th>
                      <th scope="col">Source</th>
                      <th scope="col" class="num">Items</th>
                      <th scope="col">Availability</th>
                      @if (canDispensing()) {
                        <th scope="col"><span class="sr-only">Open</span></th>
                      }
                    </tr>
                  </thead>
                  <tbody>
                    @for (item of lp.items; track item.id) {
                      <tr>
                        <td>{{ time(item.at) }}</td>
                        <td>{{ item.patientName }}</td>
                        <td>{{ item.source }}</td>
                        <td class="num">{{ item.itemCount }}</td>
                        <td>{{ item.availability }}</td>
                        @if (canDispensing()) {
                          <td>
                            <button mat-button type="button" [attr.data-testid]="'live-rx-open-' + item.id" (click)="openDispensing.emit()">
                              {{ item.action === 'BILL' ? 'Bill' : 'Issue' }}
                            </button>
                          </td>
                        }
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </section>
        }
      </div>

      @if (d.compliance; as c) {
        <section class="panel surf" aria-labelledby="ch-h" data-testid="cc-compliance">
          <div class="head">
            <h3 id="ch-h">Compliance health</h3>
            <button mat-button type="button" data-testid="cc-open-registers" (click)="openRegisters.emit()">Open registers →</button>
          </div>
          @if (failed(c.state)) {
            <p class="err">{{ couldNot }}</p>
          } @else {
            <div class="tiles">
              @if (c.h1; as h1) {
                <div class="tile" data-testid="comp-h1">
                  <span class="pill" [class.p-good]="h1.overridesToReview === 0" [class.p-warn]="h1.overridesToReview > 0">
                    {{ h1.overridesToReview === 0 ? 'Up to date' : h1.overridesToReview + ' overrides to review' }}
                  </span>
                  <div><b>Schedule H1 register</b></div>
                  <div class="s">Auto-filled from {{ h1.billsToday }} bills today</div>
                </div>
              }
              @if (c.scheduleX; as sx) {
                <div class="tile" data-testid="comp-x">
                  @if (neverCounted(sx.neverCounted) === 0 || !sx.balanced) {
                    @if (neverCounted(sx.neverCounted) === 0 || sx.toReview > 0) {
                      <span class="pill" [class.p-good]="sx.balanced" [class.p-warn]="!sx.balanced">
                        {{ sx.balanced ? 'Balanced' : sx.toReview + ' to review' }}
                      </span>
                    }
                  }
                  @if (neverCounted(sx.neverCounted) > 0) {
                    <span class="pill p-warn" data-testid="comp-x-never-counted">{{ neverCountedText(sx.neverCounted) }}</span>
                  }
                  <div><b>Schedule X / NDPS stock</b></div>
                  @if (sx.balanced && neverCounted(sx.neverCounted) === 0) {
                    <div class="s">Physical = book · double-signed</div>
                  }
                </div>
              }
              @if (c.drugLicence; as l) {
                <div class="tile" data-testid="comp-licence">
                  <span class="pill" [class.p-good]="l.state === 'VALID'" [class.p-warn]="l.state === 'DUE'" [class.p-crit]="l.state === 'EXPIRED'">
                    {{ licenceText(l.state, l.daysLeft) }}
                  </span>
                  <div><b>Drug licence 20B / 21B renewal</b></div>
                </div>
              }
            </div>
          }
        </section>
      }
    } @else {
      <p class="loading" role="status">Loading the Command Center</p>
    }
  `,
})
export class CommandCentreSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  readonly newBill = output<void>();
  readonly openRegisters = output<void>();
  readonly openDispensing = output<void>();

  protected readonly data = signal<CommandCentre | null>(null);
  protected readonly loadError = signal<string | null>(null);
  /** '' = the working store; 'ALL' = every selling store (the owner only). */
  protected readonly storeChoice = signal<'' | 'ALL'>('');

  protected readonly money = rupees;
  /** Average per bill on the same basis as the tile: net of refunds divided by the bills (rule of 0.3.8). */
  protected readonly average = (netPaise: number | undefined, bills: number | undefined): number =>
    bills ? Math.round((netPaise ?? 0) / bills) : 0;
  protected readonly time = (instant: string) => formatTimeOfInstant(instant);
  protected readonly neverCounted = (n: number | undefined): number => (typeof n === 'number' && n > 0 ? n : 0);
  protected readonly neverCountedText = (n: number | undefined): string =>
    `No count yet: ${this.neverCounted(n)} ${this.neverCounted(n) === 1 ? 'product' : 'products'}`;
  protected readonly couldNot = COULD_NOT_LOAD;
  protected readonly isNumber = (v: number | null | undefined): boolean => typeof v === 'number';
  protected readonly orDash = (v: number | null | undefined): string => (typeof v === 'number' ? rupees(v) : '—');
  protected readonly noStock = 'No stock in this store yet. Post opening stock or receive stock.';

  protected readonly owner = computed(() => this.context.context()?.caller.role === 'OWNER');
  protected readonly workingName = computed(() => this.context.workingStore()?.name ?? 'This store');
  protected readonly canNewBill = computed(() => this.context.canPos() && this.context.canSell());
  protected readonly canDispensing = computed(() => this.context.canOpdQueue() || this.context.canIpdQueue());
  protected readonly canPurchaseEntry = computed(() => this.context.canReceive());
  protected readonly dateLine = computed(() => {
    const store = this.data()?.store?.name ?? (this.storeChoice() === 'ALL' ? 'All selling stores' : this.workingName());
    return `${weekday()}, ${formatDisplayDate(istToday())} · ${store}`;
  });
  protected readonly asOf = computed(() => {
    const d = this.data();
    return d ? `As of ${formatTimeOfInstant(d.asOf)} IST` : '';
  });

  ngOnInit(): void {
    this.load();
  }

  protected failed(state: CardState): boolean {
    return state === 'ERROR';
  }

  protected band(key: keyof typeof BAND_TEXT): string {
    return BAND_TEXT[key];
  }

  protected colour(key: keyof typeof BAND_COLOUR): string {
    return BAND_COLOUR[key];
  }

  protected width(bars: { valuePaise: number | null }[], value: number | null): number {
    const max = Math.max(0, ...bars.map((b) => b.valuePaise ?? 0));
    return max === 0 || value === null ? 0 : Math.max(2, Math.round((value / max) * 100));
  }

  protected licenceText(state: string, daysLeft: number | null): string {
    switch (state) {
      case 'DUE':
        return `Due in ${daysLeft ?? 0} days`;
      case 'EXPIRED':
        return 'Expired';
      case 'VALID':
        return 'Valid';
      case 'NO_DATE':
        return 'No date';
      default:
        return 'Not entered';
    }
  }

  protected pickStore(value: string): void {
    this.storeChoice.set(value === 'ALL' ? 'ALL' : '');
    this.load();
  }

  protected load(): void {
    this.loadError.set(null);
    const storeId = this.storeChoice() === 'ALL' ? undefined : (this.context.workingStoreId() ?? undefined);
    if (storeId === undefined && this.storeChoice() !== 'ALL') {
      this.data.set(null);
      return;
    }
    this.api.commandCentre(storeId).subscribe({
      next: (answer) => this.data.set(answer),
      error: (error: unknown) => {
        this.data.set(null);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }
}
