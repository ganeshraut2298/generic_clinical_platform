import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

/** Placeholder for the Sahaj team admin portal. It is not part of phase 1. */
@Component({
  selector: 'app-admin-coming-soon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink],
  styleUrl: '../../shared-stub.scss',
  template: `
    <main class="stub surf">
      <span class="pill p-wait">Coming soon</span>
      <h1>Admin portal</h1>
      <p class="muted">
        The admin portal for the Sahaj team is coming soon. Nothing is stored or shown here yet.
      </p>
      <a mat-stroked-button class="btn-sec" routerLink="/">Back to home</a>
    </main>
  `,
})
export class AdminComingSoonPage {}
