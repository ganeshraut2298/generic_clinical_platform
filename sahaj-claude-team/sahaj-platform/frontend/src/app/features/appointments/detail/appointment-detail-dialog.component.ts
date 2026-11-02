import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { ApiError } from '../../../core/http/problem-detail';
import { formatClockLabel } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { AppointmentsApi } from '../appointments.api';
import {
  APPOINTMENT_SOURCE_LABELS,
  APPOINTMENT_VISIT_TYPE_LABELS,
  Appointment,
} from '../appointments.model';
import { canManageAppointments } from '../appointments-role.util';
import { Role } from '../../../core/platform/platform.model';

export interface AppointmentDetailDialogData {
  appointment: Appointment;
  /** Formatted "When" line, for example `Today · 9:00 AM`; computed by the board so the dialog
   * does not need to know about Today/Tomorrow. */
  whenLabel: string;
  /** Whether this appointment's date is today (gates "Create token", mockup `a.day===0`). */
  isToday: boolean;
  role: Role | undefined;
}

/**
 * Appointment detail sheet (SAH-11), mockup `openAppt`: when/doctor/visit-type/source/status,
 * and the actions valid for the current status. "Remind"/"Send reminder" always show, disabled,
 * because no WhatsApp channel exists in Phase 1 (story §6) — the layout still matches the
 * mockup rather than silently dropping a button.
 */
@Component({
  selector: 'app-appointment-detail-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './appointment-detail-dialog.component.scss',
  templateUrl: './appointment-detail-dialog.component.html',
})
export class AppointmentDetailDialogComponent {
  protected readonly data = inject<AppointmentDetailDialogData>(MAT_DIALOG_DATA);
  private readonly api = inject(AppointmentsApi);
  private readonly router = inject(Router);
  private readonly dialogRef = inject<MatDialogRef<AppointmentDetailDialogComponent, Appointment | 'queue' | null>>(MatDialogRef);

  protected readonly visitTypeLabels = APPOINTMENT_VISIT_TYPE_LABELS;
  protected readonly sourceLabels = APPOINTMENT_SOURCE_LABELS;
  protected readonly formatClockLabel = formatClockLabel;

  protected readonly appointment = signal<Appointment>(this.data.appointment);
  protected readonly confirmingCancel = signal(false);
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);

  protected readonly canManage = computed(() => canManageAppointments(this.data.role));

  protected statusText(): string {
    const a = this.appointment();
    if (a.status === 'ARRIVED') {
      return `Arrived · token T-${a.tokenNo}`;
    }
    if (a.status === 'NOSHOW') {
      return 'Did not come';
    }
    if (a.status === 'CANCELLED') {
      return 'Cancelled';
    }
    return 'Booked';
  }

  protected startCancel(): void {
    this.confirmingCancel.set(true);
  }

  protected keepAppointment(): void {
    this.confirmingCancel.set(false);
  }

  protected confirmCancel(): void {
    this.setStatus('CANCELLED');
  }

  protected markNoShow(): void {
    this.setStatus('NOSHOW');
  }

  protected createToken(): void {
    if (!this.canManage() || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.api.createToken(this.appointment().id).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.appointment.set(updated);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.actionError.set(this.messageOf(error, 'We could not create a token for this appointment. Try again.'));
      },
    });
  }

  protected openQueue(): void {
    this.dialogRef.close('queue');
    this.router.navigate(['/app/queue']);
  }

  protected close(): void {
    this.dialogRef.close(this.appointment());
  }

  private setStatus(status: 'CANCELLED' | 'NOSHOW'): void {
    if (!this.canManage() || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.api.setStatus(this.appointment().id, status).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.confirmingCancel.set(false);
        this.appointment.set(updated);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.actionError.set(this.messageOf(error, 'We could not update this appointment. Try again.'));
      },
    });
  }

  private messageOf(error: unknown, fallback: string): string {
    return error instanceof ApiError ? error.problem.detail ?? fallback : fallback;
  }
}
