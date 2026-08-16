package com.dataentry.config;

import com.dataentry.model.*;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.repository.UserRepository;
import com.dataentry.service.TranslationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String DEFAULT_SUBCATEGORY = "General";

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final CustomFieldRepository customFieldRepository;
    private final TicketRepository ticketRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final TranslationService translator;

    @Value("${app.seed.enabled:true}")
    private boolean seedEnabled;

    @Value("${app.seed.admin-username:admin}")
    private String adminUsername;

    @Value("${app.seed.admin-password:admin123}")
    private String adminPassword;

    public DataSeeder(UserRepository userRepository,
                      DepartmentRepository departmentRepository,
                      SubcategoryRepository subcategoryRepository,
                      CustomFieldRepository customFieldRepository,
                      TicketRepository ticketRepository,
                      PasswordEncoder passwordEncoder,
                      JdbcTemplate jdbc,
                      TranslationService translator) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.customFieldRepository = customFieldRepository;
        this.ticketRepository = ticketRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.translator = translator;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedEnabled) return;

        migrateSchema();
        seedUsers();
        seedDepartments();
        seedSubcategoriesPerDepartment();
        seedCustomFields();
        backfillLegacyRows();
        backfillTranslations();
    }

    /**
     * Startup schema patches for cases Hibernate's {@code ddl-auto=update} can't handle on
     * SQLite. Currently just one entry: legacy databases created before departments became
     * optional at project-create time have {@code projects.department_id NOT NULL}, and
     * SQLite has no {@code ALTER COLUMN DROP NOT NULL} — the only way to relax it is to
     * rebuild the table. Idempotent: if the column is already nullable this method is a
     * no-op.
     */
    private void migrateSchema() {
        // Clean up any orphaned rows that earlier half-completed deletes left behind. Two
        // patterns show up: TicketFieldValues pointing at a CustomField that no longer
        // exists (breaks the whole ticket list because the EntityGraph tries to load the
        // missing field), and TicketDocuments/TicketResources pointing at a deleted ticket.
        try {
            int fv = jdbc.update("DELETE FROM ticket_field_values WHERE field_id NOT IN (SELECT id FROM custom_fields)");
            int t1 = jdbc.update("DELETE FROM ticket_field_values WHERE ticket_id NOT IN (SELECT id FROM tickets)");
            int t2 = jdbc.update("DELETE FROM ticket_documents WHERE ticket_id NOT IN (SELECT id FROM tickets)");
            int t3 = jdbc.update("DELETE FROM ticket_resources WHERE ticket_id NOT IN (SELECT id FROM tickets)");
            if (fv + t1 + t2 + t3 > 0) {
                log.info("Cleaned orphaned rows — field_values(dangling field)={}, "
                        + "field_values(dangling ticket)={}, documents={}, resources={}",
                        fv, t1, t2, t3);
            }
        } catch (Exception e) {
            log.warn("Orphan cleanup skipped: {}", e.getMessage());
        }

        try {
            Integer notNull = jdbc.queryForObject(
                    "select \"notnull\" from pragma_table_info('projects') where name = 'department_id'",
                    Integer.class);
            if (notNull == null || notNull == 0) return;

            log.info("Migrating projects.department_id to NULLABLE (legacy schema).");
            // Skip the explicit BEGIN/COMMIT — Spring's @Transactional already owns the
            // connection, so issuing BEGIN in here would blow up with "cannot start a
            // transaction within a transaction". Each DDL executes inside the outer tx and
            // rolls back atomically if any statement throws.
            jdbc.execute("PRAGMA foreign_keys=OFF");
            try {
                jdbc.execute("""
                        CREATE TABLE projects_new (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          name VARCHAR(200) NOT NULL,
                          name_en VARCHAR(200),
                          name_ar VARCHAR(200),
                          subtitle VARCHAR(250),
                          subtitle_en VARCHAR(250),
                          subtitle_ar VARCHAR(250),
                          department_id INTEGER,
                          start_date DATE,
                          end_date DATE,
                          progress INTEGER NOT NULL DEFAULT 0,
                          status VARCHAR(20) NOT NULL DEFAULT 'ON_TRACK',
                          created_at TIMESTAMP NOT NULL
                        )
                        """);
                jdbc.execute("""
                        INSERT INTO projects_new
                          (id, name, name_en, name_ar, subtitle, subtitle_en, subtitle_ar,
                           department_id, start_date, end_date, progress, status, created_at)
                        SELECT
                          id, name, name_en, name_ar, subtitle, subtitle_en, subtitle_ar,
                          department_id, start_date, end_date, progress, status, created_at
                        FROM projects
                        """);
                jdbc.execute("DROP TABLE projects");
                jdbc.execute("ALTER TABLE projects_new RENAME TO projects");
            } finally {
                jdbc.execute("PRAGMA foreign_keys=ON");
            }
            log.info("projects.department_id is now nullable.");
        } catch (Exception e) {
            log.warn("Skipping projects.department_id migration: {}", e.getMessage());
        }
    }

    private void seedUsers() {
        if ("admin123".equals(adminPassword)) {
            log.error("⚠ SECURITY: admin password is the built-in default 'admin123'. "
                    + "Rotate APP_SEED_ADMIN_PASSWORD before exposing this instance.");
        }
        if (userRepository.findByUsername(adminUsername).isEmpty()) {
            userRepository.save(withTranslatedDisplayName(User.builder()
                    .username(adminUsername)
                    .passwordHash(passwordEncoder.encode(adminPassword))
                    .displayName("System Administrator")
                    .email("admin@dataentry.local")
                    .role(Role.ADMIN)
                    .active(true)
                    .build()));
            // Do NOT log the password — logs may end up in shared observability tooling.
            log.info("Seeded default admin user: {}", adminUsername);
        }

        if (userRepository.findByUsername("agent1").isEmpty()) {
            userRepository.save(withTranslatedDisplayName(User.builder()
                    .username("agent1")
                    .passwordHash(passwordEncoder.encode("agent123"))
                    .displayName("Sample Data Entry Agent")
                    .email("agent1@dataentry.local")
                    .role(Role.USER)
                    .active(true)
                    .build()));
            log.info("Seeded sample user: agent1 / agent123");
        }
    }

    private User withTranslatedDisplayName(User u) {
        TranslationService.Bilingual bi = translator.toBoth(u.getDisplayName());
        u.setDisplayNameEn(bi.en());
        u.setDisplayNameAr(bi.ar());
        return u;
    }

    private void seedDepartments() {
        if (departmentRepository.count() == 0) {
            List.of("Marketing", "Sales", "Content Review", "Compliance", "Research")
                    .forEach(name -> {
                        TranslationService.Bilingual bi = translator.toBoth(name);
                        departmentRepository.save(Department.builder()
                                .name(name).nameEn(bi.en()).nameAr(bi.ar()).active(true).build());
                    });
            log.info("Seeded default departments.");
        }
    }

    private void seedSubcategoriesPerDepartment() {
        Map<String, List<String>> extras = new HashMap<>();
        extras.put("Marketing", List.of("Blog", "Social"));
        extras.put("Content Review", List.of("Editorial", "Legal"));

        for (Department d : departmentRepository.findAll()) {
            ensureSubcategory(d, DEFAULT_SUBCATEGORY);
            for (String extra : extras.getOrDefault(d.getName(), List.of())) {
                ensureSubcategory(d, extra);
            }
        }
    }

    private Subcategory ensureSubcategory(Department d, String name) {
        return subcategoryRepository.findAllByDepartmentIdOrderByNameAsc(d.getId()).stream()
                .filter(s -> s.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseGet(() -> {
                    TranslationService.Bilingual bi = translator.toBoth(name);
                    Subcategory saved = subcategoryRepository.save(Subcategory.builder()
                            .department(d)
                            .name(name)
                            .nameEn(bi.en())
                            .nameAr(bi.ar())
                            .active(true)
                            .build());
                    log.info("Seeded subcategory '{}' under department '{}'", name, d.getName());
                    return saved;
                });
    }

    private void seedCustomFields() {
        if (customFieldRepository.count() > 0) return;

        Department marketing = departmentRepository.findAll().stream()
                .filter(d -> d.getName().equalsIgnoreCase("Marketing"))
                .findFirst()
                .orElseGet(() -> departmentRepository.findAll().stream().findFirst().orElse(null));
        if (marketing == null) return;

        Subcategory general = ensureSubcategory(marketing, DEFAULT_SUBCATEGORY);

        customFieldRepository.save(withTranslatedField(CustomField.builder()
                .subcategory(general)
                .fieldKey("priority")
                .label("Priority")
                .type(FieldType.SELECT)
                .required(true)
                .displayOrder(1)
                .options("Low,Medium,High")
                .placeholder("Select priority")
                .active(true)
                .build()));
        customFieldRepository.save(withTranslatedField(CustomField.builder()
                .subcategory(general)
                .fieldKey("reference_id")
                .label("Reference ID")
                .type(FieldType.TEXT)
                .required(false)
                .displayOrder(2)
                .placeholder("Optional reference identifier")
                .active(true)
                .build()));
        log.info("Seeded example custom fields under Marketing/General.");
    }

    private CustomField withTranslatedField(CustomField f) {
        TranslationService.Bilingual lb = translator.toBoth(f.getLabel());
        f.setLabelEn(lb.en());
        f.setLabelAr(lb.ar());
        if (f.getPlaceholder() != null && !f.getPlaceholder().isBlank()) {
            TranslationService.Bilingual pb = translator.toBoth(f.getPlaceholder());
            f.setPlaceholderEn(pb.en());
            f.setPlaceholderAr(pb.ar());
        }
        if (f.getOptions() != null && !f.getOptions().isBlank()) {
            TranslationService.Bilingual ob = translator.toBoth(f.getOptions());
            f.setOptionsEn(ob.en());
            f.setOptionsAr(ob.ar());
        }
        return f;
    }

    /**
     * Assigns a default subcategory to any pre-existing row whose subcategory_id is NULL.
     * Used when upgrading from a schema that predates the Subcategory feature.
     */
    private void backfillLegacyRows() {
        Long fieldsNullCount = safeCount("select count(*) from custom_fields where subcategory_id is null");
        Long ticketsNullCount = safeCount("select count(*) from tickets where subcategory_id is null");
        if ((fieldsNullCount == null || fieldsNullCount == 0)
                && (ticketsNullCount == null || ticketsNullCount == 0)) {
            return;
        }

        log.info("Backfilling subcategory_id — legacy custom_fields: {}, tickets: {}",
                fieldsNullCount, ticketsNullCount);

        if (fieldsNullCount != null && fieldsNullCount > 0) {
            Department fallback = departmentRepository.findAll().stream().findFirst().orElse(null);
            if (fallback != null) {
                Subcategory general = ensureSubcategory(fallback, DEFAULT_SUBCATEGORY);
                jdbc.update("update custom_fields set subcategory_id = ? where subcategory_id is null",
                        general.getId());
            }
        }

        if (ticketsNullCount != null && ticketsNullCount > 0) {
            for (Department d : departmentRepository.findAll()) {
                Subcategory general = ensureSubcategory(d, DEFAULT_SUBCATEGORY);
                jdbc.update(
                        "update tickets set subcategory_id = ? where subcategory_id is null and department_id = ?",
                        general.getId(), d.getId());
            }
        }
    }

    private Long safeCount(String sql) {
        try {
            return jdbc.queryForObject(sql, Long.class);
        } catch (Exception e) {
            log.debug("Backfill probe skipped ({}): {}", sql, e.getMessage());
            return null;
        }
    }

    /**
     * One-shot backfill: for rows whose _en / _ar columns are still null after the schema was
     * upgraded, treat the legacy single column as the source and translate to fill both sides.
     * Runs every startup but only touches rows that actually need it, so it's cheap after the
     * first run.
     */
    private void backfillTranslations() {
        try {
            departmentRepository.findAll().stream()
                    .filter(d -> d.getNameEn() == null || d.getNameAr() == null)
                    .forEach(d -> {
                        TranslationService.Bilingual bi = translator.toBoth(d.getName());
                        d.setNameEn(bi.en()); d.setNameAr(bi.ar());
                        departmentRepository.save(d);
                    });

            subcategoryRepository.findAll().stream()
                    .filter(s -> s.getNameEn() == null || s.getNameAr() == null)
                    .forEach(s -> {
                        TranslationService.Bilingual bi = translator.toBoth(s.getName());
                        s.setNameEn(bi.en()); s.setNameAr(bi.ar());
                        subcategoryRepository.save(s);
                    });

            customFieldRepository.findAll().stream()
                    .filter(f -> f.getLabelEn() == null || f.getLabelAr() == null)
                    .forEach(f -> {
                        TranslationService.Bilingual lb = translator.toBoth(f.getLabel());
                        f.setLabelEn(lb.en()); f.setLabelAr(lb.ar());
                        if (f.getPlaceholder() != null && !f.getPlaceholder().isBlank()
                                && (f.getPlaceholderEn() == null || f.getPlaceholderAr() == null)) {
                            TranslationService.Bilingual pb = translator.toBoth(f.getPlaceholder());
                            f.setPlaceholderEn(pb.en()); f.setPlaceholderAr(pb.ar());
                        }
                        if (f.getOptions() != null && !f.getOptions().isBlank()
                                && (f.getOptionsEn() == null || f.getOptionsAr() == null)) {
                            TranslationService.Bilingual ob = translator.toBoth(f.getOptions());
                            f.setOptionsEn(ob.en()); f.setOptionsAr(ob.ar());
                        }
                        customFieldRepository.save(f);
                    });

            userRepository.findAll().stream()
                    .filter(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank()
                            && (u.getDisplayNameEn() == null || u.getDisplayNameAr() == null))
                    .forEach(u -> {
                        TranslationService.Bilingual bi = translator.toBoth(u.getDisplayName());
                        u.setDisplayNameEn(bi.en()); u.setDisplayNameAr(bi.ar());
                        userRepository.save(u);
                    });
        } catch (Exception e) {
            log.warn("Translation backfill skipped: {}", e.getMessage());
        }
    }
}
