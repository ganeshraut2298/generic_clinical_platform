import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Placeholder until ADM-2 builds the Organizations directory (ADR-003 ADM-1 criterion 13). */
@Component({
  selector: 'app-admin-organizations-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .placeholder {
      padding: 28px;
      border: 1px dashed var(--line);
      border-radius: var(--r);
      color: var(--ink-2);
      background: var(--surface);
    }
    h1 {
      margin: 0 0 6px;
      font-size: 1.28rem;
      color: var(--ink);
    }
    p {
      margin: 0;
    }
  `,
  template: `
    <section class="placeholder" aria-labelledby="orgs-title">
      <h1 id="orgs-title">Organizations</h1>
      <p>Organizations coming in the next release</p>
    </section>
  `,
})
export class OrganizationsPlaceholderPage {}
