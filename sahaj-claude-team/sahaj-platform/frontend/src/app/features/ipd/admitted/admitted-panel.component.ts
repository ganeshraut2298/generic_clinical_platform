import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { formatInrPaise } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { AdmissionList, AdmittedPatientRow, admissionSubline, statusPills } from '../ipd.model';
import { FALLBACK_NOTICE, LAYOUT_NAME } from '../card/card-setup.model';
import { drawnSetup } from '../card/card-view';
import { AdmittedViewState } from './admitted-view.state';
import { PatientCardComponent } from './patient-card.component';
import {
  ALL_WARDS,
  AdmissionStartView,
  AdmittedView,
  COLS_CHOICES,
  CardAction,
  CardsPerRow,
  EMPTY_FILTER_TEXT,
  SEARCH_LABEL,
  SEARCH_ANNOUNCE_MS,
  SEARCH_MAX_LENGTH,
  SEARCH_PLACEHOLDER,
  announceText,
  filterAnnounceText,
  filterRows,
  showingText,
} from './admitted.model';

export interface OpenAdmissionRequest {
  id: string;
  start: AdmissionStartView;
}

/**
 * The body of the Admitted tab (SAH-36; mockup `ipList` overrides): toolbar (search, ward, Cards per
 * row, Cards/Table), the uniform card grid with "Showing N of M patients" paging, and today's table.
 * Search and ward filter run on the device over the loaded list and apply to both views; the text
 * never leaves the browser. What the user chose lives in {@link AdmittedViewState}. The panel makes
 * no request: sheets are opened by the page through its outputs.
 */
@Component({
  selector: 'app-admitted-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink, IconComponent, PatientCardComponent],
  styleUrl: './admitted-panel.component.scss',
  templateUrl: './admitted-panel.component.html',
})
export class AdmittedPanelComponent {
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly state = inject(AdmittedViewState);

  readonly list = input.required<AdmissionList>();
  /** "Could not refresh. Showing data from 11:05 AM." after a failed reload; null otherwise. */
  readonly staleText = input<string | null>(null);
  /** SAH-37. The owner gets the button "Customize card" and edits the card; everyone else reads it. */
  readonly isOwner = input(false);

  /** The name, a Transfer or a Discharge was chosen (or Open in the table). */
  readonly openAdmission = output<OpenAdmissionRequest>();
  readonly openNursing = output<string>();
  readonly openEmr = output<string>();

  protected readonly formatInrPaise = formatInrPaise;
  protected readonly subline = admissionSubline;
  protected readonly pills = statusPills;
  protected readonly colsChoices = COLS_CHOICES;
  /** One small square per card of the "Cards per row" icons (mockup `.pccg`). */
  protected readonly dots = (n: number): number[] => Array.from({ length: n }, (_, i) => i);
  protected readonly allWards = ALL_WARDS;
  protected readonly searchLabel = SEARCH_LABEL;
  protected readonly searchMax = SEARCH_MAX_LENGTH;
  protected readonly searchPlaceholder = SEARCH_PLACEHOLDER;
  protected readonly emptyText = EMPTY_FILTER_TEXT;

  /** Polite line after a search, a ward change, "Show 24 more" or "Show all"; empty until then. */
  protected readonly announcement = signal('');

  private announceTimer: ReturnType<typeof setTimeout> | null = null;

  /** SAH-37. The published setup, or the Sahaj standard card with a notice when it cannot be used. */
  private readonly published = computed(() => drawnSetup(this.list().cardSetup));
  protected readonly setup = computed(() => this.published().setup);
  protected readonly setupNotice = computed(() => (this.published().fallback ? FALLBACK_NOTICE : null));
  protected readonly layout = computed(() => this.setup().layout);
  /** "Standard card · Sahaj standard card · v1" (mockup `pcfoot`). */
  protected readonly footText = computed(() => {
    const s = this.setup();
    const who = this.isOwner() ? '' : ' · set by the clinic owner';
    return `${LAYOUT_NAME[s.layout]} card · ${s.isStandard ? 'Sahaj standard card' : 'customized'} · v${s.version}${who}`;
  });

