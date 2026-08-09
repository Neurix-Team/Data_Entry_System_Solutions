package com.dataentry.repository;

import com.dataentry.dto.DashboardDtos;
import com.dataentry.model.Department;
import com.dataentry.model.Role;
import com.dataentry.model.Subcategory;
import com.dataentry.model.Ticket;
import com.dataentry.model.TicketStatus;
import com.dataentry.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
class TicketRepositoryTest {

    @Autowired TicketRepository ticketRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired SubcategoryRepository subcategoryRepository;
    @Autowired UserRepository userRepository;

    private User alice;
    private User bob;
    private Department marketing;
    private Subcategory blog;

    @BeforeEach
    void seed() {
        alice = userRepository.save(User.builder()
                .username("alice").passwordHash("x").role(Role.USER)
                .displayName("Alice").active(true).build());
        bob = userRepository.save(User.builder()
                .username("bob").passwordHash("x").role(Role.USER)
                .displayName("Bob").active(true).build());

        marketing = departmentRepository.save(Department.builder().name("Marketing").active(true).build());
        blog = subcategoryRepository.save(Subcategory.builder()
                .department(marketing).name("Blog").active(true).build());

        Instant now = Instant.now();
        saveTicket(alice, TicketStatus.COMPLETED, now.minus(1, ChronoUnit.DAYS));
        saveTicket(alice, TicketStatus.COMPLETED, now);
        saveTicket(alice, TicketStatus.IN_PROGRESS, now);
        saveTicket(bob, TicketStatus.COMPLETED, now);
        saveTicket(bob, TicketStatus.REVIEW, now.minus(10, ChronoUnit.DAYS));
    }

    private void saveTicket(User u, TicketStatus status, Instant when) {
        Ticket t = Ticket.builder()
                .submittedBy(u).department(marketing).subcategory(blog)
                .title("T").content("C").websiteName("").websiteLink("")
                .status(status).submittedAt(when).build();
        ticketRepository.save(t);
    }

    @Test
    void countByStatus_returnsCorrectTotals() {
        assertThat(ticketRepository.countByStatus(TicketStatus.COMPLETED)).isEqualTo(3);
        assertThat(ticketRepository.countByStatus(TicketStatus.IN_PROGRESS)).isEqualTo(1);
        assertThat(ticketRepository.countByStatus(TicketStatus.REVIEW)).isEqualTo(1);
    }

    @Test
    void countByStatusAndSubmittedAtGreaterThanEqual_scopesToWindow() {
        Instant fiveDaysAgo = Instant.now().minus(5, ChronoUnit.DAYS);
        long completedRecent = ticketRepository
                .countByStatusAndSubmittedAtGreaterThanEqual(TicketStatus.COMPLETED, fiveDaysAgo);
        assertThat(completedRecent).isEqualTo(3);

        Instant twentyDaysAgo = Instant.now().minus(20, ChronoUnit.DAYS);
        long reviewRecent = ticketRepository
                .countByStatusAndSubmittedAtGreaterThanEqual(TicketStatus.REVIEW, twentyDaysAgo);
        assertThat(reviewRecent).isEqualTo(1);
    }

    @Test
    void countByDepartment_returnsTypedProjection() {
        List<DashboardDtos.DepartmentCount> counts = ticketRepository.countByDepartment();
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).departmentId()).isEqualTo(marketing.getId());
        assertThat(counts.get(0).total()).isEqualTo(5);
    }

    @Test
    void topPerformersByStatus_ordersByCompletedDesc() {
        List<DashboardDtos.TopPerformer> top = ticketRepository
                .topPerformersByStatus(TicketStatus.COMPLETED, PageRequest.of(0, 5));
        assertThat(top).extracting(DashboardDtos.TopPerformer::username)
                .containsExactly("alice", "bob");
        assertThat(top.get(0).completed()).isEqualTo(2);
        assertThat(top.get(1).completed()).isEqualTo(1);
    }

    @Test
    void countByUserSince_returnsTypedProjection() {
        Instant since = Instant.now().minus(2, ChronoUnit.DAYS);
        Map<Long, Long> byUser = ticketRepository.countByUserSince(since).stream()
                .collect(Collectors.toMap(DashboardDtos.UserCount::userId, DashboardDtos.UserCount::total));
        assertThat(byUser.get(alice.getId())).isEqualTo(3);
        assertThat(byUser.get(bob.getId())).isEqualTo(1);
    }

    @Test
    void userTicketsByStatus_returnsTypedStatusCounts() {
        List<DashboardDtos.StatusCount> aliceCounts =
                ticketRepository.userTicketsByStatus(alice.getId());
        Map<TicketStatus, Long> byStatus = aliceCounts.stream()
                .collect(Collectors.toMap(DashboardDtos.StatusCount::status, DashboardDtos.StatusCount::total));
        assertThat(byStatus.get(TicketStatus.COMPLETED)).isEqualTo(2);
        assertThat(byStatus.get(TicketStatus.IN_PROGRESS)).isEqualTo(1);
    }
}
