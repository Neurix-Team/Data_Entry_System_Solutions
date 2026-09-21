/**
 * A short, synthesized two-note chime for "a new message just arrived" — no audio file to
 * ship, fetch or keep in sync with a design refresh, so it is always available and adds
 * nothing to the bundle. Shaped like a bell (soft attack, smooth decay, a faint overtone)
 * rather than a flat beep, and loud enough to cut through a busy office.
 */

let ctx: AudioContext | null = null;

function audioContext(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  const Ctor = window.AudioContext
    ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return null;
  if (!ctx) ctx = new Ctor();
  return ctx;
}

/** One bell note: a fundamental plus a faint, slightly-detuned overtone, ringing out. */
function note(ac: AudioContext, destination: AudioNode, freq: number, startAt: number,
              duration: number, peak: number) {
  const fundamental = ac.createOscillator();
  fundamental.type = 'sine';
  fundamental.frequency.value = freq;

  // A pure sine reads as a synth beep; a bell has a second partial that isn't quite an
  // octave. Mixing one in at low volume is most of the difference between the two.
  const overtone = ac.createOscillator();
  overtone.type = 'sine';
  overtone.frequency.value = freq * 2.01;
  const overtoneGain = ac.createGain();
  overtoneGain.gain.value = peak * 0.18;

  const envelope = ac.createGain();
  const t0 = ac.currentTime + startAt;
  envelope.gain.setValueAtTime(0, t0);
  envelope.gain.linearRampToValueAtTime(peak, t0 + 0.012);
  envelope.gain.exponentialRampToValueAtTime(0.0006, t0 + duration);

  fundamental.connect(envelope);
  overtone.connect(overtoneGain);
  overtoneGain.connect(envelope);
  envelope.connect(destination);

  const stopAt = t0 + duration + 0.05;
  fundamental.start(t0);
  fundamental.stop(stopAt);
  overtone.start(t0);
  overtone.stop(stopAt);
}

/**
 * Plays the "new message" chime — a doorbell-style falling interval, the second note
 * landing while the first is still ringing out.
 *
 * <p>Never throws: a browser that has not yet granted audio (the autoplay policy requires
 * a prior user gesture somewhere on the page) or has no Web Audio support simply stays
 * silent. A missed sound is never worth breaking the page over.</p>
 */
export function playMessageChime() {
  try {
    const ac = audioContext();
    if (!ac) return;
    if (ac.state === 'suspended') ac.resume().catch(() => undefined);
    const master = ac.createGain();
    master.gain.value = 0.55;
    master.connect(ac.destination);
    note(ac, master, 880.00, 0, 0.42, 0.85);    // A5
    note(ac, master, 659.25, 0.13, 0.55, 0.75); // E5, arriving before A5 fades
  } catch {
    /* audio is a nice-to-have, never worth breaking the page over */
  }
}
