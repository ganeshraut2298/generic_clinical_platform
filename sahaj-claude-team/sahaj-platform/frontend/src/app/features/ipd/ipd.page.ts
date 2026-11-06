import { DOCUMENT, NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiError } from '../../core/http/problem-detail';
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
import { AdmissionsApi } from './admissions.api';
import { BedBoardComponent } from './board/bed-board.component';
import { EmergencyComponent } from './emergency/emergency.component';
import {
  EmergencyCounts,
  tileNumber,
  tileText,
} from './emergency/emergency.model';
import {
  AdmissionDetail,
  AdmissionList,
  AdmissionRow,
  admissionSubline,
  ipdLead,
  statusPills,
} from './ipd.model';
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
} from './safety/safety.model';

type IpdTab = 'board' | 'admitted' | 'er' | 'safety';
const EMERGENCY_LEAD = 'Triage first. Admit, treat and release, or refer out.';
const LOAD_ERROR = 'We could not load beds and admissions. Try again.';

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
    PageHeadComponent,
  ],
  providers: [PharmacistStore],
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

  protected readonly formatInrPaise = formatInrPaise;
  protected readonly subline = admissionSubline;
  protected readonly pills = statusPills;
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

  protected readonly loading = signal(true);
  protected readonly locked = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly list = signal<AdmissionList | null>(null);
  private readonly charges = signal<IpdCharges | null>(null);
  private readonly alertPercent = signal<number | null>(null);

  /** Counts from the Emergency tab's last answer; they replace the ones of the list until it reloads. */
  private readonly liveCounts = signal<EmergencyCounts | null>(null);
  protected readonly emergency = computed(() => this.liveCounts() ?? this.list()?.emergency ?? null);
  /** The tab, tile number and header button need IPD to be writable; Read-only hides them. */
  protected readonly emergencyOn = computed(() => this.emergency()?.writable === true);
  /** Counts from the Safety board's last answer or the summary poll; they replace the list's until it reloads. */
  private readonly liveSafety = signal<SafetyCounts | null>(null);
  /** SAH-33 (mockup sfTabOn): the tab shows when the server sent `safety` and someone is admitted. */
  protected readonly safetyOn = computed(() => {
    const l = this.list();
    return !!l && l.safety != null && l.items.length > 0;
  });
  protected readonly safetyCounts = computed(() => this.liveSafety() ?? this.list()?.safety?.counts ?? null);
  /** The number on the Safety tab: the owner's open escalations plus the orders waiting (mockup `sfCount`); a pharmacist's is the orders waiting. */
  protected readonly safetyTabNumber = computed(() =>
    safetyTabCount(this.isPharmacist() ? 0 : this.safetyCounts()?.open, this.pharmacist.waiting()),
  );
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
    // IPD switched off is answered by the pharmacist calls too (403 module-off): same lock notice.
    effect(() => {
      if (this.pharmacist.moduleOff()) {
        this.locked.set(true);
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
    startVisiblePoll(this.doc, SAFETY_REFRESH_MS, () => this.pollSafety(), this.destroyRef);
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
          if (
            error instanceof ApiError &&
            error.status === 403 &&
            error.problem.type.endsWith('module-off')
          ) {
            this.locked.set(true);
          } else {
            this.loadError.set(problemMessage(error, LOAD_ERROR));
          }
          this.loading.set(false);
        },
      });
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
    this.api
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.list.set(list);
          this.liveCounts.set(null);
          this.liveSafety.set(null);
        },
        error: () => undefined,
      });
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
   * Every 60 seconds, counts only (no names), and not while the browser tab is hidden (one poll
   * follows when it shows again): when the open or past-target number or the change token differs
   * from what the page shows, the list is read again so the tab number and the banner follow. The
   * token catches a swap the two numbers miss (one overdue escalation closes while another becomes
   * overdue). On the Safety tab the board reloads itself and passes its counts up.
   */
  private pollSafety(): void {
    if (this.polling || !this.safetyOn() || this.activeTab() === 'safety') {
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
    this.liveSafety.set(counts);
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
    this.dialog
      .open<AdmitDialogComponent, AdmitDialogData | undefined, AdmissionDetail | null>(
        AdmitDialogComponent,
        {
          panelClass: 'sheet-panel',
          width: '640px',
          maxWidth: '100vw',
          ...(bedId ? { data: { bedId } } : {}),
        },
      )
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((admission) => {
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

  protected open(row: AdmissionRow): void {
    this.openById(row.id);
  }

  protected refreshCounts(): void {
    this.refreshList();
  }

  private openById(id: string): void {
    this.dialog
      .open<AdmissionDialogComponent, AdmissionDialogData, boolean>(AdmissionDialogComponent, {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        data: { id },
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((changed) => {
        if (changed) {
          this.refreshList();
          this.board()?.reload();
        }
      });
  }
}
