import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { forkJoin } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { Brand } from '../../../core/platform/platform.model';
import { applyBrand } from '../../../core/shell/apply-brand';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { formatWeekdayDate, istToday, problemMessage } from '../config-format';
import { ClinicApi } from '../platform/clinic.api';
import {
  BRAND_PRESETS,
  ClinicProfile,
  HEX_COLOUR,
  HOLIDAY_TYPES,
  Holiday,
  HolidayType,
} from '../platform/clinic.model';

const MAPS_LINK = /^https?:\/\/\S+$/i;

const notBlank: ValidatorFn = (control) =>
  String(control.value ?? '').trim() ? null : { required: true };

/** Empty text is left out of the body, so a cleared optional field is cleared on the server. */
function textOrUndefined(value: string): string | undefined {
  const text = value.trim();
  return text ? text : undefined;
}

/**
 * Clinic profile (SAH-7), mockup `cfgProfile`: clinic details, holidays and closures, brand
 * colour. Each field saves when you leave it. Non-owners see the same page read-only; the
 * backend still checks the role. Logo upload, OPD timings and doctor leave are not built yet.
 */
@Component({
  selector: 'app-profile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, IconComponent],
  styleUrl: './profile.page.scss',
  templateUrl: './profile.page.html',
})
export class ProfilePage implements OnInit {
  private readonly api = inject(ClinicApi);
  private readonly store = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly holidayTypes = HOLIDAY_TYPES;
  protected readonly presets = BRAND_PRESETS;
  protected readonly formatWeekdayDate = formatWeekdayDate;
  protected readonly today = istToday();

