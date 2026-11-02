import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable } from 'rxjs';
import { MOBILE_PATTERN } from '../../../core/auth/auth.model';
import { ApiError } from '../../../core/http/problem-detail';
import { ROLE_LABELS, Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { TeamApi } from './team.api';
import {
  PERMISSION_COLUMNS,
  PERMISSION_ROWS,
  PermissionKey,
  RolePermission,
  TEAM_ROLES,
  TeamMember,
  isDoctorRole,
} from './team.model';

/** `24 Oct 2026, 11:05 AM` in IST, or `Never` when the person has not signed in yet. */
export function formatLastLogin(iso: string | null | undefined): string {
  if (!iso) {
    return 'Never';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return 'Never';
  }
  const datePart = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  }).format(date);
  const timePart = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  })
    .format(date)
    .toUpperCase();
  return `${datePart}, ${timePart}`;
}

export function initials(name: string): string {
  const parts = name
    .replace(/^dr\.?\s+/i, '')
    .trim()
    .split(/\s+/)
    .filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

/**
 * Team and access (SAH-6), mockup `cfgTeam`: doctors with registration numbers, staff with a
 * switch for who can sign in, and the "Who can do what" table. Owners edit; everyone else sees
 * the same page read-only. The backend still checks the role on every call.
 */
@Component({
  selector: 'app-team',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
  ],
  styleUrl: './team.page.scss',
  templateUrl: './team.page.html',
})
export class TeamPage implements OnInit {
  private readonly api = inject(TeamApi);
  private readonly store = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly roles = TEAM_ROLES;
  protected readonly roleLabels = ROLE_LABELS;
  protected readonly permissionRows = PERMISSION_ROWS;
  protected readonly permissionColumns = PERMISSION_COLUMNS;
  protected readonly formatLastLogin = formatLastLogin;
  protected readonly initials = initials;

