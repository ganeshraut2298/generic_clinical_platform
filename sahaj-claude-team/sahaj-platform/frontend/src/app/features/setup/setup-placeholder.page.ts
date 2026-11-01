import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Placeholder for the setup wizard. A verified sign-up lands here (`AuthResult.next === 'SETUP'`).
 * The real five-step wizard (mockup `pSetup`) is built in SAH-4.
 */
@Component({
  selector: 'app-setup-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../shared-stub.scss',
  template: `
    <main class="stub surf">
      <span class="pill p-wait">Coming soon</span>
      <h1>Set up your clinic</h1>
      <p class="muted">
        You are signed in. The setup wizard (clinic details, specialty, team, OPD flow, look and
        web address) is built in story SAH-4.
      </p>
    </main>
  `,
})
export class SetupPlaceholderPage {}
