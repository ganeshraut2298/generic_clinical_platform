import { DestroyRef, Directive, computed, inject, input } from '@angular/core';
import { SpecialtyContext } from '../specialty.context';
import { EmrSpecialtyValue } from '../specialty.model';

/**
 * What every specialty editor shares (ADR-023 D1): the key of its anchor section, its value as it is
 * on screen (or an empty one), and one way to write it back. Writing marks the section unsaved; the
 * draft save sends it (one write path) and the server's computed answer comes back with the record.
 */
@Directive()
export abstract class SpEditor<T extends EmrSpecialtyValue> {
  protected readonly ctx = inject(SpecialtyContext);
  readonly sectionKey = input.required<string>();

  /** The specialty component this editor edits. */
  protected abstract readonly component: T['component'];
  protected abstract blank(): T;

  protected readonly disabled = this.ctx.readOnly;
  protected readonly stored = computed<T | null>(() => this.ctx.value<T>(this.sectionKey(), this.component));
  protected readonly val = computed<T>(() => this.stored() ?? this.blank());
  /** Nothing unsaved in this section: the server's computed answer is the one to show. */
  protected readonly fresh = computed(() => this.ctx.fresh(this.sectionKey()));
  protected readonly view = this.ctx.view;

  /** Hands the screen's footer print button the print of this card, while the card is on screen (call from the constructor). */
  protected registerFooterPrint(run: () => void): void {
    inject(DestroyRef).onDestroy(this.ctx.registerFooterPrint(run));
  }

  protected patch(change: Partial<T>): void {
    if (this.disabled()) {
      return;
    }
    this.ctx.set(this.sectionKey(), { ...this.val(), ...change } as T);
  }

  protected replace(next: T): void {
    if (!this.disabled()) {
      this.ctx.set(this.sectionKey(), next);
    }
  }
}

export function toggled<K>(list: readonly K[], item: K): K[] {
  return list.includes(item) ? list.filter((x) => x !== item) : [...list, item];
}

export function numberOrNull(event: Event): number | null {
  const raw = (event.target as HTMLInputElement).value.trim();
  if (raw === '') {
    return null;
  }
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

export function textOrNull(event: Event): string | null {
  const raw = (event.target as HTMLInputElement | HTMLTextAreaElement).value;
  return raw.trim() === '' ? null : raw;
}
