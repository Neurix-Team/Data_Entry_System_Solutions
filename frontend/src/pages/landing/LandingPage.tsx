import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { Link } from "react-router-dom";
import { useT } from "../../i18n";
import { useTheme } from "../../context/ThemeContext";
import { landingCopy, experienceCopy } from "./copy";
import "./landing.css";

// Demo requests open the visitor's email application; no server submission.
const DEMO_INBOX = "demo@neurix.app";

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

function Icon({
  name,
  size = 20,
}: {
  name: keyof typeof ICONS | string;
  size?: number;
}) {
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

function DemoForm() {
  const { lang } = useT();
  const c = landingCopy[lang].demo;
  const [sent, setSent] = useState(false);
  const [errors, setErrors] = useState<Record<string, "required" | "badEmail">>({});

  const onSubmit = useCallback(
    (e: FormEvent<HTMLFormElement>) => {
      e.preventDefault();
      const data = new FormData(e.currentTarget);
      const name = String(data.get("name") ?? "").trim();
      const org = String(data.get("org") ?? "").trim();
      const email = String(data.get("email") ?? "").trim();
      const message = String(data.get("message") ?? "").trim();

      const next: Record<string, "required" | "badEmail"> = {};
      if (!name) next.name = "required";
      if (!org) next.org = "required";
      if (!email) next.email = "required";
      else if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email))
        next.email = "badEmail";
      setErrors(next);
      if (Object.keys(next).length) {
        const first = e.currentTarget.querySelector<HTMLElement>(
          `[name="${Object.keys(next)[0]}"]`,
        );
        first?.focus();
        return;
      }

      const body = [
        `${c.name}: ${name}`,
        `${c.org}: ${org}`,
        `${c.email}: ${email}`,
        "",
        message,
      ].join("\n");
      window.location.href = `mailto:${DEMO_INBOX}?subject=${encodeURIComponent(
        `Neurix demo — ${org}`,
      )}&body=${encodeURIComponent(body)}`;
      setSent(true);
    },
    [c],
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
        {/* A locked-down desktop may have no mail handler at all, in which
            case nothing opened — the address has to be readable here. */}
        <p className="nx-note nx-fallback">
          {c.direct}{" "}
          <a href={`mailto:${DEMO_INBOX}`} className="nx-mail">
            {DEMO_INBOX}
          </a>
        </p>
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
          aria-describedby={errors.name ? "nx-err-name" : undefined}
          autoComplete="name"
        />
        {errors.name && (
          <span className="nx-err" id="nx-err-name" role="alert">
            {c[errors.name]}
          </span>
        )}
      </label>
      <label className="nx-field">
        <span className="nx-stencil">{c.org}</span>
        <input
          name="org"
          placeholder={c.orgPh}
          aria-invalid={!!errors.org}
          aria-describedby={errors.org ? "nx-err-org" : undefined}
          autoComplete="organization"
        />
        {errors.org && (
          <span className="nx-err" id="nx-err-org" role="alert">
            {c[errors.org]}
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
          aria-describedby={errors.email ? "nx-err-email" : undefined}
          autoComplete="email"
        />
        {errors.email && (
          <span className="nx-err" id="nx-err-email" role="alert">
            {c[errors.email]}
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
      <p className="nx-note nx-fallback">
        {c.direct}{" "}
        <a href={`mailto:${DEMO_INBOX}`} className="nx-mail">
          {DEMO_INBOX}
        </a>
      </p>
      <p className="nx-note nx-honest">{c.honest}</p>
    </form>
  );
}

function RecordFields() {
  const { lang } = useT();
  const t = experienceCopy[lang];
  return (
    <dl className="nx-record-fields">
      {t.fields.map((field, i) => (
        <div key={field}>
          <dt>{field}</dt>
          <dd dir={i > 0 ? "ltr" : undefined}>{t.values[i]}</dd>
        </div>
      ))}
    </dl>
  );
}

function DocumentScene() {
  const { lang } = useT();
  const t = experienceCopy[lang];
  return (
    <div className="nx-scene" aria-label={t.preview}>
      <div className="nx-scene-grid" aria-hidden="true" />
      <div className="nx-scene-caption">
        <span className="nx-live-dot" />
        {t.preview}
        <span>{t.sample}</span>
      </div>
      <article className="nx-paper">
        <div className="nx-paper-head">
          <Icon name="note" size={25} />
          <span>
            NEURIX ARCHIVE
            <br />
            <small>DOCUMENT / 0412</small>
          </span>
        </div>
        <div className="nx-paper-rule" />
        <p className="nx-paper-sub">{t.source}</p>
        <h3>{t.document}</h3>
        <p className="nx-paper-sub">{t.documentSub}</p>
        <div className="nx-paper-lines" aria-hidden="true">
          {Array.from({ length: 6 }, (_, i) => (
            <i key={i} />
          ))}
        </div>
        <div className="nx-paper-highlight">
          <span>{t.fields[1]}</span>
          <b dir="ltr">MC–0412</b>
          <i className="nx-scanner" />
        </div>
        <div
          className="nx-paper-lines nx-paper-lines--short"
          aria-hidden="true"
        >
          <i />
          <i />
        </div>
        <span className="nx-paper-page">01 / 12</span>
      </article>
      <article className="nx-result">
        <div className="nx-result-head">
          <span className="nx-icon-tile">
            <Icon name="layers" />
          </span>
          <div>
            <small>{t.extracted}</small>
            <b>{t.document}</b>
          </div>
        </div>
        <RecordFields />
        <div className="nx-result-foot">
          <span className="nx-live-dot" />
          {t.record}
          <Icon name="check" size={17} />
        </div>
      </article>
      <div className="nx-approved">
        <span>
          <Icon name="check" size={20} />
        </span>
        <b>{t.approved}</b>
        <small>
          {lang === "ar"
            ? "خطوة أقرب لأرشيف منظّم"
            : "One step closer to an organized archive"}
        </small>
      </div>
      <span className="nx-scene-index" aria-hidden="true">
        01 — 05 / THE DOCUMENT JOURNEY
      </span>
    </div>
  );
}

function WorkflowPreview({ step }: { step: number }) {
  const { lang } = useT();
  const t = experienceCopy[lang];
  return (
    <div className="nx-work-preview">
      <div className="nx-preview-bar">
        <span className="nx-preview-dots" aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
        <span>NEURIX / {t.stages[step].label}</span>
        <Icon name="shield" size={16} />
      </div>
      <div className="nx-preview-body" key={step}>
        {step === 0 && (
          <>
            <span className="nx-big-icon">
              <Icon name="folder" size={30} />
            </span>
            <h3>{t.uploadLabel}</h3>
            {[
              "Meeting-minutes.pdf",
              "Correspondence.docx",
              "Archive-scan.jpg",
            ].map((file, i) => (
              <div className="nx-file" key={file}>
                <Icon name={i === 2 ? "image" : "note"} />
                <span>
                  <b dir="ltr">{file}</b>
                  <small>{t.uploadState}</small>
                </span>
                <Icon name="check" />
              </div>
            ))}
          </>
        )}
        {step === 1 && (
          <>
            <span className="nx-preview-label">
              {t.extractLabel} <b dir="ltr">AR + EN</b>
            </span>
            <div className="nx-extracted-text">
              <p>{t.extractText}</p>
              <span dir="ltr">Reference: MC–0412 / 1998</span>
            </div>
            <div className="nx-extract-footer">
              <Icon name="scan" />
              {t.stages[1].points[2]}
            </div>
          </>
        )}
        {step === 2 && (
          <>
            <span className="nx-big-icon">
              <Icon name="layers" size={30} />
            </span>
            <h3>{t.document}</h3>
            <RecordFields />
          </>
        )}
        {step === 3 && (
          <div className="nx-review-preview">
            <span className="nx-review-check">
              <Icon name="check" size={50} />
            </span>
            <h3>{t.approved}</h3>
            <p>{t.document}</p>
            <span className="nx-status-pill">REVIEW → COMPLETED</span>
          </div>
        )}
        {step === 4 && (
          <>
            <span className="nx-big-icon">
              <Icon name="folder" size={30} />
            </span>
            <h3>{t.stages[4].title}</h3>
            <pre className="nx-export-tree" dir="ltr">
              {
                "Project archive/\n  Correspondence/\n    MC-0412.pdf\n    entry-0412.md\n  index.csv"
              }
            </pre>
          </>
        )}
      </div>
      <div className="nx-preview-foot">
        <span className="nx-live-dot" />
        {t.sample}
        <span dir="ltr">0{step + 1} / 05</span>
      </div>
    </div>
  );
}

export function LandingPage() {
  const { lang, dir, setLang } = useT();
  const { theme, toggle } = useTheme();
  const c = landingCopy[lang];
  const t = experienceCopy[lang];
  const [step, setStep] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const navIds = ["floor", "isolation", "roles", "ops"];

  useEffect(() => {
    const previous = document.title;
    document.title = `Neurix — ${t.footer}`;
    return () => {
      document.title = previous;
    };
  }, [t.footer]);

  return (
    <div className="nx" dir={dir}>
      <a className="nx-skip" href="#top">
        {lang === "ar" ? "انتقل إلى المحتوى" : "Skip to content"}
      </a>
      <header className="nx-header">
        <div className="nx-shell nx-header-in">
          <a className="nx-brand" href="#top" aria-label="Neurix">
            <img src="/neurix-mark.png" alt="" width={35} height={35} />
            <b>
              neurix<span>.</span>
            </b>
          </a>
          <nav
            className="nx-header-links"
            aria-label={lang === "ar" ? "القائمة الرئيسية" : "Main navigation"}
          >
            {t.nav.map((item, i) => (
              <a href={`#${navIds[i]}`} key={item}>
                {item}
              </a>
            ))}
          </nav>
          <div className="nx-header-actions">
            <button
              className="nx-language"
              onClick={() => setLang(lang === "ar" ? "en" : "ar")}
              aria-label={
                lang === "ar" ? "Switch to English" : "التبديل إلى العربية"
              }
            >
              <Icon name="globe" size={16} />
              {lang === "ar" ? "EN" : "عربي"}
            </button>
            <button
              className="nx-theme"
              onClick={toggle}
              aria-label={lang === "ar" ? "تبديل المظهر" : "Toggle theme"}
            >
              <Icon name={theme === "dark" ? "sun" : "moon"} size={18} />
            </button>
            <Link className="nx-login" to="/login">
              {c.nav.signIn}
              <Icon name="arrow" size={16} />
            </Link>
            <button
              className="nx-menu-button"
              aria-label={lang === "ar" ? "القائمة" : "Menu"}
              aria-expanded={menuOpen}
              aria-controls="nx-mobile-nav"
              onClick={() => setMenuOpen(!menuOpen)}
              onKeyDown={(e) => {
                if (e.key === "Escape") setMenuOpen(false);
              }}
            >
              <Icon name="queue" />
            </button>
          </div>
        </div>
        {menuOpen && (
          <nav
            className="nx-mobile-nav nx-shell"
            id="nx-mobile-nav"
            aria-label={lang === "ar" ? "قائمة الموبايل" : "Mobile navigation"}
            onKeyDown={(e) => {
              if (e.key === "Escape") setMenuOpen(false);
            }}
          >
            {t.nav.map((item, i) => (
              <a
                href={`#${navIds[i]}`}
                key={item}
                onClick={() => setMenuOpen(false)}
              >
                {item}
                <Icon name="arrow" size={16} />
              </a>
            ))}
          </nav>
        )}
      </header>
      <main id="top" tabIndex={-1}>
        <section className="nx-shell nx-hero">
          <div className="nx-hero-copy">
            <span className="nx-eyebrow">
              <span className="nx-live-dot" />
              {t.eyebrow}
            </span>
            <h1>
              {t.title}
              <em>{t.accent}</em>
            </h1>
            <p className="nx-hero-lede">{t.intro}</p>
            <div className="nx-hero-actions">
              <a className="nx-commit" href="#demo">
                {t.primary}
                <Icon name="arrow" size={19} />
              </a>
              <a className="nx-text-link" href="#floor">
                {t.secondary}
                <span>↗</span>
              </a>
            </div>
            <div className="nx-promises">
              {t.promises.map((p) => (
                <span key={p}>
                  <Icon name="check" size={16} />
                  {p}
                </span>
              ))}
            </div>
          </div>
          <DocumentScene />
        </section>
        <section className="nx-formats nx-shell" aria-label={t.formats}>
          <div>
            <b>{t.formats}</b>
            <span>{t.formatNote}</span>
          </div>
          <div className="nx-format-list">
            {["PDF", "Word", "Excel", "Images", "TXT"].map((format, i) => (
              <span key={format}>
                <Icon
                  name={["note", "log", "grid", "image", "field"][i]}
                  size={23}
                />
                {format}
              </span>
            ))}
          </div>
        </section>
        <section className="nx-section nx-shell" id="floor">
          <div className="nx-section-heading">
            <span className="nx-eyebrow">{t.workflowEyebrow}</span>
            <h2>{t.workflowTitle}</h2>
            <p>{t.workflowIntro}</p>
          </div>
          <div
            className="nx-step-tabs"
            role="tablist"
            aria-label={t.workflowTitle}
          >
            {t.stages.map((stage, i) => (
              <button
                key={stage.label}
                ref={(el) => {
                  tabRefs.current[i] = el;
                }}
                id={`nx-tab-${i}`}
                role="tab"
                aria-selected={i === step}
                aria-controls="nx-workflow-panel"
                tabIndex={i === step ? 0 : -1}
                onClick={() => setStep(i)}
                onKeyDown={(e) => {
                  let next = i;
                  if (e.key === "ArrowRight")
                    next = (i + (dir === "rtl" ? 4 : 1)) % 5;
                  else if (e.key === "ArrowLeft")
                    next = (i + (dir === "rtl" ? 1 : 4)) % 5;
                  else if (e.key === "Home") next = 0;
                  else if (e.key === "End") next = 4;
                  else return;
                  e.preventDefault();
                  setStep(next);
                  tabRefs.current[next]?.focus();
                }}
              >
                <span>0{i + 1}</span>
                <b>{stage.label}</b>
                <Icon
                  name={["folder", "scan", "layers", "check", "sync"][i]}
                  size={21}
                />
              </button>
            ))}
          </div>
          <div
            className="nx-workflow"
            id="nx-workflow-panel"
            role="tabpanel"
            aria-labelledby={`nx-tab-${step}`}
            tabIndex={0}
          >
            <div className="nx-work-copy">
              <span className="nx-step-number">
                0{step + 1}
                <small> / 05</small>
              </span>
              <h3>{t.stages[step].title}</h3>
              <p>{t.stages[step].body}</p>
              <ul>
                {t.stages[step].points.map((point) => (
                  <li key={point}>
                    <Icon name="check" size={18} />
                    {point}
                  </li>
                ))}
              </ul>
              <a className="nx-text-link" href="#demo">
                {c.nav.demo}
                <Icon name="arrow" size={18} />
              </a>
            </div>
            <WorkflowPreview step={step} />
          </div>
        </section>
        <section className="nx-security" id="isolation">
          <div className="nx-shell nx-security-grid">
            <div>
              <span className="nx-eyebrow">{t.securityEyebrow}</span>
              <h2>{t.securityTitle}</h2>
              <p>{t.securityBody}</p>
              <span className="nx-security-sign">
                <Icon name="shield" size={20} />
                NEURIX / YOUR WORKSPACE
              </span>
            </div>
            <div className="nx-security-items">
              {t.securityItems.map((item, i) => (
                <article key={item.title}>
                  <span className="nx-security-icon">
                    <Icon name={["layers", "key", "log"][i]} size={23} />
                  </span>
                  <div>
                    <h3>{item.title}</h3>
                    <p>{item.body}</p>
                  </div>
                  <span className="nx-security-index">0{i + 1}</span>
                </article>
              ))}
            </div>
          </div>
        </section>
        <section className="nx-section nx-shell" id="roles">
          <div className="nx-section-heading">
            <span className="nx-eyebrow">{t.teamEyebrow}</span>
            <h2>{t.teamTitle}</h2>
            <p>{t.teamIntro}</p>
          </div>
          <div className="nx-role-grid">
            {c.roles.items.map((role, i) => (
              <article className="nx-role" key={role.name}>
                <div className="nx-role-top">
                  <span className="nx-icon-tile">
                    <Icon name={["note", "check", "grid"][i]} size={25} />
                  </span>
                  <span>0{i + 1}</span>
                </div>
                <h3>{role.name}</h3>
                <p>{t.roleDescriptions[i]}</p>
                <ul>
                  {role.can.slice(0, 3).map((item) => (
                    <li key={item}>
                      <Icon name="check" size={16} />
                      {item}
                    </li>
                  ))}
                </ul>
              </article>
            ))}
          </div>
        </section>
        <section className="nx-faq nx-shell" id="ops">
          <div>
            <span className="nx-eyebrow">FAQ</span>
            <h2>{t.faqTitle}</h2>
            <p>{t.faqIntro}</p>
          </div>
          <div className="nx-faq-list">
            {t.faqs.map((faq, i) => (
              <details key={faq.q} open={i === 0 ? true : undefined}>
                <summary>
                  {faq.q}
                  <span aria-hidden="true">+</span>
                </summary>
                <p>{faq.a}</p>
              </details>
            ))}
          </div>
        </section>
        <section className="nx-demo nx-shell" id="demo">
          <div className="nx-demo-copy">
            <span className="nx-eyebrow">{t.demoEyebrow}</span>
            <h2>{t.demoTitle}</h2>
            <p>{t.demoBody}</p>
            <span className="nx-demo-note">
              <Icon name="note" size={19} />
              {t.demoNote}
            </span>
          </div>
          <DemoForm />
        </section>
      </main>
      <footer className="nx-footer">
        <div className="nx-shell nx-footer-in">
          <a className="nx-brand" href="#top">
            <img src="/neurix-mark.png" alt="" width={30} height={30} />
            <b>
              neurix<span>.</span>
            </b>
          </a>
          <p>{t.footer}</p>
          <span>© {new Date().getFullYear()} Neurix</span>
          <a
            href="#top"
            className="nx-back-top"
            aria-label={lang === "ar" ? "العودة للأعلى" : "Back to top"}
          >
            ↑
          </a>
        </div>
      </footer>
    </div>
  );
}
