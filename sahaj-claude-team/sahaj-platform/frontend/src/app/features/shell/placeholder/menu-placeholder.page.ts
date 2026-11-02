import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';

/**
 * A plain placeholder inside the shell for a menu page not built yet (SAH-5 §3: "Pages from
 * later stories open a plain placeholder inside the shell."). The title comes from route data.
 */
@Component({
  selector: 'app-menu-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../../shared-stub.scss',
  template: `
    <main class="stub surf">
      <span class="pill p-wait">Coming soon</span>
      <h1>{{ title() }}</h1>
      <p class="muted">This page is built in a later story. You are signed in and can move around the rest of the shell.</p>
    </main>
  `,
})
export class MenuPlaceholderPage {
  private readonly route = inject(ActivatedRoute);
  protected readonly title = toSignal(
    this.route.data.pipe(map((data) => (data['title'] as string) ?? 'Coming soon')),
    { initialValue: 'Coming soon' },
  );
}
