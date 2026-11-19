import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { HistoryEvent } from '../pharmacy-inventory.model';
import { actingAsText, historyEventText } from '../inventory/inventory.util';
import { whenText } from './indent.util';

/**
 * The "History" card of the indent screens (BR-26): every step with user, store, time, quantities and reason. Before the
 * first step the card says what the mockup says.
 */
@Component({
  selector: 'app-history-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
    }
    ol {
      margin: 0;
      padding-left: 18px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .sub {
      font-size: 0.78rem;
      color: var(--ink-3);
    }
  `,
  template: `
    @if (events().length === 0) {
      <p class="sub" data-testid="history-empty">{{ emptyText() }}</p>
    } @else {
      <ol data-testid="history-list">
        @for (event of events(); track $index) {
          <li>
            <b>{{ text(event) }}</b>
            @if (event.lineLabel) {
              <span> · {{ event.lineLabel }}</span>
            } @else if (event.qtyBefore !== null || event.qtyAfter !== null) {
              <span> · {{ qty(event) }}</span>
            }
            @if (event.reason) {
              <span> · {{ event.reason.label }}{{ event.reason.note ? ' (' + event.reason.note + ')' : '' }}</span>
            }
            <div class="sub">
              {{ event.by?.name ?? 'System' }}{{ acting(event) }}{{ event.store ? ' · ' + event.store.name : '' }} ·
              {{ when(event.at) }}
            </div>
          </li>
        }
      </ol>
    }
  `,
})
export class HistoryListComponent {
  readonly events = input.required<HistoryEvent[]>();
  readonly emptyText = input('Nothing yet. History starts when the indent is submitted.');

  protected text(event: HistoryEvent): string {
    return historyEventText(event.event);
  }

  protected acting(event: HistoryEvent): string {
    const text = actingAsText(event.actingAs);
    return text ? ` (${text})` : '';
  }

  protected qty(event: HistoryEvent): string {
    return `${event.qtyBefore ?? '—'} → ${event.qtyAfter ?? '—'}`;
  }

  protected when(at: string): string {
    return whenText(at);
  }
}
