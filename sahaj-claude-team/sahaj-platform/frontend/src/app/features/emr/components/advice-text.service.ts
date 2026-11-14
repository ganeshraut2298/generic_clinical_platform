import { Injectable, inject, signal } from '@angular/core';
import { EmrApi } from '../emr.api';
import { StarterKey } from '../emr.model';
import { EmrListItem, PrintLanguage } from '../emr-phase3.model';

export interface AdviceTexts {
  mr: string | null;
  hi: string | null;
}

/**
 * Marathi and Hindi text of advice items (SAH-56-2, SAH-55-2). The text is an additive column of the
 * advice master (`nameMr`, `nameHi`, null until the human supplies it, DEC-039 answer 4). A record
 * holds only the English text, so the screen remembers what the master search returned for the
 * items of this visit: the case paper prints the Marathi or Hindi text when there is one, else
 * English, and the editor says "No Marathi text, English used". Kept in memory only.
 */
@Injectable({ providedIn: 'root' })
export class AdviceTextService {
  private readonly api = inject(EmrApi);
  private readonly pending = new Set<string>();

  readonly texts = signal<Readonly<Record<string, AdviceTexts>>>({});

  remember(itemKey: string, mr: string | null | undefined, hi: string | null | undefined): void {
    this.texts.update((all) => ({ ...all, [itemKey]: { mr: mr?.trim() || null, hi: hi?.trim() || null } }));
  }

  /** Looks the item up in the master once, so its Marathi and Hindi text is known. Errors are ignored. */
  ensure(item: Pick<EmrListItem, 'itemKey' | 'text' | 'newToList'>, specialty: StarterKey | null): void {
    const key = item.itemKey;
    if (!key || item.newToList || key in this.texts() || this.pending.has(key)) {
      return;
    }
    this.pending.add(key);
    this.api.searchMaster('advice', item.text, specialty).subscribe({
      next: (page) => {
        const hit = page.items.find((i) => i.id === key);
        if (hit) {
          this.remember(key, hit.nameMr, hit.nameHi);
        }
        this.pending.delete(key);
      },
      error: () => this.pending.delete(key),
    });
  }

  /** The text in the language, or null when the master has none (English is then used). */
  textFor(item: Pick<EmrListItem, 'itemKey'>, language: PrintLanguage): string | null {
    const entry = item.itemKey ? this.texts()[item.itemKey] : undefined;
    if (!entry || language === 'EN') {
      return null;
    }
    return language === 'MR' ? entry.mr : entry.hi;
  }

  /** True when the master is known to have no text in that language for this item. */
  lacksText(item: Pick<EmrListItem, 'itemKey' | 'newToList'>, language: PrintLanguage): boolean {
    if (language === 'EN' || item.newToList || !item.itemKey) {
      return false;
    }
    const entry = this.texts()[item.itemKey];
    return !!entry && (language === 'MR' ? !entry.mr : !entry.hi);
  }
}
