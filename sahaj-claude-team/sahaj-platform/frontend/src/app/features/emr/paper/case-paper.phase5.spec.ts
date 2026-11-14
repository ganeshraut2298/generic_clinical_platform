import { footerLines } from './case-paper.logic';

describe('case paper footer after the sign (SAH-51-6)', () => {
  it('keeps "Not valid without signature." until the visit is signed', () => {
    expect(footerLines(false, false)).toEqual(['Not valid without signature.']);
    expect(footerLines(true, false, false)).toEqual(['Medicines in generic names, capital letters (NMC).', 'Not valid without signature.']);
  });

  it('drops it once the context says signed', () => {
    expect(footerLines(false, false, true)).toEqual([]);
    expect(footerLines(true, false, true)).toEqual(['Medicines in generic names, capital letters (NMC).']);
  });
});
