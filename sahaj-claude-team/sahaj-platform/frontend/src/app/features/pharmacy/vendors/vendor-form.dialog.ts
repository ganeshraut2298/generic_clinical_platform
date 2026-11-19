import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiError } from '../../../core/http/problem-detail';
import { newClientKey } from '../../../shared/client-key';
import { provideIsoDateAdapter } from '../../../shared/format/iso-date-adapter';
import { IconComponent } from '../../../shared/icon/icon.component';
import {
  applyFieldErrors,
  controlMessage,
  loadErrorMessage,
  problemSlug,
  saveErrorMessage,
} from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { Store, Vendor, VendorInput } from '../pharmacy.model';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { gstinFormat, nameValidator, percentFormat, phoneFormat, wholeNumber } from './vendor.util';

export interface VendorFormData {
  /** The vendor being changed; absent when adding. */
  vendor?: Vendor;
}

export const VENDOR_FORM_CONFIG = {
  panelClass: 'sheet-panel',
  width: '720px',
  maxWidth: '100vw',
  ariaLabelledBy: 'vendor-form-title',
} as const;

/**
 * "+ Vendor" and "Edit vendor" sheet (story 78-8). Fields in the story's order (NEW, OQ-78-3): Name,
 * GSTIN, Drug licence required, Drug licence number, Drug licence valid till, Contact, Credit days,
 * Return policy, Supplies to. The licence fields are hidden when the licence is not required. Limits
 * match `VendorInput`. A failed save keeps what was typed; one key per opened sheet keeps a double tap
 * to one vendor.
 */
