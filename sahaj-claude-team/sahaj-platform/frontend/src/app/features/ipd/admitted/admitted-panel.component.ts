import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { formatInrPaise } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { AdmissionList, AdmittedPatientRow, admissionSubline, statusPills } from '../ipd.model';
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
  imports: [MatButtonModule, IconComponent, PatientCardComponent],
  styleUrl: './admitted-panel.component.scss',
  templateUrl: './admitted-panel.component.html',
})
export class AdmittedPanelComponent {
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly state = inject(AdmittedViewState);

  readonly list = input.required<AdmissionList>();
  /** "Could not refresh. Showing data from 11:05 AM." after a failed reload; null otherwise. */
  readonly staleText = input<string | null>(null);

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
  protected readonly searchPlaceholder = SEARCH_PLACEHOLDER;
  protected readonly emptyText = EMPTY_FILTER_TEXT;

  /** Polite line after a search, a ward change, "Show 24 more" or "Show all"; empty until then. */
  protected readonly announcement = signal('');

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

  /**
   * The result count goes into the polite region after each change. The text is replaced, never
   * cleared first, so a screen reader reads the last settled count and not an empty line.
   */
  protected onQuery(event: Event): void {
    this.state.setQuery((event.target as HTMLInputElement).value);
    this.announceResult();
  }

  protected onWard(event: Event): void {
    this.state.setWard((event.target as HTMLSelectElement).value);
    this.announceResult();
  }

  private announceResult(): void {
    this.announcement.set(filterAnnounceText(this.matches().length, this.list().items.length));
  }

  protected setView(view: AdmittedView): void {
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
    }
  }

  protected openDetails(row: AdmittedPatientRow): void {
    this.openAdmission.emit({ id: row.id, start: 'details' });
  }
}
