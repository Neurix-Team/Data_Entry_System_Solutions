import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useT } from '../../i18n';
import { useTheme } from '../../context/ThemeContext';
import { landingCopy } from './copy';
import './landing.css';

/* Where "Request a demo" is delivered. PLACEHOLDER — point this at a real
   sales inbox before the page goes public. */
const DEMO_INBOX = 'demo@neurix.app';

const CHUNKS = 48;
const LANES = 5;

function usePrefersReducedMotion() {
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const apply = () => setReduced(mq.matches);
    apply();
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  }, []);
  return reduced;
}

/** Reveals children once they scroll into view. */
function useReveal() {
  const ref = useRef<HTMLElement | null>(null);
  useEffect(() => {
    const root = ref.current;
    if (!root) return;
    let pending = Array.from(root.querySelectorAll<HTMLElement>('.nx-reveal'));
    if (!pending.length) return;

    /* Reveal anything at or above the fold. Covering "already scrolled past"
       as well as "entering from below" means a jump to an anchor can never
       leave a section stranded at opacity 0. */
    const sweep = () => {
      const fold = window.innerHeight * 0.92;
      pending = pending.filter((el) => {
        if (el.getBoundingClientRect().top >= fold) return true;
        el.dataset.shown = '1';
        return false;
      });
      if (!pending.length) detach();
    };

    let queued = false;
    const onScroll = () => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(() => {
        queued = false;
        sweep();
      });
    };
    function detach() {
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
    }

    sweep();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    return detach;
  }, []);
  return ref;
}

/** True once the element has been on screen at least once. */
function useInView<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);
  const [seen, setSeen] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      ([e]) => {
        if (e.isIntersecting) {
          setSeen(true);
          io.disconnect();
        }
      },
      { threshold: 0.25 }
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);
  return [ref, seen] as const;
}