  protected readonly matches = computed(() =>
    filterRows(this.list().items, this.state.query(), this.state.ward()),
  );
  protected readonly drawn = computed(() => this.matches().slice(0, this.state.limit()));
  protected readonly more = computed(() => this.matches().length > this.drawn().length);
  protected readonly showing = computed(() => showingText(this.drawn().length, this.matches().length));
  /** The ward chosen is no longer a ward of the clinic: the select falls back to "All wards". */
  protected readonly wardValue = computed(() =>
    this.list().wards.some((w) => w.id === this.state.ward()) ? this.state.ward() : ALL_WARDS,
  );

  constructor() {
    this.destroyRef.onDestroy(() => this.cancelAnnounce());
    // The grid opens with the published Cards per row; the person's own choice still wins.
    effect(() => {
      const n = this.setup().cardsPerRow;
      this.state.setPublishedCols(n === 2 || n === 4 ? n : 3);
    });
  }

  /**
   * The result count goes into the polite region after each change. The text is replaced, never
   * cleared first, so a screen reader reads the last settled count and not an empty line.
   */
  protected onQuery(event: Event): void {
    this.state.setQuery((event.target as HTMLInputElement).value);
    // The visible filter is instant; the polite line waits until typing pauses (DEC-025 G-10).
    this.cancelAnnounce();
    this.announceTimer = setTimeout(() => {
      this.announceTimer = null;
      this.announceResult();
    }, SEARCH_ANNOUNCE_MS);
  }

  protected onWard(event: Event): void {
    this.state.setWard((event.target as HTMLSelectElement).value);
    this.cancelAnnounce();
    this.announceResult();
  }

  private cancelAnnounce(): void {
    if (this.announceTimer !== null) {
      clearTimeout(this.announceTimer);
      this.announceTimer = null;
    }
  }

  private announceResult(): void {
    this.announcement.set(filterAnnounceText(this.matches().length, this.list().items.length));
  }

  protected setView(view: AdmittedView): void {
    this.cancelAnnounce();
    this.announcement.set('');
    this.state.setView(view);
  }

  protected setCols(cols: CardsPerRow): void {
    this.state.setCols(cols);
  }

  protected showMore(): void {
    this.reveal(() => this.state.showMore());
  }

  protected showAll(): void {
    this.reveal(() => this.state.showAll());
  }

  /**
   * Draws more cards. The keyboard focus stays on the button; when the button is gone (everything is
   * drawn) it moves to the first new card, and the new count is announced politely.
   */
  private reveal(change: () => void): void {
    const before = this.drawn().length;
    this.cancelAnnounce();
    change();
    this.announcement.set(announceText(this.drawn().length, this.matches().length));
    afterNextRender(
      () => {
        const root = this.host.nativeElement;
        if (root.querySelector('[data-testid="show-more"]') === null) {
          root.querySelectorAll<HTMLElement>('[data-testid="card-name"]')[before]?.focus();
        }
      },
      { injector: this.injector },
    );
  }

  protected onAction(row: AdmittedPatientRow, action: CardAction): void {
    switch (action) {
      case 'nursing':
        this.openNursing.emit(row.id);
        break;
      case 'emr':
        this.openEmr.emit(row.id);
        break;
      case 'transfer':
        this.openAdmission.emit({ id: row.id, start: 'move' });
        break;
      case 'discharge':
        this.openAdmission.emit({ id: row.id, start: 'discharge' });
        break;
      case 'deposit':
        this.openAdmission.emit({ id: row.id, start: 'deposit' });
        break;
    }
  }

  protected openDetails(row: AdmittedPatientRow): void {
    this.openAdmission.emit({ id: row.id, start: 'details' });
  }
}
