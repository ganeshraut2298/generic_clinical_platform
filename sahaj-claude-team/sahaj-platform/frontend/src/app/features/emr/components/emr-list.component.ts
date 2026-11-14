import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { formatInr } from '../../../shared/format/inr';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EMR_COMPONENT_CONTEXT } from '../emr-context';
import { EmrApi } from '../emr.api';
import { EmrListKind, StarterKey } from '../emr.model';
import { EmrListItem, EmrListValue, PrintLanguage } from '../emr-phase3.model';
import { AdviceTextService } from './advice-text.service';
import {
  ALREADY_BILLED_TEXT,
  AttrSpec,
  ITEM_TEXT_MAX,
  LIST_KINDS,
  LIST_MAX,
  WHATSAPP_NOTE,
  WHATSAPP_SEND_BY,
  activeItems,
  capitalise,
  choiceLabel,
  countText,
  hasText,
  isLockedByBill,
  itemsOf,
  newListItem,
  showsCode,
  withItems,
} from './emr-list.logic';

interface PickOption {
  readonly itemKey: string;
  readonly text: string;
  readonly code: string | null;
  readonly meta: string | null;
  readonly unitPricePaise: number | null;
  readonly mr: string | null;
  readonly hi: string | null;
}

type ListView = 'chips' | 'table';

const SEARCH_WAIT_MS = 250;
const QUICK_LIMIT = 7;
const RESULT_LIMIT = 8;
const TOAST_MS = 4000;

/**
 * The EMR list (SAH-56; mockup #EmrList): complaints, diagnosis, tests, advice and procedures and
 * services as one-tap chips from "Your most used", a search over the master, two attributes with
 * defaults, a table view with Added on, Added by and an Active switch, and typed items tagged "New
 * to list" that stay on this patient only (OQ-45-17). The value is written by the EMR draft save;
 * ranking is counted only when the visit is signed. Search text is sent to the server only and is
 * never logged or stored.
 */
@Component({
  selector: 'app-emr-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrl: './emr-list.component.scss',
  templateUrl: './emr-list.component.html',
})
export class EmrListComponent implements OnInit {
  private readonly api = inject(EmrApi);
  private readonly advice = inject(AdviceTextService);
  private readonly ctx = inject(EMR_COMPONENT_CONTEXT, { optional: true });
  private readonly destroyRef = inject(DestroyRef);

  readonly kind = input.required<EmrListKind>();
  readonly title = input.required<string>();
  readonly required = input(false);
  readonly value = input<unknown>(null);
  readonly disabled = input(false);
  readonly specialty = input<StarterKey | null>(null);
  readonly inputId = input.required<string>();

  readonly valueChange = output<EmrListValue>();

  protected readonly spec = computed(() => LIST_KINDS[this.kind()]);
  protected readonly items = computed(() => itemsOf(this.value()));
  protected readonly active = computed(() => activeItems(this.items()));
  protected readonly count = computed(() => countText(this.items()));
  protected readonly bad = computed(() => this.required() && this.active().length === 0);
  protected readonly empty = computed(() => (this.view() === 'chips' ? this.active().length : this.items().length) === 0);
  protected readonly canChangePrintLanguage = computed(() => this.kind() === 'ADVICE' && !!this.ctx && !this.disabled());
  protected readonly printLanguage = computed(() => this.ctx?.printLanguage() ?? 'EN');

