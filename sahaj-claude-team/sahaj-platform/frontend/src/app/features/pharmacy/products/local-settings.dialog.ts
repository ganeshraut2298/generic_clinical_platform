import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormArray,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import {
  applyFieldErrors,
  controlMessage,
  loadErrorMessage,
  problemSlug,
  saveErrorMessage,
} from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { LocalSettingsInput, ProductDetail } from '../pharmacy.model';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';

export interface LocalSettingsData {
  productId: string;
  productName: string;
}

export const LOCAL_SETTINGS_CONFIG = {
  panelClass: 'sheet-panel',
  width: '640px',
  maxWidth: '100vw',
  ariaLabelledBy: 'local-settings-title',
} as const;

const BARCODE_PATTERN = /^[A-Za-z0-9-]{1,40}$/;

/** Reorder level: empty, or a whole number 0 to 999,999. */
function reorderRule(control: AbstractControl): ValidationErrors | null {
  const text = String(control.value ?? '').trim();
  return text === '' || (/^[0-9]{1,6}$/.test(text) && Number(text) <= 999999) ? null : { reorder: true };
}

/**
 * "Local settings" sheet of an adopted or own product (story 78-5). Fields in the story's order:
 * Reorder level per store (issue units), Rack per store, Preferred vendor, Alias, Barcode, Returnable,
 * and "Stop using this product". Preferred vendor offers "None", the active vendors of the unit (story 78-8) and
 * the vendor already chosen, if any. The values are for this unit only; the global fields are
 * not here and cannot be changed.
 */
