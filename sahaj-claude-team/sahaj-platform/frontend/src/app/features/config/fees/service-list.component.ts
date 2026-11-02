import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/http/problem-detail';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../config-format';
import { OpdApi } from '../opd/opd.api';
import { SERVICE_CATEGORIES, Service, ServiceCategory, ServiceUpdate } from '../opd/opd.model';

export const DUPLICATE_SERVICE = 'That service is already in the list';

/**
 * Service price list (SAH-7), the "Service price list" panel of mockup `cfgFees`. The front desk
 * picks from it at billing. A removed service is only switched off, so past bills keep it.
 */
@Component({
  selector: 'app-service-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent],
  styleUrl: './service-list.component.scss',
  templateUrl: './service-list.component.html',
})
export class ServiceListComponent implements OnInit {
  private readonly api = inject(OpdApi);
  private readonly store = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly categories = SERVICE_CATEGORIES;

  protected readonly services = signal<Service[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly showRemoved = signal(false);
  protected readonly busyId = signal<string | null>(null);
  protected readonly adding = signal(false);
  protected readonly addError = signal<string | null>(null);
  protected readonly rowError = signal<string | null>(null);

  protected readonly isOwner = computed(() => this.store.me()?.role === 'OWNER');
  protected readonly visible = computed(() =>
    this.services().filter((s) => this.showRemoved() || s.active !== false),
  );
  protected readonly removedCount = computed(
    () => this.services().filter((s) => s.active === false).length,
  );

  protected readonly addForm = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [(c) => (String(c.value ?? '').trim() ? null : { required: true }), Validators.maxLength(120)],
    }),
    category: new FormControl<ServiceCategory>('Procedure', { nonNullable: true }),
    price: new FormControl<number | null>(null, {
      validators: [Validators.required, Validators.min(1)],
    }),
  });

  ngOnInit(): void {
    this.api.listServices().subscribe({
      next: (list) => {
        this.services.set(list);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(problemMessage(error, 'We could not load the price list. Try again.'));
      },
    });
  }

  protected get nameError(): string | null {
    const control = this.addForm.controls.name;
    if (!control.touched || !control.invalid) {
      return null;
    }
    return control.hasError('maxlength') ? 'Use 120 characters or fewer' : 'Enter the service name';
  }

  protected get priceError(): string | null {
    const control = this.addForm.controls.price;
    return control.touched && control.invalid ? 'Enter a price above ₹0' : null;
  }

  protected toggleRemoved(): void {
    this.showRemoved.update((on) => !on);
  }

  protected add(): void {
    if (!this.isOwner() || this.adding()) {
      return;
    }
    this.addForm.markAllAsTouched();
    if (this.addForm.invalid) {
      return;
    }
    const { name, category, price } = this.addForm.getRawValue();
    this.adding.set(true);
    this.addError.set(null);
    this.api.addService({ name: name.trim(), category, priceRupees: Math.round(price ?? 0) }).subscribe({
      next: (service) => {
        this.services.update((list) => [...list, service]);
        this.addForm.reset({ name: '', category, price: null });
        this.adding.set(false);
        this.toast(`${service.name} added`);
      },
      error: (error: unknown) => {
        this.adding.set(false);
        this.addError.set(this.errorText(error, 'We could not add this service. Try again.'));
      },
    });
  }

  protected rename(service: Service, input: HTMLInputElement): void {
    const name = input.value.trim();
    if (!name || name.length > 120) {
      input.value = service.name;
      this.rowError.set('Enter a service name of 1 to 120 characters');
      return;
    }
    if (name !== service.name) {
      this.save(service, { name }, () => (input.value = service.name));
    }
  }

  protected recategorise(service: Service, category: ServiceCategory): void {
    if (category !== service.category) {
      this.save(service, { category });
    }
  }

  protected reprice(service: Service, input: HTMLInputElement): void {
    const price = Math.round(Number(input.value));
    if (!Number.isFinite(price) || price < 0 || input.value.trim() === '') {
      input.value = String(service.priceRupees);
      this.rowError.set('Enter a price of ₹0 or more');
      return;
    }
    if (price !== service.priceRupees) {
      this.save(service, { priceRupees: price }, () => (input.value = String(service.priceRupees)));
    }
  }

  protected setActive(service: Service, active: boolean): void {
    this.save(
      service,
      { active },
      undefined,
      active ? `${service.name} is back in the list` : `${service.name} removed. Past bills keep it.`,
    );
  }

  private save(
    service: Service,
    patch: ServiceUpdate,
    revert?: () => void,
    success?: string,
  ): void {
    if (!service.id || !this.isOwner()) {
      return;
    }
    const id = service.id;
    this.busyId.set(id);
    this.rowError.set(null);
    this.api.updateService(id, patch).subscribe({
      next: (updated) => {
        this.services.update((list) => list.map((s) => (s.id === updated.id ? updated : s)));
        this.busyId.set(null);
        if (success) {
          this.toast(success);
        }
      },
      error: (error: unknown) => {
        revert?.();
        this.busyId.set(null);
        this.rowError.set(this.errorText(error, 'We could not save that change. Try again.'));
      },
    });
  }

  private errorText(error: unknown, fallback: string): string {
    return error instanceof ApiError && error.status === 409
      ? DUPLICATE_SERVICE
      : problemMessage(error, fallback);
  }

  private toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }
}
