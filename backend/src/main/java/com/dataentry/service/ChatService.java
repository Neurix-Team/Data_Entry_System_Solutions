package com.dataentry.service;

import com.dataentry.dto.ChatDtos.ChatAction;
import com.dataentry.dto.ChatDtos.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

 
@Service
public class ChatService {

    private static final Pattern ARABIC = Pattern.compile("[\\u0600-\\u06FF]");
    // These two must be declared BEFORE PAGES_NORM's static initializer, because normalize()
    // reads them and PAGES_NORM's block calls normalize() during class loading.
    private static final Pattern DIACRITICS = Pattern.compile("[ً-ْٰ]");
    private static final Pattern WS = Pattern.compile("\\s+");

    private record Page(
            String path,
            String[] keywordsEn,
            String[] keywordsAr,
            String labelEn,
            String labelAr,
            boolean adminOnly
    ) {}

    private static final List<Page> PAGES = List.of(
            new Page("/admin",
                    new String[]{"admin dashboard", "admin home", "overview", "stats", "statistics", "admin panel"},
                    new String[]{"لوحه الاداره", "لوحه المشرف", "احصائيات", "احصاء", "نظره عامه", "الادمن", "لوحه التحكم", "لوحه ادمن"},
                    "Admin dashboard", "لوحة المشرف", true),

            new Page("/dashboard",
                    new String[]{"my dashboard", "dashboard", "home", "my home"},
                    new String[]{"لوحتي", "الرئيسيه", "الصفحه الرئيسيه", "الرئيسي", "الصفحه الاولي", "دشبورد"},
                    "Dashboard", "الصفحة الرئيسية", false),

            new Page("/admin/users",
                    new String[]{"users", "team", "members", "employees", "agents", "accounts", "people", "add user", "new user", "user list", "team members"},
                    new String[]{"مستخدمين", "مستخدم", "الاعضاء", "عضو", "موظفين", "الموظفين", "موظف", "الفريق", "فريق", "الحسابات", "حسابات", "اضف مستخدم", "اضافه مستخدم", "مستخدم جديد", "المستخدمين", "المستخدمون"},
                    "Users", "المستخدمون", true),

            new Page("/admin/departments",
                    new String[]{"departments", "teams", "divisions", "add department"},
                    new String[]{"اقسام", "قسم", "الاداره", "الادارات", "اداره", "قسم جديد", "اضف قسم", "الاقسام", "قسمي"},
                    "Departments", "الأقسام", true),

            new Page("/admin/subcategories",
                    new String[]{"subcategories", "categories", "sub category", "subcategory"},
                    new String[]{"التصنيفات الفرعيه", "تصنيفات", "التصنيفات", "الفئات", "فئات", "فئه", "تصنيف", "الفرعيه"},
                    "Subcategories", "التصنيفات", true),

            new Page("/admin/projects",
                    new String[]{"projects", "project list", "add project"},
                    new String[]{"مشاريع", "المشاريع", "مشروع", "المشروعات", "مشروع جديد", "اضف مشروع"},
                    "Projects", "المشاريع", true),

            new Page("/admin/tickets",
                    new String[]{"all tickets", "tickets", "browse tickets", "every ticket"},
                    new String[]{"كل التذاكر", "جميع التذاكر", "كل المهام", "التذاكر", "تذاكر", "المهام", "مهام", "كل المشاكل", "جميع المهام"},
                    "All tickets", "كل التذاكر", true),

            new Page("/admin/fields",
                    new String[]{"custom fields", "fields", "form fields", "form builder", "add field"},
                    new String[]{"الحقول المخصصه", "الحقول", "حقول", "حقل", "بناء النموذج", "الحقل", "اضف حقل", "حقل جديد", "النموذج"},
                    "Custom fields", "الحقول المخصصة", true),

            new Page("/admin/reports",
                    new String[]{"reports", "analytics", "insights", "performance", "report", "stats page"},
                    new String[]{"تقارير", "التقارير", "تقرير", "تحليلات", "التحليلات", "اداء", "الاداء", "التقرير", "احصائيات مفصله", "تقارير الاداء"},
                    "Reports", "التقارير", true),

            new Page("/submit",
                    new String[]{"submit", "new ticket", "add ticket", "create ticket", "submit ticket", "new entry", "add entry", "make ticket", "create entry"},
                    new String[]{"اضافه", "اضف", "تذكره جديده", "ادخال جديد", "ارسال", "تسجيل جديد", "تذكره", "ادخال", "اضف تذكره", "اضافه تذكره", "ارسل تذكره", "تسجيل تذكره", "اضف مهمه", "مهمه جديده"},
                    "Submit a ticket", "إضافة تذكرة", false),

            new Page("/my-tickets",
                    new String[]{"my tickets", "my tasks", "my entries", "my submissions", "own tickets"},
                    new String[]{"تذاكري", "مهامي", "طلباتي", "المقدمه مني", "بتاعتي", "الي قدمتها", "اللي قدمتها", "تذكرتي"},
                    "My tickets", "تذاكري", false)
    );

