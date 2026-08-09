package com.dataentry.config;

import com.dataentry.model.*;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.repository.UserRepository;
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
                      JdbcTemplate jdbc) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.customFieldRepository = customFieldRepository;
        this.ticketRepository = ticketRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedEnabled) return;

        seedUsers();
        seedDepartments();
        seedSubcategoriesPerDepartment();
        seedCustomFields();
        backfillLegacyRows();
    }

    private void seedUsers() {
        if (userRepository.findByUsername(adminUsername).isEmpty()) {
            userRepository.save(User.builder()
                    .username(adminUsername)
                    .passwordHash(passwordEncoder.encode(adminPassword))
                    .displayName("System Administrator")
                    .email("admin@dataentry.local")
                    .role(Role.ADMIN)
                    .active(true)
                    .build());
            log.info("Seeded default admin user: {} / {}", adminUsername, adminPassword);
        }

        if (userRepository.findByUsername("agent1").isEmpty()) {
            userRepository.save(User.builder()
                    .username("agent1")
                    .passwordHash(passwordEncoder.encode("agent123"))
                    .displayName("Sample Data Entry Agent")
                    .email("agent1@dataentry.local")
                    .role(Role.USER)
                    .active(true)
                    .build());
            log.info("Seeded sample user: agent1 / agent123");
        }
    }

    private void seedDepartments() {
        if (departmentRepository.count() == 0) {
            List.of("Marketing", "Sales", "Content Review", "Compliance", "Research")
                    .forEach(name -> departmentRepository.save(
                            Department.builder().name(name).active(true).build()));
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
                    Subcategory saved = subcategoryRepository.save(Subcategory.builder()
                            .department(d)
                            .name(name)
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

        customFieldRepository.save(CustomField.builder()
                .subcategory(general)
                .fieldKey("priority")
                .label("Priority")
                .type(FieldType.SELECT)
                .required(true)
                .displayOrder(1)
                .options("Low,Medium,High")
                .placeholder("Select priority")
                .active(true)
                .build());
        customFieldRepository.save(CustomField.builder()
                .subcategory(general)
                .fieldKey("reference_id")
                .label("Reference ID")
                .type(FieldType.TEXT)
                .required(false)
                .displayOrder(2)
                .placeholder("Optional reference identifier")
                .active(true)
                .build());
        log.info("Seeded example custom fields under Marketing/General.");
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
}