  protected readonly members = signal<TeamMember[]>([]);
  protected readonly permissions = signal<RolePermission[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly permissionsError = signal<string | null>(null);
  protected readonly busyId = signal<string | null>(null);
  protected readonly confirmRemoveId = signal<string | null>(null);
  protected readonly addOpen = signal(false);
  protected readonly adding = signal(false);
  protected readonly addError = signal<string | null>(null);
  protected readonly savingPermissions = signal(false);

  protected readonly isOwner = computed(() => this.store.me()?.role === 'OWNER');
  /** The signed-in user's own membership id; empty until /me returns it. */
  private readonly selfId = computed(() => this.store.me()?.membershipId);

  private readonly visible = computed(() => this.members().filter((m) => m.status !== 'REMOVED'));
  protected readonly doctors = computed(() =>
    this.visible().filter((m) => isDoctorRole(m.role) || m.doctor != null),
  );
  protected readonly staff = computed(() => this.visible().filter((m) => !isDoctorRole(m.role)));
  private readonly activeOwners = computed(
    () => this.visible().filter((m) => m.role === 'OWNER' && m.status === 'ACTIVE').length,
  );

  protected readonly addForm = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [(c) => (String(c.value ?? '').trim() ? null : { required: true })],
    }),
    mobile: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(MOBILE_PATTERN)],
    }),
    role: new FormControl<Role>('FRONT_DESK', { nonNullable: true }),
    registrationNo: new FormControl('', { nonNullable: true }),
  });

  protected readonly permissionSummary = computed(() => {
    const matrix = this.permissions();
    return PERMISSION_COLUMNS.slice(0, 2)
      .map(({ role, label }) => {
        const allowed = PERMISSION_ROWS.filter((row) =>
          matrix.some((p) => p.role === role && p.permission === row.key && p.allowed),
        ).map((row) => row.label.toLowerCase().replace(/ \(.*\)/, ''));
        return `${label}: ${allowed.length ? allowed.join(', ') : 'nothing beyond their own steps'}`;
      })
      .join('. ');
  });

  ngOnInit(): void {
    this.api.list().subscribe({
      next: (members) => {
        this.members.set(members);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(this.messageOf(error, 'We could not load your team. Try again.'));
      },
    });
    this.api.getRolePermissions().subscribe({
      next: (matrix) => this.permissions.set(matrix),
      error: (error: unknown) =>
        this.permissionsError.set(
          this.messageOf(error, 'We could not load who can do what. Try again.'),
        ),
    });
  }

  protected isAllowed(role: Role, permission: PermissionKey): boolean {
    return this.permissions().some(
      (p) => p.role === role && p.permission === permission && p.allowed,
    );
  }

  protected togglePermission(role: Role, permission: PermissionKey): void {
    if (!this.isOwner() || this.savingPermissions()) {
      return;
    }
    const before = this.permissions();
    const next = this.withChange(before, role, permission, !this.isAllowed(role, permission));
    this.permissions.set(next);
    this.savingPermissions.set(true);
    this.api.saveRolePermissions(next).subscribe({
      next: (saved) => {
        this.permissions.set(saved);
        this.savingPermissions.set(false);
      },
      error: (error: unknown) => {
        this.permissions.set(before);
        this.savingPermissions.set(false);
        this.toast(this.messageOf(error, 'We could not save that change. Try again.'));
      },
    });
  }

  protected openAdd(role: Role = 'FRONT_DESK'): void {
    this.addForm.reset({ name: '', mobile: '', role, registrationNo: '' });
    this.addError.set(null);
    this.addOpen.set(true);
  }

  protected cancelAdd(): void {
    this.addOpen.set(false);
  }

  protected get nameError(): string | null {
    const control = this.addForm.controls.name;
    return control.touched && !control.value.trim() ? 'Enter the name' : null;
  }

  protected get mobileError(): string | null {
    const control = this.addForm.controls.mobile;
    return control.touched && control.invalid ? 'Enter a 10-digit mobile number' : null;
  }

  protected get addingDoctor(): boolean {
    return isDoctorRole(this.addForm.controls.role.value);
  }

  protected submitAdd(): void {
    if (this.adding()) {
      return;
    }
    const { name, mobile, role, registrationNo } = this.addForm.getRawValue();
    this.addForm.controls.name.markAsTouched();
    this.addForm.controls.mobile.markAsTouched();
    if (!name.trim() || this.addForm.controls.mobile.invalid) {
      return;
    }
    const reg = registrationNo.trim();
    this.adding.set(true);
    this.addError.set(null);
    this.api
      .add({
        name: name.trim(),
        mobile,
        role,
        ...(isDoctorRole(role) && reg ? { doctor: { registrationNo: reg } } : {}),
      })
      .subscribe({
        next: (member) => {
          this.members.update((list) => [...list, member]);
          this.adding.set(false);
          this.addOpen.set(false);
          this.toast(`${member.name} added`);
        },
        error: (error: unknown) => {
          this.adding.set(false);
          this.addError.set(this.messageOf(error, 'We could not add this person. Try again.'));
        },
      });
  }

  /** Owners cannot switch off, remove or demote themselves, and the last active owner stays. */
  protected canSwitch(member: TeamMember): boolean {
    if (!this.isOwner() || member.id === this.selfId()) {
      return false;
    }
    return !(member.role === 'OWNER' && member.status === 'ACTIVE' && this.activeOwners() <= 1);
  }

  protected setActive(member: TeamMember, active: boolean): void {
    const call = active ? this.api.reactivate(member.id) : this.api.deactivate(member.id);
    this.run(member.id, call, active ? `${member.name} can sign in again` : `${member.name} switched off`);
  }

  protected changeRole(member: TeamMember, role: Role): void {
    if (role === member.role) {
      return;
    }
    this.run(member.id, this.api.update(member.id, { role }), `${member.name} is now ${ROLE_LABELS[role]}`);
  }

  protected saveRegistration(member: TeamMember, value: string): void {
    const registrationNo = value.trim();
    if (registrationNo === (member.doctor?.registrationNo ?? '')) {
      return;
    }
    this.run(
      member.id,
      this.api.update(member.id, { doctor: { ...member.doctor, registrationNo } }),
      'Registration number saved',
    );
  }

  protected askRemove(member: TeamMember): void {
    this.confirmRemoveId.set(member.id);
  }

  protected cancelRemove(): void {
    this.confirmRemoveId.set(null);
  }

  protected confirmRemove(member: TeamMember): void {
    this.busyId.set(member.id);
    this.api.remove(member.id).subscribe({
      next: () => {
        this.members.update((list) =>
          list.map((m) => (m.id === member.id ? { ...m, status: 'REMOVED' } : m)),
        );
        this.busyId.set(null);
        this.confirmRemoveId.set(null);
        this.toast(`${member.name} no longer has access`);
      },
      error: (error: unknown) => {
        this.busyId.set(null);
        this.confirmRemoveId.set(null);
        this.toast(this.messageOf(error, 'We could not remove access. Try again.'));
      },
    });
  }

  private run(id: string, call: Observable<TeamMember>, success: string): void {
    this.busyId.set(id);
    call.subscribe({
      next: (updated) => {
        this.members.update((list) => list.map((m) => (m.id === updated.id ? updated : m)));
        this.busyId.set(null);
        this.toast(success);
      },
      error: (error: unknown) => {
        this.busyId.set(null);
        this.toast(this.messageOf(error, 'We could not save that change. Try again.'));
      },
    });
  }

  private withChange(
    matrix: RolePermission[],
    role: Role,
    permission: PermissionKey,
    allowed: boolean,
  ): RolePermission[] {
    const exists = matrix.some((p) => p.role === role && p.permission === permission);
    return exists
      ? matrix.map((p) => (p.role === role && p.permission === permission ? { ...p, allowed } : p))
      : [...matrix, { role, permission, allowed }];
  }

  /** The server's own message first (plan limit, last owner, duplicate mobile), then a field error. */
  private messageOf(error: unknown, fallback: string): string {
    if (error instanceof ApiError) {
      return error.problem.detail ?? error.fieldErrors[0]?.message ?? fallback;
    }
    return fallback;
  }

  private toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }
}
