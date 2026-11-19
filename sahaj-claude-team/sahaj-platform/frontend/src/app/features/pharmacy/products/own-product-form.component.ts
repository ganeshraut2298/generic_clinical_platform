import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Subscription, startWith } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { newClientKey } from '../../../shared/client-key';
import {
  applyFieldErrors,
  controlMessage,
  loadErrorMessage,
  problemSlug,
  saveErrorMessage,
} from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import {
  DuplicateCandidate,
  ProductDetail,
  ProductOptions,
  SCHEDULE_OPTIONS,
  Schedule,
} from '../pharmacy.model';
import { CONFIRM_SHEET_CONFIG, ConfirmSheetComponent } from '../shared/confirm-sheet.component';
import { duplicateMessage, duplicateUseLabel, filterOptions } from './product.util';

export type ProductFormMode = 'create' | 'edit';

/** What the form answers when it ends with a change. A plain cancel is a separate output. */
export interface ProductFormResult {
  kind: 'created' | 'saved' | 'adopted' | 'use-own';
  product: ProductDetail | null;
  /** The own product the owner chose to use instead of creating a new one (kind `use-own`). */
  code?: string;
}

const GST_PATTERN = /^(100(\.0{1,2})?|[0-9]{1,2}(\.[0-9]{1,2})?)$/;
const HSN_PATTERN = /^[0-9]{4,8}$/;
const BARCODE_PATTERN = /^[A-Za-z0-9-]{1,40}$/;

/** A field of text that must hold something other than spaces. */
function filled(control: AbstractControl): ValidationErrors | null {
  return String(control.value ?? '').trim() ? null : { required: true };
}

/** Whole number 1 to 10,000 typed as text, so "1.5" and "1e3" are refused, not rounded. */
function conversionRule(control: AbstractControl): ValidationErrors | null {
  const text = String(control.value ?? '').trim();
  return /^[0-9]{1,5}$/.test(text) && Number(text) >= 1 && Number(text) <= 10000
    ? null
    : { conversion: true };
}

/** Manufacturer is required unless the product is made in-house. */
function manufacturerRule(control: AbstractControl): ValidationErrors | null {
  const madeInHouse = control.parent?.get('madeInHouse')?.value === true;
  return madeInHouse || String(control.value ?? '').trim() ? null : { manufacturer: true };
}

/**
 * The own-product form of story 78-6, shared by the inline card "Create own product" (mockup 16, tab
 * Products; layout `card`) and the "Edit" sheet (layout `sheet`). Mockup fields first (Product name,
 * Salt / generic, Strength, Form, Purchase unit, Issue unit), then the NEW fields (OQ-78-3): Issue units
 * per purchase unit, Manufacturer or Made in-house, HSN, GST %, Schedule, Barcode. When the owner leaves
 * salt, strength or form, the duplicate check runs and the mockup's warning shows with "Use global
 * product" and "Create anyway". Limits and texts are the contract's. Nothing is shown as saved until the
 * server has answered; a failed save keeps what was typed. After a created product the card is cleared.
 */
