import { ChangeDetectionStrategy, Component, OnInit, inject, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiError } from '../../../core/http/problem-detail';
import { formatDisplayDate } from '../../../shared/format/ist-date';
import { provideIsoDateAdapter } from '../../../shared/format/iso-date-adapter';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import {
  applyFieldErrors,
  controlMessage,
  loadErrorMessage,
  problemSlug,
  saveErrorMessage,
} from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { INTEGRATION_MODE_LABELS, IntegrationMode, UnitSettings } from '../pharmacy.model';
import { CONFIRM_SHEET_CONFIG, ConfirmSheetComponent } from '../shared/confirm-sheet.component';

/** Format only, as the contract's `Gstin` pattern (OQ-78-23): no check digit, no lookup. */
export const GSTIN_PATTERN = /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/;

/** What the confirmation says before Integration mode changes (story 78-2). */
export function integrationChangeText(next: IntegrationMode): string {
  return next === 'STANDALONE'
    ? 'Standalone hides OPD and IPD links. Existing records are kept.'
    : 'Integrated shows OPD and IPD links again. Existing records are kept.';
}

function gstinValidator(control: { value: unknown }): { gstin: true } | null {
  const text = String(control.value ?? '')
    .trim()
    .toUpperCase();
  return text === '' || GSTIN_PATTERN.test(text) ? null : { gstin: true };
}

/**
 * Unit settings card at the top of Stores & staff (story 78-2; a NEW section: the layout is the
 * accepted design of DEC-062 Q1, no mockup screen draws it). Unit name from the clinic profile,
 * GSTIN, Drug licence number, Drug licence valid till, Integration mode, and two fixed facts, FEFO
 * On and Negative stock Blocked, shown with no switch. The owner edits; a person with view access
 * (a pharmacist or Central Store user) sees the values read-only.
 */
@Component({
  selector: 'app-unit-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
  ],
  providers: [provideIsoDateAdapter()],
  styleUrl: './unit-settings.card.scss',
  templateUrl: './unit-settings.card.html',
})
export class UnitSettingsCard implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  protected readonly context = inject(PharmacyContextStore);

  /** The unit settings were saved (Integration mode may change what every screen shows). */
  readonly saved = output<void>();

  protected readonly modeLabels = INTEGRATION_MODE_LABELS;
  protected readonly modes: readonly IntegrationMode[] = ['INTEGRATED', 'STANDALONE'];
  protected readonly formatDate = formatDisplayDate;
  protected readonly message = controlMessage;

  protected readonly settings = signal<UnitSettings | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly savedNote = signal(false);

  protected readonly form = new FormGroup({
    gstin: new FormControl('', { nonNullable: true, validators: [gstinValidator] }),
    drugLicenceNo: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(40)],
    }),
    // ISO `YYYY-MM-DD` (the API format); the datepicker shows it as "24 Oct 2026" and writes null when cleared.
    drugLicenceValidTill: new FormControl<string | null>(''),
    integrationMode: new FormControl<IntegrationMode>('INTEGRATED', { nonNullable: true }),
  });

  protected readonly gstinMessages = { gstin: 'Enter a 15-character GSTIN' };
  protected readonly licenceMessages = { maxlength: 'Use up to 40 characters' };
  protected readonly noMessages = {};

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.unitSettings().subscribe({
      next: (settings) => {
        this.apply(settings);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  protected save(): void {
    const current = this.settings();
    if (!current || this.saving()) {
      return;
    }
    this.formError.set(null);
    this.savedNote.set(false);
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      return;
    }
    const next = this.form.getRawValue().integrationMode;
    if (next === current.integrationMode) {
      this.send();
      return;
    }
    // Nothing changes until the owner confirms what the new mode does.
    this.dialog
      .open(ConfirmSheetComponent, {
        ...CONFIRM_SHEET_CONFIG,
        data: {
          title: `Change to ${INTEGRATION_MODE_LABELS[next]} mode?`,
          body: integrationChangeText(next),
          confirmLabel: `Change to ${INTEGRATION_MODE_LABELS[next]}`,
        },
      })
      .afterClosed()
      .subscribe((yes) => {
        if (yes === true) {
          this.send();
        }
      });
  }

  private send(): void {
    const current = this.settings();
    if (!current) {
      return;
    }
    const raw = this.form.getRawValue();
    this.saving.set(true);
    this.api
      .saveUnitSettings({
        version: current.version,
        gstin: raw.gstin.trim() === '' ? null : raw.gstin.trim().toUpperCase(),
        drugLicenceNo: raw.drugLicenceNo.trim() === '' ? null : raw.drugLicenceNo.trim(),
        drugLicenceValidTill: raw.drugLicenceValidTill ? raw.drugLicenceValidTill : null,
        integrationMode: raw.integrationMode,
      })
      .subscribe({
        next: (saved) => {
          this.saving.set(false);
          this.apply(saved);
          this.savedNote.set(true);
          this.context.refresh();
          this.saved.emit();
        },
        error: (error: unknown) => this.onSaveError(error),
      });
  }

  private onSaveError(error: unknown): void {
    this.saving.set(false);
    if (error instanceof ApiError && error.status === 400 && error.fieldErrors.length > 0) {
      const rest = applyFieldErrors(
        {
          gstin: this.form.controls.gstin,
          drugLicenceNo: this.form.controls.drugLicenceNo,
          drugLicenceValidTill: this.form.controls.drugLicenceValidTill,
          integrationMode: this.form.controls.integrationMode,
        },
        error.fieldErrors,
      );
      if (rest.length > 0) {
        this.formError.set(rest[0]);
      }
      return;
    }
    this.formError.set(saveErrorMessage(error));
    if (problemSlug(error) === 'settings-changed') {
      // Someone else saved first: the server's sentence is shown with the values that are now current.
      this.load();
    }
  }

  private apply(settings: UnitSettings): void {
    this.settings.set(settings);
    this.form.reset({
      gstin: settings.gstin ?? '',
      drugLicenceNo: settings.drugLicenceNo ?? '',
      drugLicenceValidTill: settings.drugLicenceValidTill ?? '',
      integrationMode: settings.integrationMode,
    });
  }
}
