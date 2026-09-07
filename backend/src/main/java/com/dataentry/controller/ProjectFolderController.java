package com.dataentry.controller;

import com.dataentry.dto.ProjectFolderDtos;
import com.dataentry.model.User;
import com.dataentry.service.ProjectFolderService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/project-folders")
public class ProjectFolderController {

    private final ProjectFolderService service;

    public ProjectFolderController(ProjectFolderService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProjectFolderDtos.FolderSummary> list(@AuthenticationPrincipal User current) {
        return service.listFolders(current);
    }

    @GetMapping("/{projectId}")
    public ProjectFolderDtos.FolderDetail detail(
            @PathVariable Long projectId,
            @AuthenticationPrincipal User current) {
        return service.getFolder(projectId, current);
    }

    @PostMapping(path = "/{projectId}/quick-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectFolderDtos.QuickUploadResult quickUpload(
            @PathVariable Long projectId,
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(value = "titles", required = false) List<String> titles,
            @RequestParam(value = "departmentId", required = false) Long departmentId,
            @AuthenticationPrincipal User current) {
        return service.quickUpload(projectId, departmentId, current, files, titles);
    }
}
