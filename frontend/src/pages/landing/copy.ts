/* Bilingual copy for the public landing page. */
interface LandingCopy {
  nav: { demo: string; signIn: string };
  roles: {
    title: string;
    lede: string;
    items: { name: string; body: string; can: string[] }[];
  };
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
    direct: string;
    honest: string;
  };
}

const en: LandingCopy = {
  nav: { demo: "Request a demo", signIn: "Sign in" },
  roles: {
    title: "Three roles, and they mean different things",
    lede: "Permissions are cumulative, and the URL gates deny by default — anything not explicitly opened stays shut.",
    items: [
      {
        name: "Data Entry Agent",
        body: "The person at the keyboard for a full shift.",
        can: [
          "Import a document and pull its text",
          "Capture entries, singly or in batches",
          "Track their own streak and daily trend",
          "See their folders and what has been approved",
        ],
      },
      {
        name: "Team Leader",
        body: "Runs one team and everything inside it.",
        can: [
          "Assign and track tasks across departments",
          "Manage the roster, departments and projects",
          "Review and approve entries in project folders",
          "Read reports and per-agent activity",
        ],
      },
      {
        name: "Super Admin",
        body: "Operates across every team.",
        can: [
          "Create teams and their first admin",
          "Step into any team to help, on the record",
          "Explore data across all teams and export it",
          "Issue and revoke read-only API tokens",
        ],
      },
    ],
  },
  demo: {
    title: "See it run on your own documents",
    lede: "Tell us what the archive looks like — language mix, formats, roughly how many pages — and we will walk the floor with material like yours.",
    name: "Your name",
    namePh: "Full name",
    org: "Organization",
    orgPh: "Ministry, company or contractor",
    email: "Work email",
    emailPh: "you@organization.gov",
    message: "What are you digitizing?",
    messagePh:
      "Formats, language mix, rough volume, and any deadline you are working to.",
    submit: "Request a demo",
    required: "This field is required.",
    badEmail: "Enter an email address like name@organization.com.",
    okTitle: "Your mail client is opening.",
    okBody:
      "Send the message it has drafted and we will reply with a time. Nothing was submitted from this page.",
    direct: "If nothing opened, write to us directly at",
    honest:
      "Accounts are created by an administrator — there is no self-service signup.",
  },
};

const ar: LandingCopy = {
  nav: { demo: "اطلب عرضًا", signIn: "تسجيل الدخول" },
  roles: {
    title: "ثلاثة أدوار، ولكلٍّ معناه",
    lede: "الصلاحيات تراكمية، وبوابات المسارات ترفض افتراضيًا — فما لم يُفتح صراحةً يبقى مغلقًا.",
    items: [
      {
        name: "موظف إدخال بيانات",
        body: "من يجلس إلى لوحة المفاتيح طوال الوردية.",
        can: [
          "يستورد مستندًا ويستخرج نصه",
          "يُدخل السجلات فرادى أو بالدفعة",
          "يتابع سلسلة أيامه ومعدله اليومي",
          "يرى مجلداته وما اعتُمد منها",
        ],
      },
      {
        name: "قائد فريق",
        body: "يدير فريقًا واحدًا وكل ما بداخله.",
        can: [
          "يوزّع المهام ويتابعها عبر الأقسام",
          "يدير الأعضاء والأقسام والمشاريع",
          "يراجع المدخلات في مجلدات المشاريع ويعتمدها",
          "يقرأ التقارير ونشاط كل موظف",
        ],
      },
      {
        name: "مدير عام",
        body: "يعمل عبر كل الفرق.",
        can: [
          "ينشئ الفرق وأول مدير لكل منها",
          "يدخل أي فريق للمساعدة، وكل ذلك مسجَّل",
          "يستعرض بيانات كل الفرق ويصدّرها",
          "يصدر رموز واجهة للقراءة فقط ويلغيها",
        ],
      },
    ],
  },
  demo: {
    title: "شاهده يعمل على مستنداتك أنت",
    lede: "احكِ لنا كيف يبدو الأرشيف — مزيج اللغات، والصيغ، وعدد الصفحات تقريبًا — ونمشي معك على الأرضية بمادة تشبه مادتك.",
    name: "اسمك",
    namePh: "الاسم الكامل",
    org: "الجهة",
    orgPh: "وزارة أو شركة أو مقاول",
    email: "بريد العمل",
    emailPh: "you@organization.gov",
    message: "ما الذي ترقمنه؟",
    messagePh:
      "الصيغ، ومزيج اللغات، والحجم التقريبي، وأي موعد نهائي تعمل وفقه.",
    submit: "اطلب عرضًا توضيحيًا",
    required: "هذا الحقل مطلوب.",
    badEmail: "أدخل بريدًا مثل name@organization.com.",
    okTitle: "برنامج البريد يفتح الآن.",
    okBody:
      "أرسل الرسالة التي جُهزت، وسنردّ بموعد. لم يُرسَل شيء من هذه الصفحة.",
    direct: "لو لم يفتح شيء، راسلنا مباشرة على",
    honest: "الحسابات ينشئها المسؤول — لا يوجد تسجيل ذاتي.",
  },
};

