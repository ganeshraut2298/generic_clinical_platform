import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  OnInit,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslateService } from '../../core/i18n/translate.service';
import {
  NAV_GROUP_LABEL_KEYS,
  NAV_ITEMS,
  NavItem,
  SIMPLE_MENU_KEYS,
  navItemByKey,
} from '../../core/shell/nav.config';
import { profileChip } from '../../core/shell/profile-chip';
import { ShellStore } from '../../core/shell/shell.store';
import { MenuMode, Theme } from '../../core/shell/shell.model';
import { Language, ROLE_LABELS } from '../../core/platform/platform.model';
import { IconComponent } from '../../shared/icon/icon.component';
import { formatIstDateTime } from '../../shared/format/ist-clock';
import { EmergencyBadgeStore } from '../ipd/emergency/emergency-badge.store';
import { ColleagueTriesBannerComponent } from '../pin/colleague-tries-banner.component';
import { ColleagueTriesNoticeStore } from '../pin/colleague-tries-notice.store';
import { SetPinSheetComponent } from '../pin/set-pin-sheet.component';
import { GoToDialogComponent, GoToOption } from './go-to/go-to-dialog.component';

interface NavGroupView {
  readonly group: NavItem['group'];
  readonly labelKey: string;
  readonly items: readonly NavItem[];
}

/**
 * The app shell (SAH-5): sidebar, header and router outlet every later screen sits inside.
 * Mockup `csSideHTML`/`csNavHTML` and the header markup. Role and plan menu come from `/me`;
 * every API still checks the role itself, the menu here is a convenience only.
 */
@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    NgTemplateOutlet,
    MatMenuModule,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    IconComponent,
    TranslatePipe,
    ColleagueTriesBannerComponent,
  ],
  styleUrl: './shell.component.scss',
  templateUrl: './shell.component.html',
})
export class ShellComponent implements OnInit {
  private readonly store = inject(ShellStore);
  private readonly translate = inject(TranslateService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);
  private readonly emergencyBadge = inject(EmergencyBadgeStore);
  private readonly colleagueNotice = inject(ColleagueTriesNoticeStore);

  protected readonly roleLabels = ROLE_LABELS;

  protected readonly navQuery = signal('');
  protected readonly drawerOpen = signal(false);
  protected readonly collapsedGroups = signal<Record<string, boolean>>({});
  protected readonly clock = signal(formatIstDateTime(new Date()));

  protected readonly me = this.store.me;
  protected readonly collapsed = this.store.sidebarCollapsed;
  protected readonly menuMode = this.store.menuMode;
  protected readonly language = this.store.language;
  protected readonly theme = this.store.theme;

  protected readonly clinicName = computed(() => this.me()?.clinic.name ?? '');
  protected readonly clinicArea = computed(() => this.me()?.clinic.area ?? '');
  protected readonly roleLabel = computed(() => {
    const role = this.me()?.role;
    return role ? this.roleLabels[role] : '';
  });
  /** Name over role; the role alone when the name is missing or is only the role word. */
  protected readonly chip = computed(() => profileChip(this.me()?.name, this.roleLabel()));
  protected readonly planDaysLeft = computed(() => this.me()?.clinic.planDaysLeft);
  protected readonly planMeterPercent = computed(() => {
    const left = this.planDaysLeft();
    return left == null ? 0 : Math.min(100, Math.max(0, ((365 - left) / 365) * 100));
  });

  protected readonly visibleNavItems = computed(() => {
    const menu = this.store.menu();
    return NAV_ITEMS.filter((item) => menu.includes(item.key));
  });

  protected readonly searchResults = computed(() => {
    const query = this.navQuery().trim().toLowerCase();
    if (!query) {
      return [];
    }
    return this.visibleNavItems().filter((item) =>
      this.translate.t(item.labelKey).toLowerCase().includes(query),
    );
  });

  protected readonly simpleItems = computed(() =>
    this.visibleNavItems().filter((item) => SIMPLE_MENU_KEYS.includes(item.key)),
  );

  protected readonly groupedItems = computed<NavGroupView[]>(() => {
    const items = this.visibleNavItems();
    const order: NavItem['group'][] = ['work', 'pt', 'money', 'clinic'];
    return order
      .map((group) => ({
        group,
        labelKey: NAV_GROUP_LABEL_KEYS[group],
        items: items.filter((item) => item.group === group),
      }))
      .filter((view) => view.items.length > 0);
  });

