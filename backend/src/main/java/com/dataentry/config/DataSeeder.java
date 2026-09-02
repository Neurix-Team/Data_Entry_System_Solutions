package com.dataentry.config;

import com.dataentry.model.*;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TeamRepository;
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
    private static final String DEFAULT_TEAM_SLUG = "general";

    private final TeamRepository teamRepository;
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

    @Value("${app.seed.superadmin-username:superadmin}")
    private String superAdminUsername;

    @Value("${app.seed.superadmin-password:superadmin123}")
    private String superAdminPassword;

    public DataSeeder(TeamRepository teamRepository,
                      UserRepository userRepository,
                      DepartmentRepository departmentRepository,
                      SubcategoryRepository subcategoryRepository,
                      CustomFieldRepository customFieldRepository,
                      TicketRepository ticketRepository,
                      PasswordEncoder passwordEncoder,
                      JdbcTemplate jdbc,
                      TranslationService translator) {
        this.teamRepository = teamRepository;
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

        cleanOrphanRows();
        Team defaultTeam = seedDefaultTeam();
        backfillTeamIds(defaultTeam);
        seedUsers(defaultTeam);
        seedSuperAdmin();
        seedDepartments(defaultTeam);
        seedSubcategoriesPerDepartment(defaultTeam);
        seedCustomFields(defaultTeam);
        backfillLegacyRows();
        backfillTranslations();
        splitMultiAdminTeams();
    }

    /**
     * Enforces the "one admin per team" invariant on legacy data. Every admin runs their own
     * isolated workspace, so a team that historically ended up with more than one ADMIN gets
     * split: the oldest admin (by createdAt) keeps the current team + all its projects,
     * departments, tickets, and non-admin users, while every other admin is moved into a
     * fresh empty team named after them. Idempotent — subsequent boots find no violations
     * and do nothing.
     */
    private void splitMultiAdminTeams() {
        List<Long> teamsWithMultipleAdmins;
        try {
            teamsWithMultipleAdmins = jdbc.queryForList(
                    "SELECT team_id FROM users " +
                    "WHERE role = 'ADMIN' AND team_id IS NOT NULL " +
                    "GROUP BY team_id HAVING COUNT(*) > 1",
                    Long.class);
        } catch (Exception e) {
            log.warn("multi-admin scan skipped: {}", e.getMessage());
            return;
        }
        if (teamsWithMultipleAdmins.isEmpty()) return;

        log.info("Found {} team(s) with more than one ADMIN — splitting so each admin owns "
                + "their own team.", teamsWithMultipleAdmins.size());

        for (Long teamId : teamsWithMultipleAdmins) {
            List<Map<String, Object>> admins = jdbc.queryForList(
                    "SELECT id, username, COALESCE(display_name, username) AS display " +
                    "FROM users WHERE team_id = ? AND role = 'ADMIN' " +
                    "ORDER BY created_at ASC, id ASC",
                    teamId);
            // Keep the first admin (oldest) with the existing team + all its data. Move
            // every subsequent admin to a fresh team.
            for (int i = 1; i < admins.size(); i++) {
                Long adminId = ((Number) admins.get(i).get("id")).longValue();
                String username = (String) admins.get(i).get("username");
                String display = (String) admins.get(i).get("display");
                Long newTeamId = createSoloWorkspaceTeam(username, display);
                jdbc.update("UPDATE users SET team_id = ? WHERE id = ?", newTeamId, adminId);
                log.info("Moved admin '{}' (id={}) to fresh team id={} as part of split.",
                        username, adminId, newTeamId);
            }
        }
    }

    private Long createSoloWorkspaceTeam(String username, String displayName) {
        String base = username == null ? "team" : username.trim().toLowerCase();
        String cleaned = base.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (cleaned.isEmpty()) cleaned = "team";
        String slug = cleaned;
        int i = 2;
        while (teamRepository.existsBySlugIgnoreCase(slug)) {
            slug = cleaned + "-" + i++;
        }
        String name = (displayName != null && !displayName.isBlank() ? displayName : username)
                + "'s workspace";
        TranslationService.Bilingual bi;
        try {
            bi = translator.toBoth(name);
        } catch (Exception e) {
            bi = new TranslationService.Bilingual(name, name);
        }
        Team saved = teamRepository.save(Team.builder()
                .slug(slug)
                .name(name)
                .nameEn(bi.en())
                .nameAr(bi.ar())
                .description("Auto-created during the one-admin-per-team split.")
                .color("#6366f1")
                .active(true)
                .build());
        return saved.getId();
    }

    private void cleanOrphanRows() {
        // Clean up orphaned dependent rows before normal startup work.
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
    }

    private boolean columnExists(String table, String column) {
        try {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                            "WHERE table_schema = current_schema() " +
                            "AND lower(table_name) = lower(?) AND lower(column_name) = lower(?)",
                    Long.class, table, column);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ensures a "General" team exists so every legacy row and every new admin/user has a
     * home. Recovering from a wiped-teams table simply re-creates it on the next boot.
     */
    private Team seedDefaultTeam() {
        return teamRepository.findBySlug(DEFAULT_TEAM_SLUG).orElseGet(() -> {
            TranslationService.Bilingual bi = translator.toBoth("General");
            Team saved = teamRepository.save(Team.builder()
                    .slug(DEFAULT_TEAM_SLUG)
                    .name("General")
                    .nameEn(bi.en())
                    .nameAr(bi.ar())
                    .description("Default team seeded on first startup.")
                    .color("#6366f1")
                    .active(true)
                    .build());
            log.info("Seeded default team '{}'", DEFAULT_TEAM_SLUG);
            return saved;
        });
    }

    /**
     * Any row that was created before multi-tenancy has {@code team_id IS NULL}. Point every
     * such row at the default team so the Hibernate filter can find it under the general
     * admin's session. Idempotent — subsequent runs are no-ops.
     */
    private void backfillTeamIds(Team defaultTeam) {
        Long teamId = defaultTeam.getId();
        List<String> tables = List.of(
                "users", "projects", "departments", "subcategories",
                "tickets", "custom_fields", "audit_logs"
        );
        for (String table : tables) {
            try {
                if (!columnExists(table, "team_id")) continue;
                // SUPER_ADMIN accounts MUST keep team_id = NULL — they are cross-team by
                // design, so accidentally attaching them to a team would (a) leak their
                // presence in that team's admin/users list and (b) let a team admin
                // deactivate them via the normal user-management UI.
                String sql = "users".equals(table)
                        ? "UPDATE users SET team_id = ? WHERE team_id IS NULL AND role != 'SUPER_ADMIN'"
                        : "UPDATE " + table + " SET team_id = ? WHERE team_id IS NULL";
                int updated = jdbc.update(sql, teamId);
                if (updated > 0) {
                    log.info("Backfilled team_id on {} rows in {}", updated, table);
                }
            } catch (Exception e) {
                log.warn("team_id backfill on {} skipped: {}", table, e.getMessage());
            }
        }
        // Repair: if a super admin has been mis-stamped by an older seeder run, revert it.
        try {
            int fixed = jdbc.update("UPDATE users SET team_id = NULL WHERE role = 'SUPER_ADMIN' AND team_id IS NOT NULL");
            if (fixed > 0) log.info("Repaired {} SUPER_ADMIN row(s) that had been stamped with a team_id.", fixed);
        } catch (Exception e) {
            log.warn("SUPER_ADMIN team_id repair skipped: {}", e.getMessage());
        }

        // Repair: child rows whose team_id drifted away from their parent's team. The
        // "seed subcategory into every department" bug in an earlier build stamped the
        // default team's id on subcategories whose department actually belonged to another
        // team, which the @PostLoad guard now correctly rejects as a cross-team mismatch —
        // resulting in 404s on legitimate list pages. Realign to the parent's team_id.
        try {
            int subFix = jdbc.update(
                    "UPDATE subcategories SET team_id = (SELECT team_id FROM departments d WHERE d.id = subcategories.department_id) " +
                            "WHERE team_id IS NOT NULL " +
                            "AND team_id != (SELECT team_id FROM departments d WHERE d.id = subcategories.department_id)");
            if (subFix > 0) log.info("Repaired {} subcategory row(s) whose team_id had drifted from the parent department.", subFix);

            int cfFix = jdbc.update(
                    "UPDATE custom_fields SET team_id = (SELECT team_id FROM subcategories s WHERE s.id = custom_fields.subcategory_id) " +
                            "WHERE subcategory_id IS NOT NULL AND team_id IS NOT NULL " +
                            "AND team_id != (SELECT team_id FROM subcategories s WHERE s.id = custom_fields.subcategory_id)");
            if (cfFix > 0) log.info("Repaired {} custom_field row(s) whose team_id had drifted.", cfFix);

            int tFix = jdbc.update(
                    "UPDATE tickets SET team_id = (SELECT team_id FROM departments d WHERE d.id = tickets.department_id) " +
                            "WHERE team_id IS NOT NULL " +
                            "AND team_id != (SELECT team_id FROM departments d WHERE d.id = tickets.department_id)");
            if (tFix > 0) log.info("Repaired {} ticket row(s) whose team_id had drifted from department.", tFix);

            // Cross-team FK references: null out foreign keys that would let a project/subcategory
            // point at a row owned by a different team. The Hibernate @PostLoad guard fires when
            // such an association is lazy-loaded and turns benign list pages into 404s. Nulling
            // the FK preserves the parent row and just detaches the offending reference.
            int projFk = jdbc.update(
                    "UPDATE projects SET department_id = NULL " +
                            "WHERE department_id IS NOT NULL " +
                            "AND team_id IS NOT NULL " +
                            "AND team_id != (SELECT team_id FROM departments d WHERE d.id = projects.department_id)");
            if (projFk > 0) log.info("Nulled {} projects.department_id cross-team FK(s).", projFk);

            int subFk = jdbc.update(
                    "UPDATE tickets SET subcategory_id = NULL " +
                            "WHERE subcategory_id IS NOT NULL " +
                            "AND team_id IS NOT NULL " +
                            "AND team_id != (SELECT team_id FROM subcategories s WHERE s.id = tickets.subcategory_id)");
            if (subFk > 0) log.info("Nulled {} tickets.subcategory_id cross-team FK(s).", subFk);

            int projRefFk = jdbc.update(
                    "UPDATE tickets SET project_id = NULL " +
                            "WHERE project_id IS NOT NULL " +
                            "AND team_id IS NOT NULL " +
                            "AND team_id != (SELECT team_id FROM projects p WHERE p.id = tickets.project_id)");
            if (projRefFk > 0) log.info("Nulled {} tickets.project_id cross-team FK(s).", projRefFk);

            // departments.project_id: a department in team X pointing at a project in team Y
            // would cause DepartmentService.toDto to lazy-load that project on the wrong-team
            // request, tripping the @PostLoad guard and blowing up the whole /api/admin/departments
            // response with a "Not found" 404. Nulling the FK detaches the reference so the
            // department still shows up in its own team, just without the mismatched project link.
            int deptProjFk = jdbc.update(
                    "UPDATE departments SET project_id = NULL " +
                            "WHERE project_id IS NOT NULL " +
                            "AND team_id IS NOT NULL " +
                            "AND team_id != (SELECT team_id FROM projects p WHERE p.id = departments.project_id)");
            if (deptProjFk > 0) log.info("Nulled {} departments.project_id cross-team FK(s).", deptProjFk);

            // subcategories.department_id: same story — a subcategory in team X pointing at a
            // department in team Y trips the guard when the subcategory list serialises.
            int subDeptFk = jdbc.update(
                    "UPDATE subcategories SET department_id = NULL " +
                            "WHERE department_id IS NOT NULL " +
                            "AND team_id IS NOT NULL " +
                            "AND team_id != (SELECT team_id FROM departments d WHERE d.id = subcategories.department_id)");
            if (subDeptFk > 0) log.info("Nulled {} subcategories.department_id cross-team FK(s).", subDeptFk);

            // custom_fields.subcategory_id: fields inherit tenancy from their subcategory. A
            // cross-team FK here would trip the guard when the fields list serialises for
            // /admin/fields or when a ticket loads its custom values.
            int fieldSubFk = jdbc.update(
                    "UPDATE custom_fields SET subcategory_id = NULL " +
                            "WHERE subcategory_id IS NOT NULL " +
                            "AND team_id IS NOT NULL " +
                            "AND team_id != (SELECT team_id FROM subcategories s WHERE s.id = custom_fields.subcategory_id)");
            if (fieldSubFk > 0) log.info("Nulled {} custom_fields.subcategory_id cross-team FK(s).", fieldSubFk);

            // project_members: the many-to-many join between projects and users can hold
            // cross-team rows from legacy data. The Hibernate teamFilter is not applied to
            // @ManyToMany lazy loads, so any eager fetch or getMembers() call on the wrong-team
            // side would trip the PostLoad guard on User and return "Not found". Delete the
            // mismatched rows so the association only ever contains same-team members.
            int memFix = jdbc.update(
                    "DELETE FROM project_members " +
                            "WHERE EXISTS (" +
                            "  SELECT 1 FROM projects p, users u " +
                            "   WHERE p.id = project_members.project_id " +
                            "     AND u.id = project_members.user_id " +
                            "     AND p.team_id IS NOT NULL AND u.team_id IS NOT NULL " +
                            "     AND p.team_id != u.team_id" +
                            ")");
            if (memFix > 0) log.info("Deleted {} project_members cross-team row(s).", memFix);
        } catch (Exception e) {
            log.warn("Child-team drift repair skipped: {}", e.getMessage());
        }
    }

    private void seedUsers(Team defaultTeam) {
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
                    .team(defaultTeam)
                    .active(true)
                    .build()));
            log.info("Seeded default admin user: {} (team={})", adminUsername, defaultTeam.getSlug());
        }

        if (userRepository.findByUsername("agent1").isEmpty()) {
            userRepository.save(withTranslatedDisplayName(User.builder()
                    .username("agent1")
                    .passwordHash(passwordEncoder.encode("agent123"))
                    .displayName("Sample Data Entry Agent")
                    .email("agent1@dataentry.local")
                    .role(Role.USER)
                    .team(defaultTeam)
                    .active(true)
                    .build()));
            log.info("Seeded sample user: agent1 / agent123 (team={})", defaultTeam.getSlug());
        }
    }

    /**
     * SUPER_ADMIN is intentionally not attached to any team. It's the only role that can
     * cross tenant boundaries, so an accidental team assignment would let the tenant filter
     * hide half the system from them. Rotate the credentials on first boot.
     */
    private void seedSuperAdmin() {
        String username = superAdminUsername == null ? "" : superAdminUsername.trim();
        if (username.isEmpty()) {
            log.warn("APP_SEED_SUPERADMIN_USERNAME is unset — skipping super-admin seed. "
                    + "Set it in your .env to auto-create one, or promote a user manually.");
            return;
        }
        if ("superadmin123".equals(superAdminPassword)) {
            log.error("⚠ SECURITY: super-admin password is the built-in default 'superadmin123'. "
                    + "Rotate APP_SEED_SUPERADMIN_PASSWORD before exposing this instance.");
        }
        if (userRepository.findByUsername(username).isEmpty()) {
            userRepository.save(withTranslatedDisplayName(User.builder()
                    .username(username)
                    .passwordHash(passwordEncoder.encode(superAdminPassword))
                    .displayName("Super Administrator")
                    .email("superadmin@dataentry.local")
                    .role(Role.SUPER_ADMIN)
                    .team(null)
                    .active(true)
                    .build()));
            log.info("Seeded super admin: {}", username);
        }
    }

    private User withTranslatedDisplayName(User u) {
        TranslationService.Bilingual bi = translator.toBoth(u.getDisplayName());
        u.setDisplayNameEn(bi.en());
        u.setDisplayNameAr(bi.ar());
        return u;
    }

    private void seedDepartments(Team defaultTeam) {
        if (departmentRepository.count() == 0) {
            List.of("Marketing", "Sales", "Content Review", "Compliance", "Research")
                    .forEach(name -> {
                        TranslationService.Bilingual bi = translator.toBoth(name);
                        departmentRepository.save(Department.builder()
                                .name(name).nameEn(bi.en()).nameAr(bi.ar())
                                .team(defaultTeam)
                                .active(true).build());
                    });
            log.info("Seeded default departments.");
        }
    }

    private void seedSubcategoriesPerDepartment(Team defaultTeam) {
        Map<String, List<String>> extras = new HashMap<>();
        extras.put("Marketing", List.of("Blog", "Social"));
        extras.put("Content Review", List.of("Editorial", "Legal"));

        // Only seed default subcategories on departments the DEFAULT TEAM owns. Iterating
        // every department was the source of a subtle data-corruption bug: admins in other
        // teams create their own departments, then on next boot the seeder (running with
        // no tenant context) auto-added a "General" subcategory to each of those, stamping
        // the WRONG team_id in the process — the row's team_id ended up as the default
        // team's id even though the parent department lived in a different team.
        for (Department d : departmentRepository.findAll()) {
            if (d.getTeam() == null || !defaultTeam.getId().equals(d.getTeam().getId())) continue;
            ensureSubcategory(d, DEFAULT_SUBCATEGORY, defaultTeam);
            for (String extra : extras.getOrDefault(d.getName(), List.of())) {
                ensureSubcategory(d, extra, defaultTeam);
            }
        }
    }

    private Subcategory ensureSubcategory(Department d, String name, Team fallbackTeam) {
        return subcategoryRepository.findAllByDepartmentIdOrderByNameAsc(d.getId()).stream()
                .filter(s -> s.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseGet(() -> {
                    TranslationService.Bilingual bi = translator.toBoth(name);
                    Subcategory saved = subcategoryRepository.save(Subcategory.builder()
                            .department(d)
                            .team(d.getTeam() != null ? d.getTeam() : fallbackTeam)
                            .name(name)
                            .nameEn(bi.en())
                            .nameAr(bi.ar())
                            .active(true)
                            .build());
                    log.info("Seeded subcategory '{}' under department '{}'", name, d.getName());
                    return saved;
                });
    }

    private void seedCustomFields(Team defaultTeam) {
        if (customFieldRepository.count() > 0) return;

        Department marketing = departmentRepository.findAll().stream()
                .filter(d -> d.getName().equalsIgnoreCase("Marketing"))
                .findFirst()
                .orElseGet(() -> departmentRepository.findAll().stream().findFirst().orElse(null));
        if (marketing == null) return;

        Subcategory general = ensureSubcategory(marketing, DEFAULT_SUBCATEGORY, defaultTeam);

        customFieldRepository.save(withTranslatedField(CustomField.builder()
                .subcategory(general)
                .team(defaultTeam)
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
                .team(defaultTeam)
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
                Subcategory general = ensureSubcategory(fallback, DEFAULT_SUBCATEGORY, fallback.getTeam());
                jdbc.update("update custom_fields set subcategory_id = ? where subcategory_id is null",
                        general.getId());
            }
        }

        if (ticketsNullCount != null && ticketsNullCount > 0) {
            for (Department d : departmentRepository.findAll()) {
                Subcategory general = ensureSubcategory(d, DEFAULT_SUBCATEGORY, d.getTeam());
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
