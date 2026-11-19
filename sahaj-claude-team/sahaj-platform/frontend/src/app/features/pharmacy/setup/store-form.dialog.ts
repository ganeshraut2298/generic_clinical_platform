import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { forkJoin, map, startWith } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { istToday } from '../../../shared/format/ist-date';
import { provideIsoDateAdapter } from '../../../shared/format/iso-date-adapter';
import {
  applyFieldErrors,
  controlMessage,
  delegateErrors,
  saveErrorMessage,
} from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import {
  LOAD_FAILED,
  StaffCandidate,
  Store,
  StoreCreate,
  StoreType,
  StoreUpdate,
  STORE_TYPE_LABELS,
} from '../pharmacy.model';

export interface StoreFormData {
  mode: 'create' | 'edit';
  /** The store being changed (edit only). */
  store?: Store;
  /** The unit already has a Central Store: the type list then leaves Central Store out. */
  hasCentral: boolean;
  centralStoreName: string | null;
}

export const STORE_FORM_CONFIG = {
  panelClass: 'sheet-panel',
  width: '640px',
  maxWidth: '100vw',
  ariaLabelledBy: 'store-form-title',
} as const;

const CODE_PATTERN = /^[A-Za-z0-9-]{1,10}$/;

const MESSAGES: Record<string, string> = {
  required: 'Enter a value',
  maxlength: 'Too long',
  pattern: 'Not valid',
};

/**
 * "+ Store" and "Edit store" sheet (story 78-3). Name and Code first (NEW), then Store type, Issues
 * from (set by the server: the Central Store), Approver (HOD), Delegate approver with its dates, Sale to
 * patients and Consumption entry. Limits match the contract: name up to 60, code up to 10 letters,
 * digits or hyphen. Nothing is shown as saved until the server has answered; a failed save keeps what
 * was typed.
 */
