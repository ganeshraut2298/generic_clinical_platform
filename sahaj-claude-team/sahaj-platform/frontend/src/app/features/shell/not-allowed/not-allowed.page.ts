import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../../../core/i18n/translate.pipe';

/**
 * Shown when the signed-in role has no access to a menu page (SAH-5 acceptance criterion 1):
 * typing a hidden page's URL lands here instead of the page.
 */
@Component({
  selector: 'app-not-allowed',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink, TranslatePipe],
  styleUrl: '../../shared-stub.scss',
  template: `
    <main class="stub surf">
      <span class="pill p-warn">{{ 'shell.notAllowedTitle' | t }}</span>
      <h1>{{ 'shell.notAllowedTitle' | t }}</h1>
      <p class="muted">{{ 'shell.notAllowedBody' | t }}</p>
      <a mat-stroked-button class="btn-sec" routerLink="/app/today">Back to Today</a>
    </main>
  `,
})
export class NotAllowedPage {}