    /** Pre-normalized keyword banks — computed once at class load, matched against normalized input. */
    private static final List<PageNorm> PAGES_NORM;

    static {
        List<PageNorm> tmp = new ArrayList<>();
        for (Page p : PAGES) {
            String[] en = normalizeAll(p.keywordsEn());
            String[] ar = normalizeAll(p.keywordsAr());
            tmp.add(new PageNorm(p, en, ar));
        }
        PAGES_NORM = List.copyOf(tmp);
    }

    private record PageNorm(Page page, String[] enNorm, String[] arNorm) {}

    public ChatResponse reply(String message, String role, String requestedLang, String currentPath) {
        String raw = message == null ? "" : message.trim();
        boolean ar = "ar".equalsIgnoreCase(requestedLang) || ARABIC.matcher(raw).find();
        boolean isAdmin = "ADMIN".equalsIgnoreCase(role);

        if (raw.isEmpty()) {
            return new ChatResponse(ar
                    ? "اكتب رسالتك وأنا في الخدمة."
                    : "Type a message and I'll help.", List.of());
        }

        String norm = normalize(raw);

        // ---- Small talk (short messages only, to avoid stealing longer navigation intents) ----
        if (isShortAndOnly(norm, GREETING_ALL)) {
            return new ChatResponse(ar
                    ? "أهلاً بك! قولّي على أي صفحة عايز تروحها وأنا هأوديك عليها فوراً."
                    : "Hi there! Tell me which page you want and I'll take you straight there.",
                    accessibleQuickLinks(ar, isAdmin));
        }
        if (isShortAndOnly(norm, THANKS_ALL)) {
            return new ChatResponse(ar
                    ? "على الرحب والسعة 😊"
                    : "You're welcome! Happy to help.", List.of());
        }
        if (isShortAndOnly(norm, GOODBYE_ALL)) {
            return new ChatResponse(ar
                    ? "مع السلامة! أنا هنا وقت ما تحتاجني."
                    : "Take care! I'll be here whenever you need me.", List.of());
        }

        // ---- Site info ----
        if (matchesAny(norm, SITE_INFO_ALL)) {
            String reply = ar
                    ? "Neurix هو نظام موحّد لإدارة عمليات إدخال البيانات: تنظيم الفرق والأقسام، تسجيل التذاكر ومتابعتها من الإدخال حتى الاعتماد، حقول مخصّصة يديرها المشرف، وتقارير أداء لحظية."
                    : "Neurix is a unified workspace for managing data entry: organize teams and departments, track tickets from submission to sign-off, build custom form fields, and monitor performance in real time.";
            return new ChatResponse(reply, accessibleQuickLinks(ar, isAdmin));
        }

        // ---- Help / capabilities ----
        if (matchesAny(norm, HELP_ALL)) {
            String reply = ar
                    ? "أقدر أوديك على أي صفحة في التطبيق. اكتب اسم الصفحة أو ميزة وأنا أفهمها. جرب: 'التقارير'، 'المستخدمين'، 'اضف تذكرة'."
                    : "I can navigate you to any page. Just name it or describe it. Try: 'reports', 'users', 'add ticket'.";
            return new ChatResponse(reply, accessibleAllPages(ar, isAdmin));
        }

        // ---- Best-effort page match — always fire ----
        PageNorm best = null;
        int bestScore = 0;
        for (PageNorm pn : PAGES_NORM) {
            if (pn.page().adminOnly() && !isAdmin) continue;
            int score = scorePage(norm, pn);
            if (score > bestScore) { bestScore = score; best = pn; }
        }

        if (best != null) {
            Page p = best.page();
            List<ChatAction> actions = new ArrayList<>();
            actions.add(new ChatAction("navigate", p.path(), ar ? "افتح " + p.labelAr() : "Open " + p.labelEn()));
            String reply = ar
                    ? "تمام، هأوديك على \"" + p.labelAr() + "\"."
                    : "Got it — taking you to " + p.labelEn() + ".";
            return new ChatResponse(reply, actions);
        }

         
        String reply = ar
                ? "قوللي أروح على أنهي صفحة — اختار من الأسفل:"
                : "Which page should I open? Pick one below:";
        return new ChatResponse(reply, accessibleAllPages(ar, isAdmin));
    }
 
    private static int scorePage(String normInput, PageNorm p) {
        int score = 0;
        for (String k : p.enNorm()) {
            if (k.isEmpty()) continue;
            if (normInput.contains(k)) score = Math.max(score, weight(k));
        }
        for (String k : p.arNorm()) {
            if (k.isEmpty()) continue;
            if (normInput.contains(k)) score = Math.max(score, weight(k));
        }
        return score;
    }

     
    private static int weight(String k) {
        return k.length() >= 3 ? k.length() : 0;
    }

     
    private static boolean isShortAndOnly(String normInput, String[] normPhrases) {
        if (normInput.length() > 30) return false;
        for (String p : normPhrases) {
            if (p.isEmpty()) continue;
            if (normInput.equals(p)) return true;
            // allow trailing punctuation / a single extra word like "there"
            if (normInput.startsWith(p + " ") && normInput.length() - p.length() <= 10) return true;
            if (normInput.endsWith(" " + p) && normInput.length() - p.length() <= 10) return true;
        }
        return false;
    }

