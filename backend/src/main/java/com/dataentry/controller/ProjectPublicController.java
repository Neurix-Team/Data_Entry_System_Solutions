package com.dataentry.controller;

import com.dataentry.dto.ProjectDtos;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.service.ProjectService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only project listing exposed to any authenticated user — used by the ticket
 * submission form so agents can attach their entries to a project. USER role sees
 * only projects they are a member of; ADMIN sees the full list. Writes stay under
 * the admin controller.
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectPublicController {

    private final ProjectService service;

    public ProjectPublicController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProjectDtos.ProjectResponse> list(@AuthenticationPrincipal User current) {
        if (current != null && current.getRole() == Role.ADMIN) {
            return service.list();
        }
        return service.listForMember(current == null ? null : current.getId());
    }
}
