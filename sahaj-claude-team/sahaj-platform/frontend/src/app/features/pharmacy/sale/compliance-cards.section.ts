import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { addDays, formatDisplayDateTimeOfInstant, istToday } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { ActivityPage, ActivityType, RoleAction, RoleReadout } from '../pharmacy-compliance.model';
import { loadErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';

const PAGE_SIZE = 25;

const ACTION_TEXT: Record<RoleAction, string> = {
  DISCOUNT_ABOVE_LIMIT: 'Discount above the limit',
  CANCEL_SAVED_BILL: 'Cancel saved bill',
  SALES_RETURN: 'Refund / sales return',
  STOCK_ADJUSTMENT: 'Stock adjustment',
  OVERRIDE_ALLERGY_H1: 'Override allergy / H1 block',
  RAISE_WARD_INDENT: 'Raise ward indent',
};

const COLUMN_TEXT: Record<string, string> = {
  OWNER: 'Owner',
  PHARMACIST: 'Pharmacist',
  CASHIER: 'Cashier',
  STORE_KEEPER: 'Store keeper',
  WARD_NURSE: 'Ward nurse',
};

const CELL_TEXT = {
  ALLOW: 'Allow',
  NEEDS_APPROVAL: 'Needs approval',
  DENY: 'Deny',
  SAME_DAY_ONLY: 'Same day only',
} as const;

const TYPE_TEXT: Record<ActivityType, string> = {
  ALLERGY_OVERRIDE: 'Allergy override',
  H1_OVERRIDE: 'Schedule H1 override',
  DISCOUNT_REQUESTED: 'Discount requested',
  DISCOUNT_DECIDED: 'Discount decided',
  STOCK_ADJUSTED: 'Stock adjusted',
  BILL_CANCELLED: 'Bill cancelled',
  SALES_RETURN: 'Sales return',
  SECOND_SIGNATURE: 'Second signature',
};

/**
 * The cards "Role-based permissions & approvals" and "Audit log" of P&I mockup 05-compliance-roles-and-audit.html
 * (SAH-80-19), for the owner and pharmacists. The permissions table is a read-out of what the server enforces: the
 * rows and cells are the server's, so the screen cannot disagree with it, and it has no editor ("These are the rules in
 * force. The owner changes them in Inventory setup."). The audit log lists pharmacy events newest first, 25 a page, with
 * the time, the person and role, what happened and the store, filtered by date and type. It is titled "Audit log"; the
 * mockup's "tamper-evident" is not claimed. No export here (the "User activity & audit log" report downloads it).
 */
@Component({
  selector: 'app-compliance-cards',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    table {
      min-width: 640px;
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
    input.field,
    select.field {
      min-height: 44px;
      padding: 6px 10px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
    }
    ul.log {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    ul.log li {
      padding: 8px 10px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
    }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="rb-h" data-testid="role-readout">
      <h2 id="rb-h">Role-based permissions &amp; approvals</h2>
      @if (roleError(); as text) {
        <div class="notice err" role="alert" data-testid="role-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="loadRoles()">Try again</button>
        </div>
      } @else if (roles(); as ro) {
        <div class="tbl-wrap">
          <table data-testid="role-table">
            <thead>
              <tr>
                <th scope="col">Action</th>
                @for (c of ro.columns; track c) {
                  <th scope="col">{{ column(c) }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (row of ro.rows; track row.action) {
                <tr [attr.data-testid]="'role-row-' + row.action">
                  <td>{{ action(row.action) }}</td>
                  @for (cell of row.cells; track cell.column) {
                    <td [attr.data-testid]="'role-' + row.action + '-' + cell.column">{{ cell.ruleText || cellText(cell.value) }}</td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="note" data-testid="role-sentence">{{ ro.sentence }}</p>
      } @else {
        <p class="loading" role="status">Loading</p>
      }
    </section>

    <section class="panel surf" aria-labelledby="al-h" data-testid="audit-log">
      <h2 id="al-h">Audit log</h2>
      <form class="filters" (submit)="search($event)">
        <label>
          From
          <input class="field" type="date" data-testid="log-from" [value]="from()" (input)="from.set($any($event.target).value)" />
        </label>
        <label>
          To
          <input class="field" type="date" data-testid="log-to" [value]="to()" (input)="to.set($any($event.target).value)" />
        </label>
        <label>
          What happened
          <select class="field" data-testid="log-type" (change)="type.set($any($event.target).value)">
            <option value="" [selected]="type() === ''">Everything</option>
            @for (t of types; track t.key) {
              <option [value]="t.key" [selected]="type() === t.key">{{ t.label }}</option>
            }
          </select>
        </label>
        <button mat-stroked-button type="submit" data-testid="log-show">Show</button>
      </form>
      @if (rangeError(); as text) {
        <p class="note" role="alert" data-testid="log-range-error">{{ text }}</p>
      }
      @if (logError(); as text) {
        <div class="notice err" role="alert" data-testid="log-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="loadLog(0)">Try again</button>
        </div>
      } @else if (log(); as l) {
        @if (l.items.length === 0) {
          <p class="note" data-testid="log-empty">No records for this filter.</p>
        } @else {
          <ul class="log" data-testid="log-list">
            @for (e of l.items; track $index) {
              <li>
                <b>{{ time(e.at) }}</b> · {{ e.by.name }} ({{ e.roleLabel }}) {{ e.text }} · {{ e.store.name }}
              </li>
            }
          </ul>
          <div class="pager">
            <span role="status">Page {{ l.page + 1 }} of {{ pages(l.total) }}</span>
            <button mat-stroked-button type="button" data-testid="log-prev" [disabled]="l.page === 0" (click)="loadLog(l.page - 1)">Previous</button>
            <button mat-stroked-button type="button" data-testid="log-next" [disabled]="(l.page + 1) * pageSize >= l.total" (click)="loadLog(l.page + 1)">Next</button>
          </div>
        }
      } @else {
        <p class="loading" role="status">Loading</p>
      }
    </section>
  `,
})
export class ComplianceCardsSection implements OnInit {
  private readonly api = inject(PharmacyApi);

  protected readonly roles = signal<RoleReadout | null>(null);
  protected readonly roleError = signal<string | null>(null);
  protected readonly log = signal<ActivityPage | null>(null);
  protected readonly logError = signal<string | null>(null);
  protected readonly rangeError = signal<string | null>(null);
  protected readonly from = signal(addDays(istToday(), -30));
  protected readonly to = signal(istToday());
  protected readonly type = signal<'' | ActivityType>('');

  protected readonly pageSize = PAGE_SIZE;
  protected readonly types = (Object.keys(TYPE_TEXT) as ActivityType[]).map((key) => ({ key, label: TYPE_TEXT[key] }));
  protected readonly action = (a: RoleAction) => ACTION_TEXT[a];
  protected readonly column = (c: string) => COLUMN_TEXT[c] ?? c;
  protected readonly cellText = (v: keyof typeof CELL_TEXT) => CELL_TEXT[v];
  protected readonly time = (instant: string) => formatDisplayDateTimeOfInstant(instant);
  protected readonly pages = (total: number) => Math.max(1, Math.ceil(total / PAGE_SIZE));

  ngOnInit(): void {
    this.loadRoles();
    this.loadLog(0);
  }

  protected loadRoles(): void {
    this.roleError.set(null);
    this.api.roleReadout().subscribe({
      next: (r) => this.roles.set(r),
      error: (e: unknown) => this.roleError.set(loadErrorMessage(e)),
    });
  }

  protected search(event: Event): void {
    event.preventDefault();
    this.loadLog(0);
  }

  protected loadLog(page: number): void {
    this.rangeError.set(null);
    this.logError.set(null);
    if (!this.from() || !this.to() || this.to() < this.from()) {
      this.rangeError.set('The end date must be on or after the start date.');
      return;
    }
    if (addDays(this.from(), 366) < this.to()) {
      this.rangeError.set('Choose at most 12 months.');
      return;
    }
    this.api
      .activityFeed({ from: this.from(), to: this.to(), type: this.type() || undefined, page, size: PAGE_SIZE })
      .subscribe({
        next: (p) => this.log.set(p),
        error: (e: unknown) => {
          this.log.set(null);
          this.logError.set(loadErrorMessage(e));
        },
      });
  }
}
