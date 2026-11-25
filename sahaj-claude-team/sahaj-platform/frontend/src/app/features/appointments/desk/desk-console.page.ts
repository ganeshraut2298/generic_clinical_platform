import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { ShellStore } from '../../../core/shell/shell.store';
import { addDays, formatTimeOfInstant, istToday } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { WHATSAPP_NOT_IN_PHASE } from '../../../shared/whatsapp';
import { canEditBookingRules, canEditSlotSettings, canManageAppointments } from '../appointments-role.util';
import { DeskApi } from './desk.api';
import {
  ALL_DEPARTMENTS,
  DeskBoardCard,
  DeskBookedSummary,
  DeskDaySlots,
  DeskFollowUpToBook,
  DeskPanelMode,
  DeskPickContext,
  DeskProblem,
  DeskSnapshot,
  NO_PICK_CONTEXT,
  deskProblem,
  deskRuleChips,
  departmentOptions,
  filterBoardCards,
  filterRailDoctors,
  headerDateTime,
} from './desk.model';
import { DeskActionPanelComponent } from './desk-action-panel.component';
import { DeskBoardScope, DeskFlowBoardComponent } from './desk-flow-board.component';
import { DeskDoctorRailComponent } from './desk-doctor-rail.component';
import { DeskSlotFinderComponent } from './desk-slot-finder.component';

/** The console refreshes this often while the tab is visible (ADR-032 D2). */
export const DESK_POLL_MS = 20_000;
const CLOCK_MS = 30_000;
/** With no keyboard, mouse or touch input for this long the console pauses behind a Continue button (DEC-098 F2). */
export const DESK_IDLE_MS = 15 * 60_000;
const ACTIVITY_EVENTS = ['keydown', 'mousedown', 'mousemove', 'wheel', 'touchstart', 'pointerdown'] as const;

/**
 * Front desk console, read side (SAH-90): header and counters, doctor rail, slot finder, flow
 * board, action panel (Idle) and the booking-rules footer. One polled snapshot feeds everything.
 */
@Component({
  selector: 'app-desk-console',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    RouterLink,
    IconComponent,
    DeskDoctorRailComponent,
    DeskSlotFinderComponent,
    DeskFlowBoardComponent,
    DeskActionPanelComponent,
  ],
  styleUrl: './desk-console.page.scss',
  templateUrl: './desk-console.page.html',
})
export class DeskConsolePage implements OnInit {
  private readonly api = inject(DeskApi);
  private readonly store = inject(ShellStore);
  private readonly doc = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly whatsappNote = WHATSAPP_NOT_IN_PHASE;

  protected readonly snapshot = signal<DeskSnapshot | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly refreshFailed = signal(false);
  /** Paused after 15 minutes without input: polling stopped and nothing about patients is on screen. */
  protected readonly idle = signal(false);

  protected readonly query = signal('');
  protected readonly department = signal(ALL_DEPARTMENTS);
  protected readonly scope = signal<DeskBoardScope>('all');
  protected readonly selectedId = signal<string | null>(null);
  protected readonly selectedDate = signal<string>(istToday());
  protected readonly panelMode = signal<DeskPanelMode>({ kind: 'idle' });

  protected readonly slots = signal<DeskDaySlots | null>(null);
  protected readonly slotsLoading = signal(false);
  protected readonly slotsError = signal<string | null>(null);

  protected readonly now = signal(new Date());

  /** A write is running anywhere on the page (card action, book panel or follow panel): one write at a time (SAH-92). */
  protected readonly actionBusy = signal(false);
  protected readonly actionError = signal<string | null>(null);

  protected readonly role = computed(() => this.store.me()?.role);
  protected readonly canManage = computed(() => canManageAppointments(this.role()));
  protected readonly canSlotSettings = computed(() => canEditSlotSettings(this.role()));
  protected readonly canRules = computed(() => canEditBookingRules(this.role()));
  protected readonly clinicName = computed(() => this.store.me()?.clinic?.name ?? '');

  protected readonly today = computed(() => this.snapshot()?.today ?? istToday(this.now()));
  protected readonly headerWhen = computed(() => headerDateTime(istToday(this.now()), formatTimeOfInstant(this.now())));

  protected readonly allDoctors = computed(() => this.snapshot()?.doctors.items ?? []);
  protected readonly departments = computed(() => departmentOptions(this.allDoctors()));
  protected readonly railDoctors = computed(() => filterRailDoctors(this.allDoctors(), this.department(), this.query()));
  protected readonly selectedDoctor = computed(
    () => this.allDoctors().find((d) => d.doctorMembershipId === this.selectedId()) ?? null,
  );
  protected readonly boardCards = computed(() =>
    filterBoardCards(this.snapshot()?.board.cards ?? [], this.scope(), this.selectedId(), this.query()),
  );
  protected readonly ruleChips = computed(() => {
    const rules = this.snapshot()?.bookingRules;
    return rules ? deskRuleChips(rules) : [];
  });
  protected readonly followUps = computed<readonly DeskFollowUpToBook[]>(() => this.snapshot()?.followUpsToBook.items ?? []);

