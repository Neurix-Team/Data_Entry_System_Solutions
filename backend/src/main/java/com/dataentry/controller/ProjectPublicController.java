package com.dataentry.controller;

import com.dataentry.dto.ProjectDtos;
import com.dataentry.service.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only project listing exposed to any authenticated user — used by the ticket
 * submission form so agents can attach their entries to a project. Writes stay under
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
    public List<ProjectDtos.ProjectResponse> list() {
        return service.list();
    }
}
