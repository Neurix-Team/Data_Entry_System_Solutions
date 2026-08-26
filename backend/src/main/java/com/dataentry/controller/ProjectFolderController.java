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

/**
 * Read endpoints for the "Project Folders" UI. Mounted under the shared /api/ prefix
 * (rather than /api/admin or /api/user) so both USER and ADMIN can hit it — role-based
 * scoping happens inside the service.
 */
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

    /**
     * Multi-file upload into one folder. Each file becomes its own REVIEW-status ticket
     * with the given title (or a filename-derived one if the title is blank). Reports
     * partial success — the response tells the client both what landed and what failed.
     *
     * <p>Body: {@code multipart/form-data} with:
     * <ul>
     *   <li>{@code files}   — 1..N binary parts (one per file)</li>
     *   <li>{@code titles}  — 0..N text parts, positionally paired with files. Blank or
     *                         missing entries fall back to a filename-derived title.</li>
     * </ul>
     */
    @PostMapping(path = "/{projectId}/quick-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectFolderDtos.QuickUploadResult quickUpload(
            @PathVariable Long projectId,
            @RequestPart("files") List<MultipartFile> files,
            // Titles bind as multi-valued form fields (not multipart parts), so a plain string
            // form input works regardless of Content-Type — using @RequestPart on List<String>
            // rejects text/plain parts with 415 "Content-Type is not supported".
            @RequestParam(value = "titles", required = false) List<String> titles,
            @AuthenticationPrincipal User current) {
        return service.quickUpload(projectId, current, files, titles);
    }
}
