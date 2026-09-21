package com.dataentry.controller;

import com.dataentry.dto.AdminFeatureDtos;
import com.dataentry.model.User;
import com.dataentry.service.AdminFeatureService;
import com.dataentry.service.CsvImportService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Admin feature pack endpoints: announcements (C2), workload (C3), weekly report
 * (C4), data quality (C5) and CSV import (C1). All under /api/admin/** — the
 * SecurityConfig role gate covers everything here.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminFeatureController {

    private final AdminFeatureService features;
    private final CsvImportService imports;

    public AdminFeatureController(AdminFeatureService features, CsvImportService imports) {
        this.features = features;
        this.imports = imports;
    }

    // ── C2: announcements ─────────────────────────────────────────────────────

    @GetMapping("/announcements")
    public List<AdminFeatureDtos.AnnouncementResponse> listAnnouncements() {
        return features.listAnnouncements();
    }

    @PostMapping("/announcements")
    public AdminFeatureDtos.AnnouncementResponse announce(
            @Valid @RequestBody AdminFeatureDtos.CreateAnnouncementRequest req,
            @AuthenticationPrincipal User current) {
        return features.announce(current, req);
    }

    // ── C3: workload ───────────────────────────────────────────────────────────

    @GetMapping("/workload")
    public AdminFeatureDtos.WorkloadResponse workload() {
        return features.workload();
    }

    // ── C4: weekly report ──────────────────────────────────────────────────────

    @GetMapping("/reports/weekly")
    public AdminFeatureDtos.WeeklyReport weekly() {
        return features.weekly();
    }

    @PostMapping("/reports/weekly/dispatch")
    public AdminFeatureDtos.DispatchResult dispatchWeekly() {
        return features.dispatchWeekly();
    }

    // ── C5: data quality ───────────────────────────────────────────────────────

    @GetMapping("/quality")
    public AdminFeatureDtos.QualityResponse quality() {
        return features.quality();
    }

    // ── C1: CSV import ─────────────────────────────────────────────────────────

    @PostMapping(value = "/import/tickets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminFeatureDtos.ImportResult importTickets(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User current) throws IOException {
        return imports.importCsv(current, file);
    }
}