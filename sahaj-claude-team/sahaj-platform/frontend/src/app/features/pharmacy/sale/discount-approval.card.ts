import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { IconComponent } from '../../../shared/icon/icon.component';
import { DiscountRequest } from '../pharmacy-sale.model';

/** "Waiting for approval · Owner · 4 min" from the request; the age is worked from the time it was raised. */
export function waitingText(request: DiscountRequest, now: Date = new Date()): string {
  const who = request.waitingFor === 'OWNER' ? 'Owner' : request.waitingFor === 'HOD' ? 'HOD' : null;
  const parts = ['Waiting for approval'];
  if (who) {
    parts.push(who);
  }
  if (request.raisedAt) {
    const minutes = Math.max(0, Math.floor((now.getTime() - new Date(request.raisedAt).getTime()) / 60000));
    parts.push(`${minutes} min`);
  }
  return parts.join(' · ');
}

/**
 * The discount approval card (SAH-80-6; mockup 05 "requested 15% discount · sent for approval · approved by Owner").
 * "This discount needs approval." with "Send for approval"; after sending the bill shows "Waiting for approval" with the
 * approver's role and the age; "Approved by <name>" lets the seller save; a rejection shows its reason; a changed cart
 * makes the approval void. The server decides all of it; this card only shows and asks.
 */
@Component({
  selector: 'app-discount-approval',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
  `,
  template: `
    @if (request(); as r) {
      @switch (r.status) {
        @case ('PENDING') {
          <div class="notice warn" role="status" data-testid="disc-waiting">
            <app-icon name="alert" [size]="16" />
            <span>
              {{ waiting() }}
              @if (r.escalated) {
                · Escalated
              }
              @if (r.needsAnotherApprover) {
                · Needs another approver
              }
            </span>
          </div>
          @if (r.selfApprovalOnly) {
            <p class="err" role="alert" data-testid="disc-self">Ask another approver, or set the discount rule to Lean.</p>
          }
          <div class="acts">
            <button mat-stroked-button type="button" data-testid="disc-refresh" [disabled]="busy()" (click)="refresh.emit()">Check again</button>
            <button mat-button type="button" data-testid="disc-withdraw" [disabled]="busy()" (click)="withdraw.emit()">Withdraw</button>
          </div>
        }
        @case ('APPROVED') {
          <div class="notice teal" role="status" data-testid="disc-approved">
            <app-icon name="check" [size]="16" />
            <span>Approved by {{ r.decidedBy?.name ?? 'the approver' }}</span>
          </div>
        }
        @case ('REJECTED') {
          <p class="err" role="alert" data-testid="disc-rejected">
            Not approved{{ r.rejectReason ? ': ' + r.rejectReason.label : '' }}. Lower the discount or send a new request.
          </p>
          <button mat-stroked-button type="button" data-testid="disc-send" [disabled]="busy()" (click)="send.emit()">Send for approval</button>
        }
        @case ('VOID') {
          <p class="err" role="alert" data-testid="disc-void">The bill changed after the approval. Send a new request.</p>
          <button mat-stroked-button type="button" data-testid="disc-send" [disabled]="busy()" (click)="send.emit()">Send for approval</button>
        }
        @default {
          <p class="err" role="alert" data-testid="disc-needed">This discount needs approval.</p>
          <button mat-stroked-button type="button" data-testid="disc-send" [disabled]="busy()" (click)="send.emit()">Send for approval</button>
        }
      }
    } @else {
      <p class="err" role="alert" data-testid="disc-needed">This discount needs approval.</p>
      <button mat-stroked-button type="button" data-testid="disc-send" [disabled]="busy()" (click)="send.emit()">Send for approval</button>
    }
    @if (error(); as text) {
      <p class="err" role="alert" data-testid="disc-error">{{ text }}</p>
    }
  `,
})
export class DiscountApprovalCard {
  readonly request = input<DiscountRequest | null>(null);
  readonly busy = input(false);
  readonly error = input<string | null>(null);
  readonly send = output<void>();
  readonly refresh = output<void>();
  readonly withdraw = output<void>();

  protected readonly waiting = computed(() => {
    const r = this.request();
    return r ? waitingText(r) : '';
  });
}
