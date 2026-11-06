import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Empty state of a list or panel (mockup `.empty`): a dashed box with one plain sentence, for
 * example "No follow-ups in this list." The look is in `src/styles/_console.scss`.
 */
@Component({
  selector: 'app-empty',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
    }
  `,
  template: `<div class="empty"><ng-content /></div>`,
})
export class EmptyStateComponent {}