  protected readonly view = signal<ListView>('chips');
  protected readonly adding = signal(false);
  protected readonly query = signal('');
  protected readonly results = signal<PickOption[]>([]);
  protected readonly searchFailed = signal(false);
  protected readonly quick = signal<PickOption[]>([]);
  protected readonly quickFailed = signal(false);
  protected readonly toast = signal<string | null>(null);
  protected readonly undoItems = signal<EmrListItem[] | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly typedText = computed(() => this.query().trim());
  protected readonly visibleResults = computed(() =>
    this.results()
      .filter((r) => !hasText(this.items(), r.text))
      .slice(0, RESULT_LIMIT),
  );
  protected readonly visibleQuick = computed(() =>
    this.quick()
      .filter((q) => !hasText(this.items(), q.text))
      .slice(0, QUICK_LIMIT),
  );
  protected readonly canCreate = computed(() => {
    const text = this.typedText();
    return text.length > 0 && !hasText(this.items(), text) && !this.results().some((r) => r.text.toLowerCase() === text.toLowerCase());
  });
  protected readonly addHint = computed(() =>
    this.typedText()
      ? "Not in the list? Add it as new. It stays on this patient's record."
      : 'Showing your most used first. Tap to add; keep tapping to add more.',
  );
  protected readonly whatsappNote = WHATSAPP_NOTE;
  protected readonly languages: readonly { key: PrintLanguage; label: string }[] = [
    { key: 'EN', label: 'English' },
    { key: 'MR', label: 'Marathi' },
    { key: 'HI', label: 'Hindi' },
  ];