const ICONS: Record<string, JSX.Element> = {
  grid: (
    <>
      <rect x="2.75" y="2.75" width="6" height="6" rx="1" />
      <rect x="11.25" y="2.75" width="6" height="6" rx="1" />
      <rect x="2.75" y="11.25" width="6" height="6" rx="1" />
      <rect x="11.25" y="11.25" width="6" height="6" rx="1" />
    </>
  ),
  resume: (
    <>
      <path d="M17 10a7 7 0 1 1-2.05-4.95" />
      <path d="M17.25 3v3.5h-3.5" />
    </>
  ),
  retry: (
    <>
      <path d="M3 10a7 7 0 0 1 12-4.9" />
      <path d="M17 10a7 7 0 0 1-12 4.9" />
      <path d="M15 2.5V6h-3.5" />
      <path d="M5 17.5V14h3.5" />
    </>
  ),
  gauge: (
    <>
      <path d="M3 15a7.5 7.5 0 1 1 14 0" />
      <path d="m10 15 3.4-5" />
      <circle cx="10" cy="15" r="1.1" />
    </>
  ),
  scan: (
    <>
      <path d="M3 6.5V4.75A1.75 1.75 0 0 1 4.75 3H6.5" />
      <path d="M13.5 3h1.75A1.75 1.75 0 0 1 17 4.75V6.5" />
      <path d="M17 13.5v1.75A1.75 1.75 0 0 1 15.25 17H13.5" />
      <path d="M6.5 17H4.75A1.75 1.75 0 0 1 3 15.25V13.5" />
      <path d="M3 10h14" />
    </>
  ),
  image: (
    <>
      <rect x="2.75" y="3.75" width="14.5" height="12.5" rx="1.5" />
      <circle cx="7.25" cy="8" r="1.25" />
      <path d="m3.5 14.5 3.75-3.6 3 2.6 2.6-2.3 3.65 3.3" />
    </>
  ),
  queue: (
    <>
      <path d="M3 5h14" />
      <path d="M3 10h9" />
      <path d="M3 15h5" />
      <circle cx="15.5" cy="13.5" r="2.75" />
    </>
  ),
  layers: (
    <>
      <path d="m10 2.75 7 3.6-7 3.6-7-3.6 7-3.6Z" />
      <path d="m3 10.6 7 3.6 7-3.6" />
      <path d="m3 14.3 7 3.6 7-3.6" />
    </>
  ),
  field: (
    <>
      <rect x="2.75" y="4.25" width="14.5" height="5" rx="1.25" />
      <rect x="2.75" y="11.5" width="9" height="4.75" rx="1.25" />
    </>
  ),
  stack: (
    <>
      <rect x="2.75" y="2.75" width="10" height="10" rx="1.25" />
      <path d="M7.25 17.25h8a2 2 0 0 0 2-2v-8" />
    </>
  ),
  check: (
    <>
      <circle cx="10" cy="10" r="7.25" />
      <path d="m6.75 10.25 2.2 2.2 4.3-4.6" />
    </>
  ),
  log: (
    <>
      <path d="M5.5 2.75h9a1.75 1.75 0 0 1 1.75 1.75v11a1.75 1.75 0 0 1-1.75 1.75h-9A1.75 1.75 0 0 1 3.75 15.5v-11A1.75 1.75 0 0 1 5.5 2.75Z" />
      <path d="M7 7h6" />
      <path d="M7 10.25h6" />
      <path d="M7 13.5h3.5" />
    </>
  ),
  chart: (
    <>
      <path d="M3 17h14" />
      <path d="M6 17V9.5" />
      <path d="M10 17V4.75" />
      <path d="M14 17v-4.5" />
    </>
  ),
  folder: (
    <>
      <path d="M3 6.25a1.5 1.5 0 0 1 1.5-1.5h3l1.75 2h6.25a1.5 1.5 0 0 1 1.5 1.5v6.5a1.5 1.5 0 0 1-1.5 1.5H4.5A1.5 1.5 0 0 1 3 14.75Z" />
    </>
  ),
  sync: (
    <>
      <path d="M3.25 8.5a6.75 6.75 0 0 1 11.4-3.3L17 7.5" />
      <path d="M16.75 11.5a6.75 6.75 0 0 1-11.4 3.3L3 12.5" />
      <path d="M17 3.75V7.5h-3.75" />
      <path d="M3 16.25V12.5h3.75" />
    </>
  ),
  note: (
    <>
      <path d="M11.5 2.75H5.75A1.75 1.75 0 0 0 4 4.5v11a1.75 1.75 0 0 0 1.75 1.75h8.5A1.75 1.75 0 0 0 16 15.5V7.25Z" />
      <path d="M11.25 2.75V7.5H16" />
    </>
  ),
  key: (
    <>
      <circle cx="6.5" cy="10" r="3.25" />
      <path d="M9.75 10H17" />
      <path d="M14.5 10v2.75" />
    </>
  ),
  stamp: (
    <>
      <path d="M6.5 8.25V5.5a3.5 3.5 0 1 1 7 0v2.75" />
      <path d="M4 12.25h12v1.5a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 4 13.75Z" />
      <path d="M6.5 8.25h7l1.25 4h-9.5Z" />
    </>
  ),
  filter: (
    <>
      <path d="M3 4.5h14l-5.25 6v5l-3.5 1.75V10.5Z" />
    </>
  ),
  shield: (
    <>
      <path d="M10 2.75l5.75 2.25v4.75c0 3.4-2.35 6.1-5.75 7.5-3.4-1.4-5.75-4.1-5.75-7.5V5Z" />
      <path d="m7.5 9.75 1.85 1.85 3.4-3.6" />
    </>
  ),
  globe: (
    <>
      <circle cx="10" cy="10" r="7.25" />
      <path d="M2.9 10h14.2" />
      <path d="M10 2.75c1.9 2 2.9 4.5 2.9 7.25S11.9 15.25 10 17.25C8.1 15.25 7.1 12.75 7.1 10s1-5.25 2.9-7.25Z" />
    </>
  ),
  sun: (
    <>
      <circle cx="10" cy="10" r="3.5" />
      <path d="M10 2.25v2M10 15.75v2M2.25 10h2M15.75 10h2M4.5 4.5l1.4 1.4M14.1 14.1l1.4 1.4M15.5 4.5l-1.4 1.4M5.9 14.1l-1.4 1.4" />
    </>
  ),
  moon: (
    <>
      <path d="M16 11.6A6.75 6.75 0 0 1 8.4 4a6.75 6.75 0 1 0 7.6 7.6Z" />
    </>
  ),
  arrow: (
    <>
      <path d="M3.5 10h13" />
      <path d="m11.75 5.25 4.75 4.75-4.75 4.75" />
    </>
  ),
};

