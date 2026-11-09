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
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, Subscription } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { MedicineRoundComponent } from './medicine-round.component';
import { PharmacistStore } from '../pharmacist/pharmacist.store';
import { focusIsLost, isRefusal } from '../ipd-refusal';
import { PharmacistPanelComponent } from '../pharmacist/pharmacist-panel.component';
import { PharmacistTileComponent } from '../pharmacist/pharmacist-tile.component';
import { ResponseSheetComponent, ResponseSheetData } from './response-sheet.component';
import { SafetyApi } from './safety.api';
import { startVisiblePoll } from './visible-poll';
import {
  ACTION_ERROR,
  AUDIT_TITLE,
  ClinicalEvent,
  ClinicalEventPage,
  EVENTS_ERROR,
  EscalationActionResult,
  EscalationRow,
  FIRST_PAGE,
  LOAD_ERROR,
  NEXT_PAGE,
  NOT_ADMITTED,
  NO_ESCALATIONS,
  NO_EVENTS,
  PANEL_SUBTITLE,
  PANEL_TITLE,
  SHOW_EARLIER,
  SafetyBoard,
  SafetyCounts,
  YOUR_PATIENTS_TEXT,
  ackToast,
  auditSubtitle,
  callToast,
  closedToast,
  DOSES_TILE_TITLE,
  detailLine,
  dosesTileNumber,
  dosesTileText,
  eventLine,
  eventTime,
  kindIcon,
  mergeEvents,
  noticeBody,
  noticeHead,
  openTileText,
  raisedLine,
  riskWord,
  staleBoardText,
  statusPill,
} from './safety.model';

/** Same interval as the Emergency queue (DEC-015, OQ-33-10). */
export const SAFETY_REFRESH_MS = 60_000;

/** The heading of the panel "Early warning escalations": focus goes here after a close. */
export const ESCALATIONS_HEADING_ID = 'sf-esc-h';

/**
 * The Safety tab (SAH-33), mockup `ipSafety`: the red notice, the tiles, the panel "Early warning
 * escalations" and the panel "Clinical audit trail". Buttons follow the row flags the server sends
 * (`canAcknowledge`, `canRespond`, `canCallSenior`); the server checks the role again on every call.
 *
 * - Every "ago" and the overdue state come from the server (`asOf`, minute counts); the device clock
 *   is never read.
 * - The board reloads every 60 seconds and after each action; a failed reload keeps the last data and
 *   shows "Could not refresh. Showing data from <time>.". Loading shows only on the first load.
 * - One action runs at a time: every row button is off meanwhile (double-submit guard). A row is
 *   never shown as changed until the server has answered.
 * - A 409 (`escalation-state`, `escalation-changed`) or 404 shows the server's message as a notice and
 *   reloads the board. A replayed 200 is treated as a success.
 * - Names and the plan are on screen only: nothing is logged or stored.
 */
