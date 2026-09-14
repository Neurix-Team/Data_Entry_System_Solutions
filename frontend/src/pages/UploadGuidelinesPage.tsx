import { LoginBackground } from '../components/auth/LoginBackground';
import { IconCheck } from '../components/Icons';
import { PreferencesToggle } from '../components/PreferencesToggle';
import { useT } from '../i18n';
import './upload-guidelines.css';

interface UploadGuidelinesPageProps {
  onContinue: () => void;
}

function ScanIcon() {
  return (
    <svg width={20} height={20} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 6.5V4.75A1.75 1.75 0 0 1 4.75 3H6.5" />
      <path d="M13.5 3h1.75A1.75 1.75 0 0 1 17 4.75V6.5" />
      <path d="M17 13.5v1.75A1.75 1.75 0 0 1 15.25 17H13.5" />
      <path d="M6.5 17H4.75A1.75 1.75 0 0 1 3 15.25V13.5" />
      <path d="M3 10h14" />
    </svg>
  );
}

function TableIcon() {
  return (
    <svg width={20} height={20} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="2.75" y="3.75" width="14.5" height="12.5" rx="1.5" />
      <path d="M2.75 8.5h14.5" />
      <path d="M2.75 13h14.5" />
      <path d="M8.5 3.75v12.5" />
    </svg>
  );
}

function ImageIcon() {
  return (
    <svg width={20} height={20} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="2.75" y="3.75" width="14.5" height="12.5" rx="1.5" />
      <circle cx="7.25" cy="8" r="1.25" />
      <path d="m3.5 14.5 3.75-3.6 3 2.6 2.6-2.3 3.65 3.3" />
    </svg>
  );
}

