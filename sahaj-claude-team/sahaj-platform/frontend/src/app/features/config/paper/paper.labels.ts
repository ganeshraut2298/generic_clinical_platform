import { SecondLanguage } from '../opd/opd.model';

/** Section labels in the second language, from the mockup's `PLAB`, used in the print preview. */
export interface PaperLabels {
  complaints: string;
  vitals: string;
  history: string;
  exam: string;
  diagnosis: string;
  inv: string;
  advice: string;
  next: string;
  afterFood: string;
  night: string;
  adviceText: string;
}

export const PAPER_LABELS: Record<SecondLanguage, PaperLabels> = {
  mr: {
    complaints: 'तक्रार',
    vitals: 'व्हायटल्स',
    history: 'पूर्वइतिहास',
    exam: 'तपासणी',
    diagnosis: 'निदान',
    inv: 'तपासण्या',
    advice: 'सल्ला',
    next: 'पुढील भेट',
    afterFood: 'जेवणानंतर',
    night: 'रात्री',
    adviceText: 'आराम करा, भरपूर पाणी प्या',
  },
  hi: {
    complaints: 'शिकायत',
    vitals: 'वाइटल्स',
    history: 'पुराना इतिहास',
    exam: 'परीक्षण',
    diagnosis: 'निदान',
    inv: 'जाँच',
    advice: 'सलाह',
    next: 'अगली विज़िट',
    afterFood: 'खाने के बाद',
    night: 'रात में',
    adviceText: 'आराम करें, खूब पानी पिएँ',
  },
};

export const LANGUAGE_CHOICES: readonly { value: SecondLanguage | ''; label: string }[] = [
  { value: '', label: 'English' },
  { value: 'mr', label: '+ मराठी' },
  { value: 'hi', label: '+ हिंदी' },
];