@Component({
  selector: 'app-safety',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    IconComponent,
    PharmacistTileComponent,
    PharmacistPanelComponent,
    MedicineRoundComponent,
  ],
  styleUrl: './safety.component.scss',
  templateUrl: './safety.component.html',
})
export class SafetyComponent implements OnInit {
  private readonly api = inject(SafetyApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  private readonly doc = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  /** Provided by the IPD page; absent when the board is used alone. Cleared when the board is refused. */
  private readonly pharmacist = inject(PharmacistStore, { optional: true });
  private readonly roundPanel = viewChild(MedicineRoundComponent);

  /** Counts of every board answer: the tab number and the banner of the page follow them. */
  readonly counts = output<SafetyCounts>();
  /** SAH-40: the board was refused (403); the page drops the counts it holds until Try again. */
  readonly refused = output<void>();
  /**
   * SAH-31: the owner's Safety tab also has the tile "Pharmacist checks" and the panel "Pharmacist
   * check" (mockup: owner only). The page provides the `PharmacistStore` they read.
   */
  readonly showPharmacist = input(false);

  protected readonly board = signal<SafetyBoard | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** The last reload failed: the old board stays and a small warning shows. */
  protected readonly refreshFailed = signal(false);
  /** One action runs at a time; every row button is off meanwhile. */
  protected readonly busy = signal(false);
  /** The message of a refused action (409, 404, 403 ...), kept until the next action or Dismiss. */
  protected readonly actionNotice = signal<string | null>(null);

  protected readonly events = signal<ClinicalEvent[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly eventsAsOf = signal<string | null>(null);
  protected readonly eventsLoading = signal(false);
  protected readonly eventsError = signal<string | null>(null);

  protected readonly panelTitle = PANEL_TITLE;
  protected readonly panelSubtitle = PANEL_SUBTITLE;
  protected readonly noEscalations = NO_ESCALATIONS;
  protected readonly auditTitle = AUDIT_TITLE;
  protected readonly noEvents = NO_EVENTS;
  protected readonly showEarlier = SHOW_EARLIER;
  protected readonly yourPatientsText = YOUR_PATIENTS_TEXT;
  protected readonly dosesTitle = DOSES_TILE_TITLE;
  protected readonly dosesNumber = dosesTileNumber;
  protected readonly dosesText = dosesTileText;
  protected readonly pill = statusPill;
  protected readonly raised = raisedLine;
  protected readonly detail = detailLine;
  protected readonly risk = riskWord;
  protected readonly head = noticeHead;
  protected readonly body = noticeBody;
  protected readonly tileText = openTileText;
  protected readonly subtitleOf = auditSubtitle;
  protected readonly line = eventLine;
  protected readonly icon = kindIcon;

  protected readonly staleText = computed(() => staleBoardText(this.board()?.asOf));
  protected readonly time = (event: ClinicalEvent): string =>
    eventTime(event.at, this.eventsAsOf() ?? event.at);

  /** G-2: the board was refused (403); the 60-second reload reads nothing until Try again (DEC-025, SAH-40). */
  private denied = false;
  private boardSub: Subscription | null = null;
  private eventsSub: Subscription | null = null;
  private moreSub: Subscription | null = null;
  /** The open Response sheet: closed at once when the board is refused (no patient name stays). */
  private responseSheet: { close(): void } | null = null;
  /** Try again was pressed from inside the board: the focus may fall to the body when its button is replaced. */
  private retried = false;

  ngOnInit(): void {
    this.load();
    startVisiblePoll(this.doc, SAFETY_REFRESH_MS, () => this.refresh(), this.destroyRef);
    this.destroyRef.onDestroy(() => {
      this.boardSub?.unsubscribe();
      this.eventsSub?.unsubscribe();
      this.moreSub?.unsubscribe();
      this.closeResponseSheet();
    });
  }

  private closeResponseSheet(): void {
    const sheet = this.responseSheet;
    this.responseSheet = null;
    sheet?.close();
  }

  /** First load, and "Try again": shows Loading. */
  protected load(): void {
    this.retried = this.host.nativeElement.contains(this.doc.activeElement);
    this.denied = false;
    this.loading.set(true);
    this.loadError.set(null);
    this.fetchBoard(
      () => this.loading.set(false),
      (error) => {
        this.loading.set(false);
        if (isRefusal(error)) {
          this.refuse(error);
        } else {
          this.loadError.set(problemMessage(error, LOAD_ERROR));
        }
      },
    );
    this.fetchEvents(false);
  }

  /** The 60-second reload: no Loading text; a failure keeps what is on screen. */
  protected refresh(): void {
    if (this.loading() || this.denied) {
      return;
    }
    this.fetchBoard(
      () => undefined,
      (error) => this.reloadFailed(error),
    );
    this.fetchEvents(true);
  }

  /**
   * A reload (not the first load) failed. A 403 (role changed, removed from the clinic, IPD off) clears
   * the board and the audit trail and shows the refusal as the first load does; only a network error or
   * a 5xx keeps the old board with "Could not refresh" (DEC-025 G-2).
   */
  private reloadFailed(error: unknown): void {
    if (isRefusal(error)) {
      this.refuse(error);
      return;
    }
    this.refreshFailed.set(true);
  }

  /**
   * The board was refused (403, forbidden or module-off), on the first load or on a reload (SAH-40, D-1).
   * Nothing patient-related stays: the board, the audit trail, the pharmacist rows held by the page and
   * every open sheet of this tab (Response here; Give and Verify or Reject close with the panels that
   * opened them) go. Reads stop until Try again. Focus goes to the notice when it was on the board or
   * in a sheet.
   */
  private refuse(error: unknown): void {
    const active = this.doc.activeElement;
    const focusLost =
      focusIsLost(this.host.nativeElement, this.doc, this.retried) ||
      (!!active && active.closest('.cdk-overlay-container') !== null);
    this.retried = false;
    this.denied = true;
    this.boardSub?.unsubscribe();
    this.eventsSub?.unsubscribe();
    this.moreSub?.unsubscribe();
    this.closeResponseSheet();
    this.snackBar.dismiss();
    this.pharmacist?.clear();
    this.board.set(null);
    this.events.set([]);
    this.nextCursor.set(null);
    this.eventsLoading.set(false);
    this.refreshFailed.set(false);
    this.actionNotice.set(null);
    this.loadError.set(problemMessage(error, LOAD_ERROR));
    this.refused.emit();
    if (focusLost) {
      afterNextRender(
        () => this.host.nativeElement.querySelector<HTMLElement>('.notice.err')?.focus(),
        { injector: this.injector },
      );
    }
  }

  private fetchBoard(done: () => void, failed: (error: unknown) => void): void {
    this.boardSub?.unsubscribe();
    this.boardSub = this.api.board().subscribe({
      next: (board) => {
        this.retried = false;
        this.refreshFailed.set(false);
        this.loadError.set(null);
        this.board.set(board);
        this.counts.emit(board.counts);
        done();
      },
      error: failed,
    });
  }

  /** First page (14). While older entries are showing, the new entries are merged on top. */
  private fetchEvents(quiet: boolean): void {
    this.eventsSub?.unsubscribe();
    if (!quiet) {
      this.eventsError.set(null);
    }
    this.eventsSub = this.api.events(FIRST_PAGE).subscribe({
      next: (page) => {
        this.eventsAsOf.set(page.asOf);
        const merged = mergeEvents({ items: this.events(), nextCursor: this.nextCursor() }, page);
        this.events.set(merged.items);
        this.nextCursor.set(merged.nextCursor);
        this.eventsError.set(null);
      },
      error: (error: unknown) => {
        if (!quiet) {
          this.eventsError.set(problemMessage(error, EVENTS_ERROR));
        }
      },
    });
  }

  /** "Show earlier entries": 50 more, from the cursor of the last page. */
  protected loadMore(): void {
    const cursor = this.nextCursor();
    if (cursor === null || this.eventsLoading()) {
      return;
    }
    this.eventsLoading.set(true);
    this.eventsError.set(null);
    this.moreSub = this.api
      .events(NEXT_PAGE, cursor)
      .subscribe({
        next: (page: ClinicalEventPage) => {
          this.eventsLoading.set(false);
          const known = new Set(this.events().map((e) => e.id));
          this.events.set([...this.events(), ...page.items.filter((e) => !known.has(e.id))]);
          this.nextCursor.set(page.nextCursor);
        },
        error: (error: unknown) => {
          this.eventsLoading.set(false);
          this.eventsError.set(problemMessage(error, EVENTS_ERROR));
        },
      });
  }

  /**
   * The button that opened the Response sheet is gone once the escalation is closed, so focus goes
   * to the panel heading (tabindex -1). One tick later: the dialog gives focus back to its opener
   * while it closes, and that must not win.
   */
  private focusHeading(): void {
    setTimeout(() => this.doc.getElementById(ESCALATIONS_HEADING_ID)?.focus(), 0);
  }

  /**
   * A Give from the Medicine round was answered (dose recorded, sheet refused, stale slot): the whole
   * board is read again, the user stays on this tab, and the focus goes to the row's Give button, or
   * to the panel heading once the row is gone. It moves when the new rows are drawn, also when the
   * reload fails (the old rows stay).
   */
  protected roundAnswered(): void {
    if (this.denied) {
      return;
    }
    this.actionNotice.set(null);
    const focus = () => afterNextRender(() => this.roundPanel()?.restoreFocus(), { injector: this.injector });
    this.fetchBoard(focus, (error) => {
      this.reloadFailed(error);
      focus();
    });
    this.fetchEvents(true);
  }

  /** After an action or a saved sheet; never while refused (only Try again reads). */
  private reloadAll(): void {
    if (this.denied) {
      return;
    }
    this.fetchBoard(
      () => undefined,
      (error) => this.reloadFailed(error),
    );
    this.fetchEvents(true);
  }

  protected toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 6000 });
  }

  protected dismissNotice(): void {
    this.actionNotice.set(null);
  }

  protected acknowledge(row: EscalationRow): void {
    if (this.busy() || !row.canAcknowledge) {
      return;
    }
    this.run(this.api.acknowledge(row.id, row.version), (r) => ackToast(r.escalation.patientName));
  }

  protected callSenior(row: EscalationRow): void {
    if (this.busy() || !row.canCallSenior) {
      return;
    }
    this.run(
      this.api.callSenior(row.id, { version: row.version, seniorCalls: row.seniorCalls }),
      (r) => callToast(r.escalation.patientName),
    );
  }

  private run(
    call: Observable<EscalationActionResult>,
    toast: (result: EscalationActionResult) => string,
  ): void {
    this.busy.set(true);
    this.actionNotice.set(null);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.toast(toast(result));
        this.reloadAll();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failAction(error);
      },
    });
  }

  /** A state change on the server: say what happened and reload what is really on the board. */
  private failAction(error: unknown): void {
    if (error instanceof ApiError && (error.status === 409 || error.status === 404)) {
      this.actionNotice.set(
        problemMessage(error, error.status === 404 ? NOT_ADMITTED : ACTION_ERROR),
      );
      this.reloadAll();
      return;
    }
    this.actionNotice.set(problemMessage(error, ACTION_ERROR));
  }

  /** Record response: the sheet keeps the typed plan on every failure; the board reloads behind it on a 409. */
  protected openResponse(row: EscalationRow): void {
    const board = this.board();
    if (this.busy() || !board || !row.canRespond) {
      return;
    }
    const live = computed(() => this.board()?.escalations.find((e) => e.id === row.id) ?? null);
    const sheet = this.dialog.open<ResponseSheetComponent, ResponseSheetData, EscalationActionResult | undefined>(
      ResponseSheetComponent,
      {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        data: {
          row,
          actions: board.responseActions,
          live,
          onConflict: () => this.reloadAll(),
        },
      },
    );
    this.responseSheet = sheet;
    sheet
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        if (this.responseSheet === sheet) {
          this.responseSheet = null;
        }
        if (result) {
          this.actionNotice.set(null);
          this.toast(closedToast(result.escalation.patientName));
          this.reloadAll();
          this.focusHeading();
        }
      });
  }
}
