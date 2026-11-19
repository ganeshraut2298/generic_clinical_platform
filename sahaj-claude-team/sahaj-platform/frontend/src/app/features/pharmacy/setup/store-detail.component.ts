import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { dayMonth, yesNo } from '../pharmacy-display.util';
import { STORE_TYPE_LABELS, StaffMapping, Store } from '../pharmacy.model';
import { StaffTableComponent } from './staff-table.component';

/**
 * Detail card of the selected store (mockup 16): the name as heading, the fields in the order Store
 * type, Issues from, Approver (HOD), Delegate approver, Sale to patients, Consumption entry, then
 * "Staff mapped to this store" (story 78-4). The owner changes the fields in the store sheet
 * (Edit store); a person with view access sees the same card without buttons.
 */
@Component({
  selector: 'app-store-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, EmptyStateComponent, StaffTableComponent],
  styleUrl: './store-detail.component.scss',
  template: `
    <section class="panel surf" aria-labelledby="sd-h" data-testid="store-detail">
      <div class="panel-h">
        <div class="titleline">
          <h2 id="sd-h">{{ store().name }}</h2>
          <span class="code">{{ store().code }}</span>
          @if (store().approverState === 'NONE') {
            <span class="pill p-warn" data-testid="no-approver">No approver set</span>
          }
        </div>
        @if (canEdit()) {
          <div class="actions">
            <button mat-stroked-button type="button" data-testid="edit-store" (click)="editStore.emit()">
              Edit store
            </button>
            <button mat-stroked-button type="button" data-testid="switch-off" (click)="switchOff.emit()">
              Switch off
            </button>
          </div>
        }
      </div>

      <dl class="fields">
        <div>
          <dt>Store type</dt>
          <dd data-testid="f-type">{{ typeLabels[store().type] }}</dd>
        </div>
        <div>
          <dt>Issues from</dt>
          <dd data-testid="f-issues">{{ issuesFrom() }}</dd>
        </div>
        <div>
          <dt>Approver (HOD)</dt>
          <dd data-testid="f-hod">{{ store().hod?.name ?? 'Not set' }}</dd>
        </div>
        <div>
          <dt>Delegate approver</dt>
          <dd data-testid="f-delegate">{{ delegateText() }}</dd>
        </div>
        <div>
          <dt>Sale to patients</dt>
          <dd data-testid="f-sale">{{ yn(store().saleAllowed) }}</dd>
        </div>
        <div>
          <dt>Consumption entry</dt>
          <dd data-testid="f-consumption">{{ yn(store().consumptionAllowed) }}</dd>
        </div>
      </dl>

      @if (store().lockedFields.length > 0) {
        <div class="notice teal" data-testid="locked-note">
          <app-icon name="lock" [size]="16" />
          <span>Type, Issues from and code are locked because this store has stock.</span>
        </div>
      }
      @if (store().approverState === 'NONE') {
        <p class="note" data-testid="no-approver-note">
          Approval rules that use an approver need one. Choose an approver in Edit store or map one below.
        </p>
      }

      <div class="panel-h stafftitle">
        <h3>Staff mapped to this store</h3>
        @if (canEdit()) {
          <button
            mat-stroked-button
            type="button"
            data-testid="map-staff"
            aria-label="Map staff to this store"
            (click)="mapStaff.emit()"
          >
            <app-icon name="plus" [size]="14" /> Map staff
          </button>
        }
      </div>

      @if (staffLoading()) {
        <p class="loading" role="status">Loading staff</p>
      } @else if (staffError(); as message) {
        <div class="notice err" role="alert" data-testid="staff-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ message }}</span>
        </div>
        <div class="actions">
          <button mat-stroked-button type="button" (click)="retryStaff.emit()">Try again</button>
        </div>
      } @else if (staff().length === 0) {
        <app-empty>
          @if (canEdit()) {
            No staff mapped to this store yet. Tap Map staff to add the first person.
          } @else {
            No staff mapped to this store yet.
          }
        </app-empty>
      } @else {
        <app-staff-table
          [staff]="staff()"
          [storeName]="store().name"
          [canEdit]="canEdit()"
          (changeRole)="changeStaff.emit($event)"
          (remove)="removeStaff.emit($event)"
        />
      }
      <p class="note">
        Every store user can receive, not only the HOD. A user mapped to several stores picks the working
        store after login.
      </p>
    </section>
  `,
})
export class StoreDetailComponent {
  readonly store = input.required<Store>();
  readonly staff = input.required<readonly StaffMapping[]>();
  readonly staffLoading = input(false);
  readonly staffError = input<string | null>(null);
  readonly canEdit = input(false);

  readonly editStore = output<void>();
  readonly switchOff = output<void>();
  readonly mapStaff = output<void>();
  readonly changeStaff = output<StaffMapping>();
  readonly removeStaff = output<StaffMapping>();
  readonly retryStaff = output<void>();

  protected readonly typeLabels = STORE_TYPE_LABELS;
  protected readonly yn = yesNo;

  /** The Central Store buys from vendors; every other store issues from the Central Store (mockup 16). */
  protected readonly issuesFrom = computed(() =>
    this.store().type === 'CENTRAL' ? 'Buys from vendors' : (this.store().issuesFromName ?? '—'),
  );

  /** "Dr. R. Desai · till 30 Nov", "... · ended 30 Nov", or "Not set" (the same word as an empty HOD). */
  protected readonly delegateText = computed(() => {
    const delegate = this.store().delegate;
    if (!delegate) {
      return 'Not set';
    }
    const when = delegate.state === 'ENDED' ? 'ended' : 'till';
    return `${delegate.name} · ${when} ${dayMonth(delegate.to)}`;
  });
}
