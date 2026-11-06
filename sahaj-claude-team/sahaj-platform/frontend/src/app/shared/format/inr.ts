/**
 * Indian digit grouping: 1,25,000. Written by hand so it does not depend on the ICU data
 * of the browser or of Node.
 */
export function formatIndian(value: number): string {
  const rounded = Math.round(Math.abs(value));
  const digits = String(rounded);
  const sign = value < 0 && rounded !== 0 ? '-' : '';
  if (digits.length <= 3) {
    return sign + digits;
  }
  const last3 = digits.slice(-3);
  const rest = digits.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',');
  return `${sign}${rest},${last3}`;
}

/** Rupee amount with Indian grouping, for example ₹1,25,000. */
export function formatInr(value: number): string {
  return `₹${formatIndian(value)}`;
}

/**
 * Amount held in paise, written exactly: no decimals when it is a whole number of rupees
 * (₹1,25,000), otherwise two decimals (₹187.50, ₹1,312.50). Bills never round to whole rupees.
 */
export function formatInrPaise(paise: number): string {
  const total = Math.round(Math.abs(paise));
  const rupees = Math.floor(total / 100);
  const part = total % 100;
  const sign = paise < 0 && total !== 0 ? '-' : '';
  const decimals = part === 0 ? '' : `.${String(part).padStart(2, '0')}`;
  return `₹${sign}${formatIndian(rupees)}${decimals}`;
}
