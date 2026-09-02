package com.dataentry.service;

import com.dataentry.dto.TicketDtos;
import com.dataentry.model.CustomField;
import com.dataentry.model.Department;
import com.dataentry.model.FieldType;
import com.dataentry.model.Project;
import com.dataentry.model.Role;
import com.dataentry.model.Subcategory;
import com.dataentry.model.Ticket;
import com.dataentry.model.User;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock TicketRepository ticketRepository;
    @Mock DepartmentRepository departmentRepository;
    @Mock SubcategoryRepository subcategoryRepository;
    @Mock ProjectRepository projectRepository;
    @Mock CustomFieldRepository customFieldRepository;
    @Mock TranslationService translator;
    @Mock Localizer localizer;
    @Mock AuditService audit;

    private TicketService ticketService;

    private User agent;
    private Department dept;
    private Subcategory sub;

    @BeforeEach
    void setup() {
        agent = User.builder().id(1L).username("agent").role(Role.USER).active(true).build();
        dept = Department.builder().id(10L).name("Marketing").active(true).build();
        sub = Subcategory.builder().id(100L).department(dept).name("Blog").active(true).build();
        // Translator returns the input unchanged in both languages by default — keeps existing
        // assertions on title/content/website fields valid without touching them.
        org.mockito.Mockito.lenient()
                .when(translator.toBoth(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> new TranslationService.Bilingual(inv.getArgument(0), inv.getArgument(0)));

        // Real preparer wrapping the mocked translator — gives us the actual dedup logic under
        // test rather than a mock that would silently return empty caches.
        TicketTranslationPreparer preparer = new TicketTranslationPreparer(translator);

        // selfProvider re-enters this same instance so @Transactional self-invocation paths
        // (create → createTx, createMany → createManyTx) route back into the real object.
        @SuppressWarnings("unchecked")
        ObjectProvider<TicketService> selfProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TicketDocumentService> docProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<NotificationService> notifyProvider = org.mockito.Mockito.mock(ObjectProvider.class);

        ticketService = new TicketService(
                ticketRepository, departmentRepository, subcategoryRepository,
                projectRepository, customFieldRepository,
                preparer, translator, localizer, audit,
                docProvider, notifyProvider, selfProvider);
        // lenient — tests that go through createAttachmentTicket / deleteByIdUnchecked never
        // touch the self-invocation path, and Mockito's strict mode would otherwise fail
        // them for an "unused" stub even though the create/createMany tests do exercise it.
        org.mockito.Mockito.lenient().when(selfProvider.getObject()).thenReturn(ticketService);
    }

    @Test
    void create_rejectsInactiveDepartment() {
        Department inactive = Department.builder().id(20L).name("Archive").active(false).build();
        when(departmentRepository.findById(20L)).thenReturn(Optional.of(inactive));

        // subcategoryId = null so the flow doesn't short-circuit on the subcategory lookup
        // (which now runs before department resolution in createTx).
        TicketDtos.CreateTicketRequest req = new TicketDtos.CreateTicketRequest(
                20L, null, null, "T", "C", null, null, null, null, Map.of());

        assertThatThrownBy(() -> ticketService.create(agent, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void create_rejectsSubcategoryFromDifferentDepartment() {
        Department other = Department.builder().id(30L).name("Legal").active(true).build();
        Subcategory foreign = Subcategory.builder().id(300L).department(other).name("Terms").active(true).build();

        when(departmentRepository.findById(10L)).thenReturn(Optional.of(dept));
        when(subcategoryRepository.findById(300L)).thenReturn(Optional.of(foreign));

        TicketDtos.CreateTicketRequest req = new TicketDtos.CreateTicketRequest(
                10L, 300L, null, "T", "C", null, null, null, null, Map.of());

        assertThatThrownBy(() -> ticketService.create(agent, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not belong to");
    }

    @Test
    void create_rejectsInvalidWebsiteUrl() {
        // URL is validated in buildTicket() BEFORE custom-field lookup, so no field stub needed.
        stubDeptAndSub();

        TicketDtos.CreateTicketRequest req = new TicketDtos.CreateTicketRequest(
                10L, 100L, null, "Title", "Content",
                "Site", "ftp://not-http.example.com", null, null, Map.of());

        assertThatThrownBy(() -> ticketService.create(agent, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("http://");
    }

    @Test
    void create_rejectsMissingRequiredCustomField() {
        stubDeptAndSub();
        CustomField required = CustomField.builder()
                .id(1L).subcategory(sub).fieldKey("topic").label("Topic")
                .type(FieldType.TEXT).required(true).active(true).displayOrder(0).build();
        when(customFieldRepository.findAllBySubcategoryIdAndActiveTrueOrderByDisplayOrderAscIdAsc(100L))
                .thenReturn(List.of(required));

        TicketDtos.CreateTicketRequest req = new TicketDtos.CreateTicketRequest(
                10L, 100L, null, "Title", "Content", null, null, null, null, Map.of());

        assertThatThrownBy(() -> ticketService.create(agent, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("required");
    }

    @Test
    void create_rejectsBadEmailValue() {
        stubDeptAndSub();
        CustomField emailField = CustomField.builder()
                .id(2L).subcategory(sub).fieldKey("contact").label("Contact")
                .type(FieldType.EMAIL).required(false).active(true).displayOrder(0).build();
        when(customFieldRepository.findAllBySubcategoryIdAndActiveTrueOrderByDisplayOrderAscIdAsc(100L))
                .thenReturn(List.of(emailField));

        TicketDtos.CreateTicketRequest req = new TicketDtos.CreateTicketRequest(
                10L, 100L, null, "Title", "Content", null, null, null, null,
                Map.of("contact", "not-an-email"));

        assertThatThrownBy(() -> ticketService.create(agent, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("valid email");
    }

    @Test
    void createMany_generatesOneTicketPerArticle() {
        stubDeptAndSub();
        when(customFieldRepository.findAllBySubcategoryIdAndActiveTrueOrderByDisplayOrderAscIdAsc(100L))
                .thenReturn(List.of());
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            t.setId(999L);
            return t;
        });

        List<TicketDtos.ArticleRequest> articles = List.of(
                new TicketDtos.ArticleRequest("A", "content1", null, null, null, null),
                new TicketDtos.ArticleRequest("B", "content2", null, null, null, null),
                new TicketDtos.ArticleRequest("C", "content3", null, null, null, null)
        );
        TicketDtos.BulkCreateRequest req = new TicketDtos.BulkCreateRequest(10L, 100L, null, articles, Map.of());

        TicketDtos.BulkCreateResponse res = ticketService.createMany(agent, req);
        assertThat(res.created()).isEqualTo(3);
        assertThat(res.tickets()).hasSize(3);
    }

    private void stubDeptAndSub() {
        when(departmentRepository.findById(10L)).thenReturn(Optional.of(dept));
        when(subcategoryRepository.findById(100L)).thenReturn(Optional.of(sub));
    }

    /**
     * Regression: a fresh project with no departments used to reject uploads with a
     * "add a department first" 400. It now auto-creates a default one so a user's very
     * first upload lands without waiting on an admin.
     */
    @Test
    void createAttachmentTicket_autoCreatesDefaultDepartment_whenProjectHasNone() {
        Project project = Project.builder().id(50L).name("Fresh Project").build();
        when(projectRepository.findById(50L)).thenReturn(Optional.of(project));
        when(departmentRepository.findAllByActiveTrueAndProjectIdOrderByNameAsc(50L))
                .thenReturn(List.of());
        when(departmentRepository.findAllByProjectId(50L)).thenReturn(List.of());
        when(departmentRepository.existsByNameIgnoreCase("Fresh Project")).thenReturn(false);
        when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> {
            Department d = inv.getArgument(0);
            d.setId(999L);
            return d;
        });
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            t.setId(777L);
            return t;
        });

        Ticket saved = ticketService.createAttachmentTicket(agent, 50L, null, "report.pdf");

        assertThat(saved.getId()).isEqualTo(777L);
        assertThat(saved.getDepartment().getName()).isEqualTo("Fresh Project");
        assertThat(saved.getDepartment().isActive()).isTrue();
        // The legacy pointer must be patched so a follow-up upload skips the empty-project branch.
        assertThat(project.getDepartment()).isNotNull();
        assertThat(project.getDepartment().getName()).isEqualTo("Fresh Project");
    }
}
