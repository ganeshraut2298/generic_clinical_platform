import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, map, of, tap } from 'rxjs';
import { Language } from '../platform/platform.model';

/**
 * A small in-house translation service, as `docs/stories/SAH-5.md` §3 asks for: JSON files per
 * language under `public/i18n/`, no new library. `@angular/localize` needs one build per
 * language, which does not fit a live switch. Shell labels only; each later story adds its own.
 */
@Injectable({ providedIn: 'root' })
export class TranslateService {
  private readonly http = inject(HttpClient);
  private readonly cache = new Map<Language, Record<string, string>>();
  private readonly dictSig = signal<Record<string, string>>({});
  private readonly languageSig = signal<Language>('en');

  readonly language = this.languageSig.asReadonly();
  readonly ready = computed(() => Object.keys(this.dictSig()).length > 0);

  use(language: Language): Observable<Record<string, string>> {
    this.languageSig.set(language);
    const cached = this.cache.get(language);
    if (cached) {
      this.dictSig.set(cached);
      return of(cached);
    }
    return this.http.get<Record<string, string>>(`/i18n/${language}.json`).pipe(
      tap((dict) => {
        this.cache.set(language, dict);
        this.dictSig.set(dict);
      }),
      map((dict) => dict),
    );
  }

  /** Looks up `key`; falls back to the key itself so a missing translation never breaks a page. */
  t(key: string): string {
    return this.dictSig()[key] ?? key;
  }
}
