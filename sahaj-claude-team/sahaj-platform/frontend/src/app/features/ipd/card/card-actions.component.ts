import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { IconComponent } from '../../../shared/icon/icon.component';
import type { CardAction } from '../admitted/admitted.model';
import type { ActionView } from './card-view';
import type { CardLayout } from './card-setup.model';

/**
 * The action row at the bottom of a patient card (SAH-37; mockup `pcacts`): icon over word in the
 * Standard layout, icon only in Compact (tooltip and the name "<Action> for <patient>") and icon and
 * word in Expanded. In the preview no button can be tabbed to or used.
 */
@Component({
  selector: 'app-card-actions',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrl: './card-actions.component.scss',
  templateUrl: './card-actions.component.html',
})
export class CardActionsComponent {
  readonly actions = input.required<ActionView[]>();
  readonly layout = input.required<CardLayout>();
  readonly preview = input(false);
  readonly act = output<CardAction>();

  protected press(action: CardAction): void {
    if (!this.preview()) {
      this.act.emit(action);
    }
  }
}