@Component({
  selector: 'app-vendor-form',
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
    SHEET_COLUMN_STYLES,
    `
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 12px;
      align-items: start;
    }
    .wide {
      grid-column: 1 / -1;
    }
    h3 {
      margin: 4px 0 0;
      font-size: 0.9rem;
    }
  `,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="vendor-form-title">{{ editing ? 'Edit vendor' : 'Add a vendor' }}</h2>
        <p>The suppliers you buy from. A vendor with an expired drug licence is blocked automatically.</p>
      </div>
      <button mat-icon-button type="button" (click)="close()" aria-label="Close">
        <app-icon name="x" />
      </button>
    </div>

    <form class="sheet-b" [formGroup]="form" (ngSubmit)="save()" novalidate aria-labelledby="vendor-form-title">
      <div class="grid">
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="wide">
          <mat-label>Name</mat-label>
          <input matInput formControlName="name" maxlength="120" autocomplete="off" data-testid="f-vendor-name" />
          @if (message(form.controls.name, nameMessages); as text) {
            <mat-error>{{ text }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>GSTIN</mat-label>
          <input
            matInput
            formControlName="gstin"
            maxlength="15"
            autocomplete="off"
            placeholder="Not set"
            data-testid="f-vendor-gstin"
          />
          @if (message(form.controls.gstin, gstinMessages); as text) {
            <mat-error>{{ text }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Drug licence required</mat-label>
          <mat-select formControlName="licenceRequired" data-testid="f-vendor-licence-required">
            <mat-option [value]="true">Yes</mat-option>
            <mat-option [value]="false">No</mat-option>
          </mat-select>
        </mat-form-field>

        @if (licenceRequired()) {
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Drug licence number</mat-label>
            <input
              matInput
              formControlName="licenceNo"
              maxlength="40"
              autocomplete="off"
              data-testid="f-vendor-licence-no"
            />
            @if (message(form.controls.licenceNo, licenceNoMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Drug licence valid till</mat-label>
            <input
              matInput
              [matDatepicker]="tillPicker"
              formControlName="licenceValidTill"
              placeholder="24 Oct 2026"
              autocomplete="off"
              data-testid="f-vendor-valid-till"
            />
            <mat-datepicker-toggle matIconSuffix [for]="tillPicker" aria-label="Choose the licence valid till date" />
            <mat-datepicker #tillPicker />
            @if (message(form.controls.licenceValidTill, noMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>
        }

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Contact name</mat-label>
          <input
            matInput
            formControlName="contactName"
            maxlength="80"
            autocomplete="off"
            data-testid="f-vendor-contact-name"
          />
          @if (message(form.controls.contactName, noMessages); as text) {
            <mat-error>{{ text }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Contact phone</mat-label>
          <input
            matInput
            formControlName="contactPhone"
            maxlength="10"
            inputmode="numeric"
            autocomplete="off"
            data-testid="f-vendor-phone"
          />
          @if (message(form.controls.contactPhone, phoneMessages); as text) {
            <mat-error>{{ text }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Credit days</mat-label>
          <input
            matInput
            formControlName="creditDays"
            maxlength="3"
            inputmode="numeric"
            autocomplete="off"
            data-testid="f-vendor-credit"
          />
          @if (message(form.controls.creditDays, creditMessages); as text) {
            <mat-error>{{ text }}</mat-error>
          }
        </mat-form-field>

        <h3 class="wide">Return policy</h3>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Returns accepted, days before expiry</mat-label>
          <input
            matInput
            formControlName="returnDaysBeforeExpiry"
            maxlength="3"
            inputmode="numeric"
            autocomplete="off"
            data-testid="f-vendor-return-days"
          />
          @if (message(form.controls.returnDaysBeforeExpiry, returnDaysMessages); as text) {
            <mat-error>{{ text }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Credit given on return, %</mat-label>
          <input
            matInput
            formControlName="returnCreditPercent"
            maxlength="6"
            inputmode="decimal"
            autocomplete="off"
            data-testid="f-vendor-return-percent"
          />
          @if (message(form.controls.returnCreditPercent, percentMessages); as text) {
            <mat-error>{{ text }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Credit for damaged on receipt</mat-label>
          <input
            matInput
            formControlName="damagedCreditPercent"
            maxlength="6"
            inputmode="decimal"
            autocomplete="off"
            data-testid="f-vendor-damaged-credit"
          />
          <span matTextSuffix>%</span>
          @if (message(form.controls.damagedCreditPercent, percentMessages); as text) {
            <mat-error>{{ text }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Credit after expiry</mat-label>
          <input
            matInput
            formControlName="expiredCreditPercent"
            maxlength="6"
            inputmode="decimal"
            autocomplete="off"
            data-testid="f-vendor-expired-credit"
          />
          <span matTextSuffix>%</span>
          @if (message(form.controls.expiredCreditPercent, percentMessages); as text) {
            <mat-error>{{ text }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="wide">
          <mat-label>Supplies to</mat-label>
          <mat-select formControlName="suppliesTo" multiple data-testid="f-vendor-supplies">
            @for (store of stores(); track store.id) {
              <mat-option [value]="store.id">{{ store.name }}</mat-option>
            }
          </mat-select>
          @if (message(form.controls.suppliesTo, noMessages); as text) {
            <mat-error>{{ text }}</mat-error>
          }
        </mat-form-field>
      </div>

      @if (storesError(); as text) {
        <div class="notice err" role="alert" data-testid="vendor-stores-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="loadStores()">Try again</button>
        </div>
      }
      @for (text of extraErrors(); track text) {
        <div class="notice err" role="alert"><app-icon name="alert" [size]="16" /><span>{{ text }}</span></div>
      }
      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="vendor-form-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </form>

    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close()">Cancel</button>
      <button mat-flat-button type="button" data-testid="save-vendor" [disabled]="saving()" (click)="save()">
        {{ editing ? 'Save changes' : 'Add vendor' }}
      </button>
    </div>
  `,
})
export class VendorFormDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<VendorFormDialog, Vendor | null>>(MatDialogRef);
  protected readonly data = inject<VendorFormData>(MAT_DIALOG_DATA);
  protected readonly editing = this.data.vendor !== undefined;

  /** One key for this opened sheet, so a double tap or a retry creates one vendor (rule 10). */
  private readonly clientKey = newClientKey();

  protected readonly stores = signal<Store[]>([]);
  protected readonly storesError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly extraErrors = signal<string[]>([]);

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [nameValidator] }),
    gstin: new FormControl('', { nonNullable: true, validators: [gstinFormat] }),
    licenceRequired: new FormControl(true, { nonNullable: true }),
    licenceNo: new FormControl('', { nonNullable: true }),
    // ISO `YYYY-MM-DD`; the datepicker shows "24 Oct 2026" and writes null when cleared.
    licenceValidTill: new FormControl<string | null>(''),
    contactName: new FormControl('', { nonNullable: true }),
    contactPhone: new FormControl('', { nonNullable: true, validators: [phoneFormat] }),
    creditDays: new FormControl('30', { nonNullable: true, validators: [wholeNumber(0, 365, false)] }),
    returnDaysBeforeExpiry: new FormControl('', { nonNullable: true, validators: [wholeNumber(0, 365, true)] }),
    returnCreditPercent: new FormControl('', { nonNullable: true, validators: [percentFormat] }),
    damagedCreditPercent: new FormControl('', { nonNullable: true, validators: [percentFormat] }),
    expiredCreditPercent: new FormControl('', { nonNullable: true, validators: [percentFormat] }),
    suppliesTo: new FormControl<string[]>([], { nonNullable: true }),
  });

  protected readonly licenceRequired = toSignal(this.form.controls.licenceRequired.valueChanges, {
    initialValue: true,
  });

  constructor() {
    // A server-side error set on the two licence controls must not outlive the switch to No: the controls
    // are hidden then, and an invalid hidden control would stop every later Save without a word (M-2).
    this.form.controls.licenceRequired.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
      this.clearLicenceErrors();
    });
  }

  protected readonly message = controlMessage;
  protected readonly noMessages = {};
  protected readonly nameMessages = {
    required: 'Enter the vendor name',
    maxlength: 'Use up to 120 characters',
  };
  protected readonly gstinMessages = { gstin: 'Enter a 15-character GSTIN' };
  protected readonly licenceNoMessages = { maxlength: 'Use up to 40 characters' };
  protected readonly phoneMessages = { phone: 'Enter a 10-digit phone number' };
  protected readonly creditMessages = {
    required: 'Enter 0 to 365 days',
    range: 'Enter 0 to 365 days',
  };
  protected readonly returnDaysMessages = { range: 'Enter 0 to 365 days' };
  protected readonly percentMessages = { percent: 'Enter a % from 0 to 100' };

  ngOnInit(): void {
    const vendor = this.data.vendor;
    if (vendor) {
      this.form.reset({
        name: vendor.name,
        gstin: vendor.gstin ?? '',
        licenceRequired: vendor.licenceRequired ?? false,
        licenceNo: vendor.licenceNo ?? '',
        licenceValidTill: vendor.licenceValidTill ?? '',
        contactName: vendor.contactName ?? '',
        contactPhone: vendor.contactPhone ?? '',
        creditDays: vendor.creditDays === null ? '30' : String(vendor.creditDays),
        returnDaysBeforeExpiry:
          vendor.returnDaysBeforeExpiry === null ? '' : String(vendor.returnDaysBeforeExpiry),
        returnCreditPercent: vendor.returnCreditPercent ?? '',
        damagedCreditPercent: vendor.damagedCreditPercent ?? '',
        expiredCreditPercent: vendor.expiredCreditPercent ?? '',
        suppliesTo: (vendor.suppliesTo ?? []).map((s) => s.storeId),
      });
    }
    this.loadStores();
  }

  /** Only active stores of this unit can be chosen; the Central Store is the default (story 78-8). */
  protected loadStores(): void {
    this.storesError.set(null);
    this.api.stores().subscribe({
      next: (list) => {
        this.stores.set(list.items);
        const known = new Set(list.items.map((s) => s.id));
        const current = this.form.controls.suppliesTo.value;
        const kept = current.filter((id) => known.has(id));
        if (!this.data.vendor && kept.length === 0 && list.centralStoreId) {
          this.form.controls.suppliesTo.setValue([list.centralStoreId]);
        } else if (kept.length !== current.length) {
          this.form.controls.suppliesTo.setValue(kept);
        }
      },
      error: (error: unknown) => this.storesError.set(loadErrorMessage(error)),
    });
  }

  protected close(): void {
    this.ref.close(null);
  }

  private clearLicenceErrors(): void {
    this.form.controls.licenceNo.updateValueAndValidity({ emitEvent: false });
    this.form.controls.licenceValidTill.updateValueAndValidity({ emitEvent: false });
  }

  protected save(): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    this.extraErrors.set([]);
    const raw = this.form.getRawValue();
    this.form.markAllAsTouched();
    if (!raw.licenceRequired) {
      this.clearLicenceErrors();
    }
    if (raw.licenceRequired) {
      if (raw.licenceNo.trim() === '') {
        this.form.controls.licenceNo.setErrors({ server: 'Enter the drug licence number' });
      } else if (raw.licenceNo.trim().length > 40) {
        this.form.controls.licenceNo.setErrors({ maxlength: true });
      }
      if (!raw.licenceValidTill) {
        this.form.controls.licenceValidTill.setErrors({ server: 'Enter the licence valid-till date' });
      }
    }
    if (this.form.invalid) {
      return;
    }

    const body = {
      name: raw.name.trim(),
      gstin: raw.gstin.trim() === '' ? null : raw.gstin.trim().toUpperCase(),
      licenceRequired: raw.licenceRequired,
      licenceNo: raw.licenceRequired ? raw.licenceNo.trim() : null,
      licenceValidTill: raw.licenceRequired && raw.licenceValidTill ? raw.licenceValidTill : null,
      contactName: raw.contactName.trim() === '' ? null : raw.contactName.trim(),
      contactPhone: raw.contactPhone.trim() === '' ? null : raw.contactPhone.trim(),
      creditDays: Number(raw.creditDays.trim()),
      returnDaysBeforeExpiry:
        raw.returnDaysBeforeExpiry.trim() === '' ? null : Number(raw.returnDaysBeforeExpiry.trim()),
      returnCreditPercent: raw.returnCreditPercent.trim() === '' ? null : raw.returnCreditPercent.trim(),
      // Optional levels (contract 0.2.1): an empty box sends null, which clears the stored value.
      damagedCreditPercent: raw.damagedCreditPercent.trim() === '' ? null : raw.damagedCreditPercent.trim(),
      expiredCreditPercent: raw.expiredCreditPercent.trim() === '' ? null : raw.expiredCreditPercent.trim(),
      suppliesToStoreIds: raw.suppliesTo,
    } satisfies Omit<VendorInput, 'clientKey'>;

    this.saving.set(true);
    const vendor = this.data.vendor;
    const request = vendor
      ? this.api.updateVendor(vendor.id, { version: vendor.version, ...body })
      : this.api.createVendor({ clientKey: this.clientKey, ...body });
    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.ref.close(saved);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.onSaveError(error);
      },
    });
  }

  private onSaveError(error: unknown): void {
    if (error instanceof ApiError && error.status === 400 && error.fieldErrors.length > 0) {
      const rest = applyFieldErrors(
        {
          name: this.form.controls.name,
          gstin: this.form.controls.gstin,
          licenceRequired: this.form.controls.licenceRequired,
          licenceNo: this.form.controls.licenceNo,
          licenceValidTill: this.form.controls.licenceValidTill,
          contactName: this.form.controls.contactName,
          contactPhone: this.form.controls.contactPhone,
          creditDays: this.form.controls.creditDays,
          returnDaysBeforeExpiry: this.form.controls.returnDaysBeforeExpiry,
          returnCreditPercent: this.form.controls.returnCreditPercent,
          damagedCreditPercent: this.form.controls.damagedCreditPercent,
          expiredCreditPercent: this.form.controls.expiredCreditPercent,
          suppliesTo: this.form.controls.suppliesTo,
        },
        error.fieldErrors,
        { suppliesToStoreIds: 'suppliesTo' },
      );
      this.extraErrors.set(rest);
      return;
    }
    if (problemSlug(error) === 'vendor-name-in-use') {
      this.form.controls.name.setErrors({ server: 'That vendor is already in the list' });
      this.form.controls.name.markAsTouched();
      return;
    }
    this.formError.set(saveErrorMessage(error));
  }
}
