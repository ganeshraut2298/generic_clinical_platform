import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged } from 'rxjs';
import { formatDisplayDate } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EMR_COMPONENT_CONTEXT } from '../emr-context';
import { EmrApi } from '../emr.api';
import { detailOr } from '../emr-errors';
import {
  EmrKeyFacts,
  EmrPastVisit,
  EmrPastVisitDetail,
  EmrPastVisitPage,
  PastVisitFilter,
} from '../emr-phase3.model';
import { frequencyLabel, whenLabel } from '../components/prescription.logic';

const SEARCH_WAIT_MS = 300;
const TOAST_MS = 3000;
const LOAD_ERROR = 'We could not load the history. Try again.';
const DETAIL_ERROR = 'We could not open the details. Try again.';

export const FIRST_VISIT_TEXT = 'First visit. No earlier records.';
export const NOTHING_MATCHES = 'Nothing matches.';
export const NOT_RECORDED = 'Not recorded';
export const NO_SIGNED_RECORD = 'No signed record yet.';
export const SIGNED_NOTE = 'This visit is signed';

const FILTERS: readonly { key: PastVisitFilter; label: string }[] = [
  { key: 'ALL', label: 'All' },
  { key: 'OPD', label: 'OPD' },
  { key: 'IPD', label: 'IPD' },
];

/** The key of a row: kind and id. */
export function rowKey(v: Pick<EmrPastVisit, 'kind' | 'id'>): string {
  return `${v.kind}:${v.id}`;
}

/**
 * Patient history beside the EMR (SAH-50; mockup #VisitHistory, the "Past visits · OPD + IPD" tab):
 * a short facts box for the department, earlier OPD and IPD visits newest first in pages of eight,
 * filter tabs, a search over the patient's history (done on the server), details of one visit at a
 * time and the copy actions. Visit text, diagnoses, drugs and the search text are clinical data:
 * never logged or stored in the browser.
 */
@Component({
  selector: 'app-past-visits',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrl: './past-visits-panel.component.scss',
  templateUrl: './past-visits-panel.component.html',
})
export class PastVisitsPanelComponent implements OnInit {
  private readonly api = inject(EmrApi);
  private readonly ctx = inject(EMR_COMPONENT_CONTEXT, { optional: true });
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly recordId = input.required<string>();
  /** This visit's allergies as the doctor has them on screen; replaces the Allergies fact (SAH-50-2). */
  readonly allergyLine = input<string | null>(null);

  /** Emitted after a copy action changed today's record, so the screen can show the new values. */
  readonly copied = output<void>();

  protected readonly filters = FILTERS;
  protected readonly facts = signal<EmrKeyFacts | null>(null);
  protected readonly factsFailed = signal(false);
  protected readonly items = signal<EmrPastVisit[]>([]);
  protected readonly counts = signal({ opd: 0, ipd: 0 });
  protected readonly someHidden = signal(false);
  protected readonly hasMore = signal(false);
  protected readonly pageNo = signal(0);
  protected readonly loading = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly filter = signal<PastVisitFilter>('ALL');
  protected readonly query = signal('');
  protected readonly open = signal<string | null>(null);
  protected readonly details = signal<Readonly<Record<string, EmrPastVisitDetail>>>({});
  protected readonly detailBusy = signal<string | null>(null);
  protected readonly detailError = signal<string | null>(null);
  protected readonly copying = signal<string | null>(null);
  protected readonly toast = signal<string | null>(null);

  protected readonly countText = computed(() => `${this.counts().opd} OPD · ${this.counts().ipd} IPD`);
  protected readonly firstVisit = computed(
    () => this.loaded() && this.items().length === 0 && this.counts().opd + this.counts().ipd === 0 && !this.query().trim() && !this.error(),
  );
  protected readonly nothingMatches = computed(
    () => this.loaded() && this.items().length === 0 && !this.firstVisit() && !this.error(),
  );
  protected readonly factRows = computed(() =>
    (this.facts()?.facts ?? []).map((f) => ({
      ...f,
      shown:
        f.key.toLowerCase().includes('allerg') && this.allergyLine() !== null
          ? this.allergyLine()
          : f.value,
    })),
  );

  private readonly typed = new Subject<string>();
  private toastTimer: ReturnType<typeof setTimeout> | null = null;
  private generation = 0;

  constructor() {
    this.destroyRef.onDestroy(() => {
      if (this.toastTimer) {
        clearTimeout(this.toastTimer);
      }
    });
  }

  ngOnInit(): void {
    this.typed
      .pipe(debounceTime(SEARCH_WAIT_MS), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.reload());
    this.loadFacts();
    this.reload();
  }

  protected loadFacts(): void {
    this.factsFailed.set(false);
    this.api.getKeyFacts(this.recordId()).subscribe({
      next: (facts) => this.facts.set(facts),
      error: () => this.factsFailed.set(true),
    });
  }

