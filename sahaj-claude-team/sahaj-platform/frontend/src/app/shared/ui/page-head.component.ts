import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Page header of every console screen (mockup `.pagehead`): the title and a one-line lead on
 * the left, the page actions on the right. Put the buttons inside the tag, for example:
 *
 *     <app-page-head title="Patients" lead="Every patient registered at the clinic.">
 *       <button mat-flat-button type="button">New patient</button>
 *     </app-page-head>
 *
 * The look is in `src/styles/_console.scss` (`.pagehead`, `.lead`, `.actions`).
 */
@Component({
  selector: 'app-page-head',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
    }
  `,
  template: `
    <div class="pagehead">
      <div>
        <h1>{{ title() }}</h1>
        @if (lead()) {
          <p class="lead">{{ lead() }}</p>
        }
      </div>
      <div class="actions"><ng-content /></div>
    </div>
  `,
})
export class PageHeadComponent {
  readonly title = input.required<string>();
  readonly lead = input<string>('');
}
