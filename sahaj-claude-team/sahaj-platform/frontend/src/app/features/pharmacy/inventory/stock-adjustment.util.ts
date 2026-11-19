/**
 * The checks of the stock adjustment form (story 79-5), with the contract's own sentences. The server checks again.
 * `physical` is what the person typed; `book` is the system quantity of the Available batch.
 */
export function validatePhysical(physical: number | null, book: number): string | null {
  if (physical === null || !Number.isInteger(physical) || physical < 0 || physical > 999_999) {
    return 'Enter a whole number of 0 or more';
  }
  if (physical === book) {
    return 'No change to post';
  }
  return null;
}

/**
 * The value of a difference: units times the batch purchase rate per issue unit, without GST (BR-17), in whole paise
 * rounded half up. The rate is a decimal string with up to 4 decimals; null when the batch has no purchase rate.
 */
export function adjustmentValuePaise(difference: number, purchaseRate: string | null): number | null {
  if (purchaseRate === null || purchaseRate === '') {
    return null;
  }
  const [whole, fraction = ''] = purchaseRate.split('.');
  const tenThousandths = Number(whole) * 10_000 + Number(fraction.padEnd(4, '0').slice(0, 4));
  return Math.round((Math.abs(difference) * tenThousandths) / 100);
}
