/* Landing copy, English and Arabic as peers.
   Every claim here is checked against PRODUCT.md. No "AI" framing: the content
   helper is a regex cleanup and the chat widget is a keyword router. */

export interface Fact {
  title: string;
  body: string;
}

export interface Station {
  no: string;
  lane: string;
  title: string;
  lede: string;
  facts: Fact[];
}

export interface LandingCopy {
  nav: { stations: string; security: string; roles: string; ops: string; signIn: string; theme: string; jump: string };
  hero: {
    title1: string;
    titleEm: string;
    title2: string;
    lede: string;
    cta: string;
    ctaSecondary: string;
    boardLabel: string;
    batchId: string;
    batchTitle: string;
    batchMeta: string[];
    gauges: { label: string; value: string; unit: string }[];
    gaugeNote: string;
  };
  stationsHead: { title: string; lede: string };
  stations: Station[];
  ocr: { label: string; status: string; rows: { page: string; lang: 'ar' | 'en'; text: string }[] };
  upload: { label: string; file: string; note: string; workers: string; speed: string; eta: string };
  capture: {
    label: string;
    ref: string;
    rows: { k: string; v: string; custom?: boolean; ltr?: boolean }[];
    customNote: string;
  };
  review: {
    label: string;
    stamp: string;
    stampSub: string;
    from: string;
    to: string;
    audit: string[];
  };
  isolation: { title: string; lede: string; items: Fact[]; note: string };
  roles: { title: string; lede: string; items: { name: string; body: string; can: string[] }[] };
  bilingual: { title: string; lede: string; points: Fact[] };
  ops: { title: string; lede: string; items: Fact[] };
  demo: {
    title: string;
    lede: string;
    name: string;
    namePh: string;
    org: string;
    orgPh: string;
    email: string;
    emailPh: string;
    message: string;
    messagePh: string;
    submit: string;
    required: string;
    badEmail: string;
    okTitle: string;
    okBody: string;
    honest: string;
  };
  footer: { tagline: string; signIn: string; rights: string };
}

