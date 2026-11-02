import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject, catchError, debounceTime, distinctUntilChanged, of, switchMap, tap } from 'rxjs';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../config-format';
import { OpdApi } from '../opd/opd.api';
import { Medicine, MedicinePage } from '../opd/opd.model';

/**
 * Medicine favourites (SAH-7), placed at the bottom of Case paper and Rx: it has no screen of its
 * own in the mockup. Favourites are listed first for this clinic's doctors only. The owner marks
 * them; everyone else sees the list read-only. The list is global and cannot be changed here.
 */
@Component({
  selector: 'app-medicine-favourites',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './medicine-favourites.component.scss',
  templateUrl: './medicine-favourites.component.html',
})
export class MedicineFavouritesComponent implements OnInit {
  private readonly api = inject(OpdApi);
  private readonly store = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);

  private readonly search$ = new Subject<string>();

  protected readonly query = signal('');
  protected readonly favouritesOnly = signal(false);
  protected readonly items = signal<Medicine[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busyId = signal<string | null>(null);

  private page = 0;

  protected readonly isOwner = computed(() => this.store.me()?.role === 'OWNER');
  protected readonly hasMore = computed(() => this.items().length < this.total());

  constructor() {
    this.search$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        tap(() => {
          this.page = 0;
          this.loading.set(true);
          this.loadError.set(null);
        }),
        switchMap((q) =>
          this.api.searchMedicines(q, 0, this.favouritesOnly()).pipe(
            catchError((error: unknown) => {
              this.loadError.set(problemMessage(error, 'We could not load medicines. Try again.'));
              return of<MedicinePage>({ items: [], total: 0 });
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((result) => {
        this.items.set(result.items);
        this.total.set(result.total);
        this.loading.set(false);
      });
  }

  ngOnInit(): void {
    this.load(true);
  }

  protected details(medicine: Medicine): string {
    const parts = [medicine.form, medicine.usualDose, medicine.schedule].filter((p) => !!p);
    return parts.length ? parts.join(' · ') : 'No details';
  }

  protected onQuery(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.query.set(value);
    this.search$.next(value);
  }

  protected toggleOnly(): void {
    this.favouritesOnly.update((on) => !on);
    this.load(true);
  }

  protected loadMore(): void {
    this.load(false);
  }

  protected toggleFavourite(medicine: Medicine): void {
    if (!this.isOwner() || this.busyId()) {
      return;
    }
    const make = !medicine.favourite;
    this.busyId.set(medicine.id);
    const call = make ? this.api.markFavourite(medicine.id) : this.api.unmarkFavourite(medicine.id);
    call.subscribe({
      next: () => {
        this.items.update((list) =>
          list.map((m) => (m.id === medicine.id ? { ...m, favourite: make } : m)),
        );
        this.busyId.set(null);
        this.snackBar.open(
          make ? `${medicine.name} added to favourites` : `${medicine.name} removed from favourites`,
          'Close',
          { duration: 5000 },
        );
      },
      error: (error: unknown) => {
        this.busyId.set(null);
        this.snackBar.open(
          problemMessage(error, 'We could not save that change. Try again.'),
          'Close',
          { duration: 5000 },
        );
      },
    });
  }

  private load(reset: boolean): void {
    if (reset) {
      this.page = 0;
      this.loading.set(true);
    } else {
      this.page += 1;
      this.loadingMore.set(true);
    }
    this.loadError.set(null);
    this.api.searchMedicines(this.query(), this.page, this.favouritesOnly()).subscribe({
      next: (result) => {
        this.items.update((list) => (reset ? result.items : [...list, ...result.items]));
        this.total.set(result.total);
        this.loading.set(false);
        this.loadingMore.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadingMore.set(false);
        this.loadError.set(problemMessage(error, 'We could not load medicines. Try again.'));
      },
    });
  }
}
