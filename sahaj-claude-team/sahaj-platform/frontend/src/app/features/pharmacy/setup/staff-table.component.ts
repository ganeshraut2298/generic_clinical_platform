import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import {
  otherStoresText,
  receiveText,
  roleChip,
  staffSubline,
  yesNo,
} from '../pharmacy-display.util';
import { StaffMapping } from '../pharmacy.model';

/**
 * Table "Staff mapped to this store" (mockup 16, story 78-4). The four access columns are worked out
 * by the server and only shown here: the owner cannot tick them. Change and Remove show for the owner
 * only; a person with view access sees the table without buttons. The table scrolls sideways on a
 * narrow screen.
 */
@Component({
  selector: 'app-staff-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule],
  styleUrl: './staff-table.component.scss',
  template: `
    <div class="tbl-wrap" data-testid="staff-table">
      <table>
        <thead>
          <tr>
            <th scope="col">Staff</th>
            <th scope="col">Store role</th>
            <th scope="col">Raise indent</th>
            <th scope="col">Approve</th>
            <th scope="col">Receive</th>
            <th scope="col">Consume / return</th>
            <th scope="col">Other stores</th>
            @if (canEdit()) {
              <th scope="col"><span class="sr-only">Actions</span></th>
            }
          </tr>
        </thead>
        <tbody>
          @for (m of staff(); track m.id) {
            <tr [attr.data-testid]="'staff-' + m.id">
              <td>
                <strong>{{ m.name }}</strong>
                <div class="sub">{{ subline(m) }}</div>
                @if (m.memberState === 'CANNOT_SIGN_IN') {
                  <div class="sub warn" data-testid="cannot-sign-in">Cannot sign in</div>
                }
                @if (m.needsReview) {
                  <div class="sub warn" data-testid="needs-review">
                    Needs review: counts as Store user
                  </div>
                }
              </td>
              <td>
                <span class="pill" [class]="chip(m).css">{{ chip(m).text }}</span>
              </td>
              <td [class]="m.access.raiseIndent ? 'yes' : 'no'">{{ yn(m.access.raiseIndent) }}</td>
              <td [class]="m.access.approve ? 'yes' : 'no'">{{ yn(m.access.approve) }}</td>
              <td [class]="m.access.receive ? 'yes' : 'no'">{{ receive(m) }}</td>
              <td [class]="m.access.consumeReturn ? 'yes' : 'no'">{{ yn(m.access.consumeReturn) }}</td>
              <td>{{ others(m) }}</td>
              @if (canEdit()) {
                <td class="acts">
                  <button
                    mat-stroked-button
                    type="button"
                    class="btn-sm"
                    [attr.aria-label]="'Change role of ' + m.name"
                    (click)="changeRole.emit(m)"
                  >
                    Change
                  </button>
                  <button
                    mat-stroked-button
                    type="button"
                    class="btn-sm"
                    [attr.aria-label]="'Remove ' + m.name + ' from store'"
                    (click)="remove.emit(m)"
                  >
                    Remove from store
                  </button>
                </td>
              }
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class StaffTableComponent {
  readonly staff = input.required<readonly StaffMapping[]>();
  readonly storeName = input.required<string>();
  readonly canEdit = input(false);
  readonly changeRole = output<StaffMapping>();
  readonly remove = output<StaffMapping>();

  protected readonly chip = roleChip;
  protected readonly yn = yesNo;
  protected readonly receive = receiveText;
  protected readonly others = otherStoresText;
  protected subline(mapping: StaffMapping): string {
    return staffSubline(mapping, this.storeName());
  }
}
