import { Injectable, computed, inject, signal } from '@angular/core';
import { ShellStore } from '../../core/shell/shell.store';
import { EmrApi } from './emr.api';

/** Roles that can write consultations when they have doctor details (`clinic.consultation.write`). */
const DOCTOR_ROLES: ReadonlySet<string> = new Set(['OWNER', 'DOCTOR', 'VISITING_DOCTOR']);

/**
 * Whether the signed-in user is offered "Open EMR" (SAH-46-1). The answer is `GET /emr/me`: 200
 * means yes; 403 (not a doctor role, or an owner with no doctor details) means no. Other roles are
 * not asked. Only the membership id is kept; the doctor's name and registration number are not.
 * The server checks the role again on every call.
 */
@Injectable({ providedIn: 'root' })
export class EmrAccess {
  private readonly api = inject(EmrApi);
  private readonly shell = inject(ShellStore);

  private readonly answer = signal<'unknown' | 'yes' | 'no'>('unknown');
  private readonly membership = signal<string | null>(null);
  private asked = false;

  readonly canOpen = computed(() => this.answer() === 'yes');

  /** Asks once per session; safe to call from every card. */
  ensureLoaded(): void {
    if (this.asked) {
      return;
    }
    const role = this.shell.me()?.role;
    if (!role) {
      return;
    }
    this.asked = true;
    if (!DOCTOR_ROLES.has(role)) {
      this.answer.set('no');
      return;
    }
    this.api.getDoctorContext().subscribe({
      next: (context) => {
        this.membership.set(context.membershipId);
        this.answer.set('yes');
      },
      error: () => this.answer.set('no'),
    });
  }

  /** An OPD card: a doctor or visiting doctor opens only their own patients; the owner any (SAH-46-1). */
  canOpenVisit(doctorMembershipId: string): boolean {
    if (!this.canOpen()) {
      return false;
    }
    return this.shell.me()?.role === 'OWNER' || this.membership() === doctorMembershipId;
  }
}
