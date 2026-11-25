import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { formatClockLabel } from '../../../shared/format/ist-date';
import {
  DESK_ACTION_LABELS,
  DESK_COLUMNS,
  DESK_TAG_LABELS,
  DeskBoardCard,
  columnCards,
  followUpTagText,
  shortDoctorName,
  tokenText,
  visitTypeText,
} from './desk.model';

export type DeskBoardScope = 'all' | 'selected';

/** Patient flow board (SAH-90-4): four columns, scope toggle, one action or tag per card. */
@Component({
  selector: 'app-desk-flow-board',
  imports: [MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './desk-flow-board.component.scss',
  templateUrl: './desk-flow-board.component.html',
})
export class DeskFlowBoardComponent {
  readonly cards = input.required<readonly DeskBoardCard[]>();
  readonly scope = input<DeskBoardScope>('all');
  readonly canAct = input(false);

  /** True while a write is running: every card button is disabled so a double press does nothing. */
  readonly busy = input(false);

  readonly scopeChange = output<DeskBoardScope>();
  /** The card's one action (check in, rebook, start, complete, set follow-up). */
  readonly cardAction = output<DeskBoardCard>();
  /** "Mark no-show" on a Booked card (SAH-92-4). */
  readonly markNoShow = output<DeskBoardCard>();
  /** The token on a card was pressed: opens the token panel (every role). */
  readonly tokenOpen = output<DeskBoardCard>();

  protected readonly clock = formatClockLabel;
  protected readonly shortName = shortDoctorName;
  protected readonly visitType = visitTypeText;
  protected readonly token = tokenText;
  protected readonly tagLabels = DESK_TAG_LABELS;
  protected readonly followTag = followUpTagText;

  protected readonly columns = computed(() =>
    DESK_COLUMNS.map((column) => ({ ...column, cards: columnCards(this.cards(), column.key) })),
  );

  /** A Booked card that is not yet shown as no-show can be marked by hand. */
  protected canMarkNoShow(card: DeskBoardCard): boolean {
    return card.column === 'BOOKED' && card.appointmentStatus === 'BOOKED' && card.primaryAction === 'CHECK_IN' && card.appointmentId !== null;
  }

  protected actionLabel(card: DeskBoardCard): string | null {
    return card.primaryAction === 'NONE' ? null : DESK_ACTION_LABELS[card.primaryAction];
  }
}
