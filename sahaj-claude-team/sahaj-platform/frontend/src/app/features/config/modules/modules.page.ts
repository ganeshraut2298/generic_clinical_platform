import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable } from 'rxjs';
import { MenuMode } from '../../../core/shell/shell.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { formatDisplayDateOfInstant } from '../../../shared/format/ist-date';
import { problemMessage } from '../config-format';
import { IpdAccess } from '../ipd/ipd-access';
import { ModulesApi } from './modules.api';
import { IpdState, ModulesView } from './modules.model';

const IPD_PILL: Record<IpdState, { label: string; css: string }> = {
  off: { label: 'Not on your plan', css: 'p-wait' },
  trial: { label: 'Trial', css: 'p-teal' },
  on: { label: 'On', css: 'p-good' },
  readonly: { label: 'Read-only', css: 'p-warn' },
};

export const MASTER_GROUPS: { name: string; module: 'always' | 'ipd' | 'hra'; chips: string[] }[] = [
  {
    name: 'Common to all modules',
    module: 'always',
    chips: ['Users and roles', 'Departments', 'Doctors', 'Services and prices', 'Taxes', 'Medicines', 'Templates', 'Payment modes'],
  },
  { name: 'OPD only', module: 'always', chips: ['Appointment slots', 'Token and queue rules', 'Case paper', 'Follow-up rules'] },
  {
    name: 'IPD only',
    module: 'ipd',
    chips: [
      'Wards, rooms and beds',
      'Bed categories and charges',
      'Admission types',
      'Bed transfer rules',
      'Nursing stations',
      'Emergency triage',
      'Discharge templates',
      'Packages and deposits',
    ],
  },
  { name: 'HRA only', module: 'hra', chips: ['Shifts', 'Leave types', 'Attendance'] },
];

/**
 * Plan and modules (SAH-19), mockup `cfgModules`. The "Start 30-day IPD trial" notice shows only
 * while the server says `ipdTrialAvailable` (DEC-010). The WhatsApp row says "Later".
 */
@Component({
  selector: 'app-modules-config',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './modules.page.scss',
  templateUrl: './modules.page.html',
})
export class ModulesPage implements OnInit {
  private readonly api = inject(ModulesApi);
  private readonly store = inject(ShellStore);
  private readonly ipdAccess = inject(IpdAccess);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly view = signal<ModulesView | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly isOwner = computed(() => this.store.me()?.role === 'OWNER');
  protected readonly menuMode = this.store.menuMode;
  protected readonly planLabel = computed(() => (this.view()?.plan === 'FREE' ? 'Free year' : 'Pro'));
  protected readonly ipdPill = computed(() => {
    const ipd = this.view()?.ipd;
    if (!ipd) {
      return IPD_PILL.off;
    }
    const base = IPD_PILL[ipd.state];
    return ipd.state === 'trial' && ipd.trialEndsAt
      ? { ...base, label: `Trial · ends ${formatDisplayDateOfInstant(ipd.trialEndsAt)}` }
      : base;
  });
  protected readonly groups = computed(() => {
    const v = this.view();
    return MASTER_GROUPS.map((g) => ({
      ...g,
      inUse: g.module === 'always' || (g.module === 'ipd' ? !!v && v.ipd.state !== 'off' : !!v?.hra.on),
    }));
  });

  ngOnInit(): void {
    this.api.get().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (view) => {
        this.view.set(view);
        this.ipdAccess.set(view);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(problemMessage(error, 'We could not load the plan and modules. Try again.'));
      },
    });
  }

  protected requestIpd(): void {
    if (!this.isOwner() || this.saving()) {
      return;
    }
    this.run(this.api.requestIpd(), 'We could not send the IPD request. Try again.');
  }

  protected startTrial(): void {
    if (!this.isOwner() || this.saving()) {
      return;
    }
    this.run(this.api.startIpdTrial(), 'We could not start the IPD trial. Try again.', (view) => {
      const ends = formatDisplayDateOfInstant(view.ipd.trialEndsAt);
      this.snackBar.open(`IPD trial started. It ends ${ends}`, 'Close', { duration: 5000 });
    });
  }

  protected toggleHra(): void {
    const v = this.view();
    if (!v || !this.isOwner() || this.saving()) {
      return;
    }
    this.run(this.api.setHra(!v.hra.on), 'We could not save that change. Try again.');
  }

  /** Same call the sidebar switch makes, so the menu and the saved preference stay in step. */
  protected setMode(mode: MenuMode): void {
    if (mode === this.menuMode()) {
      return;
    }
    this.store.updatePreferences({ menuMode: mode }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      error: (error: unknown) =>
        this.snackBar.open(problemMessage(error, 'We could not save the menu mode. Try again.'), 'Close', { duration: 5000 }),
    });
  }

  private run(call: Observable<ModulesView>, fallback: string, onDone?: (view: ModulesView) => void): void {
    this.saving.set(true);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (view) => {
        this.saving.set(false);
        this.view.set(view);
        this.ipdAccess.set(view);
        this.store.reloadMenu().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ error: () => undefined });
        onDone?.(view);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.snackBar.open(problemMessage(error, fallback), 'Close', { duration: 5000 });
      },
    });
  }
}