export const landingCopy = { en, ar };

/** Visitor-facing copy for the public product experience. */
export const experienceCopy = {
  ar: {
    nav: ["كيف تعمل المنصة", "حماية البيانات", "فريقك", "أسئلة شائعة"],
    eyebrow: "مساحة واحدة. لكل تفاصيل أرشيفك.",
    title: "من مستندات متفرّقة،",
    accent: "إلى بيانات لها قيمة.",
    intro:
      "اجمع مستنداتك، استخرج محتواها، وراجع بياناتها مع فريقك. نيوريكس ينظّم رحلة الرقمنة من أول ملف إلى أرشيف جاهز للاستخدام.",
    primary: "خلّينا نعرّفك على نيوريكس",
    secondary: "اكتشف طريقة العمل",
    promises: ["مصمّم للعربية والإنجليزية", "على خوادمك وتحت سيطرتك"],
    preview: "من المستند إلى السجل",
    sample: "نموذج توضيحي",
    source: "المستند الأصلي",
    document: "محضر اجتماع اللجنة",
    documentSub: "الأرشيف الإداري · المراسلات",
    extracted: "بيانات منظّمة",
    approved: "تمت المراجعة",
    record: "سجل جاهز للاستخدام",
    fields: ["التصنيف", "المرجع", "التاريخ"],
    values: ["محاضر اجتماعات", "MC–0412", "14 / 03 / 1998"],
    formats: "كل ملفات العمل، في مكانها.",
    formatNote: "مستندات، جداول، وصور ممسوحة ضوئيًا",
    workflowEyebrow: "رحلة أبسط للمستند",
    workflowTitle: "خطوات واضحة. شغل متّصل.",
    workflowIntro:
      "من الرفع إلى التصدير، كل مرحلة تسلّم اللي بعدها. اختَر خطوة وشوف دورها في رحلة بياناتك.",
    stages: [
      {
        label: "ارفع",
        title: "ابدأ بملفاتك كما هي.",
        body: "اجمع مستندات المشروع في مكان واحد، وتابع الرفع خطوة بخطوة حتى تكتمل ملفاتك.",
        points: [
          "استئناف الرفع بعد انقطاع الاتصال",
          "متابعة التقدّم وإلغاء الرفع عند الحاجة",
          "تنظيم المستندات داخل مشاريع وأقسام",
        ],
      },
      {
        label: "استخرج",
        title: "المحتوى جاهز للخطوة التالية.",
        body: "استخرج النص من المستندات والصور الممسوحة ضوئيًا، مع دعم العربية والإنجليزية في الصفحة نفسها.",
        points: [
          "استخراج النص من الملفات والصور",
          "الاحتفاظ بالصور المرتبطة بالمستند",
          "النص المستخرج متاح أثناء إدخال البيانات",
        ],
      },
      {
        label: "نظّم",
        title: "لكل نوع مستند، بياناته.",
        body: "أدخل البيانات في الحقول المناسبة لتصنيف المستند، واحتفظ بالمرجع والمصدر بجوار تفاصيل السجل.",
        points: [
          "حقول مخصّصة لكل تصنيف",
          "إدخال فردي أو دفعات للمحتوى المتكرر",
          "ربط البيانات بالمشروع والمستند",
        ],
      },
      {
        label: "راجع",
        title: "لمسة فريقك تصنع الفرق.",
        body: "يُراجع قائد الفريق المدخلات قبل اعتمادها، فيعرف الموظف حالة شغله وتعرف أنت ما اكتمل بالفعل.",
        points: [
          "مراجعة بشرية قبل الاعتماد",
          "إشعار الموظف عند اعتماد مدخله",
          "متابعة إنتاجية الفريق وحالة المدخلات",
        ],
      },
      {
        label: "صدّر",
        title: "أرشيفك جاهز للخروج معك.",
        body: "صدّر السجلات ومرفقاتها في بنية منظّمة تقدر تستخدمها وتشاركها خارج المنصة.",
        points: [
          "ملفات مرتّبة حسب المشروع والقسم",
          "تنزيل مباشر أو ملف مضغوط",
          "فهرس للملفات وملاحظات اختيارية لكل مدخل",
        ],
      },
    ],
    uploadLabel: "ملفات المشروع",
    uploadState: "تم الرفع",
    extractLabel: "النص المستخرج",
    extractText:
      "محضر اجتماع اللجنة الدائمة — تمت مناقشة المراسلات الواردة واعتماد جدول الأعمال.",
    securityEyebrow: "بياناتك في أيدٍ أمينة",
    securityTitle: "مساحة لفريقك.\nوالتحكّم لك.",
    securityBody:
      "استضف المنصة على خوادمك، وحدّد مسؤوليات فريقك. كل فريق يعمل داخل مساحته الخاصة، مع تسجيل العمليات للرجوع إليها.",
    securityItems: [
      {
        title: "مساحات عمل منفصلة",
        body: "ملفات وسجلات كل فريق مرتبطة بمساحته وصلاحياته.",
      },
      {
        title: "صلاحيات واضحة",
        body: "مسؤوليات محددة للإدخال والمراجعة وإدارة المنصة.",
      },
      {
        title: "أثر لكل إجراء",
        body: "سجل للعمليات والتغييرات يوضح من قام بالإجراء.",
      },
    ],
    teamEyebrow: "شغل الفريق، بتناغم",
    teamTitle: "كل شخص عارف خطوته الجاية.",
    teamIntro:
      "من إدخال أول سجل إلى متابعة الصورة الكاملة، لكل دور أدواته ومسؤوليته.",
    roleDescriptions: [
      "يركّز على المستند ويُدخل بياناته ويتابع حالة كل مدخل.",
      "يوزّع العمل، يراجع المدخلات، ويتابع تقدّم الفريق.",
      "يدير الفرق ويتابع البيانات والصلاحيات على مستوى المنصة.",
    ],
    faqTitle: "تفاصيل تحب تعرفها.",
    faqIntro: "إجابات سريعة قبل ما تبدأ.",
    faqs: [
      {
        q: "هل المنصة تدعم المستندات العربية؟",
        a: "نعم. الواجهة متاحة بالعربية والإنجليزية، واستخراج النص يدعم المستندات التي تجمع اللغتين. تظل المراجعة البشرية جزءًا من العمل لضبط النص المستخرج.",
      },
      {
        q: "أين تُحفظ بياناتنا؟",
        a: "المنصة قابلة للاستضافة على خوادم الجهة. مكان حفظ الملفات وقاعدة البيانات يحدده إعداد الاستضافة الخاص بكم.",
      },
      {
        q: "هل يمكن تصدير الملفات خارج المنصة؟",
        a: "نعم، يمكن تصدير السجلات ومرفقاتها مرتبة حسب المشروع والقسم، بتنزيل مباشر في المتصفحات المدعومة أو داخل ملف مضغوط.",
      },
      {
        q: "كيف يحصل الفريق على حسابات؟",
        a: "ينشئ المسؤول الحسابات ويحدد الأدوار. لا يوجد تسجيل ذاتي؛ يمكنك طلب عرض توضيحي للتعرّف على طريقة إعداد الفريق.",
      },
    ],
    demoEyebrow: "الخطوة الجاية أبسط",
    demoTitle: "خلّي أرشيفك\nيبدأ حكاية جديدة.",
    demoBody:
      "احكِ لنا عن مستنداتك وطريقة شغل فريقك، ونتعرّف معًا على الشكل المناسب لرحلة الرقمنة عندك.",
    demoNote: "الطلب يفتح رسالة جاهزة في برنامج بريدك لإرسالها.",
    footer: "من المستند، إلى المعلومة.",
  },
  en: {
    nav: ["How it works", "Data protection", "Your team", "FAQs"],
    eyebrow: "One workspace. Every detail of your archive.",
    title: "From scattered documents,",
    accent: "to data that matters.",
    intro:
      "Bring your documents together, extract their content, and review records with your team. Neurix connects every step, from the first file to a usable archive.",
    primary: "Meet your new workspace",
    secondary: "Explore the workflow",
    promises: ["Built for Arabic & English", "Self-hosted. In your control."],
    preview: "From document to record",
    sample: "Illustrative preview",
    source: "Original document",
    document: "Committee meeting minutes",
    documentSub: "Administrative archive · Correspondence",
    extracted: "Structured data",
    approved: "Reviewed",
    record: "Ready to use",
    fields: ["Category", "Reference", "Date"],
    values: ["Meeting minutes", "MC–0412", "14 / 03 / 1998"],
    formats: "Your everyday files. All together.",
    formatNote: "Documents, spreadsheets, and scanned images",
    workflowEyebrow: "A better document journey",
    workflowTitle: "Clear steps. Connected work.",
    workflowIntro:
      "Each stage picks up where the last one left off. Choose a step to explore your document’s journey.",
    stages: [
      {
        label: "Upload",
        title: "Start with the files you have.",
        body: "Bring project documents into one place and follow their upload progress from start to finish.",
        points: [
          "Resume after a dropped connection",
          "Track progress and cancel when needed",
          "Organize documents by project and department",
        ],
      },
      {
        label: "Extract",
        title: "Make the content accessible.",
        body: "Extract text from documents and scanned images, including pages that mix Arabic and English.",
        points: [
          "Text extraction for files and scans",
          "Keep images associated with the document",
          "Reference extracted text while entering records",
        ],
      },
      {
        label: "Organize",
        title: "The right fields for every record.",
        body: "Capture details in fields that fit the document category, keeping its source and reference alongside the record.",
        points: [
          "Custom fields for each category",
          "Single or batch entry for repeated content",
          "Records connected to their project and source",
        ],
      },
      {
        label: "Review",
        title: "Your team makes the difference.",
        body: "A team leader reviews entries before approval, so everyone knows what is pending and what is complete.",
        points: [
          "Human review before approval",
          "Notify agents when entries are approved",
          "Follow team progress and record status",
        ],
      },
      {
        label: "Export",
        title: "Your archive goes with you.",
        body: "Export records and their attachments in an organized structure you can use outside the platform.",
        points: [
          "Files grouped by project and department",
          "Direct download or a ZIP archive",
          "A file index and optional entry notes",
        ],
      },
    ],
    uploadLabel: "Project files",
    uploadState: "Uploaded",
    extractLabel: "Extracted text",
    extractText:
      "Standing committee meeting minutes — incoming correspondence was discussed and the agenda was approved.",
    securityEyebrow: "A home for your data",
    securityTitle: "Your team’s space.\nYour control.",
    securityBody:
      "Host the platform on your infrastructure and define your team’s responsibilities. Each team works in its own space, with actions recorded for reference.",
    securityItems: [
      {
        title: "Separate workspaces",
        body: "Each team’s files and records belong to its own workspace.",
      },
      {
        title: "Defined permissions",
        body: "Clear responsibilities for data entry, review, and administration.",
      },
      {
        title: "A record of each action",
        body: "An activity trail identifies the person behind each change.",
      },
    ],
    teamEyebrow: "Better, together",
    teamTitle: "Everyone knows their next step.",
    teamIntro:
      "From the first record to the bigger picture, each role has the tools and responsibility it needs.",
    roleDescriptions: [
      "Capture document details and follow the status of each entry.",
      "Assign work, review entries, and follow the team’s progress.",
      "Manage teams, data, and permissions across the platform.",
    ],
    faqTitle: "A few things worth knowing.",
    faqIntro: "Quick answers before you get started.",
    faqs: [
      {
        q: "Does Neurix support Arabic documents?",
        a: "Yes. The interface supports Arabic and English, and text extraction can process both languages on the same page. Human review remains part of the workflow to check extracted content.",
      },
      {
        q: "Where is our data stored?",
        a: "Neurix can be hosted on your organization’s servers. Your hosting configuration determines where files and the database are stored.",
      },
      {
        q: "Can we export our archive?",
        a: "Yes. Export records and attachments organized by project and department, directly in supported browsers or as a ZIP archive.",
      },
      {
        q: "How does our team get access?",
        a: "An administrator creates accounts and assigns roles. There is no self-service signup. Request a demo to explore team setup.",
      },
    ],
    demoEyebrow: "Your next chapter",
    demoTitle: "Give your archive\na fresh beginning.",
    demoBody:
      "Tell us about your documents and how your team works. Let’s explore a digitization workflow that fits.",
    demoNote:
      "Your request opens a prepared message in your email app for you to send.",
    footer: "From documents to knowledge.",
  },
};
