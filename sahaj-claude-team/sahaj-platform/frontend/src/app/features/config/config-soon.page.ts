import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Plain placeholder for a Clinic setup section whose story is not built yet. */
@Component({
  selector: 'app-config-soon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .soon {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 10px;
      padding: 20px;
    }
  `,
  template: `
    <section class="soon surf">
      <span class="pill p-wait">Coming soon</span>
      <p class="muted">This section is built in a later story.</p>
    </section>
  `,
})
export class ConfigSoonPage {}