  /** Loads the first page again for the filter and search text; typed text is kept on a failure. */
  protected reload(): void {
    const gen = ++this.generation;
    this.loading.set(true);
    this.error.set(null);
    this.open.set(null);
    this.pageNo.set(0);
    this.api.listPastVisits(this.recordId(), this.filter(), this.query(), 0).subscribe({
      next: (page) => {
        if (gen === this.generation) {
          this.apply(page, false);
        }
      },
      error: (e: unknown) => {
        if (gen === this.generation) {
          this.loading.set(false);
          this.loaded.set(true);
          this.error.set(detailOr(e, LOAD_ERROR));
        }
      },
    });
  }

  protected loadMore(): void {
    if (this.loadingMore() || !this.hasMore()) {
      return;
    }
    const gen = this.generation;
    const next = this.pageNo() + 1;
    this.loadingMore.set(true);
    this.api.listPastVisits(this.recordId(), this.filter(), this.query(), next).subscribe({
      next: (page) => {
        if (gen === this.generation) {
          this.apply(page, true);
        }
        this.loadingMore.set(false);
      },
      error: (e: unknown) => {
        this.loadingMore.set(false);
        this.error.set(detailOr(e, LOAD_ERROR));
      },
    });
  }

  private apply(page: EmrPastVisitPage, append: boolean): void {
    this.items.update((current) => (append ? [...current, ...page.items] : page.items));
    this.counts.set(page.counts);
    this.someHidden.set(page.someHidden);
    this.hasMore.set(page.hasMore);
    this.pageNo.set(page.page);
    this.loading.set(false);
    this.loaded.set(true);
  }

  protected setFilter(filter: PastVisitFilter): void {
    if (filter !== this.filter()) {
      this.filter.set(filter);
      this.reload();
    }
  }

  protected onQuery(event: Event): void {
    const text = (event.target as HTMLInputElement).value;
    this.query.set(text);
    this.typed.next(text.trim());
  }

  // ---- details ----

  protected toggle(visit: EmrPastVisit): void {
    const key = rowKey(visit);
    if (this.open() === key) {
      this.open.set(null);
      return;
    }
    this.open.set(key);
    this.detailError.set(null);
    if (this.details()[key]) {
      return;
    }
    this.detailBusy.set(key);
    this.api.getPastVisitDetail(this.recordId(), visit.kind, visit.id).subscribe({
      next: (detail) => {
        this.details.update((all) => ({ ...all, [key]: detail }));
        this.detailBusy.set(null);
      },
      error: (e: unknown) => {
        this.detailBusy.set(null);
        this.detailError.set(detailOr(e, DETAIL_ERROR));
      },
    });
  }

  protected detailOf(visit: EmrPastVisit): EmrPastVisitDetail | null {
    return this.details()[rowKey(visit)] ?? null;
  }

  protected isOpen(visit: EmrPastVisit): boolean {
    return this.open() === rowKey(visit);
  }

  protected medicineLine(m: EmrPastVisitDetail['medicines'][number]): string {
    return `${m.name} · ${frequencyLabel(m.frequency).split(' ')[0]} · ${m.days} days · ${whenLabel(m.when)}`;
  }

  protected typeOf(visit: EmrPastVisit): 'OPD' | 'IPD' {
    return visit.kind === 'OPD' ? 'OPD' : 'IPD';
  }

  protected dateText(visit: EmrPastVisit): string {
    return formatDisplayDate(visit.date);
  }

  // ---- actions ----

  protected copyMedicines(visit: EmrPastVisit): void {
    this.copy(visit, 'MEDICINES');
  }

  protected repeatTests(visit: EmrPastVisit): void {
    this.copy(visit, 'TESTS');
  }

  private copy(visit: EmrPastVisit, what: 'MEDICINES' | 'TESTS'): void {
    if (!this.ctx || this.copying()) {
      return;
    }
    const key = `${rowKey(visit)}:${what}`;
    this.copying.set(key);
    this.ctx.copyItems({ source: 'PAST_RECORD', sourceRecordId: visit.id, what }).subscribe((result) => {
      this.copying.set(null);
      if (!result) {
        this.say('We could not copy. Try again.');
        return;
      }
      const n = result.added;
      this.say(
        what === 'MEDICINES'
          ? `${n} medicine(s) copied to today's prescription`
          : `${n} test(s) added to today's orders`,
      );
      this.copied.emit();
    });
  }

  /** "Open full visit" or "Open discharge summary": the signed record opens read-only in a new tab. */
  protected openFull(visit: EmrPastVisit, detail: EmrPastVisitDetail): void {
    const tree =
      detail.open.target === 'RECORD'
        ? this.router.createUrlTree(['/app/emr', this.recordId(), 'past', detail.open.id])
        : this.router.createUrlTree(['/app/ipd']);
    window.open(this.router.serializeUrl(tree), '_blank', 'noopener');
    this.say(visit.kind === 'OPD' ? 'Visit record opened in a new tab' : 'Discharge summary opened in a new tab');
  }

  private say(message: string): void {
    this.toast.set(message);
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
    this.toastTimer = setTimeout(() => this.toast.set(null), TOAST_MS);
  }

  protected readonly firstVisitText = FIRST_VISIT_TEXT;
  protected readonly nothingMatchesText = NOTHING_MATCHES;
  protected readonly notRecorded = NOT_RECORDED;
  protected readonly noSignedRecord = NO_SIGNED_RECORD;
  protected readonly signedNote = SIGNED_NOTE;
}
