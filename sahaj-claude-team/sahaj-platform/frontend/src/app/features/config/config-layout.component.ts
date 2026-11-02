import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { ShellStore } from '../../core/shell/shell.store';
import { IconComponent } from '../../shared/icon/icon.component';
import { CONFIG_SECTIONS, ConfigSection, configSectionByKey } from './config-sections';

interface NavGroup {
  readonly name: string;
  readonly sections: readonly ConfigSection[];
}

/**
 * Clinic setup layout, mockup `vConfig`/`cfgNavList`/`cfgHead`: page head, the section nav on
 * the left and the section page on the right. SAH-6 (Team and access) and SAH-7 (Clinic profile)
 * share it. Read-only users see the "View only" pill; the backend still enforces the role.
 */
@Component({
  selector: 'app-config-layout',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, IconComponent],
  styleUrl: './config-layout.component.scss',
  templateUrl: './config-layout.component.html',
})
export class ConfigLayoutComponent {
  private readonly router = inject(Router);
  private readonly store = inject(ShellStore);

  protected readonly query = signal('');
  protected readonly tipsOpen = signal(true);

  protected readonly viewOnly = computed(() => this.store.me()?.role !== 'OWNER');

  protected readonly setupPercent = computed(() => this.store.me()?.clinic.setupPercent ?? null);

  private readonly url =toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  protected readonly current = computed<ConfigSection | undefined>(() => {
    const key = this.url().split('#')[0].split('?')[0].split('/').filter(Boolean).pop() ?? '';
    return configSectionByKey(key);
  });

  protected readonly groups = computed<NavGroup[]>(() => {
    const q = this.query().trim().toLowerCase();
    const sections = CONFIG_SECTIONS.filter((s) => !q || s.label.toLowerCase().includes(q));
    const names = [...new Set(sections.map((s) => s.group))];
    return names.map((name) => ({ name, sections: sections.filter((s) => s.group === name) }));
  });

  protected onQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  protected toggleTips(): void {
    this.tipsOpen.update((open) => !open);
  }
}
