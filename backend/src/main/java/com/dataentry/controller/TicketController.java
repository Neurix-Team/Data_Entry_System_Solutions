package com.dataentry.controller;

import com.dataentry.dto.DashboardDtos;
import com.dataentry.dto.TicketDtos;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.service.DashboardService;
import com.dataentry.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TicketController {

    private final TicketService service;
    private final DashboardService dashboardService;

    public TicketController(TicketService service, DashboardService dashboardService) {
        this.service = service;
        this.dashboardService = dashboardService;
    }

    @PostMapping("/user/tickets")
    public ResponseEntity<TicketDtos.TicketResponse> submit(
            @AuthenticationPrincipal User current,
            @Valid @RequestBody TicketDtos.CreateTicketRequest req) {
        return ResponseEntity.ok(service.create(current, req));
    }

    @PostMapping("/user/tickets/bulk")
    public ResponseEntity<TicketDtos.BulkCreateResponse> submitBulk(
            @AuthenticationPrincipal User current,
            @Valid @RequestBody TicketDtos.BulkCreateRequest req) {
        return ResponseEntity.ok(service.createMany(current, req));
    }

    @GetMapping("/user/tickets")
    public TicketDtos.TicketPage listMine(
            @AuthenticationPrincipal User current,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listForUser(current, page, size);
    }

    @GetMapping("/admin/tickets")
    public TicketDtos.TicketPage listAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listAll(page, size);
    }

    @GetMapping("/tickets/{id}")
    public TicketDtos.TicketResponse getOne(
            @PathVariable Long id,
            @AuthenticationPrincipal User current) {
        return service.getOne(id, current, current.getRole() == Role.ADMIN);
    }

    @PatchMapping("/admin/tickets/{id}/status")
    public TicketDtos.TicketResponse updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody TicketDtos.UpdateStatusRequest req) {
        return service.updateStatus(id, req.status());
    }

    @DeleteMapping("/admin/tickets/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ----- Stats & reports (delegate to DashboardService) -----

    @GetMapping("/admin/stats")
    public DashboardDtos.AdminStats stats() {
        return dashboardService.adminStats();
    }

    @GetMapping("/admin/reports")
    public DashboardDtos.ReportData reports() {
        return dashboardService.report();
    }
}