const en: LandingCopy = {
  nav: {
    stations: 'The floor',
    security: 'Isolation',
    roles: 'Roles',
    ops: 'Operations',
    signIn: 'Sign in',
    theme: 'Switch the work light',
    jump: 'Jump to',
  },
  hero: {
    title1: 'Paper in.',
    titleEm: 'Reviewed records',
    title2: 'out.',
    lede:
      'Neurix runs the whole digitization floor. Arabic and English come off the scan in one pass, an agent captures the entry against the extracted text, a team leader approves it, and the finished records go back onto your own disk. Self-hosted, one isolated workspace per team.',
    cta: 'Request a demo',
    ctaSecondary: 'Sign in',
    boardLabel: 'Floor board · live',
    batchId: 'Batch 0412',
    batchTitle: 'Ministry correspondence, 1998–2004',
    batchMeta: ['312 pages', 'ara + eng', 'PDF · scanned'],
    gauges: [
      { label: 'Pages in this batch', value: '312', unit: 'pg' },
      { label: 'Chunk size', value: '8', unit: 'MiB' },
      { label: 'Parallel uploads', value: '4', unit: 'ch' },
      { label: 'Resumable for', value: '24', unit: 'hr' },
    ],
    gaugeNote: 'Batch shown is demonstration data.',
  },
  stationsHead: {
    title: 'Five stations, one batch',
    lede:
      'A batch crosses the floor in the same order every time. Each station below is the part of the product that owns that stage — what it does, and the engineering that makes the claim true.',
  },
  stations: [
    {
      no: '01',
      lane: 'Intake',
      title: 'Files that actually arrive',
      lede:
        'Archive scans are large and office connections drop. Intake is built for that, not for a demo file on office wifi.',
      facts: [
        {
          title: '8 MiB chunks, four in flight',
          body:
            'Every upload is split into 8 MiB chunks and four are sent in parallel over a worker pool, so one slow chunk never stalls the rest.',
        },
        {
          title: 'Resumable for 24 hours',
          body:
            'Reopen a dropped upload and the server replies with the chunks it already holds. Those are skipped and counted straight into progress — you never re-send what landed.',
        },
        {
          title: 'Retries only what deserves a retry',
          body:
            'Backoff at 400, 1200 and 3000 ms, and only on 408, 425, 429, 5xx or a network failure. A rejected file fails immediately instead of looping.',
        },
        {
          title: 'Named limits, not vague ones',
          body:
            'Up to 500 MB per file and 500 MB per user per rolling 24 hours. Live speed and ETA the whole way, and cancel is real cancel — the server cleans the session up.',
        },
      ],
    },
    {
      no: '02',
      lane: 'Extract',
      title: 'Arabic and English in one pass',
      lede:
        'The extractor runs Tesseract with ara+eng together, so a page that mixes an Arabic body with an English reference number comes out whole. No second pass, no language switch.',
      facts: [
        {
          title: 'Scans and text files alike',
          body:
            'PDF, Word, Excel, PowerPoint, images and plain text up to 25 MB. OCR starts automatically on images and scanned pages; a born-digital PDF skips it.',
        },
        {
          title: 'Pictures come along',
          body:
            'Images found inside the document are pulled out, kept with their page number, given a caption and attached to the entry when it is submitted.',
        },
        {
          title: 'A queue that says so',
          body:
            'OCR is expensive, so it runs through one fair permit. Under load the API answers "OCR engine is busy — try again in a moment" rather than pretending and timing out.',
        },
      ],
    },
    {
      no: '03',
      lane: 'Capture',
      title: 'The form this archive needs',
      lede:
        'Extracted text sits beside the form while the agent works. The form itself bends to the archive instead of the archive bending to the form.',
      facts: [
        {
          title: 'Departments and subcategories',
          body:
            'Each department splits into subcategories, and each subcategory carries its own set of fields — so a land deed and a press clipping are not forced into one shape.',
        },
        {
          title: 'Custom fields without a migration',
          body:
            'Field values are stored in a normalized table, so adding a field to a subcategory changes no schema. Text, number, date, select and more.',
        },
        {
          title: 'Batch entry for repetitive runs',
          body:
            'Fill the shared fields once, then add article after article beneath them and submit the whole set together.',
        },
      ],
    },
    {
      no: '04',
      lane: 'Review',
      title: 'Nothing is done until a person says so',
      lede:
        'Every project is also a folder. Entries collect there pending approval, and a team leader reads them before anything counts as finished.',
      facts: [
        {
          title: 'Approve, and the agent hears about it',
          body:
            'An approved entry is marked saved to the database and the person who submitted it is notified. That is the one notification the system sends — no noise.',
        },
        {
          title: 'The audit log names the human',
          body:
            'Creates, updates, deletes and status changes are recorded with the actor and the transition. When a super admin is working inside a team, the log still attributes the action to the real person.',
        },
        {
          title: 'Throughput you can actually see',
          body:
            'Team progress, an agent leaderboard, per-agent activity broken down by department, subcategory and status, and daily streaks for the people doing the work.',
        },
      ],
    },
    {
      no: '05',
      lane: 'Export',
      title: 'Your archive, off the platform, whenever',
      lede:
        'Leaving is a feature. The records and the files that back them come out in a shape you can hand to somebody else.',
      facts: [
        {
          title: 'Written straight to a folder you pick',
          body:
            'Choose a folder on your machine and Neurix mirrors Project → Department → files into it with live progress. Chrome and Edge; every other browser gets the same tree as a ZIP.',
        },
        {
          title: 'Re-run it and only the new files move',
          body:
            'Files already on disk with the same name and size are skipped, so the second run is a sync rather than a second download.',
        },
        {
          title: 'A note and an index beside the files',
          body:
            'Optionally a Markdown note per entry and an index.csv manifest, so the archive still reads without the application.',
        },
        {
          title: 'A read-only API for the next system',
          body:
            'Named tokens with a real expiry, revocable, shown exactly once at creation. They reach a read-only pull API and nothing else.',
        },
      ],
    },
  ],
  ocr: {
    label: 'Extract · station 02',
    status: 'tesseract ara+eng',
    rows: [
      { page: 'p. 1', lang: 'ar', text: 'محضر اجتماع اللجنة الدائمة' },
      { page: 'p. 1', lang: 'en', text: 'Ref. MC-1998/0412 — Standing Committee' },
      { page: 'p. 2', lang: 'ar', text: 'البند الثالث: اعتماد المحضر السابق' },
      { page: 'p. 2', lang: 'en', text: 'Item 3: Adoption of previous minutes' },
    ],
  },
  upload: {
    label: 'Intake · station 01',
    file: 'MC-1998-0412.pdf',
    note: '48 chunks · 8 MiB each',
    workers: 'Worker',
    speed: 'MB/s',
    eta: 'ETA',
  },
  capture: {
    label: 'Capture · station 03',
    ref: 'MC-1998/0412',
    rows: [
      { k: 'Department', v: 'Correspondence' },
      { k: 'Subcategory', v: 'Committee minutes' },
      { k: 'Date', v: '1998-03-14', ltr: true },
      { k: 'Source', v: 'Central Archive, box 27' },
      { k: 'Session number', v: '14 of 1998', custom: true },
      { k: 'Committee', v: 'Standing Committee', custom: true },
    ],
    customNote: 'Marked rows are fields this subcategory defines for itself.',
  },
  review: {
    label: 'Review · station 04',
    stamp: 'Approved',
    stampSub: 'Saved to database',
    from: 'REVIEW',
    to: 'COMPLETED',
    audit: [
      'STATUS_CHANGE · TICKET 0412 · REVIEW -> COMPLETED',
      'Actor: h.mansour (Team Leader)',
      'Agent notified: their entry was approved',
    ],
  },
  isolation: {
    title: 'One team cannot see another. Three times over.',
    lede:
      'Multi-tenancy is usually a WHERE clause somebody has to remember. Here it is enforced in three independent places, so forgetting one does not open the door.',
    items: [
      {
        title: 'Stamped on write',
        body:
          'An entity listener attaches the owning team to every tenant-scoped record as it is persisted. Nothing is saved unowned.',
      },
      {
        title: 'Filtered on read',
        body:
          'An aspect wraps every transaction and switches on a Hibernate filter bound to the current team, so ordinary queries cannot reach across.',
      },
      {
        title: 'Guarded by id',
        body:
          'Fetching a record directly re-checks ownership and answers 404, deliberately not 403 — a wrong-team id cannot even confirm the row exists.',
      },
    ],
    note:
      'Super admins can step into a team to help. While they do, the audit trail keeps naming the real person behind the action.',
  },
  roles: {
    title: 'Three roles, and they mean different things',
    lede: 'Permissions are cumulative, and the URL gates deny by default — anything not explicitly opened stays shut.',
    items: [
      {
        name: 'Data Entry Agent',
        body: 'The person at the keyboard for a full shift.',
        can: [
          'Import a document and pull its text',
          'Capture entries, singly or in batches',
          'Track their own streak and daily trend',
          'See their folders and what has been approved',
        ],
      },
      {
        name: 'Team Leader',
        body: 'Runs one team and everything inside it.',
        can: [
          'Assign and track tasks across departments',
          'Manage the roster, departments and projects',
          'Review and approve entries in project folders',
          'Read reports and per-agent activity',
        ],
      },
      {
        name: 'Super Admin',
        body: 'Operates across every team.',
        can: [
          'Create teams and their first admin',
          'Step into any team to help, on the record',
          'Explore data across all teams and export it',
          'Issue and revoke read-only API tokens',
        ],
      },
    ],
  },
  bilingual: {
    title: 'Arabic is not a translation layer here',
    lede:
      'The interface, the data and the extractor are all bilingual. Switch the language and the whole board turns over, right to left, including this page.',
    points: [
      {
        title: 'The interface mirrors properly',
        body: 'Direction is driven off the document itself, so layout, spacing and controls flip rather than being nudged.',
      },
      {
        title: 'Reference data carries both',
        body: 'Teams, departments, subcategories and custom field labels each hold an English and an Arabic value — not one string translated at read time.',
      },
      {
        title: 'The extractor reads both at once',
        body: 'ara+eng run together on every page, which is what mixed administrative paper actually looks like.',
      },
    ],
  },
  ops: {
    title: 'Built to be run by someone on call',
    lede: 'It ships as a Docker Compose stack you host yourself, with the parts an operator expects to find already in place.',
    items: [
      {
        title: 'PostgreSQL 18 + Flyway',
        body: 'Versioned migrations, and the schema is validated on boot — drift fails startup instead of silently patching the database.',
      },
      {
        title: 'Prometheus, Alertmanager, Grafana',
        body: 'Actuator and Micrometer metrics, a provisioned dashboard and alert rules, all wired in the compose file.',
      },
      {
        title: 'Nightly backups',
        body: 'A dedicated sidecar dumps the database on a schedule into its own named volume.',
      },
      {
        title: 'Sessions you can actually revoke',
        body: 'Stateless JWTs carry a token version; changing a password invalidates every issued token server-side.',
      },
      {
        title: 'Rate limits on both doors',
        body: 'Login attempts are recorded and throttled, and the external API has its own limiter.',
      },
      {
        title: 'It refuses to boot unsafe',
        body: 'Wildcard CORS and insecure production settings raise at startup rather than becoming a finding later.',
      },
    ],
  },
  demo: {
    title: 'See it run on your own documents',
    lede:
      'Tell us what the archive looks like — language mix, formats, roughly how many pages — and we will walk the floor with material like yours.',
    name: 'Your name',
    namePh: 'Full name',
    org: 'Organization',
    orgPh: 'Ministry, company or contractor',
    email: 'Work email',
    emailPh: 'you@organization.gov',
    message: 'What are you digitizing?',
    messagePh: 'Formats, language mix, rough volume, and any deadline you are working to.',
    submit: 'Request a demo',
    required: 'This field is required.',
    badEmail: 'Enter an email address like name@organization.com.',
    okTitle: 'Your mail client is opening.',
    okBody: 'Send the message it has drafted and we will reply with a time. Nothing was submitted from this page.',
    honest: 'Accounts are created by an administrator — there is no self-service signup.',
  },
  footer: {
    tagline: 'Document data entry, run end to end.',
    signIn: 'Sign in to the platform',
    rights: 'Neurix',
  },
};

