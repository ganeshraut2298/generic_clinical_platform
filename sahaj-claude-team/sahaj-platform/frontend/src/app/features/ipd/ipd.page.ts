import { DOCUMENT, NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, Subject, catchError, map, of, switchMap, tap } from 'rxjs';
import { ShellStore } from '../../core/shell/shell.store';
import { formatInrPaise } from '../../shared/format/inr';
import { IconComponent } from '../../shared/icon/icon.component';
import { PageHeadComponent } from '../../shared/ui/page-head.component';
import { problemMessage } from '../config/config-format';
import { IpdApi } from '../config/ipd/ipd.api';
import { IpdCharges } from '../config/ipd/ipd.model';
import {
  AdmissionDialogComponent,
  AdmissionDialogData,
} from './admission/admission-dialog.component';
import { goingHomeCount, goingHomeText } from './discharge.model';
import { AdmitDialogComponent, AdmitDialogData } from './admit/admit-dialog.component';
import { AdmittedPanelComponent, OpenAdmissionRequest } from './admitted/admitted-panel.component';
import { AdmittedViewState } from './admitted/admitted-view.state';
import { AdmissionsApi } from './admissions.api';
import { BedBoardComponent } from './board/bed-board.component';
import { EmergencyComponent, EmergencyRefusal } from './emergency/emergency.component';
import { EmrSheetComponent, EmrSheetData } from './emr/emr-sheet.component';
import {
  EmergencyCounts,
  tileNumber,
  tileText,
} from './emergency/emergency.model';
import { isModuleOff, isRefusal } from './ipd-refusal';
import { AdmissionDetail, AdmissionList, ipdLead } from './ipd.model';
import { NursingSheetComponent, NursingSheetData } from './nursing/nursing-sheet.component';
import { PharmacistStore } from './pharmacist/pharmacist.store';
import { PharmacistTabComponent } from './pharmacist/pharmacist-tab.component';
import { safetyTabCount } from './pharmacist/pharmacist.model';
import { SAFETY_REFRESH_MS, SafetyComponent } from './safety/safety.component';
import { SafetyApi } from './safety/safety.api';
import { startVisiblePoll } from './safety/visible-poll';
import {
  BANNER_TAIL,
  OPEN_BOARD,
  SAFETY_LABEL,
  SafetyCounts,
  bannerNames,
  sameCounts,
  staleBoardText,
} from './safety/safety.model';

type IpdTab = 'board' | 'admitted' | 'er' | 'safety';
const EMERGENCY_LEAD = 'Triage first. Admit, treat and release, or refer out.';
const LOAD_ERROR = 'We could not load beds and admissions. Try again.';

/** The heading of the lock notice: focus goes here when a refusal removes the control that had it. */
const LOCK_HEADING_ID = 'ipd-lock-h';

/**
 * Beds and admissions (SAH-23, SAH-24, SAH-28), mockup `vIpd`: four summary tiles (the second is
 * Emergency), then the Bed board, Admitted and Emergency tabs; the Admitted list has Open, the "Add
 * your wards and beds" panel, and the Admit patient button. The route `/app/emergency` opens this
 * page on the Emergency tab (mockup `VIEWS.emergency`); users without that menu item open the tab here. IPD off answers 403 `module-off`, which shows the lock notice (as the Staff page
 * does).
 */