  protected readonly counters = computed(() => {
    const c = this.snapshot()?.counters;
    return [
      { label: 'Booked', value: c?.booked ?? 0 },
      { label: 'Waiting', value: c?.waiting ?? 0 },
      { label: 'In consult', value: c?.inConsult ?? 0 },
      { label: 'Done', value: c?.done ?? 0 },
      { label: 'Free today', value: c?.freeToday ?? 0 },
    ];
  });

  protected readonly followDoctor = computed(() => {
    const mode = this.panelMode();
    return mode.kind === 'follow' ? (this.allDoctors().find((d) => d.doctorMembershipId === mode.doctorMembershipId) ?? null) : null;
  });

  protected readonly pickedTime = computed(() => {
    const mode = this.panelMode();
    return mode.kind === 'book' && mode.doctorMembershipId === this.selectedId() && mode.date === this.selectedDate()
      ? mode.time
      : null;
  });

  private inFlight = false;
  /** A write finished while a read was running: read again as soon as that one ends. */
  private refreshQueued = false;
  private slotsSeq = 0;
  private lastActivity = Date.now();
  private idleTimer: ReturnType<typeof setTimeout> | null = null;
  /** After a Rebook, Move or Find slot the finder jumps to the doctor's first day with free slots when the day has none. */
  private advanceToFreeDay = false;

  ngOnInit(): void {
    this.refresh();

    const poll = setInterval(() => {
      if (this.isVisible() && !this.idle()) {
        this.refresh();
      }
    }, DESK_POLL_MS);
    const clock = setInterval(() => this.now.set(new Date()), CLOCK_MS);
    const onVisibility = () => {
      if (this.isVisible() && !this.idle()) {
        this.refresh();
      }
    };
    this.doc.addEventListener('visibilitychange', onVisibility);
    const onActivity = () => {
      this.lastActivity = Date.now();
    };
    for (const name of ACTIVITY_EVENTS) {
      this.doc.addEventListener(name, onActivity, { passive: true });
    }
    this.armIdleTimer(DESK_IDLE_MS);
    this.destroyRef.onDestroy(() => {
      clearInterval(poll);
      clearInterval(clock);
      this.doc.removeEventListener('visibilitychange', onVisibility);
      for (const name of ACTIVITY_EVENTS) {
        this.doc.removeEventListener(name, onActivity);
      }
      if (this.idleTimer !== null) {
        clearTimeout(this.idleTimer);
      }
    });
  }

  /** Continue after the idle pause: show the console again and read it at once. */
  protected resume(): void {
    this.lastActivity = Date.now();
    this.idle.set(false);
    this.armIdleTimer(DESK_IDLE_MS);
    this.refresh();
  }

  private armIdleTimer(delay: number): void {
    this.idleTimer = setTimeout(() => {
      const left = DESK_IDLE_MS - (Date.now() - this.lastActivity);
      if (left > 0) {
        this.armIdleTimer(left);
        return;
      }
      this.pause();
    }, delay);
  }

  /** Nothing about a patient stays on screen while paused: the search text and the open panel are cleared. */
  private pause(): void {
    this.idleTimer = null;
    this.idle.set(true);
    this.query.set('');
    this.panelMode.set({ kind: 'idle' });
    this.actionError.set(null);
  }

  /** Refresh on demand, for the poll, tab return, "Try again" and after any action (SAH-91 to SAH-93). */
  refresh(afterWrite = false): void {
    if (this.inFlight) {
      this.refreshQueued = afterWrite || this.refreshQueued;
      return;
    }
    this.keepDateInWindow();
    this.inFlight = true;
    this.api.getSnapshot({ doctorMembershipId: this.selectedId(), date: this.selectedDate() }).subscribe({
      next: (snap) => {
        this.inFlight = false;
        this.snapshot.set(snap);
        this.loading.set(false);
        this.loadError.set(null);
        this.refreshFailed.set(false);
        this.applySnapshotSlots(snap);
        this.runQueuedRefresh();
      },
      error: () => {
        this.inFlight = false;
        this.loading.set(false);
        if (this.snapshot()) {
          this.refreshFailed.set(true);
        } else {
          this.loadError.set('We could not load the front desk console. Try again.');
        }
        this.runQueuedRefresh();
      },
    });
  }

