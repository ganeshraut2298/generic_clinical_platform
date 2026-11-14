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
import { EmrApi } from '../emr.api';
import { EmrFieldDefinition, EmrItemListEntry, StarterKey } from '../emr.model';

export interface PickOption {
  readonly itemKey: string;
  readonly text: string;
  readonly hint: string | null;
}

/** The field of the Allergies section that holds drug allergies (library field allergy.drug_allergy). */
const DRUG_ALLERGY_KEY = 'drug_allergy';
const SEARCH_WAIT_MS = 250;
const LIST_MAX = 50;
const TEXT_MAX = 200;

/**
 * The Search list field (complaints, diagnoses, medicines, tests, services): chips for what is
 * chosen, a search box that reads the master named by the field, the doctor's quick-add chips and,
 * when the field allows it, typed text tagged "New to list" that stays on this patient only
 * (OQ-45-17). The search text is sent to the server only; it is never logged or stored.
 */
@Component({
  selector: 'app-item-list-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrl: './item-list-field.component.scss',
  templateUrl: './item-list-field.component.html',
})
export class ItemListFieldComponent implements OnInit {
  private readonly api = inject(EmrApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly field = input.required<EmrFieldDefinition>();
  readonly entries = input<readonly EmrItemListEntry[]>([]);
  readonly disabled = input(false);
  /** The record's starter department, used to order the search and the quick options. */
  readonly specialty = input<StarterKey | null>(null);
  readonly inputId = input.required<string>();

  readonly changed = output<EmrItemListEntry[]>();

  protected readonly query = signal('');
  protected readonly results = signal<PickOption[]>([]);
  protected readonly quick = signal<PickOption[]>([]);
  protected readonly searchFailed = signal(false);
  protected readonly listError = signal<string | null>(null);

  private readonly typed = new Subject<string>();
  private services: PickOption[] | null = null;

  protected readonly canAddFree = computed(() => this.field().allowFreeText);
  protected readonly chosenKeys = computed(() => new Set(this.entries().map((e) => e.itemKey ?? `text:${e.text.toLowerCase()}`)));
  protected readonly visibleResults = computed(() => this.results().filter((r) => !this.chosenKeys().has(r.itemKey)));
  protected readonly visibleQuick = computed(() => this.quick().filter((q) => !this.chosenKeys().has(q.itemKey)));
  protected readonly canType = computed(() => this.field().optionsSource !== 'FIELD');
  protected readonly typedText = computed(() => this.query().trim());
  protected readonly canAddTyped = computed(() => {
    const text = this.typedText();
    return this.canAddFree() && text.length > 0 && !this.chosenKeys().has(`text:${text.toLowerCase()}`);
  });

  ngOnInit(): void {
    this.typed
      .pipe(
        debounceTime(SEARCH_WAIT_MS),
        distinctUntilChanged(),
        switchMap((q) => this.search(q)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((options) => this.results.set(options));
    this.loadQuick();
  }

  private loadQuick(): void {
    const field = this.field();
    if (field.key === DRUG_ALLERGY_KEY && !this.disabled()) {
      // The Allergies section stores a picked drug class with itemKey = classKey (SAH-48-2); typed text never triggers the check.
      this.api
        .listAllergyClasses()
        .pipe(
          map((classes) => classes.map((c) => ({ itemKey: c.classKey, text: c.label, hint: null }))),
          catchError(() => of<PickOption[]>([])),
        )
        .subscribe((options) => this.quick.set(options));
      return;
    }
    if (field.optionsSource === 'FIELD') {
      this.quick.set(field.options.map((o) => ({ itemKey: o.key, text: o.label, hint: null })));
      return;
    }
    if (field.optionsSource !== 'MASTER' || !field.master || this.disabled()) {
      return;
    }
    this.api
      .quickOptions(field.master, this.specialty(), 10)
      .pipe(
        map((list) =>
          list.items.map((i) => ({ itemKey: i.itemKey, text: i.name, hint: i.icd10Code ?? i.form ?? null })),
        ),
        catchError(() => of<PickOption[]>([])),
      )
      .subscribe((options) => this.quick.set(options));
  }

  private search(q: string): Observable<PickOption[]> {
    const field = this.field();
    this.searchFailed.set(false);
    if (!q.trim()) {
      return of([]);
    }
    let source: Observable<PickOption[]>;
    switch (field.optionsSource) {
      case 'SERVICES':
        source = this.searchServices(q);
        break;
      case 'MASTER':
        source = this.searchMaster(q);
        break;
      default:
        return of([]);
    }
    return source.pipe(
      catchError(() => {
        this.searchFailed.set(true);
        return of<PickOption[]>([]);
      }),
    );
  }

  private searchMaster(q: string): Observable<PickOption[]> {
    const master = this.field().master;
    if (master === 'diagnoses') {
      return this.api
        .searchDiagnoses(q, this.specialty())
        .pipe(map((hits) => hits.map((h) => ({ itemKey: h.code, text: h.name, hint: h.code }))));
    }
    if (master === 'medicines') {
      return this.api
        .searchMedicines(q)
        .pipe(map((page) => page.items.map((m) => ({ itemKey: m.id, text: m.name, hint: m.form ?? null }))));
    }
    if (master === 'complaints' || master === 'investigations' || master === 'advice') {
      return this.api
        .searchMaster(master, q, this.specialty())
        .pipe(map((page) => page.items.map((i) => ({ itemKey: i.id, text: i.name, hint: null }))));
    }
    return of([]);
  }

  private searchServices(q: string): Observable<PickOption[]> {
    const filter = (list: PickOption[]): PickOption[] =>
      list.filter((s) => s.text.toLowerCase().includes(q.trim().toLowerCase())).slice(0, 20);
    if (this.services) {
      return of(filter(this.services));
    }
    return this.api.activeServices().pipe(
      map((list) => {
        this.services = list.map((s, i) => ({ itemKey: s.id ?? `service-${i}`, text: s.name, hint: s.category }));
        return filter(this.services);
      }),
    );
  }

  protected onInput(event: Event): void {
    const text = (event.target as HTMLInputElement).value;
    this.query.set(text);
    this.listError.set(null);
    this.typed.next(text);
  }

  protected pick(option: PickOption): void {
    this.add({ itemKey: option.itemKey, text: option.text, newToList: false });
  }

  protected addTyped(): void {
    const text = this.typedText();
    if (!this.canAddTyped()) {
      return;
    }
    if (text.length > TEXT_MAX) {
      this.listError.set(`Use at most ${TEXT_MAX} characters.`);
      return;
    }
    this.add({ itemKey: null, text, newToList: true });
  }

  private add(entry: EmrItemListEntry): void {
    if (this.disabled()) {
      return;
    }
    if (this.entries().length >= LIST_MAX) {
      this.listError.set(`Add at most ${LIST_MAX}.`);
      return;
    }
    this.changed.emit([...this.entries(), entry]);
    this.query.set('');
    this.results.set([]);
    this.typed.next('');
  }

  protected remove(index: number): void {
    if (this.disabled()) {
      return;
    }
    this.changed.emit(this.entries().filter((_, i) => i !== index));
  }

  protected onEnter(event: Event): void {
    event.preventDefault();
    const first = this.visibleResults()[0];
    if (first && !this.canAddTyped()) {
      this.pick(first);
    } else {
      this.addTyped();
    }
  }
}