@Component({
  selector: 'app-own-product-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.as-sheet]': "layout() === 'sheet'" },
  imports: [
    ReactiveFormsModule,
    MatAutocompleteModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
  ],
  styles: `
    :host {
      display: block;
    }
    :host(.as-sheet) {
      display: contents;
    }
    /* In the Edit sheet the form is the scrolling body of a column capped by the screen (QA-78-5). */
    :host(.as-sheet) .sheet-b {
      flex: 1 1 auto;
      min-height: 0;
      overflow-y: auto;
      overscroll-behavior: contain;
    }
    :host(.as-sheet) .sheet-f {
      flex: none;
    }
    .card-form {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 12px;
      align-items: start;
    }
    .mfr {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .dup {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .dup-row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 12px;
      align-items: center;
      justify-content: space-between;
    }
    .dup-row p {
      flex: 1 1 280px;
      margin: 0;
    }
    .dup-acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .card-f button {
      min-height: 44px;
    }
  `,
  template: `
    <form
      [class]="layout() === 'sheet' ? 'sheet-b' : 'card-form'"
      [formGroup]="form"
      (ngSubmit)="save()"
      novalidate
      [attr.aria-labelledby]="labelledBy()"
    >
      @if (loading()) {
        <p class="loading" role="status">Loading the product</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="product-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="loadProduct()">Try again</button>
        </div>
      } @else {
        <div class="grid">
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Product name</mat-label>
            <input matInput formControlName="name" maxlength="80" autocomplete="off" data-testid="f-name" />
            @if (message(form.controls.name, nameMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Salt / generic</mat-label>
            <input
              matInput
              formControlName="salt"
              maxlength="80"
              autocomplete="off"
              data-testid="f-salt"
              (blur)="checkDuplicates()"
            />
            @if (message(form.controls.salt, saltMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Strength</mat-label>
            <input
              matInput
              formControlName="strength"
              maxlength="80"
              autocomplete="off"
              data-testid="f-strength"
              (blur)="checkDuplicates()"
            />
            @if (message(form.controls.strength, strengthMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Form</mat-label>
            <input
              matInput
              formControlName="form"
              maxlength="30"
              autocomplete="off"
              [matAutocomplete]="formAuto"
              data-testid="f-form"
              (blur)="checkDuplicates()"
            />
            <mat-autocomplete #formAuto="matAutocomplete" (optionSelected)="checkDuplicates()">
              @for (option of formOptions(); track option) {
                <mat-option [value]="option">{{ option }}</mat-option>
              }
            </mat-autocomplete>
            @if (message(form.controls.form, formMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Purchase unit</mat-label>
            <input
              matInput
              formControlName="purchaseUnit"
              maxlength="20"
              autocomplete="off"
              [matAutocomplete]="purchaseAuto"
              data-testid="f-purchase-unit"
            />
            <mat-autocomplete #purchaseAuto="matAutocomplete">
              @for (option of purchaseOptions(); track option) {
                <mat-option [value]="option">{{ option }}</mat-option>
              }
            </mat-autocomplete>
            @if (message(form.controls.purchaseUnit, purchaseMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Issue unit</mat-label>
            <input
              matInput
              formControlName="issueUnit"
              maxlength="20"
              autocomplete="off"
              [matAutocomplete]="issueAuto"
              data-testid="f-issue-unit"
            />
            <mat-autocomplete #issueAuto="matAutocomplete">
              @for (option of issueOptions(); track option) {
                <mat-option [value]="option">{{ option }}</mat-option>
              }
            </mat-autocomplete>
            @if (message(form.controls.issueUnit, issueMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Issue units per purchase unit</mat-label>
            <input
              matInput
              formControlName="conversion"
              inputmode="numeric"
              autocomplete="off"
              data-testid="f-conversion"
            />
            @if (message(form.controls.conversion, conversionMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <div class="mfr">
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Manufacturer</mat-label>
              <input
                matInput
                formControlName="manufacturer"
                maxlength="80"
                autocomplete="off"
                data-testid="f-manufacturer"
              />
              @if (message(form.controls.manufacturer, manufacturerMessages); as text) {
                <mat-error>{{ text }}</mat-error>
              }
            </mat-form-field>
            <mat-checkbox formControlName="madeInHouse" data-testid="f-made-in-house">Made in-house</mat-checkbox>
          </div>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>HSN</mat-label>
            <input matInput formControlName="hsn" maxlength="8" inputmode="numeric" autocomplete="off" data-testid="f-hsn" />
            @if (message(form.controls.hsn, hsnMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>GST %</mat-label>
            <input matInput formControlName="gstRate" inputmode="decimal" autocomplete="off" data-testid="f-gst" />
            @if (message(form.controls.gstRate, gstMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Schedule</mat-label>
            <mat-select formControlName="schedule" placeholder="Choose the schedule" data-testid="f-schedule">
              @for (option of schedules; track option.value) {
                <mat-option [value]="option.value">{{ option.label }}</mat-option>
              }
            </mat-select>
            @if (message(form.controls.schedule, scheduleMessages); as text) {
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
        </div>

        @if (unitsLocked()) {
          <div class="notice teal" data-testid="units-locked-note">
            <app-icon name="lock" [size]="16" />
            <span>Locked because this product has stock.</span>
          </div>
        }

        @if (duplicates().length > 0) {
          <div class="notice warn dup" role="status" data-testid="duplicate-warning">
            @for (candidate of duplicates(); track candidate.code) {
              <div class="dup-row">
                <p data-testid="duplicate-message">{{ duplicateText(candidate) }}</p>
                <div class="dup-acts">
                  <button
                    mat-flat-button
                    type="button"
                    [attr.data-testid]="'use-' + candidate.code"
                    [disabled]="saving()"
                    (click)="useCandidate(candidate)"
                  >
                    {{ useLabel(candidate) }}
                  </button>
                </div>
              </div>
            }
            <div class="dup-acts">
              <button
                mat-stroked-button
                type="button"
                data-testid="create-anyway"
                [disabled]="saving()"
                (click)="save(true)"
              >
                {{ editing() ? 'Save anyway' : 'Create anyway' }}
              </button>
            </div>
          </div>
        }
      }

      @for (text of extraErrors(); track text) {
        <div class="notice err" role="alert"><app-icon name="alert" [size]="16" /><span>{{ text }}</span></div>
      }
      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="product-form-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          @if (changedByOther()) {
            <button mat-stroked-button type="button" data-testid="load-current" (click)="loadProduct()">
              Show current values
            </button>
          }
        </div>
      }
    </form>

    <div [class]="layout() === 'sheet' ? 'sheet-f' : 'actions card-f'">
      @if (layout() === 'sheet') {
        <button mat-stroked-button type="button" (click)="cancelled.emit()">Cancel</button>
      }
      <button
        mat-flat-button
        type="button"
        data-testid="save-product"
        [disabled]="saving() || loading() || loadError() !== null"
        (click)="save()"
      >
        {{ editing() ? 'Save changes' : 'Create product' }}
      </button>
    </div>
  `,
})
export class OwnProductFormComponent implements OnInit, OnDestroy {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);

  readonly mode = input.required<ProductFormMode>();
  /** The product to edit (mode `edit`). */
  readonly productId = input<string>();
  /** `card` is the inline card of the mockup; `sheet` is the Edit sheet, whose header and footer sit around it. */
  readonly layout = input<'card' | 'sheet'>('card');
  readonly labelledBy = input('own-product-h');
  /** The owner finished with a change: created, saved, adopted, or chose an own product to use. */
  readonly finished = output<ProductFormResult>();
  /** The owner left the sheet without a change. */
  readonly cancelled = output<void>();

  protected readonly editing = computed(() => this.mode() === 'edit');
  protected readonly schedules = SCHEDULE_OPTIONS;
  protected readonly message = controlMessage;
  protected readonly duplicateText = duplicateMessage;
  protected readonly useLabel = duplicateUseLabel;

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly changedByOther = signal(false);
  protected readonly extraErrors = signal<string[]>([]);
  protected readonly duplicates = signal<DuplicateCandidate[]>([]);
  protected readonly unitsLocked = signal(false);
  private readonly options = signal<ProductOptions>({ units: [], forms: [] });

  /** The product as loaded (edit): its version and the schedule that a change is compared with. */
  private loaded: ProductDetail | null = null;
  private lastCheck = '';
  private clientKey = newClientKey();
  private checkSub: Subscription | null = null;

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [filled, Validators.maxLength(80)] }),
    salt: new FormControl('', { nonNullable: true, validators: [filled, Validators.maxLength(80)] }),
    strength: new FormControl('', { nonNullable: true, validators: [filled, Validators.maxLength(80)] }),
    form: new FormControl('', { nonNullable: true, validators: [filled, Validators.maxLength(30)] }),
    purchaseUnit: new FormControl('', { nonNullable: true, validators: [filled, Validators.maxLength(20)] }),
    issueUnit: new FormControl('', { nonNullable: true, validators: [filled, Validators.maxLength(20)] }),
    conversion: new FormControl('', { nonNullable: true, validators: [conversionRule] }),
    manufacturer: new FormControl('', { nonNullable: true, validators: [manufacturerRule, Validators.maxLength(80)] }),
    madeInHouse: new FormControl(false, { nonNullable: true }),
    hsn: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(HSN_PATTERN)] }),
    gstRate: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(GST_PATTERN)] }),
    schedule: new FormControl<Schedule | ''>('', { nonNullable: true, validators: [Validators.required] }),
    barcode: new FormControl('', { nonNullable: true, validators: [Validators.pattern(BARCODE_PATTERN)] }),
  });

  protected readonly nameMessages = { required: 'Enter the product name', maxlength: 'Keep it to 80 characters' };
  protected readonly saltMessages = { required: 'Enter the salt', maxlength: 'Keep it to 80 characters' };
  protected readonly strengthMessages = { required: 'Enter the strength', maxlength: 'Keep it to 80 characters' };
  protected readonly formMessages = { required: 'Enter the form', maxlength: 'Keep it to 30 characters' };
  protected readonly purchaseMessages = {
    required: 'Enter the purchase unit',
    maxlength: 'Keep it to 20 characters',
  };
  protected readonly issueMessages = { required: 'Enter the issue unit', maxlength: 'Keep it to 20 characters' };
  protected readonly conversionMessages = { conversion: 'Enter a whole number, 1 or more' };
  protected readonly manufacturerMessages = {
    manufacturer: 'Enter the manufacturer or choose Made in-house',
    maxlength: 'Keep it to 80 characters',
  };
  protected readonly hsnMessages = { required: 'Enter a 4 to 8 digit HSN', pattern: 'Enter a 4 to 8 digit HSN' };
  protected readonly gstMessages = {
    required: 'Enter a GST % from 0 to 100',
    pattern: 'Enter a GST % from 0 to 100',
  };
  protected readonly scheduleMessages = { required: 'Choose the schedule' };
  protected readonly barcodeMessages = { pattern: 'Use letters, digits and hyphen, up to 40 characters' };

  private readonly formValue = toSignal(this.form.controls.form.valueChanges.pipe(startWith('')), { initialValue: '' });
  private readonly purchaseValue = toSignal(this.form.controls.purchaseUnit.valueChanges.pipe(startWith('')), {
    initialValue: '',
  });
  private readonly issueValue = toSignal(this.form.controls.issueUnit.valueChanges.pipe(startWith('')), {
    initialValue: '',
  });
  protected readonly formOptions = computed(() => filterOptions(this.options().forms, this.formValue()));
  protected readonly purchaseOptions = computed(() => filterOptions(this.options().units, this.purchaseValue()));
  protected readonly issueOptions = computed(() => filterOptions(this.options().units, this.issueValue()));

  ngOnInit(): void {
    this.form.controls.madeInHouse.valueChanges.subscribe((made) => {
      if (made) {
        this.form.controls.manufacturer.setValue('');
        this.form.controls.manufacturer.disable({ emitEvent: false });
      } else {
        this.form.controls.manufacturer.enable({ emitEvent: false });
      }
      this.form.controls.manufacturer.updateValueAndValidity({ emitEvent: false });
    });
    this.api.productOptions().subscribe({
      next: (options) => this.options.set(options),
      error: () => undefined,
    });
    if (this.editing()) {
      this.loadProduct();
    }
  }

  ngOnDestroy(): void {
    this.checkSub?.unsubscribe();
  }

  /** Edit: reads the product (also after "Show current values"). */
  protected loadProduct(): void {
    const id = this.productId();
    if (!this.editing() || !id) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.formError.set(null);
    this.changedByOther.set(false);
    this.api.product(id).subscribe({
      next: (product) => {
        this.loaded = product;
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
    this.form.reset({
      name: product.name,
      salt: product.salt,
      strength: product.strength,
      form: product.form,
      purchaseUnit: product.purchaseUnit,
      issueUnit: product.issueUnit,
      conversion: String(product.conversion),
      manufacturer: product.manufacturer ?? '',
      madeInHouse: product.madeInHouse,
      hsn: product.hsn,
      gstRate: product.gstRate,
      schedule: product.schedule,
      barcode: product.localSettings.barcode ?? '',
    });
    this.unitsLocked.set(product.unitsLocked);
    for (const control of [this.form.controls.purchaseUnit, this.form.controls.issueUnit, this.form.controls.conversion]) {
      if (product.unitsLocked) {
        control.disable({ emitEvent: false });
      } else {
        control.enable({ emitEvent: false });
      }
    }
    if (product.madeInHouse) {
      this.form.controls.manufacturer.disable({ emitEvent: false });
    }
    this.lastCheck = this.signature();
    this.duplicates.set([]);
  }

  /** The card after a saved product: empty again, a new client key, no old warning, no red fields. */
  private clear(): void {
    this.form.reset();
    this.form.controls.manufacturer.enable({ emitEvent: false });
    this.formError.set(null);
    this.extraErrors.set([]);
    this.duplicates.set([]);
    this.lastCheck = this.signature();
    this.clientKey = newClientKey();
  }

  private finish(result: ProductFormResult): void {
    if (!this.editing()) {
      this.clear();
    }
    this.finished.emit(result);
  }

  private signature(): string {
    const v = this.form.getRawValue();
    return [v.salt, v.strength, v.form].map((x) => x.trim().toLowerCase()).join('|');
  }

  /**
   * The duplicate check, run when the owner leaves salt, strength or form (OQ-78-17): all three must be
   * filled in and something must have changed since the last check. A change clears an earlier warning.
   */
  protected checkDuplicates(): void {
    const v = this.form.getRawValue();
    const signature = this.signature();
    if (signature === this.lastCheck) {
      return;
    }
    this.lastCheck = signature;
    this.duplicates.set([]);
    const salt = v.salt.trim();
    const strength = v.strength.trim();
    const form = v.form.trim();
    if (!salt || !strength || !form || salt.length > 80 || strength.length > 80 || form.length > 30) {
      return;
    }
    this.runCheck(salt, strength, form);
  }

  private runCheck(salt: string, strength: string, form: string): void {
    this.checkSub?.unsubscribe();
    const exclude = this.editing() ? this.productId() : undefined;
    this.checkSub = this.api.findDuplicates(salt, strength, form, exclude).subscribe({
      next: (candidates) => this.duplicates.set(candidates),
      // The check is advice. If it cannot run, the server checks again when the owner saves.
      error: () => this.duplicates.set([]),
    });
  }

  /** "Use global product" adopts it; "Use <own product>" creates nothing and shows that product. */
  protected useCandidate(candidate: DuplicateCandidate): void {
    if (this.saving()) {
      return;
    }
    if (candidate.source === 'OWN' || !candidate.catalogueProductId) {
      this.finish({ kind: 'use-own', product: null, code: candidate.code });
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    this.api.adoptProduct(candidate.catalogueProductId).subscribe({
      next: (product) => {
        this.saving.set(false);
        this.finish({ kind: 'adopted', product });
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.formError.set(saveErrorMessage(error));
      },
    });
  }

  protected save(ignoreDuplicates = false): void {
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
    const schedule = this.form.getRawValue().schedule;
    const previous = this.loaded?.schedule;
    if (this.editing() && previous && schedule !== previous) {
      this.dialog
        .open(ConfirmSheetComponent, {
          ...CONFIRM_SHEET_CONFIG,
          data: {
            title: 'Change the schedule?',
            body: 'This changes how the product is sold and recorded. Check the schedule.',
            confirmLabel: 'Change schedule',
          },
        })
        .afterClosed()
        .subscribe((yes) => {
          if (yes === true) {
            this.send(ignoreDuplicates);
          }
        });
      return;
    }
    this.send(ignoreDuplicates);
  }

  private send(ignoreDuplicates: boolean): void {
    const raw = this.form.getRawValue();
    const body = {
      name: raw.name.trim(),
      salt: raw.salt.trim(),
      strength: raw.strength.trim(),
      form: raw.form.trim(),
      purchaseUnit: raw.purchaseUnit.trim(),
      issueUnit: raw.issueUnit.trim(),
      conversion: Number(raw.conversion),
      manufacturer: raw.madeInHouse ? null : raw.manufacturer.trim() || null,
      madeInHouse: raw.madeInHouse,
      hsn: raw.hsn.trim(),
      gstRate: raw.gstRate.trim(),
      schedule: raw.schedule === '' ? null : raw.schedule,
      barcode: raw.barcode.trim() || null,
      ignoreDuplicates,
    };
    this.saving.set(true);
    const loaded = this.loaded;
    const id = this.productId();
    const request =
      this.editing() && loaded && id
        ? this.api.updateOwnProduct(id, { version: loaded.version, ...body })
        : this.api.createOwnProduct({ clientKey: this.clientKey, ...body });
    request.subscribe({
      next: (product) => {
        this.saving.set(false);
        this.finish({ kind: this.editing() ? 'saved' : 'created', product });
      },
      error: (error: unknown) => this.onError(error, body.salt, body.strength, body.form),
    });
  }

  private onError(error: unknown, salt: string, strength: string, form: string): void {
    this.saving.set(false);
    if (!(error instanceof ApiError) || error.status === 0 || error.status >= 500) {
      this.formError.set(saveErrorMessage(error));
      return;
    }
    // The server answered, so nothing was saved and the next try is a new request.
    this.clientKey = newClientKey();
    const slug = problemSlug(error);
    if (error.status === 400 && error.fieldErrors.length > 0) {
      this.extraErrors.set(
        applyFieldErrors(
          {
            name: this.form.controls.name,
            salt: this.form.controls.salt,
            strength: this.form.controls.strength,
            form: this.form.controls.form,
            purchaseUnit: this.form.controls.purchaseUnit,
            issueUnit: this.form.controls.issueUnit,
            conversion: this.form.controls.conversion,
            manufacturer: this.form.controls.manufacturer,
            hsn: this.form.controls.hsn,
            gstRate: this.form.controls.gstRate,
            schedule: this.form.controls.schedule,
            barcode: this.form.controls.barcode,
          },
          error.fieldErrors,
        ),
      );
      return;
    }
    if (slug === 'possible-duplicate') {
      // The check at the field was skipped or is out of date: show the warning now, save nothing.
      this.lastCheck = this.signature();
      this.runCheck(salt, strength, form);
      return;
    }
    if (slug === 'barcode-in-use') {
      this.form.controls.barcode.setErrors({ server: saveErrorMessage(error) });
      this.form.controls.barcode.markAsTouched();
      return;
    }
    this.changedByOther.set(slug === 'settings-changed');
    this.formError.set(saveErrorMessage(error));
  }
}
