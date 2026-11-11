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
 *
 * A lead that is still being completed by later calls (`reserveLead`) is laid out on top of a hidden copy
 * of the words it is expected to end with (`leadLike`). The copy is a real `.lead` box (same font, same
 * width limit), so the left block has the width, the line count and the height of the finished lead at
 * every screen width. The action buttons therefore wrap exactly where they will wrap when the words arrive,
 * and nothing below moves (SAH-37 D-11). The copy is generated content of a hidden element: it is not
 * in the page text and screen readers skip it. Once the lead is complete the plain lead is drawn, as in
 * the mockup.
 */
@Component({
  selector: 'app-page-head',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
    }
    .lead-hold {
      display: grid;
    }
    .lead-hold > .lead {
      grid-area: 1 / 1;
    }
    .lead-hold > .sizer {
      visibility: hidden;
      pointer-events: none;
    }
    .lead-hold > .sizer::after {
      content: attr(data-like);
    }
  `,
  template: `
    <div class="pagehead">
      <div>
        <h1>{{ title() }}</h1>
        @if (lead()) {
          @if (reserveLead()) {
            <div class="lead-hold">
              <p class="lead reserve">{{ lead() }}</p>
              <span class="lead sizer" [attr.data-like]="leadLike() || lead()"></span>
            </div>
          } @else {
            <p class="lead">{{ lead() }}</p>
          }
        }
      </div>
      <div class="actions"><ng-content /></div>
    </div>
  `,
})
export class PageHeadComponent {
  readonly title = input.required<string>();
  readonly lead = input<string>('');
  /** The lead is still being completed by later calls: size the head for the words it will end with. */
  readonly reserveLead = input<boolean>(false);
  /** The words the finished lead is expected to have (only used with `reserveLead`; empty = the lead as it is now). */
  readonly leadLike = input<string>('');
}
