import {
  DestroyRef,
  Directive,
  ElementRef,
  afterNextRender,
  inject,
  output,
  signal,
} from '@angular/core';

/**
 * Marks an element as a scene. Adds the class `in-view` while at least 15% of it is on screen
 * and emits the same state, so animations can run only while the scene is visible.
 * Without IntersectionObserver the scene counts as in view, like the mockup.
 */
@Directive({
  selector: '[appInView]',
  exportAs: 'appInView',
  host: { class: 'scene', '[class.in-view]': 'visible()' },
})
export class InViewDirective {
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  readonly visible = signal(false);
  readonly inViewChange = output<boolean>();

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      if (typeof IntersectionObserver === 'undefined') {
        this.set(true);
        return;
      }
      const observer = new IntersectionObserver(
        (entries) => {
          const last = entries[entries.length - 1];
          if (last) {
            this.set(last.isIntersecting);
          }
        },
        { threshold: 0.15 },
      );
      observer.observe(this.element);
      destroyRef.onDestroy(() => observer.disconnect());
    });
  }

  private set(value: boolean): void {
    if (this.visible() !== value) {
      this.visible.set(value);
      this.inViewChange.emit(value);
    }
  }
}