  /** The console can stay open past midnight: a day before today or beyond the 7-day window goes back to today. */
  private keepDateInWindow(): void {
    const today = istToday();
    const date = this.selectedDate();
    if (date < today || date > addDays(today, 6)) {
      this.selectedDate.set(today);
    }
  }

  protected setQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  protected setDepartment(value: string): void {
    this.department.set(value);
  }

  protected setScope(scope: DeskBoardScope): void {
    this.scope.set(scope);
  }

  protected selectDoctor(id: string): void {
    this.selectedId.set(id);
    this.selectedDate.set(this.today());
    const mode = this.panelMode();
    // Moving an appointment or booking a follow-up keeps its context when another doctor is chosen in the rail.
    if ((mode.kind === 'pick' || mode.kind === 'book') && (mode.reschedule || mode.followUp)) {
      this.panelMode.set({ kind: 'pick', doctorMembershipId: id, reschedule: mode.reschedule, followUp: mode.followUp });
    } else {
      this.panelMode.set({ kind: 'idle' });
    }
    this.loadSlots();
  }

  protected selectDay(date: string): void {
    this.selectedDate.set(date);
    this.loadSlots();
  }

  protected pickSlot(time: string): void {
    const id = this.selectedId();
    if (!this.canManage() || !id) {
      return;
    }
    const mode = this.panelMode();
    const context: DeskPickContext =
      mode.kind === 'pick' || mode.kind === 'book' ? { reschedule: mode.reschedule, followUp: mode.followUp } : NO_PICK_CONTEXT;
    const inside = (this.slots()?.groups ?? []).some((g) => g.slots.some((s) => s.time === time && s.insideCutOff));
    this.panelMode.set({ kind: 'book', doctorMembershipId: id, date: this.selectedDate(), time, insideCutOff: inside, ...context });
  }

  protected findSlot(item: DeskFollowUpToBook): void {
    if (!this.canManage()) {
      return;
    }
    this.startPick(item.doctorMembershipId, { reschedule: null, followUp: item });
  }

  protected closePanel(): void {
    this.panelMode.set({ kind: 'idle' });
  }

  protected retrySlots(): void {
    this.loadSlots();
  }

  /** A card button: check in, rebook, move, start, complete or set follow-up. */
  protected onCardAction(card: DeskBoardCard): void {
    if (!this.canManage() || this.actionBusy()) {
      return;
    }
    switch (card.primaryAction) {
      case 'CHECK_IN':
        this.checkIn(card);
        break;
      case 'REBOOK':
      case 'MOVE_TO_NEW_SLOT':
        this.actionError.set(null);
        this.startPick(card.doctorMembershipId, { reschedule: card, followUp: null });
        break;
      case 'START_CONSULTATION':
      case 'COMPLETE_VISIT':
        this.moveStage(card);
        break;
      case 'SET_FOLLOW_UP':
        this.actionError.set(null);
        this.openFollowUp(card);
        break;
      case 'NONE':
        break;
    }
  }

  protected onMarkNoShow(card: DeskBoardCard): void {
    if (!this.canManage() || !card.appointmentId || this.actionBusy()) {
      return;
    }
    this.write(this.api.markNoShow(card.appointmentId), () => undefined);
  }

  /** The token on a card opens the token panel with the current numbers (any role). */
  protected onTokenOpen(card: DeskBoardCard): void {
    if (card.visitId) {
      this.actionError.set(null);
      this.panelMode.set({ kind: 'token', visitId: card.visitId, panel: null, issued: false });
    }
  }

  protected onBooked(summary: DeskBookedSummary): void {
    this.panelMode.set({ kind: 'booked', ...summary });
    this.refresh(true);
    this.loadSlots();
  }

  /** The book and follow panels report their writes here so every button waits for the one running. */
  protected onPanelBusy(busy: boolean): void {
    this.actionBusy.set(busy);
  }

  protected onFollowSaved(): void {
    this.panelMode.set({ kind: 'idle' });
    this.refresh(true);
  }

  /** A panel write failed: refresh what the problem says changed, and keep the panel open. */
  protected onPanelFailed(problem: DeskProblem): void {
    if (problem.slotTaken) {
      this.loadSlots();
    }
    if (problem.refreshBoard || problem.slotTaken) {
      this.refresh(true);
    }
  }

  private checkIn(card: DeskBoardCard): void {
    if (!card.appointmentId) {
      return;
    }
    this.write(
      this.api.checkIn(card.appointmentId),
      (result) => this.panelMode.set({ kind: 'token', visitId: result.tokenPanel.visitId, panel: result.tokenPanel, issued: true }),
      (error) => {
        // Someone else checked this patient in: show the message and the token they got.
        const visitId = error instanceof ApiError ? error.problem.visitId : undefined;
        if (visitId) {
          this.panelMode.set({ kind: 'token', visitId, panel: null, issued: false });
        }
      },
    );
  }

