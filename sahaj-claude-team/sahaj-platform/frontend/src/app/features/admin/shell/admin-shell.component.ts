import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatMenuModule } from '@angular/material/menu';
import {
  ActivatedRouteSnapshot,
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { filter } from 'rxjs';
import { IconComponent } from '../../../shared/icon/icon.component';
import { AdminAuthService } from '../auth/admin-auth.service';

export const SCOPE_LABEL = 'Platform Management';
export const STRIP_TEXT = 'Platform view: counts only. Patient records stay inside each organization.';

/** Initials for the profile badge: first letters of the first two words of the name. */
export function initialsOf(name: string): string {
  const letters = name
    .trim()
    .split(/\s+/)
    .filter((part) => part.length > 0)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase());
  return letters.join('') || '?';
}

/**
 * Admin chrome (ADM-1), mockup `pAdminV3`: sidebar with brand and the group Platform Management,
 * top bar with the scope pill and the profile menu, the "counts only" strip, a breadcrumb and the
 * page. The mockup's Switch, search, Pilot/10 lakh toggle, notifications and help are not built.
 */
@Component({
  selector: 'app-admin-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatMenuModule, RouterLink, RouterLinkActive, RouterOutlet, IconComponent],
  styleUrl: './admin-shell.component.scss',
  templateUrl: './admin-shell.component.html',
  host: { '(document:keydown.escape)': 'closeDrawer()' },
})
export class AdminShellComponent {
  private readonly auth = inject(AdminAuthService);
  private readonly router = inject(Router);

  protected readonly scopeLabel = SCOPE_LABEL;
  protected readonly stripText = STRIP_TEXT;

  protected readonly collapsed = signal(false);
  protected readonly drawerOpen = signal(false);
  protected readonly signingOut = signal(false);
  protected readonly crumb = signal<string | null>(this.currentCrumb());

  protected readonly adminName = computed(() => this.auth.admin()?.name ?? '');
  protected readonly roleLabel = computed(() => this.auth.admin()?.roleLabel ?? 'Platform admin');
  protected readonly initials = computed(() => initialsOf(this.adminName()));

  constructor() {
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.crumb.set(this.currentCrumb()));
  }

  protected toggleCollapsed(): void {
    this.collapsed.update((value) => !value);
  }

  protected openDrawer(): void {
    this.drawerOpen.set(true);
  }

  protected closeDrawer(): void {
    this.drawerOpen.set(false);
  }

  protected signOut(): void {
    if (this.signingOut()) {
      return;
    }
    this.signingOut.set(true);
    this.auth.signOut().subscribe(() => {
      this.signingOut.set(false);
      void this.router.navigate(['/admin/sign-in']);
    });
  }

  private currentCrumb(): string | null {
    let snapshot: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    let crumb: unknown = null;
    while (snapshot) {
      crumb = snapshot.data['crumb'] ?? crumb;
      snapshot = snapshot.firstChild;
    }
    return typeof crumb === 'string' ? crumb : null;
  }
}
