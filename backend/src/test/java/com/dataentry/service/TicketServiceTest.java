package com.dataentry.service;

import com.dataentry.dto.TicketDtos;
import com.dataentry.model.CustomField;
import com.dataentry.model.Department;
import com.dataentry.model.FieldType;
import com.dataentry.model.Role;
import com.dataentry.model.Subcategory;
import com.dataentry.model.Ticket;
import com.dataentry.model.User;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    @Mock CustomFieldRepository customFieldRepository;
    @Mock TranslationService translator;
    @Mock Localizer localizer;
    @Mock AuditService audit;

    @InjectMocks TicketService ticketService;

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
    }

    @Test
    void create_rejectsInactiveDepartment() {
        Department inactive = Department.builder().id(20L).name("Archive").active(false).build();
        when(departmentRepository.findById(20L)).thenReturn(Optional.of(inactive));

        TicketDtos.CreateTicketRequest req = new TicketDtos.CreateTicketRequest(
                20L, 100L, "T", "C", null, null, Map.of());

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
                10L, 300L, "T", "C", null, null, Map.of());

        assertThatThrownBy(() -> ticketService.create(agent, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not belong to");
    }

    @Test
    void create_rejectsInvalidWebsiteUrl() {
        // URL is validated in buildTicket() BEFORE custom-field lookup, so no field stub needed.
        stubDeptAndSub();

        TicketDtos.CreateTicketRequest req = new TicketDtos.CreateTicketRequest(
                10L, 100L, "Title", "Content",
                "Site", "ftp://not-http.example.com", Map.of());

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
                10L, 100L, "Title", "Content", null, null, Map.of());

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
                10L, 100L, "Title", "Content", null, null,
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
                new TicketDtos.ArticleRequest("A", "content1", null, null),
                new TicketDtos.ArticleRequest("B", "content2", null, null),
                new TicketDtos.ArticleRequest("C", "content3", null, null)
        );
        TicketDtos.BulkCreateRequest req = new TicketDtos.BulkCreateRequest(10L, 100L, articles, Map.of());

        TicketDtos.BulkCreateResponse res = ticketService.createMany(agent, req);
        assertThat(res.created()).isEqualTo(3);
        assertThat(res.tickets()).hasSize(3);
    }

    private void stubDeptAndSub() {
        when(departmentRepository.findById(10L)).thenReturn(Optional.of(dept));
        when(subcategoryRepository.findById(100L)).thenReturn(Optional.of(sub));
    }
}