  private readonly typed = new Subject<string>();
  private services: PickOption[] | null = null;
  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => this.clearToastTimer());
    // Advice in another language: learn whether the master has its text, for the note and the paper.
    effect(() => {
      if (this.kind() !== 'ADVICE') {
        return;
      }
      for (const item of this.items()) {
        if (item.attr1 !== 'EN') {
          this.advice.ensure(item, this.specialty());
        }
      }
    });
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
    this.loadQuick();
  }

  // ---- reading the master ----

  protected loadQuick(): void {
    if (this.disabled()) {
      return;
    }
    this.quickFailed.set(false);
    const done = (options: PickOption[]): void => this.quick.set(options);
    const failed = (): Observable<PickOption[]> => {
      this.quickFailed.set(true);
      return of<PickOption[]>([]);
    };
    if (this.kind() === 'SERVICES') {
      this.loadServices().pipe(catchError(failed)).subscribe(done);
      return;
    }
    const master = this.spec().master;
    if (!master) {
      return;
    }
    this.api
      .quickOptions(master, this.specialty(), QUICK_LIMIT)
      .pipe(
        map((list) =>
          list.items.map<PickOption>((i) => ({
            itemKey: i.itemKey,
            text: i.name,
            code: i.icd10Code,
            meta: i.useCount > 0 ? `· ${i.useCount}` : null,
            unitPricePaise: null,
            mr: null,
            hi: null,
          })),
        ),
        catchError(failed),
      )
      .subscribe(done);
  }

  private loadServices(): Observable<PickOption[]> {
    if (this.services) {
      return of(this.services);
    }
    return this.api.activeServices().pipe(
      map((list) => {
        this.services = list.map<PickOption>((s, i) => ({
          itemKey: s.id ?? `service-${i}`,
          text: s.name,
          code: null,
          meta: formatInr(s.priceRupees),
          unitPricePaise: Math.round(s.priceRupees * 100),
          mr: null,
          hi: null,
        }));
        return this.services;
      }),
    );
  }

  private search(q: string): Observable<PickOption[]> {
    this.searchFailed.set(false);
    const text = q.trim();
    if (!text) {
      return of([]);
    }
    let source: Observable<PickOption[]>;
    switch (this.kind()) {
      case 'SERVICES':
        source = this.loadServices().pipe(
          map((all) => all.filter((s) => s.text.toLowerCase().includes(text.toLowerCase())).slice(0, 20)),
        );
        break;
      case 'DIAGNOSIS':
        source = this.api.searchDiagnoses(text, this.specialty()).pipe(
          map((hits) =>
            hits.map<PickOption>((h) => ({
              itemKey: h.code,
              text: h.name,
              code: h.code,
              meta: null,
              unitPricePaise: null,
              mr: null,
              hi: null,
            })),
          ),
        );
        break;
      default: {
        const master = this.spec().master;
        if (master !== 'complaints' && master !== 'investigations' && master !== 'advice') {
          return of([]);
        }
        source = this.api.searchMaster(master, text, this.specialty()).pipe(
          map((page) =>
            page.items.map<PickOption>((i) => ({
              itemKey: i.id,
              text: i.name,
              code: null,
              meta: null,
              unitPricePaise: null,
              mr: i.nameMr ?? null,
              hi: i.nameHi ?? null,
            })),
          ),
        );
      }
    }
    return source.pipe(
      catchError(() => {
        this.searchFailed.set(true);
        return of<PickOption[]>([]);
      }),
    );
  }

  // ---- screen events ----

  protected setView(view: ListView): void {
    this.view.set(view);
  }

  protected toggleAdd(): void {
    this.adding.update((v) => !v);
    this.query.set('');
    this.results.set([]);
    this.notice.set(null);
  }

  protected onQuery(event: Event): void {
    const text = (event.target as HTMLInputElement).value;
    this.query.set(text);
    this.notice.set(null);
    this.typed.next(text);
  }

  protected onEnter(event: Event): void {
    event.preventDefault();
    const first = this.visibleResults()[0];
    if (first && !this.canCreate()) {
      this.pick(first);
    } else if (first && this.typedText().toLowerCase() === first.text.toLowerCase()) {
      this.pick(first);
    } else {
      this.createTyped();
    }
  }

  protected pick(option: PickOption): void {
    if (this.disabled() || this.full() || hasText(this.items(), option.text)) {
      return;
    }
    if (this.kind() === 'ADVICE') {
      this.advice.remember(option.itemKey, option.mr, option.hi);
    }
    const item = newListItem(
      this.kind(),
      { itemKey: option.itemKey, text: option.text, newToList: false, code: option.code, unitPricePaise: option.unitPricePaise },
      this.ctx?.printLanguage() ?? 'EN',
    );
    this.commit([...this.items(), item], `${option.text} added`);
    this.query.set('');
    this.results.set([]);
    this.typed.next('');
  }

  /** "+ Add “text” as new <noun>": stays on this patient only and is not offered to anyone else. */
  protected createTyped(): void {
    const text = this.typedText();
    if (this.disabled() || text === '') {
      return;
    }
    if (text.length > ITEM_TEXT_MAX) {
      this.notice.set(`Use at most ${ITEM_TEXT_MAX} characters.`);
      return;
    }
    if (hasText(this.items(), text) || this.full()) {
      return;
    }
    const item = newListItem(this.kind(), { itemKey: null, text, newToList: true }, this.ctx?.printLanguage() ?? 'EN');
    this.commit([...this.items(), item], `${capitalise(text)} added`);
    this.query.set('');
    this.results.set([]);
    this.typed.next('');
  }

  private full(): boolean {
    if (this.items().length >= LIST_MAX) {
      this.notice.set(`Add at most ${LIST_MAX}.`);
      return true;
    }
    return false;
  }

  protected attrOf(item: EmrListItem, which: 1 | 2): string {
    const spec = which === 1 ? this.spec().attr1 : this.spec().attr2;
    return choiceLabel(spec, which === 1 ? item.attr1 : item.attr2);
  }

  protected specOf(which: 1 | 2): AttrSpec {
    return which === 1 ? this.spec().attr1 : this.spec().attr2;
  }

  protected isChoiceDisabled(which: 1 | 2, key: string): boolean {
    return which === 2 && this.kind() === 'ADVICE' && WHATSAPP_SEND_BY.has(key);
  }

  /** The chip pill: each press moves to the next value of the list (mockup #EmrList), skipping switched-off ones. */
  protected cycleAttr(item: EmrListItem, which: 1 | 2): void {
    const choices = this.specOf(which).choices.filter((c) => !this.isChoiceDisabled(which, c.key));
    const current = which === 1 ? item.attr1 : item.attr2;
    const at = choices.findIndex((c) => c.key === current);
    const next = choices[(at + 1) % choices.length];
    if (!next || next.key === current || this.disabled()) {
      return;
    }
    if (isLockedByBill(this.kind(), item)) {
      this.notice.set(ALREADY_BILLED_TEXT);
      return;
    }
    this.notice.set(null);
    this.emit(this.items().map((i) => (i.id === item.id ? { ...i, [which === 1 ? 'attr1' : 'attr2']: next.key } : i)));
    if (this.kind() === 'ADVICE' && which === 1 && next.key !== 'EN') {
      this.advice.ensure(item, this.specialty());
    }
  }

  protected onAttr(item: EmrListItem, which: 1 | 2, event: Event): void {
    const select = event.target as HTMLSelectElement;
    const key = select.value;
    if (this.disabled() || this.isChoiceDisabled(which, key)) {
      select.value = which === 1 ? item.attr1 : item.attr2;
      return;
    }
    if (isLockedByBill(this.kind(), item)) {
      select.value = which === 1 ? item.attr1 : item.attr2;
      this.notice.set(ALREADY_BILLED_TEXT);
      return;
    }
    this.notice.set(null);
    this.emit(this.items().map((i) => (i.id === item.id ? { ...i, [which === 1 ? 'attr1' : 'attr2']: key } : i)));
    if (this.kind() === 'ADVICE' && which === 1 && key !== 'EN') {
      this.advice.ensure(item, this.specialty());
    }
  }

  protected noTextNote(item: EmrListItem): string | null {
    if (this.kind() !== 'ADVICE' || (item.attr1 !== 'MR' && item.attr1 !== 'HI')) {
      return null;
    }
    return this.advice.lacksText(item, item.attr1) ? `No ${item.attr1 === 'MR' ? 'Marathi' : 'Hindi'} text, English used` : null;
  }

  protected toggleActive(item: EmrListItem): void {
    if (this.disabled()) {
      return;
    }
    if (isLockedByBill(this.kind(), item)) {
      this.notice.set(ALREADY_BILLED_TEXT);
      return;
    }
    const next = this.items().map((i) => (i.id === item.id ? { ...i, active: !i.active } : i));
    this.commit(next, item.active ? `${item.text} disabled. Kept in the record but not printed.` : `${item.text} enabled`);
  }

  protected remove(item: EmrListItem): void {
    if (this.disabled()) {
      return;
    }
    if (isLockedByBill(this.kind(), item)) {
      this.notice.set(ALREADY_BILLED_TEXT);
      return;
    }
    this.commit(this.items().filter((i) => i.id !== item.id), `${item.text} removed`);
  }

  protected undo(): void {
    const previous = this.undoItems();
    if (!previous) {
      return;
    }
    this.emit(previous);
    this.clearToast();
  }

  protected setPrintLanguage(event: Event): void {
    this.ctx?.setPrintLanguage((event.target as HTMLSelectElement).value as PrintLanguage);
  }

  // ---- helpers for the template ----

  protected code(item: EmrListItem): string | null {
    return showsCode(this.kind(), item) ? (item.code ?? null) : null;
  }

  protected addedOn(item: EmrListItem): string {
    return item.addedAt ? formatDisplayDateTimeOfInstant(item.addedAt) : 'Not saved yet';
  }

  protected price(item: EmrListItem): string | null {
    return this.kind() === 'SERVICES' && item.unitPricePaise != null ? formatInr(item.unitPricePaise / 100) : null;
  }

  protected toggleLabel(item: EmrListItem): string {
    return `${item.active ? 'Disable' : 'Enable'} ${item.text}`;
  }

  private emit(items: EmrListItem[]): void {
    this.valueChange.emit(withItems(this.value() as EmrListValue | null, this.kind(), items));
  }

  private commit(items: EmrListItem[], message: string): void {
    const previous = this.items();
    this.emit(items);
    this.notice.set(null);
    this.toast.set(message);
    this.undoItems.set(previous);
    this.clearToastTimer();
    this.toastTimer = setTimeout(() => this.clearToast(), TOAST_MS);
  }

  private clearToast(): void {
    this.clearToastTimer();
    this.toast.set(null);
    this.undoItems.set(null);
  }

  private clearToastTimer(): void {
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
      this.toastTimer = null;
    }
  }
}
