package com.dataentry.service;

import com.dataentry.dto.ChatMessagingDtos;
import com.dataentry.model.ChatGroup;
import com.dataentry.model.ChatGroupMember;
import com.dataentry.model.ChatGroupMessage;
import com.dataentry.model.Role;
import com.dataentry.model.Team;
import com.dataentry.model.User;
import com.dataentry.repository.ChatGroupAttachmentRepository;
import com.dataentry.repository.ChatGroupMemberRepository;
import com.dataentry.repository.ChatGroupMessageRepository;
import com.dataentry.repository.ChatGroupRepository;
import com.dataentry.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rules a group lives or dies by: who may manage it, that it can never be left with
 * nobody able to, and that the same reachability limits as 1:1 chat apply to who can be
 * added. The repositories are in-memory fakes so each test reads as the scenario it names.
 */
class ChatGroupServiceTest {

    private final List<ChatGroup> groupStore = new ArrayList<>();
    private final List<ChatGroupMember> memberStore = new ArrayList<>();
    private final List<ChatGroupMessage> messageStore = new ArrayList<>();
    private final List<User> userStore = new ArrayList<>();

    private ChatGroupService service;
    private Team team;
    private Team otherTeam;
    private User alice;      // team member — creates groups
    private User bob;        // team member
    private User carol;      // team member
    private User outsider;   // a different team
    private User superAdmin;

    @BeforeEach
    void setUp() {
        team = Team.builder().id(1L).slug("t1").name("Team One").build();
        otherTeam = Team.builder().id(2L).slug("t2").name("Team Two").build();
        alice = user(1L, "alice", Role.USER, team);
        bob = user(2L, "bob", Role.USER, team);
        carol = user(3L, "carol", Role.USER, team);
        outsider = user(4L, "outsider", Role.USER, otherTeam);
        superAdmin = user(5L, "root", Role.SUPER_ADMIN, null);

        ChatGroupRepository groups = mock(ChatGroupRepository.class);
        when(groups.save(any(ChatGroup.class))).thenAnswer(inv -> {
            ChatGroup g = inv.getArgument(0);
            if (g.getId() == null) {
                g.setId((long) (groupStore.size() + 1));
                groupStore.add(g);
            }
            return g;
        });

        ChatGroupMemberRepository members = mock(ChatGroupMemberRepository.class);
        when(members.save(any(ChatGroupMember.class))).thenAnswer(inv -> {
            ChatGroupMember m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId((long) (memberStore.size() + 1));
                memberStore.add(m);
            }
            return m;
        });
        when(members.findByGroupIdAndUserId(anyLong(), anyLong())).thenAnswer(inv -> memberStore.stream()
                .filter(m -> m.getGroup().getId().equals(inv.getArgument(0))
                        && m.getUser().getId().equals(inv.getArgument(1)))
                .findFirst());
        when(members.findAllByGroupIdOrderByRoleAscJoinedAtAsc(anyLong())).thenAnswer(inv -> memberStore.stream()
                .filter(m -> m.getGroup().getId().equals(inv.getArgument(0)))
                .sorted(java.util.Comparator.comparing((ChatGroupMember m) -> m.getRole().ordinal())
                        .thenComparing(ChatGroupMember::getJoinedAt))
                .toList());
        when(members.countByGroupIdAndRole(anyLong(), any())).thenAnswer(inv -> memberStore.stream()
                .filter(m -> m.getGroup().getId().equals(inv.getArgument(0)) && m.getRole() == inv.getArgument(1))
                .count());
        when(members.countByGroupId(anyLong())).thenAnswer(inv -> memberStore.stream()
                .filter(m -> m.getGroup().getId().equals(inv.getArgument(0))).count());
        org.mockito.Mockito.doAnswer(inv -> {
            memberStore.remove((ChatGroupMember) inv.getArgument(0));
            return null;
        }).when(members).delete(any(ChatGroupMember.class));

