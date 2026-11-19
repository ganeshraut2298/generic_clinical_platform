import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { controlMessage, saveErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { ApprovalRules, TransactionType } from '../pharmacy.model';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import {
  PRESET_DESCRIPTIONS,
  PRESET_LABELS,
  PresetKey,
  REPLACE_ROWS_TEXT,
  TRANSACTION_LABELS,
  limitIsPercent,
  toPresetLimit,
} from './approval-rules.util';

export interface PresetDialogData {
  preset: PresetKey;
  baseVersionNo: number;
  /** The transaction types that need a limit under this preset, as the server listed them (contract 0.2.1). */
  limitTypes: readonly TransactionType[];
}

export const PRESET_DIALOG_CONFIG = {
  panelClass: 'sheet-panel',
  width: '560px',
  maxWidth: '100vw',
  ariaLabelledBy: 'preset-title',
} as const;

/**
 * Confirmation of a preset (story 78-9): "This replaces all rows below. You can change them afterwards."
 * A preset whose owner step needs a limit asks for it here, one line per transaction type, with the choice
 * "No amount condition". Sahaj supplies no default amount (OQ-78-26). Nothing changes until "Apply preset".
 */
@Component({
  selector: 'app-preset-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    IconComponent,
  ],
  styles: [
    SHEET_COLUMN_STYLES,
    `
    .limit {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px;
      margin-top: 8px;
    }
    .limit mat-form-field {
      flex: 1 1 200px;
    }
  `,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="preset-title">Apply the {{ presetLabel }} preset?</h2>
        <p data-testid="preset-description">{{ description }}</p>
      </div>
      <button mat-icon-button type="button" (click)="close()" aria-label="Close">
        <app-icon name="x" />
      </button>
    </div>

    <form class="sheet-b" [formGroup]="form" (ngSubmit)="apply()" novalidate aria-labelledby="preset-title">
      <p data-testid="preset-confirm">{{ replaceText }}</p>
      @if (types.length > 0) {
        <p class="note">
          Enter the limit above which the owner approves. Sahaj sets no amounts; you can also choose no amount
          condition.
        </p>
        @for (type of types; track type) {
          <div class="limit" [formGroupName]="type">
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>{{ label(type) }}, {{ isPercent(type) ? 'limit %' : 'limit ₹' }}</mat-label>
              <input
                matInput
                [formControlName]="isPercent(type) ? 'percent' : 'amount'"
                inputmode="decimal"
                autocomplete="off"
                [attr.data-testid]="'preset-limit-' + type"
              />
              @if (message(limitControl(type), noMessages); as text) {
                <mat-error>{{ text }}</mat-error>
              }
            </mat-form-field>
            <mat-checkbox formControlName="none" [attr.data-testid]="'preset-none-' + type">
              No amount condition
            </mat-checkbox>
          </div>
        }
      }
      @for (text of extraErrors(); track text) {
        <div class="notice err" role="alert"><app-icon name="alert" [size]="16" /><span>{{ text }}</span></div>
      }
      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="preset-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </form>

    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close()">Cancel</button>
      <button mat-flat-button type="button" data-testid="apply-preset" [disabled]="saving()" (click)="apply()">
        Apply preset
      </button>
    </div>
  `,
})
export class PresetDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<PresetDialog, ApprovalRules | null>>(MatDialogRef);
  protected readonly data = inject<PresetDialogData>(MAT_DIALOG_DATA);

  protected readonly presetLabel = PRESET_LABELS[this.data.preset];
  protected readonly description = PRESET_DESCRIPTIONS[this.data.preset];
  protected readonly replaceText = REPLACE_ROWS_TEXT;
  protected readonly types: readonly TransactionType[] = this.data.limitTypes;
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly extraErrors = signal<string[]>([]);
  protected readonly message = controlMessage;
  protected readonly noMessages = {};
  protected readonly label = (type: TransactionType) => TRANSACTION_LABELS[type];
  protected readonly isPercent = limitIsPercent;

  protected readonly form = new FormGroup(
    Object.fromEntries(
      this.data.limitTypes.map((type) => [
        type,
        new FormGroup({
          amount: new FormControl('', { nonNullable: true }),
          percent: new FormControl('', { nonNullable: true }),
          none: new FormControl(false, { nonNullable: true }),
        }),
      ]),
    ) as Record<string, FormGroup>,
  );

  ngOnInit(): void {
    // Choosing "No amount condition" clears the box; typing a value clears the choice.
    for (const type of this.types) {
      const group = this.form.controls[type];
      const field = limitIsPercent(type) ? 'percent' : 'amount';
      group.controls['none'].valueChanges.subscribe((none: boolean) => {
        if (none) {
          group.controls[field].setValue('', { emitEvent: false });
          group.controls[field].setErrors(null);
        }
      });
      group.controls[field].valueChanges.subscribe((value: string) => {
        if (value !== '' && group.controls['none'].value) {
          group.controls['none'].setValue(false, { emitEvent: false });
        }
      });
    }
  }

  protected limitControl(type: TransactionType) {
    return this.form.controls[type].controls[limitIsPercent(type) ? 'percent' : 'amount'];
  }

  protected close(): void {
    this.ref.close(null);
  }

  protected apply(): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    this.extraErrors.set([]);
    const limits = [];
    let missing = false;
    for (const type of this.types) {
      const group = this.form.controls[type];
      const entry = toPresetLimit(type, group.getRawValue() as { amount: string; percent: string; none: boolean });
      if (entry) {
        limits.push(entry);
        continue;
      }
      missing = true;
      const control = this.limitControl(type);
      const empty = String(control.value ?? '').trim() === '';
      control.setErrors({ server: empty ? 'Enter the limit' : limitIsPercent(type) ? 'Enter a % from 0 to 100' : 'Enter an amount up to ₹ 9,99,99,999' });
      control.markAsTouched();
    }
    if (missing) {
      return;
    }

    this.saving.set(true);
    this.api
      .applyApprovalPreset({ baseVersionNo: this.data.baseVersionNo, preset: this.data.preset, limits })
      .subscribe({
        next: (rules) => {
          this.saving.set(false);
          this.ref.close(rules);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.onError(error);
        },
      });
  }

  private onError(error: unknown): void {
    if (error instanceof ApiError && error.status === 400 && error.fieldErrors.length > 0) {
      const rest: string[] = [];
      for (const { field, message } of error.fieldErrors) {
        const type = field.replace(/^limits\./, '') as TransactionType;
        if (this.types.includes(type)) {
          const control = this.limitControl(type);
          control.setErrors({ server: message });
          control.markAsTouched();
        } else {
          rest.push(message);
        }
      }
      this.extraErrors.set(rest);
      return;
    }
    // 409 rules-changed shows the server sentence; the page reads the current rules when the sheet closes.
    this.formError.set(saveErrorMessage(error));
  }
}
