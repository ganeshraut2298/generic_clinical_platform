import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Footer: brand line, Sahaj team sign-in, data location and the GST note. */
@Component({
  selector: 'app-home-footer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  styles: `
    footer {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      font-size: 0.82rem;
      color: var(--ink-3);
      padding-top: 14px;
      border-top: 1px solid var(--line);
    }
    a {
      color: var(--ink-3);
      font-weight: 500;
      text-decoration: underline;
      padding: 12px 0;
    }
  `,
  template: `
    <footer>
      <span>
        Sahaj · made for Indian clinics, from Pune ·
        <a routerLink="/admin">Sahaj team sign-in</a>
      </span>
      <span>Patient data hosted in India · Prices exclude GST</span>
    </footer>
  `,
})
export class HomeFooterComponent {}