function Icon({ name, size = 20 }: { name: keyof typeof ICONS | string; size?: number }) {
  const glyph = ICONS[name] ?? ICONS.check;
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {glyph}
    </svg>
  );
}

const STATION_ICONS: string[][] = [
  ['grid', 'resume', 'retry', 'gauge'],
  ['scan', 'image', 'queue'],
  ['layers', 'field', 'stack'],
  ['check', 'log', 'chart'],
  ['folder', 'sync', 'note', 'key'],
];

/* ── Station 01 apparatus: the chunk grid ───────────────────── */

function ChunkGrid({ reduced }: { reduced: boolean }) {
  const { lang } = useT();
  const c = landingCopy[lang].upload;
  const [ref, seen] = useInView<HTMLDivElement>();
  const [states, setStates] = useState<string[]>(() => Array(CHUNKS).fill('idle'));
  const [speed, setSpeed] = useState(0);

  useEffect(() => {
    if (!seen) return;
    if (reduced) {
      setStates(Array(CHUNKS).fill('done'));
      setSpeed(38);
      return;
    }
    let next = 0;
    const inFlight = new Map<number, number>();
    const tick = window.setInterval(() => {
      setStates((prev) => {
        const out = [...prev];
        inFlight.forEach((ticks, idx) => {
          if (ticks <= 0) {
            out[idx] = 'done';
            inFlight.delete(idx);
          } else {
            inFlight.set(idx, ticks - 1);
          }
        });
        while (inFlight.size < 4 && next < CHUNKS) {
          out[next] = 'flight';
          inFlight.set(next, 1 + Math.floor(Math.random() * 3));
          next += 1;
        }
        if (next >= CHUNKS && inFlight.size === 0) {
          next = 0;
          return Array(CHUNKS).fill('idle');
        }
        return out;
      });
      setSpeed(28 + Math.round(Math.random() * 22));
    }, 220);
    return () => window.clearInterval(tick);
  }, [seen, reduced]);

  const done = states.filter((s) => s === 'done').length;
  const pct = Math.round((done / CHUNKS) * 100);
  const busy = states.filter((s) => s === 'flight').length;

  return (
    <div className="nx-panel nx-apparatus" ref={ref}>
      <div className="nx-app-head">
        <span className="nx-stencil nx-stencil--signal">{c.label}</span>
        <span className="nx-note nx-num">{pct}%</span>
      </div>
      <p className="nx-note" style={{ marginBottom: '0.875rem' }}>
        <span className="nx-num">{c.file}</span> · {c.note}
      </p>
      <div className="nx-chunkgrid" aria-hidden="true">
        {states.map((s, i) => (
          <span key={i} className="nx-chunk" data-state={s} />
        ))}
      </div>
      <div className="nx-workers" aria-hidden="true">
        {[0, 1, 2, 3].map((w) => (
          <div key={w} className="nx-worker" data-busy={w < busy ? '1' : '0'}>
            {c.workers} {w + 1}
            <span className="nx-bar">
              <i style={{ transform: `scaleX(${w < busy ? 1 : 0})` }} />
            </span>
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', gap: '1.5rem', marginTop: '1.125rem', flexWrap: 'wrap' }}>
        <Readout value={String(speed).padStart(2, '0')} unit={c.speed} />
        <Readout value={String(Math.max(0, 60 - Math.round((pct / 100) * 60))).padStart(2, '0')} unit={c.eta} signal />
      </div>
    </div>
  );
}

function Readout({ value, unit, signal }: { value: string; unit: string; signal?: boolean }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'flex-end', gap: '0.25rem' }}>
      <span className="nx-readout" dir="ltr">
        {value.split('').map((d, i) => (
          <span key={i} className={signal ? 'nx-digit nx-digit--signal' : 'nx-digit'}>
            {d}
          </span>
        ))}
      </span>
      <span className="nx-readout-unit">{unit}</span>
    </span>
  );
}