export function UploadGuidelinesPage({ onContinue }: UploadGuidelinesPageProps) {
  const { lang, dir } = useT();
  const isAr = lang === 'ar';

  const copy = isAr
    ? {
        headline: 'ملفات أفضل، بيانات أنظف',
        tagline: 'دقيقة تحضير الآن توفّر ساعات تصحيح لاحقًا.',
        features: [
          'استخرج نص الكتابة اليدوية قبل الرفع',
          'تجنّب الجداول — النص العادي أدق في الاستخراج',
          'أضف وصفًا لكل صورة بالعربية والإنجليزية',
        ],
        eyebrow: 'قبل الرفع',
        title: 'كلمة سريعة قبل تسجيل الدخول',
        intro: 'بعض الإرشادات التي تحافظ على دقة البيانات المستخرجة — يُرجى قراءتها مرة واحدة قبل المتابعة.',
        recommended: 'الخيار المفضّل',
        items: [
          {
            title: 'الكتب والمستندات المكتوبة بخط اليد',
            body:
              'لا تقم برفع الصورة الممسوحة ضوئيًا للصفحة المكتوبة بخط اليد كما هي. مرّرها أولًا على أداة استخراج نصوص بالذكاء الاصطناعي (OCR) ليتم التقاط النص بدقة، ثم ارفع النسخة المعالجة. نُفضّل هذا الحل بشكل كبير عن عدم الرفع إطلاقًا: ملفٌ واحد دقيق ومُعالَج جيدًا أفضل بكثير من عشرة ملفات متسرّعة يصعب قراءتها.',
          },
          {
            title: 'قلّل من استخدام الجداول',
            body:
              'تجنّب تنسيق المحتوى في شكل جداول داخل الملفات التي ترفعها. الجداول أصعب بكثير على أداة الاستخراج لقراءتها بشكل صحيح — النص العادي المتسلسل يُستخرج بدقة أعلى.',
          },
          {
            title: 'الصور تحتاج إلى وصف',
            body:
              'قلّل من عدد الصور قدر الإمكان، وإذا تضمّن الملف صورة، أضِف لها وصفًا احترافيًا ومفصّلًا — بالعربية والإنجليزية معًا — حتى لا يضيع معناها أثناء الاستخراج.',
          },
        ],
        exampleLabel: 'مثال على وصف جيد',
        continueLabel: 'تم القراءة — متابعة',
        footer: 'كل الحقوق محفوظة',
      }
    : {
        headline: 'Better files, cleaner data',
        tagline: 'A minute of preparation now saves hours of correction later.',
        features: [
          'Extract handwritten text before you upload',
          'Skip tables — plain text extracts best',
          'Caption every image in Arabic and English',
        ],
        eyebrow: 'Before you upload',
        title: 'A quick word before you sign in',
        intro: 'A few guidelines that keep extracted data accurate — please read them once before continuing.',
        recommended: 'Recommended',
        items: [
          {
            title: 'Handwritten books and documents',
            body:
              "Don't upload a raw scan of a handwritten page as-is. Run it through an AI extraction (OCR) tool first so the text is captured correctly, then upload the processed file. We strongly prefer this over skipping the upload altogether — one accurate, well-processed file is worth far more than ten rushed, unreadable ones.",
          },
          {
            title: 'Keep tables to a minimum',
            body:
              'Avoid formatting content as tables inside the files you upload. Tables are much harder for the extraction pipeline to read reliably — plain, linear text extracts far more accurately.',
          },
          {
            title: 'Images need a description',
            body:
              "Keep images to a minimum, and when a file does include one, add a professional, detailed caption for it — in both Arabic and English — so its meaning isn't lost during extraction.",
          },
        ],
        exampleLabel: 'Example of a good caption',
        continueLabel: "I've read this — Continue",
        footer: 'All rights reserved',
      };

  const itemIcons = [<ScanIcon key="scan" />, <TableIcon key="table" />, <ImageIcon key="image" />];

  return (
    <div className="auth-shell" dir={dir}>
      <div className="auth-corner">
        <PreferencesToggle />
      </div>

      <aside className="auth-brand-panel">
        <img
          className="auth-brand-mark-bg"
          src="/neurix-mark.png"
          alt=""
          aria-hidden="true"
          onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
        />
        <LoginBackground />

        <div className="auth-brand-content">
          <h1 className="auth-brand-headline">{copy.headline}</h1>
          <p className="auth-brand-tagline">{copy.tagline}</p>

          <ul className="auth-brand-features">
            {copy.features.map((f) => (
              <li key={f}>
                <span className="dot" aria-hidden="true"><IconCheck size={12} /></span>
                {f}
              </li>
            ))}
          </ul>
        </div>

        <div className="auth-brand-footer">
          © {new Date().getFullYear()} Neurix — {copy.footer}
        </div>
      </aside>

      <main className="auth-form-panel guide-form-panel">
        <div className="guide-card">
          <div className="guide-logo">
            <img
              src="/neurix-logo.png"
              alt="Neurix"
              width={180}
              height={54}
              onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
            />
          </div>

          <span className="guide-eyebrow">{copy.eyebrow}</span>
          <h1 className="guide-title">{copy.title}</h1>
          <p className="guide-intro">{copy.intro}</p>

          <ol className="guide-list">
            {copy.items.map((item, i) => (
              <li className={`guide-item${i === 0 ? ' guide-item--recommended' : ''}`} key={item.title}>
                <span className="guide-item-icon">{itemIcons[i]}</span>
                <div className="guide-item-body">
                  <div className="guide-item-head">
                    <h2>{item.title}</h2>
                    {i === 0 && <span className="guide-badge">{copy.recommended}</span>}
                  </div>
                  <p>{item.body}</p>

                  {i === 2 && (
                    <div className="guide-example">
                      <div className="guide-example-head">
                        <ImageIcon />
                        {copy.exampleLabel}
                      </div>
                      <p className="guide-example-caption" dir="ltr">
                        <b>EN —</b> Figure 3: Handwritten ledger page dated 12 March 1998. Shows three columns —
                        item name, quantity, and unit price in Egyptian pounds; ink is faded in the bottom-right corner.
                      </p>
                      <p className="guide-example-caption" dir="rtl">
                        <b>AR —</b> الشكل ٣: صفحة من دفتر حسابات مكتوبة بخط اليد، بتاريخ ١٢ مارس ١٩٩٨. تُظهر ثلاثة
                        أعمدة: اسم الصنف، والكمية، وسعر الوحدة بالجنيه المصري؛ الحبر باهت في الزاوية اليمنى السفلية.
                      </p>
                    </div>
                  )}
                </div>
              </li>
            ))}
          </ol>

          <button type="button" className="btn btn-primary guide-continue" onClick={onContinue}>
            <IconCheck size={16} />
            {copy.continueLabel}
          </button>
        </div>
      </main>
    </div>
  );
}
