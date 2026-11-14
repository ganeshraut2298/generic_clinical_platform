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
import { Observable, Subject, catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EMR_COMPONENT_CONTEXT } from '../emr-context';
import { EmrApi } from '../emr.api';
import { EmrMedicineOption, EmrPrescriptionRow, EmrPrescriptionValue, EmrRxFrequency, EmrRxWhen } from '../emr-phase3.model';
import {
  ALERT_BLOCK_TEXT,
  ALLERGY_LINE,
  FREQUENCIES,
  ROWS_MAX,
  WHEN_CHOICES,
  alertText,
  hasMedicine,
  rowFromOption,
  rowsOf,
  stepDays,
  typedRow,
  warningMessage,
  withRows,
} from './prescription.logic';

const SEARCH_WAIT_MS = 250;
const TOAST_MS = 4000;
const NAME_MAX = 160;

/**
 * The prescription (SAH-48; mockup #GeneralTreatment "Prescription"): medicine rows by generic
 * name with four fixed frequencies, days (minus and plus) and a when-to-take choice; the clinic's
 * favourites; Repeat last Rx; Clear all with Undo; and the drug-class allergy alert with
 * "Remove it" and "I have reviewed the alert and want to prescribe anyway". Names print in capitals
 * only when the clinic setting is on; the editor shows them as stored. Medicine names are clinical
 * data: never logged or stored in the browser.
 */
@Component({
  selector: 'app-prescription',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrl: './prescription.component.scss',
  templateUrl: './prescription.component.html',
})
export class PrescriptionComponent implements OnInit {
  private readonly api = inject(EmrApi);
  private readonly ctx = inject(EMR_COMPONENT_CONTEXT, { optional: true });
  private readonly destroyRef = inject(DestroyRef);

  readonly title = input('Prescription');
  readonly required = input(false);
  readonly value = input<unknown>(null);
  readonly disabled = input(false);
  readonly inputId = input.required<string>();

  readonly valueChange = output<EmrPrescriptionValue>();

  protected readonly frequencies = FREQUENCIES;
  protected readonly whenChoices = WHEN_CHOICES;
  protected readonly safetyLine = ALLERGY_LINE;
  protected readonly blockText = ALERT_BLOCK_TEXT;

  protected readonly rows = computed(() => rowsOf(this.value()));
  protected readonly bad = computed(() => this.required() && this.rows().length === 0);
  protected readonly query = signal('');
  protected readonly results = signal<EmrMedicineOption[]>([]);
  protected readonly searchFailed = signal(false);
  protected readonly favourites = signal<EmrMedicineOption[]>([]);
  protected readonly favouritesFailed = signal(false);
  protected readonly toast = signal<string | null>(null);
  protected readonly undoRows = signal<EmrPrescriptionRow[] | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly copying = signal(false);

  /** Known drug classes of the rows added from the master, for the "contains a sulfa drug" line. */
  private readonly classes = signal<Readonly<Record<string, EmrMedicineOption['allergyClass']>>>({});

  protected readonly alerts = computed(() => {
    const ids = new Set(this.rows().map((r) => r.id));
    return (this.ctx?.allergyAlerts() ?? []).filter((a) => ids.has(a.rowId));
  });
  protected readonly alertRows = computed(() => new Set(this.alerts().map((a) => a.rowId)));
  protected readonly visibleFavourites = computed(() => this.favourites().filter((f) => !hasMedicine(this.rows(), f.name)));
  protected readonly visibleResults = computed(() => this.results().filter((r) => !hasMedicine(this.rows(), r.name)));
  protected readonly canAddTyped = computed(() => {
    const text = this.query().trim();
    return text.length > 0 && !hasMedicine(this.rows(), text) && !this.results().some((r) => r.name.toLowerCase() === text.toLowerCase());
  });
  protected readonly canRepeat = computed(() => !!this.ctx?.lastSignedPrescription() && !this.disabled());
  protected readonly showRepeat = computed(() => !!this.ctx && !this.disabled());

