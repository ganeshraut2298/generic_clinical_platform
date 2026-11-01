import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Eyebrow, heading and short lead that open every section of the home page. */
@Component({
  selector: 'app-section-head',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 10px;
      max-width: 700px;
    }
    h2 {
      font-size: clamp(1.6rem, 3vw, 2.3rem);
      font-weight: 700;
      letter-spacing: -0.02em;
      line-height: 1.15;
    }
    p {
      color: var(--ink-2);
      font-size: 1.02rem;
      max-width: 62ch;
    }
  `,
  template: `
    <span class="eyebrow">{{ eyebrow() }}</span>
    <h2 [id]="headingId()">{{ heading() }}</h2>
    @if (lead()) {
      <p>{{ lead() }}</p>
    }
  `,
})
export class SectionHeadComponent {
  readonly eyebrow = input.required<string>();
  readonly heading = input.required<string>();
  readonly lead = input('');
  /** The id of the heading, so the section can point to it with aria-labelledby. */
  readonly headingId = input.required<string>();
}
