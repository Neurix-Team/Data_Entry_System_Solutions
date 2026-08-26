package com.dataentry.dto;

import java.util.List;

/**
 * DTOs for the "Project Folders" view — projects rendered as folders that group every
 * ticket branched off them. Distinct from {@link ProjectDtos} because folder cards need
 * per-status counts rather than the full member/department shape.
 */
public class ProjectFolderDtos {

    /** One card in the folder grid. Counts are scoped by the caller's role — for a USER
     *  they reflect only tickets the user submitted; for ADMIN/SUPER_ADMIN they cover
     *  every ticket in the project. */
    public record FolderSummary(
            Long projectId,
            String projectName,
            String projectNameEn,
            String projectNameAr,
            String subtitle,
            String subtitleEn,
            String subtitleAr,
            long total,
            long pending,
            long approved,
            String status
    ) {}

    /** A ticket-within-folder row. Reuses {@link TicketDtos.TicketResponse} verbatim so
     *  the frontend can show the full ticket detail (attachments, custom values, …). */
    public record FolderDetail(
            Long projectId,
            String projectName,
            String projectNameEn,
            String projectNameAr,
            List<TicketDtos.TicketResponse> tickets
    ) {}

    /** Response envelope for the multi-file quick-upload endpoint. Reports how many
     *  files landed as tickets vs. failed, so the client can show a partial-success
     *  message rather than a black-box "something broke". */
    public record QuickUploadResult(
            int created,
            int failed,
            List<TicketDtos.TicketResponse> tickets,
            List<QuickUploadFailure> failures
    ) {}

    public record QuickUploadFailure(
            String filename,
            String reason
    ) {}
}
