export const LANG = (process.env.NX_LANG || 'en').toLowerCase() === 'ar' ? 'ar' : 'en';
const edition = await import(`./narration.${LANG}.mjs`);

export const VOICE = edition.VOICE;
export const RATE = edition.RATE ?? '+3%';
export const RTL = !!edition.RTL;
export const SCENES = edition.SCENES;
export const CARDS = edition.CARDS;

export const META_FILE = `narration_meta.${LANG}.json`;
export const TTS_DIR = `tts/${LANG}`;
export const OUTPUT_NAME = LANG === 'ar' ? 'Neurix-Product-Tour-AR' : 'Neurix-Product-Tour';