  private readonly typed = new Subject<string>();
  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => this.clearToastTimer());
  }

  ngOnInit(): void {
    this.typed
      .pipe(
        debounceTime(SEARCH_WAIT_MS),
        distinctUntilChanged(),
        switchMap((q) => this.search(q)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((options) => this.results.set(options));
    this.loadFavourites();
  }

  protected loadFavourites(): void {
    if (this.disabled()) {
      return;
    }
    this.favouritesFailed.set(false);
    this.api
      .listMedicineFavourites()
      .pipe(
        catchError(() => {
          this.favouritesFailed.set(true);
          return of<EmrMedicineOption[]>([]);
        }),
      )
      .subscribe((list) => this.favourites.set(list));
  }

  private search(q: string): Observable<EmrMedicineOption[]> {
    this.searchFailed.set(false);
    if (!q.trim()) {
      return of([]);
    }
    return this.api.searchEmrMedicines(q.trim()).pipe(
      map((page) => page.items),
      catchError(() => {
        this.searchFailed.set(true);
        return of<EmrMedicineOption[]>([]);
      }),
    );
  }

  // ---- adding and changing rows ----

  protected onQuery(event: Event): void {
    const text = (event.target as HTMLInputElement).value;
    this.query.set(text);
    this.notice.set(null);
    this.typed.next(text);
  }

  protected onEnter(event: Event): void {
    event.preventDefault();
    const first = this.visibleResults()[0];
    if (first) {
      this.addOption(first, 'SEARCH');
    } else {
      this.addTyped();
    }
  }

  protected addOption(option: EmrMedicineOption, source: 'SEARCH' | 'FAVOURITE'): void {
    if (this.disabled() || hasMedicine(this.rows(), option.name) || this.full()) {
      return;
    }
    this.ctx?.noteMedicineClass(option.medicineId, option.allergyClass);
    this.classes.update((all) => ({ ...all, [option.medicineId]: option.allergyClass }));
    const row = rowFromOption(option, source);
    const flagged = option.allergyClass !== null;
    this.commit([...this.rows(), row], flagged ? warningMessage(option.name, option.allergyClass!) : `${option.name} added`);
    this.query.set('');
    this.results.set([]);
    this.typed.next('');
  }

  protected addTyped(): void {
    const name = this.query().trim();
    if (this.disabled() || !name || hasMedicine(this.rows(), name) || this.full()) {
      return;
    }
    if (name.length > NAME_MAX) {
      this.notice.set(`Use at most ${NAME_MAX} characters.`);
      return;
    }
    this.commit([...this.rows(), typedRow(name)], `${name} added`);
    this.query.set('');
    this.results.set([]);
    this.typed.next('');
  }

  private full(): boolean {
    if (this.rows().length >= ROWS_MAX) {
      this.notice.set(`Add at most ${ROWS_MAX} medicines.`);
      return true;
    }
    return false;
  }

  private change(id: string, patch: Partial<EmrPrescriptionRow>): void {
    this.emit(this.rows().map((r) => (r.id === id ? { ...r, ...patch } : r)));
  }

  protected setFrequency(row: EmrPrescriptionRow, frequency: EmrRxFrequency): void {
    if (!this.disabled()) {
      this.change(row.id, { frequency });
    }
  }

  protected stepDays(row: EmrPrescriptionRow, direction: 1 | -1): void {
    if (!this.disabled()) {
      this.change(row.id, { days: stepDays(row.days, direction) });
    }
  }

  protected setWhen(row: EmrPrescriptionRow, event: Event): void {
    if (!this.disabled()) {
      this.change(row.id, { when: (event.target as HTMLSelectElement).value as EmrRxWhen });
    }
  }

  protected remove(row: EmrPrescriptionRow): void {
    if (!this.disabled()) {
      this.commit(this.rows().filter((r) => r.id !== row.id), `${row.name} removed`);
    }
  }

  protected clearAll(): void {
    if (!this.disabled() && this.rows().length > 0) {
      this.commit([], 'Prescription cleared');
    }
  }

  protected undo(): void {
    const previous = this.undoRows();
    if (previous) {
      this.emit(previous);
      this.clearToast();
    }
  }

  /** "Repeat last Rx": the server copies the patient's last signed medicines; items already there are skipped. */
  protected repeatLast(): void {
    if (!this.ctx || !this.canRepeat() || this.copying()) {
      return;
    }
    this.copying.set(true);
    this.ctx.copyItems({ source: 'LAST_SIGNED_PRESCRIPTION', what: 'MEDICINES' }).subscribe((result) => {
      this.copying.set(false);
      if (!result) {
        this.notice.set('We could not copy the last prescription. Try again.');
        return;
      }
      this.notice.set(null);
      this.say(
        result.added > 0
          ? `Last prescription copied (${result.added} ${result.added === 1 ? 'medicine' : 'medicines'})`
          : 'Nothing to add. Those medicines are already on the prescription.',
      );
    });
  }

  // ---- allergy alert ----

  protected alertMessage(a: { medicineName: string; allergyClass: 'SULFA' | 'PENICILLIN' }): string {
    return alertText(a.medicineName, a.allergyClass);
  }

  protected removeFlagged(rowId: string): void {
    if (this.disabled()) {
      return;
    }
    this.commit(
      this.rows().filter((r) => r.id !== rowId),
      'Removed. Allergy conflict cleared.',
    );
  }

  protected setReviewed(rowId: string, allergyClass: string, event: Event): void {
    if (this.disabled()) {
      return;
    }
    const confirmed = (event.target as HTMLInputElement).checked;
    this.change(rowId, { allergyReview: confirmed ? { allergyClass, confirmed: true } : null });
  }

  // ---- helpers for the template ----

  protected subline(row: EmrPrescriptionRow): string {
    const cls = row.medicineId ? this.classes()[row.medicineId] : null;
    const parts = [row.form ?? '', row.doseText ?? '', row.note ?? '', cls === 'SULFA' ? 'contains a sulfa drug' : ''].filter((p) => p);
    return parts.join(' · ');
  }

  private emit(rows: EmrPrescriptionRow[]): void {
    this.valueChange.emit(withRows(this.value() as EmrPrescriptionValue | null, rows));
  }

  private commit(rows: EmrPrescriptionRow[], message: string): void {
    const previous = this.rows();
    this.emit(rows);
    this.notice.set(null);
    this.say(message, previous);
  }

  private say(message: string, undo: EmrPrescriptionRow[] | null = null): void {
    this.toast.set(message);
    this.undoRows.set(undo);
    this.clearToastTimer();
    this.toastTimer = setTimeout(() => this.clearToast(), TOAST_MS);
  }

  private clearToast(): void {
    this.clearToastTimer();
    this.toast.set(null);
    this.undoRows.set(null);
  }

  private clearToastTimer(): void {
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
      this.toastTimer = null;
    }
  }
}
