import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { map, startWith } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { istToday } from '../../../shared/format/ist-date';
import { provideIsoDateAdapter } from '../../../shared/format/iso-date-adapter';
import { IconComponent } from '../../../shared/icon/icon.component';
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
  StaffMapping,
  StoreRole,
  STORE_ROLE_LABELS,
} from '../pharmacy.model';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';

export interface MapStaffData {
  storeId: string;
  storeName: string;
  /** Set to change an existing mapping (store role and delegate dates); unset to map a new person. */
  mapping?: StaffMapping;
}

export const MAP_STAFF_CONFIG = {
  panelClass: 'sheet-panel',
  width: '560px',
  maxWidth: '100vw',
  ariaLabelledBy: 'map-title',
} as const;

/**
 * "+ Map staff" and "Change" sheet (story 78-4): Person (active team members of this unit), Store
 * role (only the roles the server allows for that person's team role: a Store staff member can only
 * be Store user or Central Store user, a Pharmacist store role needs no extra step) and, for a
 * Delegate approver, the start and end dates. The four access columns are not asked: the server
 * works them out.
 */
@Component({
  selector: 'app-map-staff',
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
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="map-title">{{ editing ? 'Change role' : 'Map staff' }}</h2>
        <p>{{ data.storeName }}</p>
      </div>
      <button mat-icon-button type="button" (click)="close()" aria-label="Close">
        <app-icon name="x" />
      </button>
    </div>

    <form class="sheet-b" [formGroup]="form" (ngSubmit)="save()" novalidate aria-labelledby="map-title">
      @if (loading()) {
        <p class="loading" role="status">Loading team members</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else {
        @if (editing) {
          <div class="fixed">
            <span class="field-label">Person</span>
            <span class="fixedv" data-testid="f-person-fixed">{{ data.mapping?.name }}</span>
          </div>
        } @else {
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Person</mat-label>
            <mat-select placeholder="Choose an option" formControlName="membershipId" data-testid="f-person">
              @for (c of people(); track c.membershipId) {
                <mat-option [value]="c.membershipId">{{ c.name }} · {{ c.roleLabel }}</mat-option>
              }
            </mat-select>
            @if (message(form.controls.membershipId, personMessages); as text) {
              <mat-error>{{ text }}</mat-error>
            }
          </mat-form-field>
          @if (people().length === 0) {
            <p class="note" data-testid="no-people">
              Everyone in your team is already mapped to this store.
            </p>
          }
        }

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Store role</mat-label>
          <mat-select placeholder="Choose an option" formControlName="storeRole" data-testid="f-role">
            @for (role of roles(); track role) {
              <mat-option [value]="role">{{ roleLabels[role] }}</mat-option>
            }
          </mat-select>
          @if (message(form.controls.storeRole, roleMessages); as text) {
            <mat-error>{{ text }}</mat-error>
          }
        </mat-form-field>

        @if (isDelegate()) {
          <div class="dates">
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Delegate from</mat-label>
              <input
                matInput
                [matDatepicker]="fromPicker"
                formControlName="delegateFrom"
                placeholder="24 Oct 2026"
                autocomplete="off"
                data-testid="f-from"
              />
              <mat-datepicker-toggle matIconSuffix [for]="fromPicker" aria-label="Choose the start date" />
              <mat-datepicker #fromPicker />
              @if (message(form.controls.delegateFrom, roleMessages); as text) {
                <mat-error>{{ text }}</mat-error>
              }
            </mat-form-field>
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Delegate until</mat-label>
              <input
                matInput
                [matDatepicker]="toPicker"
                formControlName="delegateTo"
                [min]="today"
                placeholder="24 Oct 2026"
                autocomplete="off"
                data-testid="f-to"
              />
              <mat-datepicker-toggle matIconSuffix [for]="toPicker" aria-label="Choose the end date" />
              <mat-datepicker #toPicker />
              @if (message(form.controls.delegateTo, roleMessages); as text) {
                <mat-error>{{ text }}</mat-error>
              }
            </mat-form-field>
          </div>
        }
      }

      @for (text of extraErrors(); track text) {
        <div class="notice err" role="alert"><app-icon name="alert" [size]="16" /><span>{{ text }}</span></div>
      }
      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="map-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </form>

    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close()">Cancel</button>
      <button
        mat-flat-button
        type="button"
        data-testid="save-mapping"
        [disabled]="saving() || loading()"
        (click)="save()"
      >
        {{ editing ? 'Save changes' : 'Map to store' }}
      </button>
    </div>
  `,
  styles: [
    SHEET_COLUMN_STYLES,
    `
    .fixed {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .fixedv {
      font-weight: 600;
    }
    .dates {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 12px;
    }
  `,
  ],
})
export class MapStaffDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<MapStaffDialog, StaffMapping | null>>(MatDialogRef);
  protected readonly data = inject<MapStaffData>(MAT_DIALOG_DATA);

  protected readonly editing = this.data.mapping !== undefined;
  protected readonly roleLabels = STORE_ROLE_LABELS;
  protected readonly today = istToday();
  protected readonly message = controlMessage;
  protected readonly personMessages = { required: 'Choose a team member' };
  protected readonly roleMessages = { required: 'Choose a store role' };

  private readonly candidates = signal<StaffCandidate[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly extraErrors = signal<string[]>([]);

  protected readonly form = new FormGroup({
    membershipId: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    storeRole: new FormControl<StoreRole | ''>('', { nonNullable: true, validators: [Validators.required] }),
    // ISO `YYYY-MM-DD` (the API format); the datepicker shows it as "24 Oct 2026" and writes null when cleared.
    delegateFrom: new FormControl<string | null>(''),
    delegateTo: new FormControl<string | null>(''),
  });

  private readonly personId = toSignal(
    this.form.controls.membershipId.valueChanges.pipe(startWith('')),
    { initialValue: '' },
  );
  protected readonly isDelegate = toSignal(
    this.form.controls.storeRole.valueChanges.pipe(map((role) => role === 'DELEGATE')),
    { initialValue: false },
  );

  /** People who can still be mapped here (the ones already in this store are left out). */
  protected readonly people = computed(() => this.candidates().filter((c) => !c.alreadyMappedHere));

  /** The store roles the server allows for the chosen person. */
  protected readonly roles = computed<StoreRole[]>(() => {
    const id = this.editing ? this.data.mapping?.membershipId : this.personId();
    const candidate = this.candidates().find((c) => c.membershipId === id);
    if (candidate) {
      return candidate.allowedStoreRoles;
    }
    return this.data.mapping ? [this.data.mapping.storeRole] : [];
  });

  ngOnInit(): void {
    const mapping = this.data.mapping;
    if (mapping) {
      this.form.patchValue({
        membershipId: mapping.membershipId,
        storeRole: mapping.storeRole,
        delegateFrom: mapping.delegateFrom ?? this.today,
        delegateTo: mapping.delegateTo ?? '',
      });
    } else {
      this.form.patchValue({ delegateFrom: this.today });
      // A new person starts as Store user, or as the first role the server allows.
      this.form.controls.membershipId.valueChanges.subscribe(() => {
        const allowed = this.roles();
        const current = this.form.controls.storeRole.value;
        if (allowed.length > 0 && (current === '' || !allowed.includes(current))) {
          this.form.controls.storeRole.setValue(allowed.includes('STORE_USER') ? 'STORE_USER' : allowed[0]);
        }
      });
    }
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.staffCandidates(this.data.storeId).subscribe({
      next: (list) => {
        this.candidates.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(LOAD_FAILED);
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
    if (raw.storeRole === '') {
      return;
    }
    const role: StoreRole = raw.storeRole;
    const dateErrors =
      role === 'DELEGATE'
        ? delegateErrors(
            {
              hodId: '',
              delegateId: raw.membershipId,
              from: raw.delegateFrom ?? '',
              to: raw.delegateTo ?? '',
            },
            this.today,
          )
        : {};
    for (const [name, text] of Object.entries(dateErrors)) {
      this.form.get(name)?.setErrors({ server: text });
    }
    if (this.form.invalid) {
      return;
    }

    const dates =
      role === 'DELEGATE'
        ? { delegateFrom: raw.delegateFrom ?? '', delegateTo: raw.delegateTo ?? '' }
        : { delegateFrom: null, delegateTo: null };
    const mapping = this.data.mapping;
    this.saving.set(true);
    const request = mapping
      ? this.api.changeStaffMapping(this.data.storeId, mapping.id, {
          version: mapping.version,
          storeRole: role,
          ...dates,
        })
      : this.api.mapStaff(this.data.storeId, {
          membershipId: raw.membershipId,
          storeRole: role,
          ...dates,
        });
    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.ref.close(saved);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        if (error instanceof ApiError && error.status === 400 && error.fieldErrors.length > 0) {
          this.extraErrors.set(
            applyFieldErrors(
              {
                membershipId: this.form.controls.membershipId,
                storeRole: this.form.controls.storeRole,
                delegateFrom: this.form.controls.delegateFrom,
                delegateTo: this.form.controls.delegateTo,
              },
              error.fieldErrors,
            ),
          );
          return;
        }
        this.formError.set(saveErrorMessage(error));
      },
    });
  }
}