    private static boolean matchesAny(String normInput, String[] normPhrases) {
        for (String p : normPhrases) {
            if (p.isEmpty()) continue;
            if (normInput.contains(p)) return true;
        }
        return false;
    }

 

    private List<ChatAction> accessibleQuickLinks(boolean ar, boolean isAdmin) {
        List<ChatAction> a = new ArrayList<>();
        if (isAdmin) {
            a.add(nav("/admin",         ar ? "لوحة المشرف"   : "Admin dashboard"));
            a.add(nav("/admin/tickets", ar ? "كل التذاكر"    : "All tickets"));
            a.add(nav("/admin/users",   ar ? "المستخدمون"    : "Users"));
        } else {
            a.add(nav("/dashboard",  ar ? "الرئيسية"     : "Dashboard"));
            a.add(nav("/submit",     ar ? "إضافة تذكرة"  : "Submit a ticket"));
            a.add(nav("/my-tickets", ar ? "تذاكري"       : "My tickets"));
        }
        return a;
    }

 
    private List<ChatAction> accessibleAllPages(boolean ar, boolean isAdmin) {
        List<ChatAction> a = new ArrayList<>();
        for (Page p : PAGES) {
            if (p.adminOnly() && !isAdmin) continue;
            a.add(nav(p.path(), ar ? p.labelAr() : p.labelEn()));
        }
        return a;
    }

    private static ChatAction nav(String path, String label) {
        return new ChatAction("navigate", path, label);
    }

 
    /**
     * Fold the input so that near-variants match. Applied to both the user's message and
     * every keyword before comparison.
     *
     * <ul>
     *   <li>Lowercase (English)</li>
     *   <li>Strip Arabic diacritics (harakat + shadda + sukun): U+064B..U+0652</li>
     *   <li>Unify hamza forms أ إ آ ء → ا / ئ ؤ → ي / و</li>
     *   <li>Unify alef-maksura ى → ي</li>
     *   <li>Unify ta-marbuta ة → ه</li>
     *   <li>Strip tatweel (kashida) ـ</li>
     *   <li>Collapse whitespace</li>
     * </ul>
     */
    static String normalize(String s) {
        if (s == null) return "";
        String out = s.toLowerCase();
        out = DIACRITICS.matcher(out).replaceAll("");
        out = out.replace('أ', 'ا')
                 .replace('إ', 'ا')
                 .replace('آ', 'ا')
                 .replace('ٱ', 'ا')
                 .replace('ى', 'ي')
                 .replace('ئ', 'ي')
                 .replace('ؤ', 'و')
                 .replace('ة', 'ه')
                 .replace('ـ', ' ');
        out = out.replace("ء", "");
        out = out.replace(',', ' ').replace('،', ' ').replace('.', ' ').replace('؟', ' ')
                 .replace('?', ' ').replace('!', ' ').replace('"', ' ').replace('\'', ' ');
        out = WS.matcher(out).replaceAll(" ").trim();
        return out;
    }

    private static String[] normalizeAll(String[] arr) {
        String[] out = new String[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = normalize(arr[i]);
        return out;
    }
 
    private static final String[] GREETING_ALL = normalizeAll(new String[]{
            "hi", "hello", "hey", "yo", "hiya", "good morning", "good evening", "good afternoon",
            "اهلا", "أهلاً", "مرحبا", "مرحباً", "ازيك", "إزيك", "السلام عليكم",
            "صباح الخير", "مساء الخير", "سلام", "هاي", "هلا"
    });
    private static final String[] THANKS_ALL = normalizeAll(new String[]{
            "thanks", "thank you", "thx", "ty", "appreciate", "cheers",
            "شكرا", "شكراً", "متشكر", "متشكرة", "تسلم", "يعطيك العافية", "جزاك الله", "الف شكر"
    });
    private static final String[] GOODBYE_ALL = normalizeAll(new String[]{
            "bye", "goodbye", "see you", "see ya", "cya", "later",
            "مع السلامه", "وداعا", "باي", "سلامو", "الى اللقاء"
    });
    private static final String[] SITE_INFO_ALL = normalizeAll(new String[]{
            "what is this", "what does this", "what is neurix", "about this site",
            "about this app", "what does the site do", "what does this site do",
            "what does this app do", "tell me about the site", "tell me about the app",
            "what is the site", "what is the app",
            "ايه ده", "الموقع ده", "التطبيق ده", "الموقع بيعمل ايه", "التطبيق بيعمل ايه",
            "ايه نيوريكس", "نبذه", "تعريف الموقع", "الموقع يعمل ايه"
    });
    private static final String[] HELP_ALL = normalizeAll(new String[]{
            "help", "what can you do", "what can you", "capabilities", "how do you work",
            "ساعدني", "ساعديني", "بتعمل ايه", "قدراتك", "ايه اللي تقدر", "ممكن تعمل ايه", "بتعمل ايه انت"
    });
}
