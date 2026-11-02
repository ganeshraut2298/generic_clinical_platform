import { Pipe, PipeTransform, inject } from '@angular/core';
import { TranslateService } from './translate.service';

/** `{{ 'nav.today' | t }}`. Impure: re-reads the dictionary signal when the language changes. */
@Pipe({ name: 't', pure: false })
export class TranslatePipe implements PipeTransform {
  private readonly translate = inject(TranslateService);

  transform(key: string): string {
    return this.translate.t(key);
  }
}
