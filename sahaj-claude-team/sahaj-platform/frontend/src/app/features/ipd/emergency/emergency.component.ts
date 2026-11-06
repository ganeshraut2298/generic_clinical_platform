import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { AdmitDialogComponent, AdmitDialogData } from '../admit/admit-dialog.component';
import { AdmissionsApi } from '../admissions.api';
import { AdmissionDetail } from '../ipd.model';
import { EmergencyBadgeStore } from './emergency-badge.store';
import { EmergencyRegisterComponent } from './emergency-register.component';
import { EmergencyApi } from './emergency.api';
import {
  CASE_CLOSED,
  EmergencyCase,
  EmergencyCounts,
  EmergencyLevel,
  EmergencyQueue,
  NO_FREE_BED,
  TriageLevel,
  ageSexText,
  forPatientText,
  isFullCase,
  queueNote,
  referredToast,
  registeredToast,
  releasedToast,
  retriagedToast,
  seenToast,
  staleQueueText,
  waitText,
} from './emergency.model';

export const QUEUE_REFRESH_MS = 60_000;
const LOAD_ERROR = 'We could not load the emergency queue. Try again.';
const ACTION_ERROR = 'We could not do that. Try again.';
const SHEET_ERROR = 'We could not open the admission sheet. Try again.';

/**
 * The Emergency page body (SAH-28), mockup `ipEr`: Quick registration (left) and the Emergency
 * queue (right). Every action answers the whole queue, so the list is replaced from the answer; it
 * also reloads every 60 seconds while open. Buttons follow the server's flags (`canAct`,
 * `canRetriage`, `canAdmit`, `canRegister`); the server checks the role again on every call.
 */
@Component({
  selector: 'app-emergency',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, EmergencyRegisterComponent],
  styleUrl: './emergency.component.scss',
  templateUrl: './emergency.component.html',
})
export class EmergencyComponent implements OnInit {
  private readonly api = inject(EmergencyApi);
  private readonly admissions = inject(AdmissionsApi);
  private readonly badge = inject(EmergencyBadgeStore);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  /** Counts of every queue answer: the tile and the tab of the page follow them. */
  readonly counts = output<EmergencyCounts>();
  /** An emergency patient was admitted through the Admit sheet. */
  readonly admitted = output<AdmissionDetail>();

  protected readonly queue = signal<EmergencyQueue | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** One action runs at a time; the card buttons are off meanwhile. */
  protected readonly busy = signal(false);

  protected readonly note = computed(() => {
    const q = this.queue();
    return q ? queueNote(q) : '';
  });
  protected readonly ageSex = ageSexText;
  protected readonly wait = waitText;

  /** The last 60-second reload failed: the old queue stays and a small warning shows. */
  protected readonly refreshFailed = signal(false);
  protected readonly staleText = computed(() => staleQueueText(this.queue()?.asOf));
  protected readonly forPatient = forPatientText;

  private refreshing = false;

  ngOnInit(): void {
    this.load();
    const timer = setInterval(() => this.refresh(), QUEUE_REFRESH_MS);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.queue().subscribe({
      next: (queue) => {
        this.loading.set(false);
        this.apply(queue);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(problemMessage(error, LOAD_ERROR));
      },
    });
  }

  /** Reload without the loading text; a failure keeps what is on screen. */
  protected refresh(): void {
    if (this.refreshing || this.loading()) {
      return;
    }
    this.refreshing = true;
    this.api.queue().subscribe({
      next: (queue) => {
        this.refreshing = false;
        this.apply(queue);
      },
      error: () => {
        this.refreshing = false;
        this.refreshFailed.set(true);
      },
    });
  }

  private apply(queue: EmergencyQueue): void {
    this.refreshFailed.set(false);
    this.queue.set(queue);
    this.badge.accept(queue.counts);
    this.counts.emit(queue.counts);
  }

  protected levelOf(item: EmergencyCase): EmergencyLevel | undefined {
    return this.queue()?.levels.find((l) => l.level === item.level);
  }

  protected toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }

  protected onRegistered(queue: EmergencyQueue): void {
    this.apply(queue);
    const acted = queue.actedCase;
    if (isFullCase(acted)) {
      this.toast(registeredToast(acted));
    }
  }

  protected seen(item: EmergencyCase): void {
    this.act(this.api.seen(item.id), item, seenToast);
  }

  protected release(item: EmergencyCase): void {
    this.act(this.api.release(item.id), item, releasedToast);
  }

  protected refer(item: EmergencyCase): void {
    this.act(this.api.refer(item.id), item, referredToast);
  }

  protected retriage(item: EmergencyCase, select: HTMLSelectElement): void {
    const level = Number(select.value) as TriageLevel;
    if (level === item.level) {
      return;
    }
    this.act(this.api.setLevel(item.id, level), item, retriagedToast, () => {
      select.value = String(item.level);
    });
  }

  private act(
    call: Observable<EmergencyQueue>,
    item: EmergencyCase,
    toast: (acted: EmergencyCase) => string,
    failed?: () => void,
  ): void {
    if (this.busy()) {
      failed?.();
      return;
    }
    this.busy.set(true);
    call.subscribe({
      next: (queue) => {
        this.busy.set(false);
        this.apply(queue);
        this.toast(toast(isFullCase(queue.actedCase) ? queue.actedCase : item));
      },
      error: (error: unknown) => {
        this.busy.set(false);
        failed?.();
        this.fail(error);
      },
    });
  }

  /** A closed case or a state change on the server: say so and reload what is really in the queue. */
  protected fail(error: unknown): void {
    if (error instanceof ApiError && error.status === 409) {
      const closed = error.problem.type.endsWith('case-closed');
      this.toast(problemMessage(error, closed ? CASE_CLOSED : ACTION_ERROR));
      this.refresh();
      return;
    }
    if (error instanceof ApiError && error.status === 404) {
      this.toast(CASE_CLOSED);
      this.refresh();
      return;
    }
    this.toast(problemMessage(error, ACTION_ERROR));
  }

  /**
   * Admit hand-off (AC7). The options are read with the case id first: when the suggested bed is
   * null there is no free bed, so only the message shows and the sheet does not open.
   */
  protected admit(item: EmergencyCase): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.admissions.options(item.id).subscribe({
      next: (options) => {
        this.busy.set(false);
        const handoff = options.emergencyCase;
        if (!handoff) {
          this.toast(SHEET_ERROR);
          return;
        }
        if (!handoff.suggestedBedId) {
          this.toast(NO_FREE_BED);
          return;
        }
        this.openSheet(item, options);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.fail(error);
      },
    });
  }

  private openSheet(item: EmergencyCase, options: AdmitDialogData['options']): void {
    this.dialog
      .open<AdmitDialogComponent, AdmitDialogData, AdmissionDetail | null>(AdmitDialogComponent, {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        data: { emergencyCaseId: item.id, options, onCaseClosed: () => this.refresh() },
      })
      .afterClosed()
      .subscribe((admission) => {
        if (admission) {
          this.admitted.emit(admission);
          this.refresh();
        }
      });
  }
}
