import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  inject,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarRef, TextOnlySnackBar } from '@angular/material/snack-bar';
import { Observable } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { EmrAccess } from '../../emr/emr-access.service';
import { EmrLauncher } from '../../emr/emr-launcher.service';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { AdmitDialogComponent, AdmitDialogData } from '../admit/admit-dialog.component';
import { AdmissionsApi } from '../admissions.api';
import { focusIsLost, isModuleOff, isRefusal } from '../ipd-refusal';
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

/**
 * What the Emergency screen tells the page when a read is refused (SAH-40): the error, and whether focus
 * was on this screen or in its Admit sheet (closed before the page hears of it, so the page can not see it).
 */
export interface EmergencyRefusal {
  readonly error: unknown;
  readonly focusWasInside: boolean;
}
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
  protected readonly emrAccess = inject(EmrAccess);
  private readonly emrLauncher = inject(EmrLauncher);
  private readonly badge = inject(EmergencyBadgeStore);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** Counts of every queue answer: the tile and the tab of the page follow them. */
  readonly counts = output<EmergencyCounts>();
  /** An emergency patient was admitted through the Admit sheet. */
  readonly admitted = output<AdmissionDetail>();
  /**
   * SAH-40: the server refused a read (403). The queue and its counts are already cleared here; the
   * page drops the counts it was given, and locks itself when IPD is off (`module-off`).
   */
  readonly refused = output<EmergencyRefusal>();

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
  /** SAH-40: a read was refused; nothing is read again until Try again (the timer ticks and does nothing). */
  private denied = false;
  /** Bumped by a refusal and by `load()`: an answer that started before is dropped when it arrives. */
  private epoch = 0;
  /** The open Admit sheet: closed on a refusal and with this screen, so no casualty name stays on screen (SAH-40). */
  private sheet: { close(): void } | null = null;
  /** The toast of the last action that succeeded: dismissed on a refusal, as it may name the patient (SAH-40). */
  private successToast: MatSnackBarRef<TextOnlySnackBar> | null = null;
  /** Try again was pressed from inside the screen: the focus may fall to the body when its button is replaced. */
  private retried = false;

  ngOnInit(): void {
    this.emrAccess.ensureLoaded();
    this.load();
    const timer = setInterval(() => this.refresh(), QUEUE_REFRESH_MS);
    this.destroyRef.onDestroy(() => {
      clearInterval(timer);
      this.closeSheet();
    });
  }

  /** SAH-46-4. "Open EMR" on an Emergency card: the emergency assessment for this case. */
  protected openEmr(c: EmergencyCase): void {
    this.emrLauncher.open('ER_ASSESSMENT', c.id);
  }

  protected load(): void {
    const at = ++this.epoch;
    this.retried = this.host.nativeElement.contains(document.activeElement);
    this.denied = false;
    this.refreshing = false;
    this.busy.set(false);
    this.loading.set(true);
    this.loadError.set(null);
    this.api.queue().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (queue) => {
        if (at !== this.epoch) {
          return;
        }
        this.loading.set(false);
        this.apply(queue);
      },
      error: (error: unknown) => {
        if (at !== this.epoch) {
          return;
        }
        this.loading.set(false);
        if (isRefusal(error)) {
          this.refuse(error);
        } else {
          this.loadError.set(problemMessage(error, LOAD_ERROR));
        }
      },
    });
  }

  /**
   * Reload without the loading text; a network error or a 5xx keeps what is on screen, a 403 clears
   * it (SAH-40, DEC-025 G-2). While refused nothing is read: only Try again reads again.
   */
  protected refresh(): void {
    if (this.refreshing || this.loading() || this.denied) {
      return;
    }
    this.refreshing = true;
    const at = this.epoch;
    this.api.queue().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (queue) => {
        if (at !== this.epoch) {
          return;
        }
        this.refreshing = false;
        this.apply(queue);
      },
      error: (error: unknown) => {
        if (at !== this.epoch) {
          return;
        }
        this.refreshing = false;
        if (isRefusal(error)) {
          this.refuse(error);
        } else {
          this.refreshFailed.set(true);
        }
      },
    });
  }

  /**
   * A 403 on a read: the queue, the registration form and the counts leave the screen and the memory
   * of this component. IPD off (`module-off`) is left to the page, which shows its lock notice; any
   * other 403 shows the notice of the first load. Answers that started earlier are dropped.
   */
  private refuse(error: unknown): void {
    // The Admit sheet is in the CDK overlay, outside the host: closing it drops focus to the body.
    const focusWasInside =
      focusIsLost(this.host.nativeElement, document, this.retried) || this.sheet !== null;
    this.retried = false;
    this.epoch++;
    this.denied = true;
    this.refreshing = false;
    this.busy.set(false);
    this.loading.set(false);
    this.queue.set(null);
    this.refreshFailed.set(false);
    this.closeSheet();
    this.successToast?.dismiss();
    this.successToast = null;
    if (isModuleOff(error)) {
      this.loadError.set(null);
    } else {
      this.loadError.set(problemMessage(error, LOAD_ERROR));
      if (focusWasInside) {
        afterNextRender(
          () => this.host.nativeElement.querySelector<HTMLElement>('.notice.err')?.focus(),
          { injector: this.injector },
        );
      }
    }
    this.refused.emit({ error, focusWasInside });
  }

  private closeSheet(): void {
    const sheet = this.sheet;
    this.sheet = null;
    sheet?.close();
  }

  private apply(queue: EmergencyQueue): void {
    this.retried = false;
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

  /** A toast for an action the server accepted; kept so that a later refusal can dismiss it. */
  private successMessage(message: string): void {
    this.successToast = this.snackBar.open(message, 'Close', { duration: 5000 });
  }

  protected onRegistered(queue: EmergencyQueue): void {
    if (this.denied) {
      return;
    }
    this.apply(queue);
    const acted = queue.actedCase;
    if (isFullCase(acted)) {
      this.successMessage(registeredToast(acted));
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
    const at = this.epoch;
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (queue) => {
        if (at !== this.epoch) {
          return;
        }
        this.busy.set(false);
        this.apply(queue);
        this.successMessage(toast(isFullCase(queue.actedCase) ? queue.actedCase : item));
      },
      error: (error: unknown) => {
        if (at !== this.epoch) {
          return;
        }
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
    if (isRefusal(error)) {
      // The action's 403 may be a narrower role; the read that follows decides what to clear (SAH-40).
      this.refresh();
    }
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
    const at = this.epoch;
    this.admissions.options(item.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (options) => {
        if (at !== this.epoch) {
          return;
        }
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
        if (at !== this.epoch) {
          return;
        }
        this.busy.set(false);
        this.fail(error);
      },
    });
  }

  private openSheet(item: EmergencyCase, options: AdmitDialogData['options']): void {
    const sheet = this.dialog.open<AdmitDialogComponent, AdmitDialogData, AdmissionDetail | null>(
      AdmitDialogComponent,
      {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        data: { emergencyCaseId: item.id, options, onCaseClosed: () => this.refresh() },
      },
    );
    this.sheet = sheet;
    sheet
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((admission) => {
        if (this.sheet === sheet) {
          this.sheet = null;
        }
        if (admission) {
          this.admitted.emit(admission);
          this.refresh();
        }
      });
  }
}