@Component({
  selector: 'app-local-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    IconComponent,
  ],
  styles: [
    SHEET_COLUMN_STYLES,
    `
    .stores {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .store-row {
      display: grid;
      grid-template-columns: minmax(120px, 1fr) minmax(120px, 1fr) minmax(120px, 1fr);
      gap: 10px;
      align-items: start;
    }
    .store-name {
      align-self: center;
      font-weight: 600;
      overflow-wrap: anywhere;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 12px;
      align-items: start;
    }
    h3 {
      margin: 4px 0 0;
      font-size: 0.95rem;
    }
  `,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="local-settings-title">Local settings</h2>
        <p>{{ data.productName }}</p>
      </div>
      <button mat-icon-button type="button" (click)="close()" aria-label="Close">
        <app-icon name="x" />
      </button>
    </div>

    <form class="sheet-b" [formGroup]="form" (ngSubmit)="save()" novalidate aria-labelledby="local-settings-title">
      @if (loading()) {
        <p class="loading" role="status">Loading local settings</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="local-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else {
        <h3>Reorder level and rack per store</h3>
        @if (stores.length === 0) {
          <p class="note" data-testid="no-stores">There is no active store yet. Create a store first.</p>
        } @else {
          <div class="stores" formArrayName="storeSettings">
            @for (group of stores.controls; track group.controls.storeId.value; let i = $index) {
              <div class="store-row" [formGroupName]="i" [attr.data-testid]="'store-row-' + i">
                <span class="store-name">{{ group.controls.storeName.value }}</span>
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>Reorder level</mat-label>
                  <input
                    matInput
                    formControlName="reorderLevel"
                    inputmode="numeric"
                    autocomplete="off"
                    [attr.data-testid]="'f-reorder-' + i"
                  />
                  <mat-hint>In {{ issueUnit() }}</mat-hint>
                  @if (message(group.controls.reorderLevel, reorderMessages); as text) {
                    <mat-error>{{ text }}</mat-error>
                  }
                </mat-form-field>
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>Rack</mat-label>
                  <input
                    matInput
                    formControlName="rack"
                    maxlength="30"
                    autocomplete="off"
                    [attr.data-testid]="'f-rack-' + i"
                  />
                  @if (message(group.controls.rack, rackMessages); as text) {
                    <mat-error>{{ text }}</mat-error>
                  }
                </mat-form-field>
              </div>
            }
          </div>
        }

        <div class="grid">
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Preferred vendor</mat-label>
            <mat-select formControlName="preferredVendorId" placeholder="Choose an option" data-testid="f-vendor">
              <mat-option value="">None</mat-option>
              @for (vendor of vendorChoices(); track vendor.id) {
                <mat-option [value]="vendor.id">{{ vendor.name }}</mat-option>
              }
            </mat-select>
            @if (message(form.controls.preferredVendorId, vendorMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Alias</mat-label>
            <input matInput formControlName="alias" maxlength="40" autocomplete="off" data-testid="f-alias" />
            @if (message(form.controls.alias, aliasMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Barcode</mat-label>
            <input matInput formControlName="barcode" maxlength="40" autocomplete="off" data-testid="f-barcode" />
            @if (message(form.controls.barcode, barcodeMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Returnable</mat-label>
            <mat-select formControlName="returnable" placeholder="Choose an option" data-testid="f-returnable">
              <mat-option [value]="true">Yes</mat-option>
              <mat-option [value]="false">No</mat-option>
            </mat-select>
          </mat-form-field>
        </div>

        @if (vendorNeedsReview()) {
          <div class="notice warn" data-testid="vendor-review">
            <app-icon name="alert" [size]="16" />
            <span>Preferred vendor needs review</span>
          </div>
        }

        <mat-slide-toggle formControlName="stop" data-testid="f-stop">Stop using this product</mat-slide-toggle>
        @if (form.controls.stop.value) {
          <p class="note" data-testid="stop-note">
            The product will not be offered for new work. Its history is kept.
          </p>
        }
      }

      @for (text of extraErrors(); track text) {
        <div class="notice err" role="alert"><app-icon name="alert" [size]="16" /><span>{{ text }}</span></div>
      }
      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="local-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          @if (changedByOther()) {
            <button mat-stroked-button type="button" data-testid="load-current" (click)="load()">
              Show current values
            </button>
          }
        </div>
      }
    </form>

    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close()">Cancel</button>
      <button
        mat-flat-button
        type="button"
        data-testid="save-local"
        [disabled]="saving() || loading() || loadError() !== null"
        (click)="save()"
      >
        Save settings
      </button>
    </div>
  `,
})
export class LocalSettingsDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<LocalSettingsDialog, ProductDetail | null>>(MatDialogRef);
  protected readonly data = inject<LocalSettingsData>(MAT_DIALOG_DATA);
  protected readonly message = controlMessage;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly changedByOther = signal(false);
  protected readonly extraErrors = signal<string[]>([]);
  protected readonly issueUnit = signal('issue units');
  protected readonly currentVendor = signal<{ id: string; name: string } | null>(null);
  protected readonly vendorNeedsReview = signal(false);
  /** The active vendors of the unit (story 78-8); a failed read leaves just None and the current vendor. */
  private readonly activeVendors = signal<{ id: string; name: string }[]>([]);
  protected readonly vendorChoices = computed(() => {
    const current = this.currentVendor();
    const list = this.activeVendors();
    return current && !list.some((v) => v.id === current.id) ? [current, ...list] : list;
  });
  private version = 0;

  protected readonly stores = new FormArray<
    FormGroup<{
      storeId: FormControl<string>;
      storeName: FormControl<string>;
      reorderLevel: FormControl<string>;
      rack: FormControl<string>;
    }>
  >([]);

  protected readonly form = new FormGroup({
    storeSettings: this.stores,
    preferredVendorId: new FormControl('', { nonNullable: true }),
    alias: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(40)] }),
    barcode: new FormControl('', { nonNullable: true, validators: [Validators.pattern(BARCODE_PATTERN)] }),
    returnable: new FormControl(true, { nonNullable: true }),
    stop: new FormControl(false, { nonNullable: true }),
  });

  protected readonly reorderMessages = { reorder: 'Enter a whole number, 0 or more' };
  protected readonly rackMessages = { maxlength: 'Keep the rack to 30 characters' };
  protected readonly vendorMessages = { required: 'Choose an active vendor' };
  protected readonly aliasMessages = { maxlength: 'Keep the alias to 40 characters' };
  protected readonly barcodeMessages = { pattern: 'Use letters, digits and hyphen, up to 40 characters' };

  ngOnInit(): void {
    this.load();
    this.api.vendors({ status: 'ACTIVE', size: 100 }).subscribe({
      next: (page) => this.activeVendors.set(page.items.map((v) => ({ id: v.id, name: v.name }))),
      error: () => undefined,
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.formError.set(null);
    this.changedByOther.set(false);
    this.extraErrors.set([]);
    this.api.product(this.data.productId).subscribe({
      next: (product) => {
        this.fill(product);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  private fill(product: ProductDetail): void {
    const local = product.localSettings;
    this.version = product.version;
    this.issueUnit.set(product.issueUnit);
    this.currentVendor.set(local.preferredVendor ? { id: local.preferredVendor.id, name: local.preferredVendor.name } : null);
    this.vendorNeedsReview.set(local.preferredVendor?.needsReview ?? false);
    this.stores.clear();
    for (const setting of local.storeSettings) {
      this.stores.push(
        new FormGroup({
          storeId: new FormControl(setting.storeId, { nonNullable: true }),
          storeName: new FormControl(setting.storeName, { nonNullable: true }),
          reorderLevel: new FormControl(setting.reorderLevel === null ? '' : String(setting.reorderLevel), {
            nonNullable: true,
            validators: [reorderRule],
          }),
          rack: new FormControl(setting.rack ?? '', { nonNullable: true, validators: [Validators.maxLength(30)] }),
        }),
      );
    }
    this.form.patchValue({
      preferredVendorId: local.preferredVendor?.id ?? '',
      alias: local.alias ?? '',
      barcode: local.barcode ?? '',
      returnable: local.returnable,
      stop: !local.active,
    });
    this.form.markAsPristine();
  }

  protected close(): void {
    this.ref.close(null);
  }

  protected save(): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    this.changedByOther.set(false);
    this.extraErrors.set([]);
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      return;
    }
    const raw = this.form.getRawValue();
    const body: LocalSettingsInput = {
      version: this.version,
      storeSettings: raw.storeSettings.map((s) => ({
        storeId: s.storeId,
        reorderLevel: s.reorderLevel.trim() === '' ? null : Number(s.reorderLevel),
        rack: s.rack.trim() || null,
      })),
      preferredVendorId: raw.preferredVendorId || null,
      alias: raw.alias.trim() || null,
      barcode: raw.barcode.trim() || null,
      returnable: raw.returnable,
      active: !raw.stop,
    };
    this.saving.set(true);
    this.api.saveLocalSettings(this.data.productId, body).subscribe({
      next: (product) => {
        this.saving.set(false);
        this.ref.close(product);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.onError(error);
      },
    });
  }

  private onError(error: unknown): void {
    if (error instanceof ApiError && error.status === 400 && error.fieldErrors.length > 0) {
      this.extraErrors.set(
        applyFieldErrors(
          {
            preferredVendorId: this.form.controls.preferredVendorId,
            alias: this.form.controls.alias,
            barcode: this.form.controls.barcode,
          },
          error.fieldErrors,
        ),
      );
      return;
    }
    const slug = problemSlug(error);
    if (slug === 'barcode-in-use') {
      this.form.controls.barcode.setErrors({ server: saveErrorMessage(error) });
      this.form.controls.barcode.markAsTouched();
      return;
    }
    this.changedByOther.set(slug === 'settings-changed');
    this.formError.set(saveErrorMessage(error));
  }
}