  protected readonly profile = signal<ClinicProfile | null>(null);
  protected readonly holidays = signal<Holiday[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly savingBrand = signal(false);
  protected readonly addingHoliday = signal(false);
  protected readonly holidayError = signal<string | null>(null);
  protected readonly removingId = signal<string | null>(null);
  protected readonly customOpen = signal(false);

  protected readonly isOwner = computed(() => this.store.me()?.role === 'OWNER');
  protected readonly currentBrand = computed<Brand>(() => this.profile()?.brand ?? 'blue');
  protected readonly webAddress = computed(() => {
    const slug = this.profile()?.slug;
    return slug ? `${slug}.sahaj.in` : '';
  });

  protected readonly detailsForm = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [notBlank, Validators.maxLength(160)],
    }),
    nameLocal: new FormControl('', { nonNullable: true }),
    address: new FormControl('', { nonNullable: true }),
    phone: new FormControl('', { nonNullable: true }),
    whatsapp: new FormControl('', { nonNullable: true }),
    mapsUrl: new FormControl('', { nonNullable: true, validators: [Validators.pattern(MAPS_LINK)] }),
  });

  protected readonly holidayForm = new FormGroup({
    date: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    name: new FormControl('', { nonNullable: true, validators: [notBlank] }),
    type: new FormControl<HolidayType>('CLOSED', { nonNullable: true }),
  });

  protected readonly customHex = new FormControl('', {
    nonNullable: true,
    validators: [Validators.pattern(HEX_COLOUR)],
  });

  ngOnInit(): void {
    forkJoin({ profile: this.api.getProfile(), holidays: this.api.listHolidays() }).subscribe({
      next: ({ profile, holidays }) => {
        this.profile.set(profile);
        this.holidays.set(this.sorted(holidays));
        this.detailsForm.reset({
          name: profile.name,
          nameLocal: profile.nameLocal ?? '',
          address: profile.address ?? '',
          phone: profile.phone ?? '',
          whatsapp: profile.whatsapp ?? '',
          mapsUrl: profile.mapsUrl ?? '',
        });
        this.customHex.setValue(profile.brandHex ?? '');
        this.customOpen.set(profile.brand === 'custom');
        if (!this.isOwner()) {
          this.detailsForm.disable();
          this.holidayForm.disable();
          this.customHex.disable();
        }
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(problemMessage(error, 'We could not load your clinic profile. Try again.'));
      },
    });
  }

  protected get nameError(): string | null {
    const control = this.detailsForm.controls.name;
    if (!control.touched || !control.invalid) {
      return null;
    }
    return control.hasError('maxlength') ? 'Use 160 characters or fewer' : 'Enter the clinic name';
  }

  protected get mapsError(): string | null {
    const control = this.detailsForm.controls.mapsUrl;
    return control.touched && control.invalid ? 'Paste a link that starts with https://' : null;
  }

  /** Saves the details when a field is left. A field with an error is not sent. */
  protected saveDetails(): void {
    const current = this.profile();
    if (!current || !this.isOwner()) {
      return;
    }
    this.detailsForm.markAllAsTouched();
    if (this.detailsForm.invalid) {
      return;
    }
    const value = this.detailsForm.getRawValue();
    const body: ClinicProfile = {
      ...current,
      name: value.name.trim(),
      nameLocal: textOrUndefined(value.nameLocal),
      address: textOrUndefined(value.address),
      phone: textOrUndefined(value.phone),
      whatsapp: textOrUndefined(value.whatsapp),
      mapsUrl: textOrUndefined(value.mapsUrl),
    };
    this.saving.set(true);
    this.api.saveProfile(body).subscribe({
      next: (saved) => {
        this.profile.set(saved);
        this.saving.set(false);
        this.toast('Clinic details saved');
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.toast(problemMessage(error, 'We could not save that change. Try again.'));
      },
    });
  }

  protected pickPreset(brand: Brand): void {
    const current = this.profile();
    if (!current || !this.isOwner() || this.savingBrand()) {
      return;
    }
    this.customOpen.set(false);
    this.saveBrand({ ...current, brand, brandHex: undefined });
  }

  protected toggleCustom(): void {
    this.customOpen.update((open) => !open);
  }

  protected useCustom(): void {
    const current = this.profile();
    const raw = this.customHex.value.trim();
    const hex = raw.startsWith('#') ? raw : `#${raw}`;
    if (!current || !this.isOwner() || this.savingBrand()) {
      return;
    }
    if (!HEX_COLOUR.test(hex)) {
      this.customHex.markAsTouched();
      this.customHex.setErrors({ pattern: true });
      return;
    }
    this.saveBrand({ ...current, brand: 'custom', brandHex: hex.toLowerCase() });
  }

  protected get hexError(): string | null {
    const control = this.customHex;
    return control.touched && control.invalid ? 'Enter 6 characters, like 1F5BC8' : null;
  }

  private saveBrand(body: ClinicProfile): void {
    this.savingBrand.set(true);
    this.api.saveProfile(body).subscribe({
      next: (saved) => {
        this.profile.set(saved);
        this.customHex.setValue(saved.brandHex ?? '');
        applyBrand(saved.brand ?? 'blue', saved.brandHex);
        this.savingBrand.set(false);
        this.toast('Brand colour saved');
      },
      error: (error: unknown) => {
        this.savingBrand.set(false);
        this.toast(problemMessage(error, 'We could not save the colour. Try again.'));
      },
    });
  }

  protected addHoliday(): void {
    if (!this.isOwner() || this.addingHoliday()) {
      return;
    }
    this.holidayForm.markAllAsTouched();
    if (this.holidayForm.invalid) {
      return;
    }
    const { date, name, type } = this.holidayForm.getRawValue();
    this.addingHoliday.set(true);
    this.holidayError.set(null);
    this.api.addHoliday({ date, name: name.trim(), type }).subscribe({
      next: (holiday) => {
        this.holidays.update((list) => this.sorted([...list, holiday]));
        this.holidayForm.reset({ date: '', name: '', type: 'CLOSED' });
        this.addingHoliday.set(false);
        this.toast(`${holiday.name} added`);
      },
      error: (error: unknown) => {
        this.addingHoliday.set(false);
        this.holidayError.set(
          error instanceof ApiError && error.status === 409
            ? 'A holiday is already set for that date.'
            : problemMessage(error, 'We could not add this holiday. Try again.'),
        );
      },
    });
  }

  protected removeHoliday(holiday: Holiday): void {
    if (!holiday.id || !this.isOwner()) {
      return;
    }
    const id = holiday.id;
    this.removingId.set(id);
    this.api.removeHoliday(id).subscribe({
      next: () => {
        this.holidays.update((list) => list.filter((h) => h.id !== id));
        this.removingId.set(null);
        this.toast(`${holiday.name} removed`);
      },
      error: (error: unknown) => {
        this.removingId.set(null);
        this.toast(problemMessage(error, 'We could not remove this holiday. Try again.'));
      },
    });
  }

  protected typeLabel(type: HolidayType): string {
    return HOLIDAY_TYPES.find((t) => t.value === type)?.label ?? type;
  }

  private sorted(list: Holiday[]): Holiday[] {
    return [...list].sort((a, b) => a.date.localeCompare(b.date));
  }

  private toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }
}