/* ── Station 02 apparatus: the OCR monitor ──────────────────── */

function OcrMonitor({ reduced }: { reduced: boolean }) {
  const { lang } = useT();
  const c = landingCopy[lang].ocr;
  const [ref, seen] = useInView<HTMLDivElement>();
  const [typed, setTyped] = useState<number[]>(() => c.rows.map(() => 0));

  useEffect(() => {
    setTyped(c.rows.map(() => (reduced ? Number.MAX_SAFE_INTEGER : 0)));
  }, [c, reduced]);

  useEffect(() => {
    if (!seen || reduced) return;
    let row = 0;
    let n = 0;
    const tick = window.setInterval(() => {
      if (row >= c.rows.length) {
        row = 0;
        n = 0;
        setTyped(c.rows.map(() => 0));
        return;
      }
      n += 1;
      setTyped((prev) => {
        const out = [...prev];
        out[row] = n;
        return out;
      });
      if (n >= c.rows[row].text.length) {
        row += 1;
        n = 0;
      }
    }, 42);
    return () => window.clearInterval(tick);
  }, [seen, reduced, c]);

  return (
    <div className="nx-crt" ref={ref}>
      {!reduced && <span className="nx-scanline" aria-hidden="true" />}
      <div className="nx-app-head" style={{ position: 'relative', zIndex: 1 }}>
        <span className="nx-stencil nx-stencil--signal">{c.label}</span>
        <span className="nx-note" dir="ltr">
          {c.status}
        </span>
      </div>
      <div className="nx-ocr-rows">
        {c.rows.map((r, i) => {
          const shown = r.text.slice(0, typed[i] ?? 0);
          const active = !reduced && (typed[i] ?? 0) > 0 && (typed[i] ?? 0) < r.text.length;
          return (
            <div className="nx-ocr-row" key={i}>
              <span className="nx-ocr-page">{r.page}</span>
              <span className="nx-ocr-text" data-lang={r.lang} dir={r.lang === 'ar' ? 'rtl' : 'ltr'}>
                {shown}
                {active && <span className="nx-caret" />}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/* ── The hero board ─────────────────────────────────────────── */

function FloorBoard({ reduced }: { reduced: boolean }) {
  const { lang } = useT();
  const c = landingCopy[lang];
  const [active, setActive] = useState(0);

  useEffect(() => {
    if (reduced) return;
    const tick = window.setInterval(() => setActive((a) => (a + 1) % LANES), 3000);
    return () => window.clearInterval(tick);
  }, [reduced]);

  return (
    <div className="nx-board-stage">
      <div className="nx-board">
        <div className="nx-board-lanes">
          {c.stations.map((s, i) => (
            <div
              key={s.no}
              className={i === active ? 'nx-lane-slot nx-lane-slot--active' : 'nx-lane-slot'}
            >
              {i === active && i === 0 && !reduced && <span className="nx-scanhead" aria-hidden="true" />}
              <span className="nx-stencil nx-lane-name">
                <b className="nx-num">{s.no}</b>
                {s.lane}
              </span>
            </div>
          ))}

          {/* One batch, one node: it slides lane to lane rather than being
              re-created in each, so the card the visitor follows is the
              same card the whole way across the board. */}
          <article className="nx-batch nx-batch--transit" style={{ ['--i' as string]: active }}>
            <span className="nx-batch-id nx-num">{c.hero.batchId}</span>
            <p className="nx-batch-title">{c.hero.batchTitle}</p>
            <div className="nx-batch-meta">
              {c.hero.batchMeta.map((m) => (
                <span key={m}>{m}</span>
              ))}
            </div>
            <span className="nx-batch-stage nx-stencil">{c.stations[active]?.lane}</span>
          </article>
        </div>
      </div>

      <div className="nx-board-gauges">
        {c.hero.gauges.map((g, i) => (
          <div key={g.label}>
            <Readout value={g.value} unit={g.unit} signal={i === 2} />
            <span className="nx-note nx-gauge-label">{g.label}</span>
          </div>
        ))}
      </div>
      <p className="nx-note nx-board-note">{c.hero.gaugeNote}</p>
    </div>
  );
}

/* ── Demo form ──────────────────────────────────────────────── */

function DemoForm() {
  const { lang } = useT();
  const c = landingCopy[lang].demo;
  const [sent, setSent] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const onSubmit = useCallback(
    (e: FormEvent<HTMLFormElement>) => {
      e.preventDefault();
      const data = new FormData(e.currentTarget);
      const name = String(data.get('name') ?? '').trim();
      const org = String(data.get('org') ?? '').trim();
      const email = String(data.get('email') ?? '').trim();
      const message = String(data.get('message') ?? '').trim();

      const next: Record<string, string> = {};
      if (!name) next.name = c.required;
      if (!org) next.org = c.required;
      if (!email) next.email = c.required;
      else if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email)) next.email = c.badEmail;
      setErrors(next);
      if (Object.keys(next).length) {
        const first = e.currentTarget.querySelector<HTMLElement>(`[name="${Object.keys(next)[0]}"]`);
        first?.focus();
        return;
      }

      const body = [`${c.name}: ${name}`, `${c.org}: ${org}`, `${c.email}: ${email}`, '', message].join('\n');
      window.location.href = `mailto:${DEMO_INBOX}?subject=${encodeURIComponent(
        `Neurix demo — ${org}`
      )}&body=${encodeURIComponent(body)}`;
      setSent(true);
    },
    [c]
  );

  if (sent) {
    return (
      <div className="nx-panel nx-form">
        <div className="nx-form-ok">
          <Icon name="check" />
          <span>
            <b>{c.okTitle}</b>
            <br />
            {c.okBody}
          </span>
        </div>
      </div>
    );
  }

  return (
    <form className="nx-panel nx-form" onSubmit={onSubmit} noValidate>
      <label className="nx-field">
        <span className="nx-stencil">{c.name}</span>
        <input
          name="name"
          placeholder={c.namePh}
          aria-invalid={!!errors.name}
          aria-describedby={errors.name ? 'nx-err-name' : undefined}
          autoComplete="name"
        />
        {errors.name && (
          <span className="nx-err" id="nx-err-name" role="alert">
            {errors.name}
          </span>
        )}
      </label>
      <label className="nx-field">
        <span className="nx-stencil">{c.org}</span>
        <input
          name="org"
          placeholder={c.orgPh}
          aria-invalid={!!errors.org}
          aria-describedby={errors.org ? 'nx-err-org' : undefined}
          autoComplete="organization"
        />
        {errors.org && (
          <span className="nx-err" id="nx-err-org" role="alert">
            {errors.org}
          </span>
        )}
      </label>
      <label className="nx-field">
        <span className="nx-stencil">{c.email}</span>
        <input
          name="email"
          type="email"
          dir="ltr"
          placeholder={c.emailPh}
          aria-invalid={!!errors.email}
          aria-describedby={errors.email ? 'nx-err-email' : undefined}
          autoComplete="email"
        />
        {errors.email && (
          <span className="nx-err" id="nx-err-email" role="alert">
            {errors.email}
          </span>
        )}
      </label>
      <label className="nx-field">
        <span className="nx-stencil">{c.message}</span>
        <textarea name="message" placeholder={c.messagePh} />
      </label>
      <button type="submit" className="nx-commit">
        <Icon name="check" size={18} />
        {c.submit}
      </button>
      <p className="nx-note" style={{ marginTop: '1rem' }}>
        {c.honest}
      </p>
    </form>
  );
}

/* ── Page ───────────────────────────────────────────────────── */

export function LandingPage() {
  const { lang, dir, setLang } = useT();
  const { theme, toggle } = useTheme();
  const c = useMemo(() => landingCopy[lang], [lang]);
  const reduced = usePrefersReducedMotion();
  const rootRef = useReveal();

  useEffect(() => {
    document.title = 'Neurix — ' + c.footer.tagline;
  }, [c]);

  return (
    <div className="nx" ref={rootRef as React.RefObject<HTMLDivElement>} dir={dir}>
      <header className="nx-header nx-rail">
        <div className="nx-shell nx-header-in">
          <a className="nx-brand" href="#top">
            <img src="/neurix-mark.png" alt="" width={30} height={30} />
            <b>Neurix</b>
          </a>
          <nav className="nx-header-links">
            <a href="#floor">{c.nav.stations}</a>
            <a href="#isolation">{c.nav.security}</a>
            <a href="#roles">{c.nav.roles}</a>
            <a href="#ops">{c.nav.ops}</a>
          </nav>
          {/* The same wayfinding as one control, for rails too narrow to
              carry the full set. */}
          <select
            className="nx-jump"
            aria-label={c.nav.jump}
            value=""
            onChange={(e) => {
              const id = e.target.value;
              if (id) document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' });
              e.target.value = '';
            }}
          >
            <option value="">{c.nav.jump}</option>
            <option value="floor">{c.nav.stations}</option>
            <option value="isolation">{c.nav.security}</option>
            <option value="roles">{c.nav.roles}</option>
            <option value="ops">{c.nav.ops}</option>
            <option value="demo">{c.demo.title}</option>
          </select>
          <button
            type="button"
            className="nx-knob"
            onClick={() => setLang(lang === 'ar' ? 'en' : 'ar')}
            aria-label={lang === 'ar' ? 'Switch to English' : 'التبديل إلى العربية'}
          >
            <i aria-hidden="true" />
            {lang === 'ar' ? 'English' : 'العربية'}
          </button>
          <button
            type="button"
            className="nx-lamp"
            onClick={toggle}
            aria-label={c.nav.theme}
            title={c.nav.theme}
          >
            <Icon name={theme === 'dark' ? 'sun' : 'moon'} size={18} />
          </button>
          <Link className="nx-steel-btn" to="/login">
            {c.nav.signIn}
          </Link>
        </div>
      </header>

      <main id="top">
        <section className="nx-hero">
          {/* The headline is painted onto the plate the board is bolted to,
              and the commit switch is mounted on that same rail. */}
          <div className="nx-hero-plate nx-rail">
            <div className="nx-shell nx-hero-plate-in">
              <h1>
                {c.hero.title1} <em>{c.hero.titleEm}</em> {c.hero.title2}
              </h1>
              <div className="nx-hero-side">
                <p className="nx-hero-lede">{c.hero.lede}</p>
                <div className="nx-hero-actions">
                  <a className="nx-commit" href="#demo">
                    {c.hero.cta}
                  </a>
                  <Link className="nx-steel-btn" to="/login">
                    {c.hero.ctaSecondary}
                  </Link>
                </div>
              </div>
            </div>
          </div>

          <div className="nx-shell nx-hero-board">
            <FloorBoard reduced={reduced} />
          </div>
        </section>

        <section id="floor" className="nx-station nx-shell">
          <div className="nx-station-head nx-reveal">
            <span className="nx-station-no nx-num" aria-hidden="true">
              00
            </span>
            <div>
              <h2>{c.stationsHead.title}</h2>
              <p className="nx-lede">{c.stationsHead.lede}</p>
            </div>
          </div>
        </section>

        {c.stations.map((s, i) => (
          <section className="nx-station nx-shell" key={s.no} id={`station-${i + 1}`}>
            <div className="nx-station-head nx-reveal">
              <span className="nx-station-plate">
                <span className="nx-station-no nx-num" aria-hidden="true">
                  {s.no}
                </span>
                <span className="nx-stencil nx-stencil--signal">{s.lane}</span>
              </span>
              <div>
                <h2>{s.title}</h2>
                <p className="nx-lede">{s.lede}</p>
              </div>
            </div>

            <div className="nx-split">
              <ul className="nx-facts nx-reveal">
                {s.facts.map((f, fi) => (
                  <li key={f.title}>
                    <Icon name={STATION_ICONS[i]?.[fi] ?? 'check'} />
                    <span>
                      <b>{f.title}</b>
                      <span>{f.body}</span>
                    </span>
                  </li>
                ))}
              </ul>
              <div className="nx-reveal">
                {i === 0 && <ChunkGrid reduced={reduced} />}
                {i === 1 && <OcrMonitor reduced={reduced} />}
                {i === 2 && <CaptureCard />}
                {i === 3 && <ReviewCard />}
                {i === 4 && <ExportTree />}
              </div>
            </div>
          </section>
        ))}

        <section id="isolation" className="nx-station nx-shell">
          <div className="nx-station-head nx-station-head--plain nx-reveal">
            <div>
              <h2>{c.isolation.title}</h2>
              <p className="nx-lede">{c.isolation.lede}</p>
            </div>
          </div>
          <div className="nx-interlocks nx-reveal">
            {c.isolation.items.map((it, i) => (
              <article className="nx-interlock" key={it.title}>
                <span className="nx-bolt">
                  <Icon name={['stamp', 'filter', 'shield'][i] ?? 'shield'} />
                </span>
                <h3>{it.title}</h3>
                <p>{it.body}</p>
              </article>
            ))}
          </div>
          <p className="nx-note nx-reveal" style={{ marginTop: '1.25rem', maxWidth: '60ch' }}>
            {c.isolation.note}
          </p>
        </section>

        <section id="roles" className="nx-station nx-shell">
          <div className="nx-station-head nx-station-head--plain nx-reveal">
            <div>
              <h2>{c.roles.title}</h2>
              <p className="nx-lede">{c.roles.lede}</p>
            </div>
          </div>
          <div className="nx-roles nx-reveal">
            {c.roles.items.map((r, i) => (
              <article
                className="nx-role"
                key={r.name}
                style={{ ['--role' as string]: `var(--nx-tab-${[2, 1, 3][i] ?? 1})` }}
              >
                <h3>{r.name}</h3>
                <p>{r.body}</p>
                <ul>
                  {r.can.map((x) => (
                    <li key={x}>{x}</li>
                  ))}
                </ul>
              </article>
            ))}
          </div>
        </section>

        <section className="nx-station nx-shell">
          <div className="nx-station-head nx-station-head--plain nx-reveal">
            <div>
              <h2>{c.bilingual.title}</h2>
              <p className="nx-lede">{c.bilingual.lede}</p>
            </div>
          </div>
          <ul className="nx-facts nx-reveal" style={{ maxWidth: '72ch' }}>
            {c.bilingual.points.map((p, i) => (
              <li key={p.title}>
                <Icon name={['scan', 'layers', 'globe'][i] ?? 'check'} />
                <span>
                  <b>{p.title}</b>
                  <span>{p.body}</span>
                </span>
              </li>
            ))}
          </ul>
        </section>

        <section id="ops" className="nx-station nx-shell">
          <div className="nx-station-head nx-station-head--plain nx-reveal">
            <div>
              <h2>{c.ops.title}</h2>
              <p className="nx-lede">{c.ops.lede}</p>
            </div>
          </div>
          <div className="nx-ops nx-reveal">
            {c.ops.items.map((o) => (
              <div className="nx-op" key={o.title}>
                <b>{o.title}</b>
                <span>{o.body}</span>
              </div>
            ))}
          </div>
        </section>

        <section id="demo" className="nx-demo nx-shell">
          <div className="nx-demo-grid">
            <div className="nx-reveal">
              <h2>{c.demo.title}</h2>
              <p className="nx-lede" style={{ marginTop: '1rem' }}>
                {c.demo.lede}
              </p>
            </div>
            <div className="nx-reveal">
              <DemoForm />
            </div>
          </div>
        </section>
      </main>

      <footer className="nx-footer">
        <div className="nx-shell nx-footer-in">
          <span className="nx-note">
            © {new Date().getFullYear()} {c.footer.rights} — {c.footer.tagline}
          </span>
          <Link className="nx-steel-btn" to="/login">
            {c.footer.signIn}
          </Link>
        </div>
      </footer>
    </div>
  );
}

/* ── Small station apparatus ────────────────────────────────── */

/* Station 03: the finished record, as captured — values written into the
   entry, not an empty form. The product has no field-builder UI, so nothing
   here may look like one. */
function CaptureCard() {
  const { lang } = useT();
  const c = landingCopy[lang].capture;
  return (
    <div className="nx-panel nx-apparatus">
      <div className="nx-app-head">
        <span className="nx-stencil nx-stencil--signal">{c.label}</span>
        <span className="nx-note nx-num" dir="ltr">
          {c.ref}
        </span>
      </div>
      <dl className="nx-record">
        {c.rows.map((r) => (
          <div key={r.k} className={r.custom ? 'nx-record-row nx-record-row--custom' : 'nx-record-row'}>
            <dt className="nx-stencil">{r.k}</dt>
            <dd dir={r.ltr ? 'ltr' : undefined}>{r.v}</dd>
          </div>
        ))}
      </dl>
      <p className="nx-note nx-record-foot">
        <span className="nx-record-key" aria-hidden="true" />
        {c.customNote}
      </p>
    </div>
  );
}

/* Station 04: the approval stamp and the audit line it writes. */
function ReviewCard() {
  const { lang } = useT();
  const c = landingCopy[lang].review;
  return (
    <div className="nx-panel nx-apparatus">
      <div className="nx-app-head">
        <span className="nx-stencil nx-stencil--signal">{c.label}</span>
      </div>
      <div className="nx-stamp-area">
        <span className="nx-stamp" aria-hidden="true">
          <b>{c.stamp}</b>
          <i>{c.stampSub}</i>
        </span>
        <div className="nx-transition" dir="ltr">
          <span className="nx-state">{c.from}</span>
          <Icon name="arrow" size={18} />
          <span className="nx-state nx-state--done">{c.to}</span>
        </div>
      </div>
      <ul className="nx-audit">
        {c.audit.map((a) => (
          <li key={a}>
            <Icon name="log" size={15} />
            <span>{a}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ExportTree() {
  const { lang } = useT();
  const c = landingCopy[lang];
  return (
    <div className="nx-panel nx-apparatus">
      <div className="nx-app-head">
        <span className="nx-stencil nx-stencil--signal">
          {c.stations[4].no} · {c.stations[4].lane}
        </span>
      </div>
      <pre className="nx-tree">
        <b>Ministry Archive 1998/</b>
        {'\n  '}
        <b>Correspondence/</b>
        {'\n    MC-1998-0412.pdf      '}
        <i>new</i>
        {'\n    MC-1998-0413.pdf      '}
        <u>skipped</u>
        {'\n    entry-0412.md         '}
        <i>new</i>
        {'\n  '}
        <b>Land Registry/</b>
        {'\n    LR-2001-0088.tif      '}
        <u>skipped</u>
        {'\n  index.csv               '}
        <i>new</i>
      </pre>
    </div>
  );
}
