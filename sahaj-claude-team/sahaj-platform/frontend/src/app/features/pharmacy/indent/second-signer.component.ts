import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { SecondSignerCandidate } from '../pharmacy-inventory.model';

/**
 * The second signature of a Schedule X line (rule 19, BR-32): a colleague from the server's list (never the caller)
 * and their own 4-digit PIN. The PIN field is a password field with autocomplete off; the screen clears it after
 * every send. Nothing is stored or logged.
 */
@Component({
  selector: 'app-second-signer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatFormFieldModule, MatInputModule, MatSelectModule],
  styles: `
    .row-err {
      color: var(--crit);
      font-size: 0.78rem;
    }
    .signer-err {
      flex-basis: 100%;
    }
    .signer {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: flex-start;
    }
  `,
  template: `
    <div class="signer">
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Second signer</mat-label>
        <mat-select data-testid="f-signer" [value]="signerId()" (selectionChange)="signerId.set($event.value)">
          @for (person of signers(); track person.membershipId) {
            <mat-option [value]="person.membershipId">{{ person.name }} · {{ person.roleLabel }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
        @if (signerError(); as text) {
          <span class="row-err signer-err" role="alert">{{ text }}</span>
        }
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Their PIN</mat-label>
        <input
          matInput
          type="password"
          inputmode="numeric"
          autocomplete="off"
          maxlength="4"
          data-testid="f-pin"
          [value]="pin()"
          (input)="pin.set($any($event.target).value)"
        />
      </mat-form-field>
        @if (pinError(); as text) {
          <span class="row-err signer-err" role="alert">{{ text }}</span>
        }
    </div>
    @if (loaded() && signers().length === 0) {
      <p class="notice" role="status" data-testid="no-second-signer">No other pharmacist or owner is available to sign.</p>
    }
  `,
})
export class SecondSignerComponent {
  readonly signers = input.required<SecondSignerCandidate[]>();
  readonly loaded = input(false);
  readonly signerError = input<string | null>(null);
  readonly pinError = input<string | null>(null);
  readonly signerId = model('');
  readonly pin = model('');
}
