import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { newClientKey } from '../../../shared/client-key';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { EmrApi } from './emr.api';
import { MedicineOrderRow, OrderActionResult, repliedToast } from './emr.model';
import { classifyFailure } from './order-action.logic';

export const REPLY_MISSING = 'Write your reply.';
export const REPLY_MAX = 300;
const REPLY_ERROR = 'We could not send that reply. Try again.';

/**
 * The Reply box under "Pharmacist query: <question>" on a Queried order row (SAH-76-2, OQ-76-11): required, up
 * to 300 characters. Nothing is sent to anyone; the order goes back to Pharmacist check and the pharmacist
 * reads the reply on the queue. The question and the reply are clinical text: on screen only.
 */
@Component({
  selector: 'app-query-reply',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule],
  styles: `
    :host {
      display: block;
      margin-top: 6px;
    }
    form {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    button {
      min-height: 44px;
      align-self: flex-start;
    }
  `,
  template: `
    <form (submit)="$event.preventDefault(); submit()" novalidate data-testid="query-reply">
      <label [for]="'reply-' + order().id">Reply</label>
      <textarea
        [id]="'reply-' + order().id"
        class="input"
        rows="2"
        [maxlength]="max"
        [formControl]="reply"
        (input)="problem.set(null)"
        [attr.aria-invalid]="problem() ? 'true' : null"
        [attr.aria-describedby]="problem() ? 'reply-err-' + order().id : null"
      ></textarea>
      @if (problem(); as message) {
        <span class="err-text" role="alert" [id]="'reply-err-' + order().id">{{ message }}</span>
      }
      <button
        mat-flat-button
        type="submit"
        data-act="query-reply"
        [disabled]="running()"
        [attr.aria-busy]="running()"
        [attr.aria-label]="'Send reply for ' + order().drugName"
      >
        Reply
      </button>
    </form>
  `,
})
export class QueryReplyComponent {
  private readonly api = inject(EmrApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly scopeRefusals = inject(ScopeRefusals);

  readonly order = input.required<MedicineOrderRow>();
  readonly admissionId = input('');
  /** The sheet now, or a refusal that needs the sheet read again. */
  readonly answered = output<OrderActionResult>();

  protected readonly max = REPLY_MAX;
  protected readonly reply = new FormControl('', { nonNullable: true });
  protected readonly running = signal(false);
  protected readonly problem = signal<string | null>(null);
  private key = newClientKey();

  protected submit(): void {
    if (this.running()) {
      return;
    }
    const text = this.reply.value.trim();
    if (text === '' || text.length > REPLY_MAX) {
      this.problem.set(REPLY_MISSING);
      return;
    }
    const order = this.order();
    this.running.set(true);
    this.api
      .queryReply(order.id, { clientKey: this.key, orderVersion: order.orderVersion, reply: text })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sheet) => {
          this.running.set(false);
          this.answered.emit({ kind: 'done', sheet, toast: repliedToast(order.drugName) });
        },
        error: (error: unknown) => {
          this.running.set(false);
          const f = classifyFailure(error, REPLY_ERROR);
          switch (f.kind) {
            case 'scope':
              this.scopeRefusals.report(this.admissionId(), true);
              return;
            case 'conflict':
              this.answered.emit(f.result);
              return;
            case 'fields':
              this.problem.set(f.errors.reason ?? f.other ?? REPLY_ERROR);
              return;
            case 'stay':
              this.problem.set(f.message);
              if (f.newKey) {
                this.key = newClientKey();
              }
          }
        },
      });
  }
}