@Component({
  selector: 'app-store-form',
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
  styleUrl: './store-form.dialog.scss',
  templateUrl: './store-form.dialog.html',
})
export class StoreFormDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<StoreFormDialog, Store | null>>(MatDialogRef);
  protected readonly data = inject<StoreFormData>(MAT_DIALOG_DATA);

  protected readonly typeLabels = STORE_TYPE_LABELS;
  protected readonly today = istToday();
  protected readonly editing = this.data.mode === 'edit';

  protected readonly hodOptions = signal<StaffCandidate[]>([]);
  protected readonly delegateOptions = signal<StaffCandidate[]>([]);
  protected readonly candidatesLoading = signal(false);
  protected readonly candidatesError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly extraErrors = signal<string[]>([]);

  protected readonly typeOptions: readonly StoreType[] = this.buildTypeOptions();

  protected readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [(c) => (String(c.value ?? '').trim() ? null : { required: true }), Validators.maxLength(60)],
    }),
    code: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(CODE_PATTERN)],
    }),
    type: new FormControl<StoreType>('PHARMACY', { nonNullable: true }),
    hodMembershipId: new FormControl('', { nonNullable: true }),
    delegateMembershipId: new FormControl('', { nonNullable: true }),
    // ISO `YYYY-MM-DD` (the API format); the datepicker shows it as "24 Oct 2026" and writes null when cleared.
    delegateFrom: new FormControl<string | null>(''),
    delegateTo: new FormControl<string | null>(''),
    saleAllowed: new FormControl(true, { nonNullable: true }),
    consumptionAllowed: new FormControl(false, { nonNullable: true }),
  });

  protected readonly nameMessages = {
    required: 'Enter the store name',
    maxlength: 'Use up to 60 characters',
  };
  protected readonly codeMessages = {
    required: 'Enter the store code',
    pattern: 'Use up to 10 letters, digits or hyphens',
  };
  protected readonly messages = MESSAGES;
  protected readonly message = controlMessage;

  ngOnInit(): void {
    const store = this.data.store;
    if (store) {
      this.form.patchValue({
        name: store.name,
        code: store.code,
        type: store.type,
        hodMembershipId: store.hod?.membershipId ?? '',
        saleAllowed: store.saleAllowed,
        consumptionAllowed: store.consumptionAllowed,
        ...(store.delegate && store.delegate.state !== 'ENDED'
          ? {
              delegateMembershipId: store.delegate.membershipId,
              delegateFrom: store.delegate.from,
              delegateTo: store.delegate.to,
            }
          : {}),
      });
      if (store.type === 'CENTRAL' || store.lockedFields.includes('TYPE')) {
        this.form.controls.type.disable();
      }
      if (store.lockedFields.includes('CODE')) {
        this.form.controls.code.disable();
      }
    } else {
      const type = (this.data.hasCentral ? 'PHARMACY' : 'CENTRAL') as StoreType;
      this.form.patchValue({
        type,
        saleAllowed: type === 'PHARMACY',
        consumptionAllowed: false,
        delegateFrom: this.today,
      });
      // NEW defaults (story 78-3): until the owner changes them, they follow the type.
      this.form.controls.type.valueChanges.subscribe((next) => {
        if (this.form.controls.saleAllowed.pristine) {
          this.form.controls.saleAllowed.setValue(next === 'PHARMACY', { emitEvent: false });
        }
        if (this.form.controls.consumptionAllowed.pristine) {
          this.form.controls.consumptionAllowed.setValue(next === 'WARD', { emitEvent: false });
        }
      });
    }
    this.loadCandidates();
  }

  private readonly typeSig = toSignal(
    this.form.controls.type.valueChanges.pipe(startWith(this.form.controls.type.value)),
    { initialValue: this.form.controls.type.value },
  );

  protected readonly issuesFrom = computed(() => {
    if (this.typeSig() === 'CENTRAL') {
      return 'Buys from vendors';
    }
    return this.data.store?.issuesFromName ?? this.data.centralStoreName ?? 'Central Store';
  });

  protected readonly delegateChosen = toSignal(
    this.form.controls.delegateMembershipId.valueChanges.pipe(map((id) => id !== '')),
    { initialValue: false },
  );

  /**
   * Approver and Delegate candidates (F-11, pharmacy.yaml 0.1.1). A new store has no id yet, so the list is
   * read without `storeId` and is filtered by team role only; an existing store is read through its own id.
   */
  protected loadCandidates(): void {
    const storeId = this.data.store?.id ?? null;
    this.candidatesLoading.set(true);
    this.candidatesError.set(null);
    forkJoin([
      this.api.staffCandidates(storeId, 'APPROVER'),
      this.api.staffCandidates(storeId, 'DELEGATE'),
    ]).subscribe({
      next: ([approvers, delegates]) => {
        this.hodOptions.set(approvers);
        this.delegateOptions.set(delegates);
        this.candidatesLoading.set(false);
      },
      error: () => {
        this.candidatesLoading.set(false);
        this.candidatesError.set(LOAD_FAILED);
      },
    });
  }

  protected close(): void {
    this.ref.close(null);
  }

  protected save(): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    this.extraErrors.set([]);
    this.form.markAllAsTouched();
    const raw = this.form.getRawValue();

    const draftErrors = delegateErrors(
      {
        hodId: raw.hodMembershipId,
        delegateId: raw.delegateMembershipId,
        from: raw.delegateFrom ?? '',
        to: raw.delegateTo ?? '',
      },
      this.today,
    );
    for (const [name, text] of Object.entries(draftErrors)) {
      this.form.get(name)?.setErrors({ server: text });
    }
    if (this.form.invalid) {
      return;
    }

    const delegate = raw.delegateMembershipId
      ? {
          membershipId: raw.delegateMembershipId,
          from: raw.delegateFrom ?? '',
          to: raw.delegateTo ?? '',
        }
      : null;
    const hod = raw.hodMembershipId || null;
    const common = {
      name: raw.name.trim(),
      code: raw.code.trim(),
      type: raw.type,
      saleAllowed: raw.saleAllowed,
      consumptionAllowed: raw.consumptionAllowed,
    };

    this.saving.set(true);
    const store = this.data.store;
    const request = store
      ? this.api.updateStore(store.id, {
          version: store.version,
          ...common,
          hodMembershipId: hod,
          delegate,
        } satisfies StoreUpdate)
      : this.api.createStore({
          ...common,
          hodMembershipId: hod,
          delegate,
        } satisfies StoreCreate);

    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.ref.close(saved);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        if (error instanceof ApiError && error.status === 400 && error.fieldErrors.length > 0) {
          const rest = applyFieldErrors(
            {
              name: this.form.controls.name,
              code: this.form.controls.code,
              type: this.form.controls.type,
              hodMembershipId: this.form.controls.hodMembershipId,
              delegateMembershipId: this.form.controls.delegateMembershipId,
              delegateFrom: this.form.controls.delegateFrom,
              delegateTo: this.form.controls.delegateTo,
            },
            error.fieldErrors,
            {
              'delegate.membershipId': 'delegateMembershipId',
              'delegate.from': 'delegateFrom',
              'delegate.to': 'delegateTo',
              delegate: 'delegateMembershipId',
            },
          );
          this.extraErrors.set(rest);
          return;
        }
        this.formError.set(saveErrorMessage(error));
      },
    });
  }

  private buildTypeOptions(): StoreType[] {
    if (this.data.mode === 'edit') {
      return this.data.store?.type === 'CENTRAL'
        ? ['CENTRAL']
        : ['PHARMACY', 'SUB_STORE', 'WARD'];
    }
    return this.data.hasCentral ? ['PHARMACY', 'SUB_STORE', 'WARD'] : ['CENTRAL', 'PHARMACY', 'SUB_STORE', 'WARD'];
  }
}