        ChatGroupMessageRepository messages = mock(ChatGroupMessageRepository.class);
        when(messages.save(any(ChatGroupMessage.class))).thenAnswer(inv -> {
            ChatGroupMessage m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId((long) (messageStore.size() + 1));
                messageStore.add(m);
            }
            return m;
        });
        when(messages.findRecent(anyLong(), any())).thenReturn(List.of());

        UserRepository users = mock(UserRepository.class);
        when(users.findAllById(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return userStore.stream().filter(u -> ids.contains(u.getId())).toList();
        });
        when(users.findById(anyLong())).thenAnswer(inv -> userStore.stream()
                .filter(u -> u.getId().equals(inv.getArgument(0))).findFirst());

        service = new ChatGroupService(groups, members, messages,
                mock(ChatGroupAttachmentRepository.class), users, mock(NotificationService.class),
                mock(ChatSocketSessionRegistry.class), mock(ChatFilesService.class),
                mock(AuditService.class));
    }

    private User user(long id, String name, Role role, Team t) {
        User u = User.builder().id(id).username(name).role(role).team(t).active(true).build();
        userStore.add(u);
        return u;
    }

    private ChatMessagingDtos.GroupItem createGroup(User by, Long... memberIds) {
        return service.create(by, new ChatMessagingDtos.CreateGroupRequest("Crew", List.of(memberIds)));
    }

    private ChatGroupMember membership(Long groupId, User u) {
        return memberStore.stream()
                .filter(m -> m.getGroup().getId().equals(groupId) && m.getUser().getId().equals(u.getId()))
                .findFirst().orElse(null);
    }

    // --- creating ---------------------------------------------------------------------

    @Test
    void theCreatorBecomesTheGroupAdmin() {
        ChatMessagingDtos.GroupItem g = createGroup(alice, 2L, 3L);

        assertThat(g.isAdmin()).isTrue();
        assertThat(membership(g.id(), alice).isAdmin()).isTrue();
        assertThat(membership(g.id(), bob).isAdmin()).isFalse();
    }

    @Test
    void aGroupNeedsAtLeastOneOtherMember() {
        assertThatThrownBy(() -> createGroup(alice, 1L)) // only themselves
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least one other");
    }

    @Test
    void cannotAddSomeoneFromAnotherTeamUnlessASuperAdminIsInvolved() {
        assertThatThrownBy(() -> createGroup(alice, 4L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("your team");

        // A super admin is reachable from any team, exactly as in 1:1 chat.
        assertThat(createGroup(alice, 5L).id()).isNotNull();
    }

    @Test
    void aSuperAdminCanBuildAGroupAcrossTeams() {
        assertThat(createGroup(superAdmin, 1L, 4L).memberCount()).isEqualTo(3);
    }

    // --- managing ----------------------------------------------------------------------

    @Test
    void onlyAnAdminMayAddMembers() {
        Long id = createGroup(alice, 2L).id();

        assertThatThrownBy(() -> service.addMembers(bob, id, new ChatMessagingDtos.AddMembersRequest(List.of(3L))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("admin");

        service.addMembers(alice, id, new ChatMessagingDtos.AddMembersRequest(List.of(3L)));
        assertThat(membership(id, carol)).isNotNull();
    }

    @Test
    void onlyAnAdminMayRemoveSomeoneElse() {
        Long id = createGroup(alice, 2L, 3L).id();

        assertThatThrownBy(() -> service.removeMember(bob, id, 3L))
                .isInstanceOf(ResponseStatusException.class);

        service.removeMember(alice, id, 3L);
        assertThat(membership(id, carol)).isNull();
    }

    @Test
    void anyMemberMayLeaveOnTheirOwn() {
        Long id = createGroup(alice, 2L, 3L).id();

        service.removeMember(bob, id, 2L);

        assertThat(membership(id, bob)).isNull();
    }

    @Test
    void theLastAdminLeavingPromotesSomeoneSoTheGroupIsNeverAdminless() {
        Long id = createGroup(alice, 2L, 3L).id();

        service.removeMember(alice, id, 1L); // alice is the only admin

        assertThat(membership(id, alice)).isNull();
        long admins = memberStore.stream()
                .filter(m -> m.getGroup().getId().equals(id) && m.isAdmin()).count();
        assertThat(admins).isEqualTo(1);
    }

    @Test
    void cannotDemoteTheLastAdmin() {
        Long id = createGroup(alice, 2L).id();

        assertThatThrownBy(() -> service.setAdmin(alice, id, 1L, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least one admin");
    }

    @Test
    void anAdminCanPromoteAndThenDemote() {
        Long id = createGroup(alice, 2L).id();

        service.setAdmin(alice, id, 2L, true);
        assertThat(membership(id, bob).isAdmin()).isTrue();

        service.setAdmin(alice, id, 2L, false);
        assertThat(membership(id, bob).isAdmin()).isFalse();
    }

    @Test
    void aNonMemberCanNeitherReadNorSend() {
        Long id = createGroup(alice, 2L).id();

        assertThatThrownBy(() -> service.history(carol, id, null, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.sendText(3L, id, "hi", null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aMemberCanSendAndItCountsAsHavingReadUpToThen() {
        Long id = createGroup(alice, 2L).id();

        service.sendText(2L, id, "hello team", null);

        assertThat(messageStore.stream().anyMatch(m -> "hello team".equals(m.getBody()))).isTrue();
        assertThat(membership(id, bob).getLastReadAt()).isNotNull();
    }

    @Test
    void anEmptyMessageIsRejected() {
        Long id = createGroup(alice, 2L).id();

        assertThatThrownBy(() -> service.sendText(2L, id, "   ", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("empty");
    }
}
