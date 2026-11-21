import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ActiveReason } from '../pharmacy-inventory.model';
import { ReasonInput } from '../pharmacy-sale.model';

const NOTE_MIN = 4;
const NOTE_MAX = 300;

/**
 * A reason chip row with a note for "Other" (mockup 02 "Pharmacist override needs a reason and is logged"). Used for the
 * allergy override and the Schedule H1 override. The reasons come from the server's lists; a reason that needs a note
 * asks for 4 to 300 characters. Nothing is saved here: the chosen reason goes on the cart line.
 */
@Component({
  selector: 'app-reason-pick',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .chips button {
      min-height: 44px;
      padding: 0 14px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
      cursor: pointer;
    }
    .chips button[aria-pressed='true'] {
      border: 2px solid var(--pri);
      background: var(--pri-soft);
      color: var(--pri-text);
      font-weight: 700;
    }
    textarea {
      min-height: 64px;
      padding: 6px 10px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
    }
    .err {
      color: var(--crit);
      font-size: 0.8rem;
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
  `,
  template: `
    <b>{{ heading() }}</b>
    <div class="chips" role="group" [attr.aria-label]="heading()">
      @for (r of reasons(); track r.reasonKey) {
        <button type="button" [attr.aria-pressed]="key() === r.reasonKey" [attr.data-testid]="'reason-' + r.reasonKey" (click)="pick(r)">
          {{ r.label }}
        </button>
      }
    </div>
    @if (needsNote()) {
      <textarea
        aria-label="Note"
        maxlength="300"
        data-testid="reason-note"
        [value]="note()"
        (input)="note.set($any($event.target).value)"
      ></textarea>
    }
    @if (error(); as text) {
      <p class="err" role="alert" data-testid="reason-error">{{ text }}</p>
    }
    <div class="acts">
      <button mat-flat-button type="button" data-testid="reason-confirm" (click)="confirm()">{{ confirmLabel() }}</button>
      <button mat-stroked-button type="button" data-testid="reason-dismiss" (click)="dismissed.emit()">Cancel</button>
    </div>
  `,
})
export class ReasonPickComponent {
  readonly reasons = input.required<ActiveReason[]>();
  readonly heading = input.required<string>();
  readonly confirmLabel = input('Confirm');
  readonly chosen = output<ReasonInput>();
  readonly dismissed = output<void>();

  protected readonly key = signal('');
  protected readonly note = signal('');
  protected readonly error = signal<string | null>(null);

  protected needsNote(): boolean {
    return this.reasons().find((r) => r.reasonKey === this.key())?.needsNote === true;
  }

  protected pick(reason: ActiveReason): void {
    this.key.set(reason.reasonKey);
    this.error.set(null);
  }

  protected confirm(): void {
    if (this.key() === '') {
      this.error.set('Choose a reason.');
      return;
    }
    if (this.needsNote()) {
      const length = this.note().trim().length;
      if (length < NOTE_MIN || length > NOTE_MAX) {
        this.error.set(`Write a note of ${NOTE_MIN} to ${NOTE_MAX} characters.`);
        return;
      }
      this.chosen.emit({ reasonKey: this.key(), note: this.note().trim() });
      return;
    }
    this.chosen.emit({ reasonKey: this.key(), note: null });
  }
}