  private moveStage(card: DeskBoardCard): void {
    if (!card.visitId || !card.stage || !card.nextStage) {
      return;
    }
    const completing = card.primaryAction === 'COMPLETE_VISIT';
    this.write(this.api.moveStage(card.visitId, card.nextStage, card.stage), () => {
      if (completing) {
        this.openFollowUp(card);
      }
    });
  }

  private openFollowUp(card: DeskBoardCard): void {
    if (!card.visitId) {
      return;
    }
    this.panelMode.set({
      kind: 'follow',
      visitId: card.visitId,
      patientName: card.patientName,
      doctorMembershipId: card.doctorMembershipId,
      doctorName: card.doctorName,
    });
  }

  /** Pick a slot: the doctor is selected, the day is today, and the finder jumps to the first free day when today has none. */
  private startPick(doctorMembershipId: string, context: DeskPickContext): void {
    this.selectedId.set(doctorMembershipId);
    this.selectedDate.set(this.today());
    this.panelMode.set({ kind: 'pick', doctorMembershipId, ...context });
    this.advanceToFreeDay = true;
    this.loadSlots();
  }

  /** One write at a time. On success the console is read again; on failure the plain message is shown. */
  private write<T>(call: Observable<T>, onSuccess: (result: T) => void, onFailure?: (error: unknown) => void): void {
    if (this.actionBusy()) {
      return;
    }
    this.actionBusy.set(true);
    this.actionError.set(null);
    call.subscribe({
      next: (result) => {
        this.actionBusy.set(false);
        onSuccess(result);
        this.refresh(true);
      },
      error: (error: unknown) => {
        this.actionBusy.set(false);
        const problem = deskProblem(error);
        this.actionError.set(problem.message);
        onFailure?.(error);
        if (problem.refreshBoard) {
          this.refresh(true);
        }
      },
    });
  }

  private runQueuedRefresh(): void {
    if (this.refreshQueued) {
      this.refreshQueued = false;
      this.refresh();
    }
  }

  private isVisible(): boolean {
    return this.doc.visibilityState === 'visible';
  }

  /** The snapshot's `selected` keeps the finder current between clicks, unless a day load is running. */
  private applySnapshotSlots(snap: DeskSnapshot): void {
    this.refreshRescheduleVersion(snap);
    if (this.selectedId() === null) {
      const first = snap.doctors.items[0];
      if (first) {
        this.selectedId.set(first.doctorMembershipId);
        this.loadSlots();
      }
      return;
    }
    const selected = snap.selected;
    if (
      selected &&
      !this.slotsLoading() &&
      selected.doctorMembershipId === this.selectedId() &&
      selected.date === this.selectedDate()
    ) {
      this.slots.set(selected);
      this.slotsError.set(null);
    }
  }

  /** The moved appointment's version comes from the newest snapshot; a card with no version is an error, never version 0. */
  private refreshRescheduleVersion(snap: DeskSnapshot): void {
    const mode = this.panelMode();
    if ((mode.kind !== 'pick' && mode.kind !== 'book') || !mode.reschedule?.appointmentId) {
      return;
    }
    const id = mode.reschedule.appointmentId;
    const card = snap.board.cards.find((c) => c.appointmentId === id);
    if (!card) {
      return;
    }
    if (card.appointmentVersion === null || card.appointmentVersion === undefined) {
      this.actionError.set('This appointment could not be checked. The board now shows the latest.');
      this.panelMode.set({ kind: 'idle' });
      return;
    }
    if (card.appointmentVersion !== mode.reschedule.appointmentVersion) {
      this.panelMode.set({ ...mode, reschedule: { ...mode.reschedule, appointmentVersion: card.appointmentVersion } });
    }
  }

  private loadSlots(): void {
    const id = this.selectedId();
    if (!id) {
      return;
    }
    const seq = ++this.slotsSeq;
    const advance = this.advanceToFreeDay;
    this.advanceToFreeDay = false;
    this.slotsLoading.set(true);
    this.slotsError.set(null);
    this.api.getDaySlots(id, this.selectedDate()).subscribe({
      next: (result) => {
        if (seq !== this.slotsSeq) {
          return;
        }
        if (advance && result.freeCount === 0 && result.nextFreeDate) {
          this.selectedDate.set(result.nextFreeDate);
          this.loadSlots();
          return;
        }
        this.slots.set(result);
        this.slotsLoading.set(false);
      },
      error: () => {
        if (seq !== this.slotsSeq) {
          return;
        }
        this.slotsLoading.set(false);
        this.slotsError.set('We could not load the slots. Try again.');
      },
    });
  }
}