const ar: LandingCopy = {
  nav: {
    stations: 'خط العمل',
    security: 'العزل',
    roles: 'الأدوار',
    ops: 'التشغيل',
    signIn: 'تسجيل الدخول',
    theme: 'تبديل إضاءة العمل',
    jump: 'انتقل إلى',
  },
  hero: {
    title1: 'ورق داخل.',
    titleEm: 'سجلات مُراجَعة',
    title2: 'خارجة.',
    lede:
      'نيوريكس يدير أرضية الرقمنة بالكامل. العربية والإنجليزية تُقرأ من المسح الضوئي في مرور واحد، ثم يُدخل الموظف البيانات أمام النص المستخرج، ويعتمدها قائد الفريق، وتعود السجلات النهائية إلى القرص الخاص بك. يعمل على خوادمك، ولكل فريق مساحة عمل معزولة.',
    cta: 'اطلب عرضًا توضيحيًا',
    ctaSecondary: 'تسجيل الدخول',
    boardLabel: 'لوحة الأرضية · مباشر',
    batchId: 'دفعة ٠٤١٢',
    batchTitle: 'مراسلات وزارية، ١٩٩٨–٢٠٠٤',
    batchMeta: ['٣١٢ صفحة', 'عربي + إنجليزي', 'PDF · ممسوح ضوئيًا'],
    gauges: [
      { label: 'صفحات هذه الدفعة', value: '312', unit: 'صفحة' },
      { label: 'حجم الجزء', value: '8', unit: 'م.ب' },
      { label: 'رفع متوازٍ', value: '4', unit: 'أجزاء' },
      { label: 'قابل للاستئناف', value: '24', unit: 'ساعة' },
    ],
    gaugeNote: 'الدفعة المعروضة بيانات توضيحية.',
  },
  stationsHead: {
    title: 'خمس محطات، ودفعة واحدة',
    lede:
      'تعبر الدفعة الأرضية بالترتيب نفسه في كل مرة. كل محطة أدناه هي الجزء من المنصة المسؤول عن تلك المرحلة — ماذا يفعل، والهندسة التي تجعل الكلام صحيحًا.',
  },
  stations: [
    {
      no: '٠١',
      lane: 'الاستلام',
      title: 'ملفات تصل فعلًا',
      lede: 'ملفات الأرشيف كبيرة، وشبكات المكاتب تنقطع. الاستلام مبني لهذا الواقع، لا لملف تجريبي على شبكة مثالية.',
      facts: [
        {
          title: 'أجزاء ٨ ميجابايت، أربعة معًا',
          body: 'يُقسَّم كل رفع إلى أجزاء بحجم ٨ ميجابايت تُرسل أربعة منها بالتوازي، فلا يوقف جزء بطيء بقية الملف.',
        },
        {
          title: 'استئناف حتى ٢٤ ساعة',
          body: 'افتح رفعًا انقطع، فيردّ الخادم بالأجزاء التي وصلته بالفعل؛ تُتخطى وتُحتسب في التقدم مباشرة، فلا تُعيد إرسال ما وصل.',
        },
        {
          title: 'إعادة المحاولة عند الحاجة فقط',
          body: 'تباعد ٤٠٠ ثم ١٢٠٠ ثم ٣٠٠٠ مللي ثانية، وفقط مع ٤٠٨ و٤٢٥ و٤٢٩ وأخطاء الخادم أو انقطاع الشبكة. أما الملف المرفوض فيفشل فورًا بدل الدوران.',
        },
        {
          title: 'حدود معلومة لا غامضة',
          body: 'حتى ٥٠٠ ميجابايت للملف، و٥٠٠ ميجابايت لكل مستخدم خلال ٢٤ ساعة متحركة، مع سرعة ووقت متبقٍ مباشرين، وإلغاء حقيقي ينظّف الجلسة على الخادم.',
        },
      ],
    },
    {
      no: '٠٢',
      lane: 'الاستخراج',
      title: 'العربية والإنجليزية في مرور واحد',
      lede:
        'يشغّل المستخرج محرك Tesseract باللغتين معًا، فتخرج الصفحة التي تجمع نصًا عربيًا ورقمًا مرجعيًا إنجليزيًا كاملة. بلا مرور ثانٍ وبلا تبديل لغة.',
      facts: [
        {
          title: 'الممسوح والنصي سواء',
          body: 'ملفات PDF وWord وExcel وPowerPoint والصور والنص العادي حتى ٢٥ ميجابايت. يبدأ الاستخراج الضوئي تلقائيًا مع الصور والصفحات الممسوحة، ويتخطاه ملف PDF نصي المنشأ.',
        },
        {
          title: 'الصور تأتي معها',
          body: 'تُستخرج الصور داخل المستند مع رقم صفحتها، ويُضاف لها وصف، وتُرفق بالمدخل عند الإرسال.',
        },
        {
          title: 'طابور يقول الحقيقة',
          body: 'الاستخراج الضوئي مكلف، فيمر عبر تصريح واحد عادل. وتحت الضغط يردّ النظام بأن المحرك مشغول وأن يُعاد المحاولة بعد لحظة، بدل التظاهر ثم انتهاء المهلة.',
        },
      ],
    },
    {
      no: '٠٣',
      lane: 'الإدخال',
      title: 'النموذج الذي يحتاجه هذا الأرشيف',
      lede: 'يظل النص المستخرج بجوار النموذج أثناء العمل. والنموذج هو من ينحني للأرشيف، لا العكس.',
      facts: [
        {
          title: 'أقسام وتصنيفات فرعية',
          body: 'ينقسم كل قسم إلى تصنيفات فرعية، ولكل تصنيف حقوله الخاصة — فلا يُحشر عقد ملكية وقصاصة صحفية في شكل واحد.',
        },
        {
          title: 'حقول مخصصة بلا ترحيل قاعدة بيانات',
          body: 'تُخزَّن قيم الحقول في جدول منفصل، فإضافة حقل إلى تصنيف لا تغيّر بنية القاعدة. نص ورقم وتاريخ وقائمة اختيار وغيرها.',
        },
        {
          title: 'إدخال بالدفعة للأعمال المتكررة',
          body: 'املأ الحقول المشتركة مرة واحدة، ثم أضف مقالًا تلو الآخر تحتها وأرسل المجموعة كلها معًا.',
        },
      ],
    },
    {
      no: '٠٤',
      lane: 'المراجعة',
      title: 'لا شيء يكتمل حتى يقول إنسان ذلك',
      lede: 'كل مشروع هو أيضًا مجلد. تتجمع فيه المدخلات بانتظار الاعتماد، ويقرأها قائد الفريق قبل أن يُحتسب أي شيء منتهيًا.',
      facts: [
        {
          title: 'اعتمِد، فيصل الخبر للموظف',
          body: 'يُعلَّم المدخل المعتمد بأنه حُفظ في قاعدة البيانات، ويُخطَر من أرسله. وهذا هو الإشعار الوحيد في النظام — بلا ضجيج.',
        },
        {
          title: 'سجل التدقيق يسمّي الإنسان',
          body: 'تُسجَّل عمليات الإنشاء والتعديل والحذف وتغيير الحالة مع الفاعل والانتقال. وحين يعمل مدير عام داخل فريق، يظل السجل ينسب الفعل إلى الشخص الحقيقي.',
        },
        {
          title: 'إنتاجية تراها بعينك',
          body: 'تقدّم الفريق، ولوحة ترتيب للموظفين، ونشاط كل موظف موزعًا على القسم والتصنيف والحالة، وسلاسل أيام متتابعة لمن يؤدون العمل.',
        },
      ],
    },
    {
      no: '٠٥',
      lane: 'التصدير',
      title: 'أرشيفك، خارج المنصة، متى شئت',
      lede: 'المغادرة ميزة. تخرج السجلات والملفات التي تسندها بشكل يمكنك تسليمه لجهة أخرى.',
      facts: [
        {
          title: 'يُكتب مباشرة في مجلد تختاره',
          body: 'اختر مجلدًا على جهازك، فينسخ نيوريكس البنية مشروع ← قسم ← ملفات داخله مع تقدم مباشر. في كروم وإيدج؛ وبقية المتصفحات تحصل على الشجرة نفسها في ملف مضغوط.',
        },
        {
          title: 'أعد التشغيل فينتقل الجديد فقط',
          body: 'تُتخطى الملفات الموجودة على القرص بالاسم والحجم نفسيهما، فتصير المرة الثانية مزامنة لا تنزيلًا جديدًا.',
        },
        {
          title: 'ملاحظة وفهرس بجوار الملفات',
          body: 'اختياريًا ملف ملاحظات لكل مدخل وفهرس index.csv، فيظل الأرشيف مقروءًا من دون البرنامج.',
        },
        {
          title: 'واجهة قراءة فقط للنظام التالي',
          body: 'رموز مسماة لها مدة صلاحية حقيقية، قابلة للإلغاء، تُعرض مرة واحدة عند الإنشاء. تصل إلى واجهة سحب للقراءة فقط ولا شيء غيرها.',
        },
      ],
    },
  ],
  ocr: {
    label: 'الاستخراج · المحطة ٠٢',
    status: 'tesseract عربي+إنجليزي',
    rows: [
      { page: 'ص ١', lang: 'ar', text: 'محضر اجتماع اللجنة الدائمة' },
      { page: 'ص ١', lang: 'en', text: 'Ref. MC-1998/0412 — Standing Committee' },
      { page: 'ص ٢', lang: 'ar', text: 'البند الثالث: اعتماد المحضر السابق' },
      { page: 'ص ٢', lang: 'en', text: 'Item 3: Adoption of previous minutes' },
    ],
  },
  upload: {
    label: 'الاستلام · المحطة ٠١',
    file: 'MC-1998-0412.pdf',
    note: '٤٨ جزءًا · ٨ م.ب لكل جزء',
    workers: 'عامل',
    speed: 'م.ب/ث',
    eta: 'المتبقي',
  },
  capture: {
    label: 'الإدخال · المحطة 03',
    ref: 'MC-1998/0412',
    rows: [
      { k: 'القسم', v: 'المراسلات' },
      { k: 'التصنيف الفرعي', v: 'محاضر اللجان' },
      { k: 'التاريخ', v: '1998-03-14', ltr: true },
      { k: 'المصدر', v: 'الأرشيف المركزي، صندوق 27' },
      { k: 'رقم الجلسة', v: '14 لسنة 1998', custom: true },
      { k: 'اللجنة', v: 'اللجنة الدائمة', custom: true },
    ],
    customNote: 'الصفوف المعلَّمة حقول يعرّفها هذا التصنيف لنفسه.',
  },
  review: {
    label: 'المراجعة · المحطة 04',
    stamp: 'مُعتمد',
    stampSub: 'حُفظ في قاعدة البيانات',
    from: 'REVIEW',
    to: 'COMPLETED',
    audit: [
      'STATUS_CHANGE · TICKET 0412 · REVIEW -> COMPLETED',
      'الفاعل: h.mansour (قائد فريق)',
      'أُخطر الموظف بأن مدخله اعتُمد',
    ],
  },
  isolation: {
    title: 'لا يرى فريقٌ فريقًا آخر. ثلاث مرات.',
    lede:
      'العزل بين المستأجرين عادةً شرط في الاستعلام يتذكره أحدهم. هنا يُفرض في ثلاثة مواضع مستقلة، فنسيان واحد لا يفتح الباب.',
    items: [
      {
        title: 'خَتْم عند الكتابة',
        body: 'يُلحق مستمعٌ للكيانات الفريقَ المالك بكل سجل عند حفظه. فلا يُحفظ شيء بلا مالك.',
      },
      {
        title: 'تصفية عند القراءة',
        body: 'يلتف جانبٌ برمجي حول كل معاملة ويشغّل مرشّح Hibernate مربوطًا بالفريق الحالي، فلا تستطيع الاستعلامات العادية العبور.',
      },
      {
        title: 'حراسة بالمعرّف',
        body: 'جلب سجل بمعرّفه مباشرة يعيد التحقق من الملكية ويردّ ٤٠٤ لا ٤٠٣ عن قصد — فمعرّف من فريق آخر لا يؤكد حتى وجود السجل.',
      },
    ],
    note: 'يمكن للمدير العام الدخول إلى فريق للمساعدة. وطوال ذلك يظل سجل التدقيق يسمّي الشخص الحقيقي وراء الفعل.',
  },
  roles: {
    title: 'ثلاثة أدوار، ولكلٍّ معناه',
    lede: 'الصلاحيات تراكمية، وبوابات المسارات ترفض افتراضيًا — فما لم يُفتح صراحةً يبقى مغلقًا.',
    items: [
      {
        name: 'موظف إدخال بيانات',
        body: 'من يجلس إلى لوحة المفاتيح طوال الوردية.',
        can: [
          'يستورد مستندًا ويستخرج نصه',
          'يُدخل السجلات فرادى أو بالدفعة',
          'يتابع سلسلة أيامه ومعدله اليومي',
          'يرى مجلداته وما اعتُمد منها',
        ],
      },
      {
        name: 'قائد فريق',
        body: 'يدير فريقًا واحدًا وكل ما بداخله.',
        can: [
          'يوزّع المهام ويتابعها عبر الأقسام',
          'يدير الأعضاء والأقسام والمشاريع',
          'يراجع المدخلات في مجلدات المشاريع ويعتمدها',
          'يقرأ التقارير ونشاط كل موظف',
        ],
      },
      {
        name: 'مدير عام',
        body: 'يعمل عبر كل الفرق.',
        can: [
          'ينشئ الفرق وأول مدير لكل منها',
          'يدخل أي فريق للمساعدة، وكل ذلك مسجَّل',
          'يستعرض بيانات كل الفرق ويصدّرها',
          'يصدر رموز واجهة للقراءة فقط ويلغيها',
        ],
      },
    ],
  },
  bilingual: {
    title: 'العربية هنا ليست طبقة ترجمة',
    lede: 'الواجهة والبيانات والمستخرج كلها ثنائية اللغة. بدّل اللغة فتنقلب اللوحة كلها من اليمين إلى اليسار، وهذه الصفحة منها.',
    points: [
      {
        title: 'الواجهة تنعكس كما ينبغي',
        body: 'الاتجاه مأخوذ من المستند نفسه، فينقلب التخطيط والمسافات وعناصر التحكم بدل أن تُزحزح يدويًا.',
      },
      {
        title: 'البيانات المرجعية تحمل اللغتين',
        body: 'الفرق والأقسام والتصنيفات وعناوين الحقول لكل منها قيمة عربية وأخرى إنجليزية — لا نصٌّ واحد يُترجم عند القراءة.',
      },
      {
        title: 'المستخرج يقرأ الاثنتين معًا',
        body: 'العربية والإنجليزية تعملان معًا على كل صفحة، وهذا هو شكل الأوراق الإدارية في الواقع.',
      },
    ],
  },
  ops: {
    title: 'مبنيّ ليشغّله من هو تحت الطلب',
    lede: 'يُسلَّم كحزمة Docker Compose تستضيفها بنفسك، وفيها ما يتوقع المشغّل أن يجده جاهزًا.',
    items: [
      {
        title: 'PostgreSQL 18 مع Flyway',
        body: 'ترحيلات مُرقَّمة، وتحقق من البنية عند الإقلاع — فأي انحراف يوقف التشغيل بدل أن يرقّع القاعدة بصمت.',
      },
      {
        title: 'Prometheus وAlertmanager وGrafana',
        body: 'مقاييس عبر Actuator وMicrometer، ولوحة جاهزة وقواعد تنبيه، كلها موصولة في ملف الحزمة.',
      },
      {
        title: 'نسخ احتياطي كل ليلة',
        body: 'خدمة مرافقة مخصصة تأخذ نسخة من القاعدة وفق جدول إلى وحدة تخزين خاصة بها.',
      },
      {
        title: 'جلسات يمكن إبطالها فعلًا',
        body: 'تحمل رموز JWT رقم إصدار، وتغيير كلمة المرور يُبطل كل الرموز الصادرة من جهة الخادم.',
      },
      {
        title: 'حدود على البابين',
        body: 'محاولات الدخول تُسجَّل وتُقيَّد، وللواجهة الخارجية مقيّد خاص بها.',
      },
      {
        title: 'يرفض الإقلاع غير الآمن',
        body: 'إعدادات CORS المفتوحة والإعدادات الإنتاجية غير الآمنة تُوقف التشغيل بدل أن تصير ملاحظة أمنية لاحقًا.',
      },
    ],
  },
  demo: {
    title: 'شاهده يعمل على مستنداتك أنت',
    lede: 'احكِ لنا كيف يبدو الأرشيف — مزيج اللغات، والصيغ، وعدد الصفحات تقريبًا — ونمشي معك على الأرضية بمادة تشبه مادتك.',
    name: 'اسمك',
    namePh: 'الاسم الكامل',
    org: 'الجهة',
    orgPh: 'وزارة أو شركة أو مقاول',
    email: 'بريد العمل',
    emailPh: 'you@organization.gov',
    message: 'ما الذي ترقمنه؟',
    messagePh: 'الصيغ، ومزيج اللغات، والحجم التقريبي، وأي موعد نهائي تعمل وفقه.',
    submit: 'اطلب عرضًا توضيحيًا',
    required: 'هذا الحقل مطلوب.',
    badEmail: 'أدخل بريدًا مثل name@organization.com.',
    okTitle: 'برنامج البريد يفتح الآن.',
    okBody: 'أرسل الرسالة التي جُهزت، وسنردّ بموعد. لم يُرسَل شيء من هذه الصفحة.',
    honest: 'الحسابات ينشئها المسؤول — لا يوجد تسجيل ذاتي.',
  },
  footer: {
    tagline: 'إدخال بيانات المستندات، مُدارًا من أوله لآخره.',
    signIn: 'الدخول إلى المنصة',
    rights: 'نيوريكس',
  },
};

export const landingCopy = { en, ar };