  constructor() {
    // The count on "Emergency" is read only while the server's menu has the key (ADR-005).
    effect(() => {
      if (this.store.menu().includes('emergency')) {
        this.emergencyBadge.start();
      } else {
        this.emergencyBadge.stop();
      }
    });
    this.destroyRef.onDestroy(() => this.emergencyBadge.stop());

    // SAH-38 (ADR-014 D8): the colleague-tries notice is asked for after the first screen is shown, so
    // sign-in is never slowed. A failed load is tried again at a later route change (once a minute at most).
    afterNextRender(() => this.colleagueNotice.load());
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.colleagueNotice.retryAfterFailure());
    this.destroyRef.onDestroy(() => this.colleagueNotice.reset());
  }

  /** Number on a menu item; only "Emergency" has one, and only above 0. */
  protected badgeOf(item: NavItem): number {
    return item.key === 'emergency' ? this.emergencyBadge.count() : 0;
  }

  ngOnInit(): void {
    this.translate.use(this.language()).subscribe();
    const timer = setInterval(() => this.clock.set(formatIstDateTime(new Date())), 30_000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  @HostListener('document:keydown', ['$event'])
  protected onGlobalKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      this.openGoTo();
      return;
    }
    if (event.key === 'Escape' && this.drawerOpen()) {
      this.closeDrawer();
    }
  }

  /** The icon rail has no group headings to open, so every group stays open there. */
  protected isGroupOpen(group: NavItem['group']): boolean {
    return this.collapsed() || this.collapsedGroups()[group] !== true;
  }

  protected toggleGroup(group: NavItem['group']): void {
    this.collapsedGroups.update((groups) => ({ ...groups, [group]: groups[group] !== true }));
  }

  protected toggleDrawer(): void {
    this.drawerOpen.update((open) => !open);
  }

  protected closeDrawer(): void {
    this.drawerOpen.set(false);
  }

  protected toggleCollapsed(): void {
    this.store.updatePreferences({ sidebarCollapsed: !this.collapsed() }).subscribe();
  }

  protected setMenuMode(mode: MenuMode): void {
    this.store.updatePreferences({ menuMode: mode }).subscribe();
  }

  protected setLanguage(language: Language): void {
    this.translate.use(language).subscribe();
    this.store.updatePreferences({ language }).subscribe();
  }

  protected toggleTheme(): void {
    const next: Theme = this.theme() === 'dark' ? 'light' : 'dark';
    this.store.updatePreferences({ theme: next }).subscribe();
  }

  protected openGoTo(): void {
    const options: GoToOption[] = this.visibleNavItems().map((item) => ({
      ...item,
      label: this.translate.t(item.labelKey),
    }));
    this.dialog
      .open<GoToDialogComponent, GoToOption[], string | undefined>(GoToDialogComponent, {
        data: options,
        panelClass: 'sheet-panel',
        autoFocus: 'first-tabbable',
      })
      .afterClosed()
      .subscribe((key) => {
        this.closeDrawer();
        if (!key) {
          return;
        }
        const item = navItemByKey(key);
        if (item) {
          void this.router.navigate(['/app', item.route]);
        }
      });
  }

  /**
   * SAH-31 (ADR-010 D12): a role without the Team page (no `config` menu item) sets or changes
   * its own PIN from the account menu. The roles that have the page do it on their Team row.
   */
  protected readonly showMyPin = computed(() => !this.store.menu().includes('config'));

  private readonly accountButton = viewChild<ElementRef<HTMLButtonElement>>('accountButton');

  /**
   * The menu item that opened the sheet is gone when the sheet closes, so the dialog has nothing to
   * give the focus back to: the account button takes it (a11y: focus returns to where the user was).
   */
  protected openMyPin(): void {
    this.dialog
      .open(SetPinSheetComponent, {
        panelClass: 'sheet-panel',
        width: '520px',
        maxWidth: '100vw',
        ariaLabelledBy: 'sp-title',
        restoreFocus: false,
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.accountButton()?.nativeElement.focus());
  }

  protected logout(): void {
    this.auth.logout().subscribe(() => {
      this.colleagueNotice.reset();
      this.store.clear();
      void this.router.navigate(['/login']);
    });
  }
}
