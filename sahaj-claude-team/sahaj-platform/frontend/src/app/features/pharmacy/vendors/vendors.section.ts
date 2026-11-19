import { ChangeDetectionStrategy, Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { sheetToast } from '../../../shared/ui/sheet-toast';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { Vendor, VendorStatusFilter } from '../pharmacy.model';
import { CONFIRM_SHEET_CONFIG, ConfirmSheetComponent } from '../shared/confirm-sheet.component';
import { VENDOR_FORM_CONFIG, VendorFormData, VendorFormDialog } from './vendor-form.dialog';
import {
  canSwitchOff,
  canSwitchOn,
  creditText,
  gstinText,
  licenceExpired,
  licenceText,
  suppliesToText,
  vendorStatusChip,
} from './vendor.util';

const PAGE_SIZE = 25;

/** The filter values of `GET /vendors`; the default of the server is Active and Blocked. */
const FILTERS: readonly { value: VendorStatusFilter | 'DEFAULT'; label: string }[] = [
  { value: 'DEFAULT', label: 'Active and blocked' },
  { value: 'INACTIVE', label: 'Switched off' },
  { value: 'ALL', label: 'All vendors' },
];

/**
 * "Vendors" section of Inventory setup (mockup 16 tab "Vendors"; story 78-8): the card "Vendors /
 * suppliers" with "+ Vendor", the table Vendor, GSTIN, Drug licence, Credit, Products, Supplies to,
 * Status, and the note under it. Blocked is computed by the server from the licence date. A reader of
 * setup sees the table and no button; Add, Edit and Switch off are drawn only with SETUP_EDIT.
 */
@Component({
  selector: 'app-vendors',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatFormFieldModule, MatSelectModule, IconComponent, EmptyStateComponent],
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .filter {
      display: block;
      max-width: 260px;
      margin-top: 8px;
    }
    table {
      min-width: 900px;
    }
    .warn-text {
      color: var(--warn);
    }
    .acts {
      white-space: nowrap;
      text-align: right;
    }
    .acts button + button {
      margin-left: 6px;
    }
    .sr-only {
      position: absolute;
      left: -9999px;
    }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
      margin-top: 10px;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="vm-h" data-testid="vendors">
      <div class="panel-h">
        <h2 id="vm-h">Vendors / suppliers</h2>
        @if (canEdit()) {
          <button
            mat-flat-button
            type="button"
            data-testid="add-vendor"
            aria-label="Add vendor"
            (click)="openForm()"
          >
            <app-icon name="plus" [size]="14" /> Vendor
          </button>
        }
      </div>

      <mat-form-field appearance="outline" subscriptSizing="dynamic" class="filter">
        <mat-label>Show</mat-label>
        <mat-select
          [value]="filter()"
          (selectionChange)="setFilter($event.value)"
          data-testid="vendor-filter"
        >
          @for (option of filters; track option.value) {
            <mat-option [value]="option.value">{{ option.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>

      @if (actionError(); as text) {
        <div class="notice err" role="alert" data-testid="vendor-action-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }

      @if (loading()) {
        <p class="loading" role="status" data-testid="vendors-loading">Loading vendors</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="vendors-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
        <div class="actions">
          <button mat-stroked-button type="button" (click)="load(page())">Try again</button>
        </div>
      } @else if (rows().length === 0) {
        <app-empty>
          @if (filter() !== 'DEFAULT') {
            <span data-testid="vendors-none">No vendors in this view.</span>
          } @else if (canEdit()) {
            <span data-testid="vendors-empty">No vendors yet. Add the suppliers you buy from.</span>
          } @else {
            <span data-testid="vendors-empty">No vendors yet.</span>
          }
        </app-empty>
      } @else {
        <div class="tbl-wrap" data-testid="vendor-table">
          <table>
            <thead>
              <tr>
                <th scope="col">Vendor</th>
                <th scope="col">GSTIN</th>
                <th scope="col">Drug licence</th>
                <th scope="col">Credit</th>
                <th scope="col">Products</th>
                <th scope="col">Supplies to</th>
                <th scope="col">Status</th>
                @if (canEdit()) {
                  <th scope="col"><span class="sr-only">Actions</span></th>
                }
              </tr>
            </thead>
            <tbody>
              @for (vendor of rows(); track vendor.id) {
                <tr [attr.data-testid]="'vendor-' + vendor.id">
                  <td>
                    <strong>{{ vendor.name }}</strong>
                  </td>
                  <td>{{ gstin(vendor) }}</td>
                  <td [class.warn-text]="expired(vendor)" [attr.data-testid]="'vendor-licence-' + vendor.id">
                    {{ licence(vendor) }}
                  </td>
                  <td>{{ credit(vendor) }}</td>
                  <td>{{ vendor.productsCount ?? '—' }}</td>
                  <td>{{ supplies(vendor) }}</td>
                  <td>
                    <span class="pill" [class]="status(vendor).css" [attr.data-testid]="'vendor-status-' + vendor.id">
                      {{ status(vendor).text }}
                    </span>
                  </td>
                  @if (canEdit()) {
                    <td class="acts">
                      <button
                        mat-stroked-button
                        type="button"
                        class="btn-sm"
                        [attr.data-testid]="'edit-vendor-' + vendor.id"
                        [attr.aria-label]="'Edit ' + vendor.name"
                        (click)="openForm(vendor)"
                      >
                        Edit
                      </button>
                      @if (switchable(vendor)) {
                        <button
                          mat-stroked-button
                          type="button"
                          class="btn-sm"
                          [attr.data-testid]="'switch-off-vendor-' + vendor.id"
                          [attr.aria-label]="'Switch off ' + vendor.name"
                          (click)="confirmSwitchOff(vendor)"
                        >
                          Switch off
                        </button>
                      }
                      @if (switchableOn(vendor)) {
                        <button
                          mat-stroked-button
                          type="button"
                          class="btn-sm"
                          [attr.data-testid]="'switch-on-vendor-' + vendor.id"
                          [attr.aria-label]="'Switch on ' + vendor.name"
                          (click)="switchOn(vendor)"
                        >
                          Switch on
                        </button>
                      }
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>

        <div class="pager" data-testid="vendor-pager">
          <span role="status" data-testid="vendor-range">{{ rangeText() }}</span>
          <button
            mat-stroked-button
            type="button"
            data-testid="vendors-prev"
            [disabled]="page() === 0"
            (click)="load(page() - 1)"
          >
            Previous
          </button>
          <button
            mat-stroked-button
            type="button"
            data-testid="vendors-next"
            [disabled]="!hasNext()"
            (click)="load(page() + 1)"
          >
            Next
          </button>
        </div>
      }

      <p class="note" data-testid="vendors-note">
        A vendor with an expired drug licence is blocked from new purchase orders automatically.
      </p>
    </section>
  `,
})
export class VendorsSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly context = inject(PharmacyContextStore);

  /** A vendor was added, changed or switched off, so the live count on the tab needs reading again. */
  readonly changed = output<void>();

  protected readonly canEdit = this.context.canEditSetup;
  protected readonly filters = FILTERS;
  protected readonly filter = signal<VendorStatusFilter | 'DEFAULT'>('DEFAULT');
  protected readonly rows = signal<Vendor[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);

  protected readonly hasNext = computed(() => (this.page() + 1) * PAGE_SIZE < this.total());
  protected readonly rangeText = computed(() => {
    const from = this.page() * PAGE_SIZE + 1;
    const to = Math.min(this.total(), (this.page() + 1) * PAGE_SIZE);
    return `Showing ${from}–${to} of ${this.total()}`;
  });

  protected readonly gstin = gstinText;
  protected readonly licence = licenceText;
  protected readonly expired = licenceExpired;
  protected readonly credit = creditText;
  protected readonly supplies = suppliesToText;
  protected readonly status = vendorStatusChip;
  protected readonly switchable = canSwitchOff;
  protected readonly switchableOn = canSwitchOn;

  ngOnInit(): void {
    this.load(0);
  }

  protected setFilter(value: VendorStatusFilter | 'DEFAULT'): void {
    this.filter.set(value);
    this.load(0);
  }

  /** Reads one page. A change to the list never shows a stale page as current. */
  protected load(pageNo: number, quiet = false): void {
    if (!quiet) {
      this.loading.set(true);
    }
    this.loadError.set(null);
    const filter = this.filter();
    this.api
      .vendors({ status: filter === 'DEFAULT' ? undefined : filter, page: pageNo, size: PAGE_SIZE })
      .subscribe({
        next: (answer) => {
          this.rows.set(answer.items);
          this.total.set(answer.total);
          this.page.set(answer.page);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(loadErrorMessage(error));
        },
      });
  }

  protected openForm(vendor?: Vendor): void {
    this.actionError.set(null);
    const data: VendorFormData = { vendor };
    this.dialog
      .open(VendorFormDialog, { ...VENDOR_FORM_CONFIG, data })
      .afterClosed()
      .subscribe((saved: Vendor | null | undefined) => {
        // Read again after every close: a conflict may have changed what is current.
        this.load(this.page(), true);
        if (saved) {
          this.changed.emit();
          this.toast(vendor ? 'Vendor saved' : 'Vendor added');
        }
      });
  }

  protected confirmSwitchOff(vendor: Vendor): void {
    this.actionError.set(null);
    this.dialog
      .open(ConfirmSheetComponent, {
        ...CONFIRM_SHEET_CONFIG,
        data: {
          title: `Switch off ${vendor.name}?`,
          body: 'It will not be offered for new work or as a preferred vendor. Its records are kept and it is not deleted.',
          confirmLabel: 'Switch off',
        },
      })
      .afterClosed()
      .subscribe((yes) => {
        if (yes !== true) {
          return;
        }
        this.api.switchOffVendor(vendor.id).subscribe({
          next: () => {
            this.load(this.page(), true);
            this.changed.emit();
            this.toast('Vendor switched off');
          },
          error: (error: unknown) => this.actionError.set(saveErrorMessage(error)),
        });
      });
  }

  /** Switching on needs no confirmation: nothing is lost by it (it is the way back from "Switch off"). */
  protected switchOn(vendor: Vendor): void {
    this.actionError.set(null);
    this.api.switchOnVendor(vendor.id).subscribe({
      next: () => {
        this.load(this.page(), true);
        this.changed.emit();
        this.toast('Vendor switched on');
      },
      error: (error: unknown) => this.actionError.set(saveErrorMessage(error)),
    });
  }

  private toast(text: string): void {
    this.snackBar.open(text, undefined, sheetToast(3000));
  }
}
