import { NgComponentOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, Type, computed, effect, input, signal } from '@angular/core';
import { EmrLayoutSection } from '../emr.model';
import { editorLoader } from './specialty-registry';

/**
 * Shows the specialty editor of a section (ADR-023 D1). The editor loads lazily; while it loads the
 * section says so. The editor reads and writes its own value through the specialty context, so only
 * the section key goes in.
 */
@Component({
  selector: 'app-specialty-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgComponentOutlet],
  template: `
    @if (editor(); as component) {
      <ng-container *ngComponentOutlet="component; inputs: inputs()" />
    } @else {
      <p class="faint t-xs" aria-busy="true">Loading</p>
    }
  `,
})
export class SpecialtyHostComponent {
  readonly section = input.required<EmrLayoutSection>();
  /** Which part of a split editor to draw (Obstetrics: the ANC column or the Quick tools cards); null draws all of it. */
  readonly area = input<'main' | 'tools' | null>(null);
  protected readonly inputs = computed<Record<string, unknown>>(() => {
    const area = this.area();
    return area ? { sectionKey: this.section().key, area } : { sectionKey: this.section().key };
  });
  protected readonly editor = signal<Type<unknown> | null>(null);
  private readonly component = computed(() => this.section().specialtyEditor?.component ?? null);

  constructor() {
    effect(() => {
      const loader = editorLoader(this.component());
      if (loader) {
        void loader().then((type) => this.editor.set(type));
      }
    });
  }
}
