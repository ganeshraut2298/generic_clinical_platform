import { ChangeDetectionStrategy, Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { sheetToast } from '../../../shared/ui/sheet-toast';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { StaffMapping, Store } from '../pharmacy.model';
import { CONFIRM_SHEET_CONFIG, ConfirmSheetComponent } from '../shared/confirm-sheet.component';
import { MAP_STAFF_CONFIG, MapStaffData, MapStaffDialog } from './map-staff.dialog';
import { StoreDetailComponent } from './store-detail.component';
import { STORE_FORM_CONFIG, StoreFormData, StoreFormDialog } from './store-form.dialog';
import { StoreTreeComponent } from './store-tree.component';
import { UnitSettingsCard } from './unit-settings.card';

/**
 * "Stores & staff" section of Inventory setup (mockup 16; stories 78-2, 78-3, 78-4): the unit
 * settings card on top, then the tree "Stores in this unit" on the left and the detail card of the
 * selected store, with its staff table, on the right (stacked on a tablet or phone). One primary
 * button: "+ Store". Everything the signed-in person cannot change is not drawn (view-only).
 */
@Component({
  selector: 'app-stores-staff',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    IconComponent,
    EmptyStateComponent,
    StoreTreeComponent,
    StoreDetailComponent,
    UnitSettingsCard,
  ],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .split {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: flex-start;
    }
    .left {
      flex: 1 1 300px;
      min-width: 0;
    }
    .right {
      flex: 999 1 560px;
      min-width: 0;
    }
  `,
  template: `
    <app-unit-settings (saved)="changed.emit()" />

    @if (loading()) {
      <section class="panel surf" aria-busy="true" data-testid="stores-loading">
        <p class="loading" role="status">Loading stores</p>
      </section>
    } @else if (loadError(); as text) {
      <section class="panel surf" data-testid="stores-error">
        <div class="notice err" role="alert">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
        <div class="actions">
          <button mat-stroked-button type="button" (click)="reload()">Try again</button>
        </div>
      </section>
    } @else {
      @if (actionError(); as text) {
        <div class="notice err" role="alert" data-testid="action-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      @if (stores().length === 0) {
        <section class="panel surf" data-testid="stores-empty">
          <div class="panel-h">
            <h2>Stores in this unit</h2>
            @if (canEdit()) {
              <button
                mat-flat-button
                type="button"
                data-testid="add-store"
                aria-label="Add store"
                (click)="openCreate()"
              >
                <app-icon name="plus" [size]="14" /> Store
              </button>
            }
          </div>
          <app-empty>
            @if (canEdit()) {
              No stores yet. Create your Central Store to start.
            } @else {
              No stores yet.
            }
          </app-empty>
        </section>
      } @else {
        <div class="split">
          <div class="left">
            <app-store-tree
              [stores]="stores()"
              [selectedId]="selectedId()"
              [canAdd]="canEdit()"
              (picked)="select($event)"
              (add)="openCreate()"
            />
          </div>
          <div class="right">
            @if (selected(); as store) {
              <app-store-detail
                [store]="store"
                [staff]="staff()"
                [staffLoading]="staffLoading()"
                [staffError]="staffError()"
                [canEdit]="canEdit()"
                (editStore)="openEdit(store)"
                (switchOff)="confirmSwitchOff(store)"
                (mapStaff)="openMap(store)"
                (changeStaff)="openChange(store, $event)"
                (removeStaff)="confirmRemove(store, $event)"
                (retryStaff)="loadStaff(store.id)"
              />
            }
          </div>
        </div>
      }
    }
  `,
})
export class StoresStaffSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly context = inject(PharmacyContextStore);

  /** Stores or mappings changed, so the live counts above need reading again. */
  readonly changed = output<void>();

  protected readonly canEdit = this.context.canEditSetup;

  protected readonly stores = signal<Store[]>([]);
  private readonly centralStoreId = signal<string | null>(null);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);

  protected readonly staff = signal<StaffMapping[]>([]);
  protected readonly staffLoading = signal(false);
  protected readonly staffError = signal<string | null>(null);

  protected readonly selected = computed(
    () => this.stores().find((s) => s.id === this.selectedId()) ?? null,
  );

  ngOnInit(): void {
    this.reload();
  }

  protected reload(selectId?: string): void {
    this.loadStores(true, selectId);
  }

  private loadStores(showLoading: boolean, selectId?: string): void {
    if (showLoading) {
      this.loading.set(true);
    }
    this.loadError.set(null);
    this.api.stores().subscribe({
      next: (list) => {
        this.stores.set(list.items);
        this.centralStoreId.set(list.centralStoreId);
        const keep = selectId ?? this.selectedId();
        const next = list.items.find((s) => s.id === keep) ?? list.items[0] ?? null;
        this.selectedId.set(next?.id ?? null);
        this.loading.set(false);
        if (next) {
          this.loadStaff(next.id);
        } else {
          this.staff.set([]);
        }
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  protected select(storeId: string): void {
    if (storeId === this.selectedId()) {
      return;
    }
    this.selectedId.set(storeId);
    this.actionError.set(null);
    this.loadStaff(storeId);
  }

  protected loadStaff(storeId: string): void {
    this.staffLoading.set(true);
    this.staffError.set(null);
    this.staff.set([]);
    this.api.storeStaff(storeId).subscribe({
      next: (items) => {
        if (this.selectedId() === storeId) {
          this.staff.set(items);
          this.staffLoading.set(false);
        }
      },
      error: (error: unknown) => {
        if (this.selectedId() === storeId) {
          this.staffLoading.set(false);
          this.staffError.set(loadErrorMessage(error));
        }
      },
    });
  }

  // ---- stores

  protected openCreate(): void {
    const central = this.stores().find((s) => s.type === 'CENTRAL') ?? null;
    this.openStoreForm({
      mode: 'create',
      hasCentral: this.centralStoreId() !== null,
      centralStoreName: central?.name ?? null,
    });
  }

  protected openEdit(store: Store): void {
    const central = this.stores().find((s) => s.type === 'CENTRAL') ?? null;
    this.openStoreForm({
      mode: 'edit',
      store,
      hasCentral: this.centralStoreId() !== null,
      centralStoreName: central?.name ?? null,
    });
  }

  private openStoreForm(data: StoreFormData): void {
    this.actionError.set(null);
    this.dialog
      .open(StoreFormDialog, { ...STORE_FORM_CONFIG, data })
      .afterClosed()
      .subscribe((saved: Store | null | undefined) => {
        // The list is read again after every close: a conflict may have changed what is current.
        this.loadStores(false, saved?.id);
        if (saved) {
          this.afterChange();
          this.toast(data.mode === 'create' ? 'Store created' : 'Store saved');
        }
      });
  }

  protected confirmSwitchOff(store: Store): void {
    this.actionError.set(null);
    this.dialog
      .open(ConfirmSheetComponent, {
        ...CONFIRM_SHEET_CONFIG,
        data: {
          title: `Switch off ${store.name}?`,
          body: 'It will not be offered for new work. Its history is kept and its staff mappings stop counting.',
          confirmLabel: 'Switch off',
        },
      })
      .afterClosed()
      .subscribe((yes) => {
        if (yes !== true) {
          return;
        }
        this.api.switchOffStore(store.id).subscribe({
          next: () => {
            this.loadStores(false);
            this.afterChange();
            this.toast('Store switched off');
          },
          error: (error: unknown) => this.actionError.set(saveErrorMessage(error)),
        });
      });
  }

  // ---- staff

  protected openMap(store: Store): void {
    this.openMapDialog({ storeId: store.id, storeName: store.name });
  }

  protected openChange(store: Store, mapping: StaffMapping): void {
    this.openMapDialog({ storeId: store.id, storeName: store.name, mapping });
  }

  private openMapDialog(data: MapStaffData): void {
    this.actionError.set(null);
    this.dialog
      .open(MapStaffDialog, { ...MAP_STAFF_CONFIG, data })
      .afterClosed()
      .subscribe((saved: StaffMapping | null | undefined) => {
        if (saved) {
          this.refreshAfterMapping(data.storeId);
          this.toast(data.mapping ? 'Role saved' : 'Staff mapped');
        }
      });
  }

  protected confirmRemove(store: Store, mapping: StaffMapping): void {
    this.actionError.set(null);
    this.dialog
      .open(ConfirmSheetComponent, {
        ...CONFIRM_SHEET_CONFIG,
        data: {
          title: `Remove ${mapping.name} from ${store.name}?`,
          body: 'They lose this store at once. The record stays in the history.',
          confirmLabel: 'Remove from store',
        },
      })
      .afterClosed()
      .subscribe((yes) => {
        if (yes !== true) {
          return;
        }
        this.api.removeStaffMapping(store.id, mapping.id).subscribe({
          next: () => {
            this.refreshAfterMapping(store.id);
            this.toast('Removed from store');
          },
          error: (error: unknown) => this.actionError.set(saveErrorMessage(error)),
        });
      });
  }

  /** A mapping changed the staff count and may change the approver of the store. */
  private refreshAfterMapping(storeId: string): void {
    this.loadStores(false, storeId);
    this.afterChange();
  }

  private afterChange(): void {
    this.context.refresh();
    this.changed.emit();
  }

  private toast(text: string): void {
    this.snackBar.open(text, undefined, sheetToast(3000));
  }
}
