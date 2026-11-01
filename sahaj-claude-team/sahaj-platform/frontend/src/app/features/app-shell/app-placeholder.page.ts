import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Placeholder for the signed-in app shell. A login or clinic selection lands here
 * (`AuthResult.next === 'APP'`). The real shell and Today screen are built in SAH-5.
 */
@Component({
  selector: 'app-app-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../shared-stub.scss',
  template: `
    <main class="stub surf">
      <span class="pill p-wait">Coming soon</span>
      <h1>Today</h1>
      <p class="muted">You are signed in. The app shell and Today screen are built in story SAH-5.</p>
    </main>
  `,
})
export class AppPlaceholderPage {}