@Component({
  selector: 'app-ipd',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    NgTemplateOutlet,
    RouterLink,
    IconComponent,
    BedBoardComponent,
    EmergencyComponent,
    SafetyComponent,
    PharmacistTabComponent,
    AdmittedPanelComponent,
    PageHeadComponent,
  ],
  providers: [PharmacistStore, AdmittedViewState],
  styleUrl: './ipd.page.scss',
  templateUrl: './ipd.page.html',
})
export class IpdPage implements OnInit {
  private readonly api = inject(AdmissionsApi);
  private readonly layoutApi = inject(IpdApi);
  private readonly safetyApi = inject(SafetyApi);
  protected readonly pharmacist = inject(PharmacistStore);
  private readonly shell = inject(ShellStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  private readonly doc = inject(DOCUMENT);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly goingHome = goingHomeCount;
  protected readonly goingHomeLine = goingHomeText;
  protected readonly safetyLabel = SAFETY_LABEL;
  protected readonly openBoardLabel = OPEN_BOARD;
  protected readonly bannerNames = bannerNames;
  protected readonly bannerTail = BANNER_TAIL;

  /** SAH-31 (ADR-009 D11): a PHARMACIST gets the Safety tab only, with the tile and the panel. */
  protected readonly isPharmacist = computed(() => this.shell.me()?.role === 'PHARMACIST');
  protected readonly tab = signal<IpdTab>(
    this.shell.me()?.role === 'PHARMACIST'
      ? 'safety'
      : this.route.snapshot.data['tab'] === 'er'
        ? 'er'
        : 'board',
  );
  private readonly board = viewChild(BedBoardComponent);
  private polling = false;
  /** A reload of the list is on its way (SAH-36): the 60-second timer does not start a second one. */
  private reloading = false;
  /** Every reload goes through here; a newer request cancels the older one, so the latest always wins (R36-3). */
  private readonly reloadRequests = new Subject<void>();

  protected readonly loading = signal(true);
  protected readonly locked = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly list = signal<AdmissionList | null>(null);
  /** SAH-36. A reload failed: the last answer stays on screen with "Could not refresh. Showing data from ...". */
  private readonly refreshFailed = signal(false);
  protected readonly staleText = computed(() =>
    this.refreshFailed() ? staleBoardText(this.list()?.asOf) : null,
  );
  private readonly charges = signal<IpdCharges | null>(null);
  private readonly alertPercent = signal<number | null>(null);

  /** Counts from the Emergency tab's last answer; they replace the ones of the list until it reloads. */
  private readonly liveCounts = signal<EmergencyCounts | null>(null);
  protected readonly emergency = computed(() => this.liveCounts() ?? this.list()?.emergency ?? null);
  /** The tab, tile number and header button need IPD to be writable; Read-only hides them. */
  protected readonly emergencyOn = computed(() => this.emergency()?.writable === true);
  /** Counts from the Safety board's last answer or the summary poll; they replace the list's until it reloads. */
  private readonly liveSafety = signal<SafetyCounts | null>(null);
  /** SAH-40: the Safety board was refused; its counts stay hidden until it answers again (Try again). */
  private readonly safetyRefused = signal(false);
  /** SAH-33 (mockup sfTabOn): the tab shows when the server sent `safety` and someone is admitted. */
  protected readonly safetyOn = computed(() => {
    const l = this.list();
    return !!l && l.safety != null && l.items.length > 0;
  });
  protected readonly safetyCounts = computed(() => this.liveSafety() ?? this.list()?.safety?.counts ?? null);
  /**
   * The number on the Safety tab (mockup `sfCount`): open escalations, the owner's orders waiting and
   * the late doses (SAH-35; due doses are not added); a pharmacist's is the orders waiting. The
   * server's counts only; the page adds nothing of its own.
   */
  protected readonly safetyTabNumber = computed(() => {
    const pharmacist = this.isPharmacist();
    const counts = pharmacist ? null : this.safetyCounts();
    if (this.pharmacist.refused() || this.safetyRefused()) {
      // SAH-40: after a refusal the counts are unknown; a partial sum would read as a real total.
      return 0;
    }
    return safetyTabCount(counts?.open, this.pharmacist.waiting(), counts?.dosesLate);
  });
  protected readonly tabs = computed<IpdTab[]>(() => [
    'board',
    'admitted',
    ...(this.emergencyOn() ? (['er'] as const) : []),
    ...(this.safetyOn() ? (['safety'] as const) : []),
  ]);
  protected readonly activeTab = computed<IpdTab>(() => {
    const tab = this.tab();
    if ((tab === 'er' && !this.emergencyOn()) || (tab === 'safety' && !this.safetyOn())) {
      return 'board';
    }
    return tab;
  });
  protected readonly tileNumber = computed(() => {
    const e = this.emergency();
    return e ? tileNumber(e) : '—';
  });
  protected readonly tileText = computed(() => {
    const e = this.emergency();
    return e ? tileText(e) : { text: '', critical: false };
  });
  protected readonly heading = computed(() =>
    this.activeTab() === 'er' ? 'Emergency' : 'Beds and admissions',
  );

  protected readonly isOwner = computed(() => this.shell.me()?.role === 'OWNER');
  private pharmacistSummarised = false;
  /** The sheets this page opened and that are still open: closed when the page locks or is refused (SAH-40). */
  private readonly openSheets = new Set<{ close(): void }>();
  /**
   * SAH-40 R-1: the list was refused (403 forbidden). Nothing reads or shows the list again, not even the
   * 60-second Safety poll on another tab, until Try again (`load()`).
   */
  private refused = false;
  protected readonly noBeds = computed(() => (this.list()?.bedsTotal ?? 0) === 0);
  /** Set once the Emergency tab is opened (route or click): the casualty queue works before beds exist. */
  private readonly erOpened = signal(this.tab() === 'er');
  /** Without beds only the setup notice shows, until the Emergency tab is opened (and writable). */
  protected readonly setupOnly = computed(
    () => this.noBeds() && !(this.erOpened() && this.emergencyOn()),
  );
  protected readonly occupancy = computed(() => {
    const l = this.list();
    return l && l.bedsTotal > 0 ? Math.round((l.bedsOccupied / l.bedsTotal) * 100) : 0;
  });

  /** "Beds and admissions · Bed and nursing charges post at midnight · deposit alert at 80% ..." */
  protected readonly lead = computed(() => {
    if (this.isPharmacist()) {
      return '';
    }
    return this.activeTab() === 'er'
      ? EMERGENCY_LEAD
      : ipdLead(this.charges()?.bedDayCut, this.alertPercent());
  });

  constructor() {
    this.reloadRequests
      .pipe(
        switchMap(() =>
          this.api.list().pipe(
            map((list) => ({ list, error: null as unknown })),
            catchError((error: unknown) => of({ list: null, error })),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((answer) => {
        // Only the newest request can answer (switchMap drops the older ones), so the flag is cleared once.
        this.reloading = false;
        if (answer.list === null) {
          if (isRefusal(answer.error)) {
            // G-2 (DEC-025): 403 (role changed, removed, IPD off): the old clinical data leaves the screen
            // and the notices of the first load show. Only network errors and 5xx keep the stale data.
            this.refuse(answer.error);
          } else {
            this.refreshFailed.set(true);
          }
          return;
        }
        this.list.set(answer.list);
        this.refreshFailed.set(false);
        this.liveCounts.set(null);
        this.liveSafety.set(null);
      });
    // IPD switched off is answered by the pharmacist calls too (403 module-off): same lock notice,
    // and nothing of the page stays in memory behind it (SAH-40).
    effect(() => {
      if (this.pharmacist.moduleOff()) {
        untracked(() => this.lock());
      }
    });
  }

  ngOnInit(): void {
    if (this.isPharmacist()) {
      // The admissions list and the escalations board are not for this role (they carry bills).
      this.loading.set(false);
    } else {
      this.load();
    }
    startVisiblePoll(this.doc, SAFETY_REFRESH_MS, () => this.pollList(), this.destroyRef);
    startVisiblePoll(this.doc, SAFETY_REFRESH_MS, () => this.pollPharmacist(), this.destroyRef);
  }

  /**
   * Counts only, every 60 seconds and not while the browser tab is hidden (reuse of the Safety
   * poll's timer): the number on the Safety tab follows the orders waiting, and the panel reloads
   * itself when the count or the change token differs and it is on screen.
   */
  private pollPharmacist(): void {
    if (this.isPharmacist() || (this.isOwner() && this.safetyOn())) {
      this.pharmacist.pollSummary();
    }
  }

  protected load(): void {
    this.refused = false;
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.list.set(list);
          this.liveCounts.set(null);
          this.liveSafety.set(null);
          this.loading.set(false);
          this.loadLeadParts();
          if (this.isOwner() && this.safetyOn() && !this.pharmacistSummarised) {
            // The page opens: one counts-only call fills the tab number before the tab is opened.
            this.pharmacistSummarised = true;
            this.pharmacist.pollSummary();
          }
        },
        error: (error: unknown) => {
          if (isModuleOff(error)) {
            this.locked.set(true);
          } else {
            // A 403 on the first load or on Try again: the same hold as a refused reload (R-1).
            this.refused = isRefusal(error);
            this.loadError.set(problemMessage(error, LOAD_ERROR));
          }
          this.loading.set(false);
        },
      });
  }

  /**
   * A 403 on a read: clear what was shown (also the pharmacist rows held for this page), then the lock
   * notice (IPD off) or the error notice of the first load.
   */
  private refuse(error: unknown, focusWasInside = false): void {
    if (isModuleOff(error)) {
      this.lock(focusWasInside);
      return;
    }
    this.refused = true;
    this.clearData();
    this.loadError.set(problemMessage(error, LOAD_ERROR));
  }

  private clearData(): void {
    this.list.set(null);
    this.liveCounts.set(null);
    this.liveSafety.set(null);
    this.safetyRefused.set(false);
    this.refreshFailed.set(false);
    this.pharmacistSummarised = false;
    this.pharmacist.clear();
    this.closeSheets();
    this.snackBar.dismiss();
  }

  /** Closes the sheets this page opened (Admission, Nursing, EMR, Admit): they show names and bills. */
  private closeSheets(): void {
    const sheets = [...this.openSheets];
    this.openSheets.clear();
    for (const sheet of sheets) {
      sheet.close();
    }
  }

  /** The answer of a sheet this page opened; the sheet is tracked until it closes. */
  private track<T>(ref: { close(): void; afterClosed(): Observable<T | undefined> }): Observable<T | undefined> {
    this.openSheets.add(ref);
    return ref.afterClosed().pipe(
      tap(() => this.openSheets.delete(ref)),
      takeUntilDestroyed(this.destroyRef),
    );
  }

  /** IPD is off: clear everything, then the lock notice; focus moves to its heading if a control lost it. */
  private lock(focusLostBefore = false): void {
    const focusWasInside =
      focusLostBefore ||
      this.host.nativeElement.contains(this.doc.activeElement) ||
      this.openSheets.size > 0;
    this.clearData();
    this.locked.set(true);
    if (focusWasInside) {
      afterNextRender(() => this.doc.getElementById(LOCK_HEADING_ID)?.focus(), {
        injector: this.injector,
      });
    }
  }

  /** Only decorates the lead line; a failure leaves the plain title. */
  private loadLeadParts(): void {
    if (this.charges() !== null || this.alertPercent() !== null) {
      return;
    }
    this.layoutApi
      .layout()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (layout) => this.charges.set(layout.charges), error: () => undefined });
    this.api
      .rules()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rules) => this.alertPercent.set(rules.alertPercent),
        error: () => undefined,
      });
  }

  private refreshList(): void {
    if (this.locked() || this.refused) {
      return;
    }
    this.reloading = true;
    this.reloadRequests.next();
  }

  protected selectTab(tab: IpdTab): void {
    if (tab === 'er') {
      this.erOpened.set(true);
    }
    const leavingSafety = this.activeTab() === 'safety' && tab !== 'safety';
    this.tab.set(tab);
    if (leavingSafety) {
      // The banner and the tab number follow what the board last showed.
      this.refreshList();
    }
  }

  /**
   * Every 60 seconds while the browser tab is visible (ADR-013 D7). On the Admitted tab the whole list
   * is read again (its answer carries the safety counts, so the counts-only summary call below is not
   * made there); on the Safety tab the board refreshes itself; on every other tab the summary poll runs.
   * A reload still on its way is not doubled.
   */
  private pollList(): void {
    if (this.activeTab() !== 'admitted') {
      this.pollSafety();
      return;
    }
    if (!this.reloading && this.list() !== null && !this.locked() && !this.noBeds()) {
      this.refreshList();
    }
  }

  /**
   * Counts only (no names), and not while the browser tab is hidden (one poll
   * follows when it shows again): when the open or past-target number or the change token differs
   * from what the page shows, the list is read again so the tab number and the banner follow. The
   * token catches a swap the two numbers miss (one overdue escalation closes while another becomes
   * overdue). On the Safety tab the board reloads itself and passes its counts up.
   */
  private pollSafety(): void {
    if (this.polling || this.refused || !this.safetyOn() || this.activeTab() === 'safety') {
      return;
    }
    this.polling = true;
    this.safetyApi
      .summary()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (counts) => {
          this.polling = false;
          const shown = this.safetyCounts();
          if (!shown || !sameCounts(shown, counts)) {
            this.refreshList();
          }
        },
        error: () => {
          this.polling = false;
        },
      });
  }

  /**
   * The banner button: opens the Safety tab. The button leaves the page with the Admitted panel, so
   * focus goes to the Safety tab itself (the heading of the board is not drawn before it loads).
   */
  protected openSafetyFromBanner(): void {
    this.selectTab('safety');
    this.doc.getElementById('ipd-tab-safety')?.focus();
  }

  protected onSafetyCounts(counts: SafetyCounts): void {
    this.safetyRefused.set(false);
    this.liveSafety.set(counts);
  }

  /** The Safety board was refused (SAH-40): the counts it sent leave the tab number and the banner until it answers again. */
  protected onSafetyRefused(): void {
    this.liveSafety.set(null);
    this.safetyRefused.set(true);
  }

  /** Header button "Emergency": the menu page when the user has the item, else the tab here. */
  protected openEmergency(): void {
    if (this.shell.menu().includes('emergency')) {
      void this.router.navigateByUrl('/app/emergency');
    } else {
      this.selectTab('er');
    }
  }

  protected onEmergencyCounts(counts: EmergencyCounts): void {
    this.liveCounts.set(counts);
  }

  /**
   * The Emergency tab was refused (SAH-40): the counts it sent are dropped, so the tile and the tab
   * follow this page's own list answer (or "—"). IPD off locks the whole page.
   */
  protected onEmergencyRefused(refusal: EmergencyRefusal): void {
    this.liveCounts.set(null);
    if (isModuleOff(refusal.error)) {
      this.refuse(refusal.error, refusal.focusWasInside);
    }
  }

  protected onTabKeydown(event: KeyboardEvent): void {
    const order = this.tabs();
    const at = order.indexOf(this.activeTab());
    let next: number;
    switch (event.key) {
      case 'ArrowRight':
        next = (at + 1) % order.length;
        break;
      case 'ArrowLeft':
        next = (at - 1 + order.length) % order.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = order.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    this.selectTab(order[next]);
    document.getElementById('ipd-tab-' + order[next])?.focus();
  }

  /** Opens the Admit sheet, with a bed preselected when it comes from the bed board. */
  protected admit(bedId?: string): void {
    this.track(
      this.dialog.open<AdmitDialogComponent, AdmitDialogData | undefined, AdmissionDetail | null>(
        AdmitDialogComponent,
        {
          panelClass: 'sheet-panel',
          width: '640px',
          maxWidth: '100vw',
          ...(bedId ? { data: { bedId } } : {}),
        },
      ),
    ).subscribe((admission) => {
        if (admission) {
          this.afterAdmitted(admission);
        }
      });
  }

  /** Toast and reloads after the Admit sheet saved (from the page, a bed tile or an emergency card). */
  protected afterAdmitted(admission: AdmissionDetail): void {
    const deposit =
      admission.depositPaise > 0
        ? ` · deposit ${formatInrPaise(admission.depositPaise)} received`
        : ' · deposit due today';
    this.snackBar.open(
      `${admission.patientName} admitted to bed ${admission.bedCode} · ${admission.ipNumber}${deposit}`,
      'Close',
      { duration: 8000 },
    );
    this.refreshList();
    this.board()?.reload();
  }

  protected openAdmission(id: string): void {
    this.openById(id);
  }

  /** The name, Transfer or Discharge of a card, or Open in the table (start view: in memory only). */
  protected openFromCard(request: OpenAdmissionRequest): void {
    this.openById(request.id, request.start);
  }

  /** The card's Nursing: the sheet of SAH-30 for the admission; focus returns to the button on close. */
  protected openNursing(admissionId: string): void {
    this.track(
      this.dialog.open<NursingSheetComponent, NursingSheetData, boolean>(NursingSheetComponent, {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        ariaLabelledBy: 'nv-title',
        data: { admissionId },
      }),
    ).subscribe((saved) => {
        if (saved) {
          this.refreshList();
        }
      });
  }

  /** The card's EMR: the sheet of SAH-31 for the admission; focus returns to the button on close. */
  protected openEmr(admissionId: string): void {
    this.track(
      this.dialog.open<EmrSheetComponent, EmrSheetData>(EmrSheetComponent, {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        ariaLabelledBy: 'emr-title',
        data: { admissionId },
      }),
    ).subscribe();
  }

  protected refreshCounts(): void {
    this.refreshList();
  }

  private openById(id: string, startView: AdmissionDialogData['startView'] = 'details'): void {
    this.track(
      this.dialog.open<AdmissionDialogComponent, AdmissionDialogData, boolean>(AdmissionDialogComponent, {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        data: startView === 'details' ? { id } : { id, startView },
        // SAH-36 F-1 (and SAH-37 for Deposit): from a card's Transfer or Deposit the sheet places the focus itself (on the bed select), so
        // Material's first-tabbable step (the Close button) must not take it back. With autoFocus off,
        // Material still focuses the sheet container until the sheet moves the focus. restoreFocus stays
        // on, so closing returns to the card's button.
        ...(startView === 'move' || startView === 'deposit' ? { autoFocus: false } : {}),
      }),
    ).subscribe((changed) => {
        if (changed) {
          this.refreshList();
          this.board()?.reload();
        }
      });
  }
}
