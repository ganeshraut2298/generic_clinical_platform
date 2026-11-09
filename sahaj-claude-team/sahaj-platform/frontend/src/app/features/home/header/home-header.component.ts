import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { ScrollTo } from '../../../core/scroll/scroll-to.service';
import { IconComponent } from '../../../shared/icon/icon.component';

export const SECTION_LINKS = [
  { id: 'setup', label: 'How it works' },
  { id: 'workflow', label: 'Workflow' },
  { id: 'specialties', label: 'Specialties' },
  { id: 'compare', label: 'Compare' },
  { id: 'pricing', label: 'Pricing' },
] as const;

/** Sticky header: brand, section links, Admin portal, Log in and Start Free. */
@Component({
  selector: 'app-home-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink, IconComponent],
  styleUrl: './home-header.component.scss',
  template: `
    <button type="button" class="skip" (click)="scroll.section('main')">
      Skip to main content
    </button>
    <header class="lnav surf" data-surface data-dot-exclude>
      <div class="brand">
        <div class="logo">
          <svg
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.4"
            stroke-linecap="round"
            aria-hidden="true"
          >
            <path d="M12 5v14M5 12h14" />
          </svg>
        </div>
        <div>
          <b>Sahaj</b>
          <small>Clinic software for India</small>
        </div>
      </div>
      <nav class="llinks" aria-label="Sections">
        @for (link of links; track link.id) {
          <button mat-button type="button" (click)="scroll.section(link.id)">
            {{ link.label }}
          </button>
        }
      </nav>
      <div class="actions">
        <a
          mat-button
          routerLink="/admin"
          class="admin"
          aria-label="Admin portal"
          title="Admin portal for the Sahaj team"
        >
          <app-icon name="shield" [size]="16" />
          <span class="hide-sm">Admin portal</span>
        </a>
        <a mat-button routerLink="/login" [queryParams]="{ mode: 'login' }">Log in</a>
        <a mat-flat-button routerLink="/login" [queryParams]="{ mode: 'signup' }">Start Free</a>
      </div>
    </header>
  `,
})
export class HomeHeaderComponent {
  protected readonly scroll = inject(ScrollTo);
  protected readonly links = SECTION_LINKS;
}
