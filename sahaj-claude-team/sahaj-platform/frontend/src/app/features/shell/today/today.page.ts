import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { ShellStore } from '../../../core/shell/shell.store';

/**
 * Today, the start page for every role (mockup `firstRunHTML`'s Getting started panel and the
 * setup progress pill). The panel itself is built in SAH-7; this hooks the pill onto `/me`'s
 * `clinic.setupPercent` so the owner sees progress as soon as they sign in.
 */
@Component({
  selector: 'app-today',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../../shared-stub.scss',
  template: `
    <main class="stub surf">
      @if (!setupComplete()) {
        <span class="pill p-warn">Setup {{ setupPercent() }}% done</span>
      }
      <h1>Today</h1>
      <p class="muted">You are signed in to {{ clinicName() }}. The Today screen's own content (tokens, queue and the Getting started checklist) is built in a later story.</p>
    </main>
  `,
})
export class TodayPage {
  private readonly store = inject(ShellStore);

  protected readonly clinicName = computed(() => this.store.me()?.clinic.name ?? '');
  protected readonly setupComplete = computed(() => this.store.me()?.clinic.setupComplete ?? true);
  protected readonly setupPercent = computed(() => this.store.me()?.clinic.setupPercent ?? 0);
}
