/** Default web address from the clinic name, as the mockup's `slugify` does: drop common
 * clinic words, keep lowercase letters and digits, cap at 20 characters. */
export function slugify(name: string): string {
  const stripped = name
    .toLowerCase()
    .replace(/\b(clinic|hospital|family|the|dr|and|of|care|centre|center)\b/g, '')
    .replace(/[^a-z0-9]/g, '')
    .slice(0, 20);
  return stripped || 'myclinic';
}
