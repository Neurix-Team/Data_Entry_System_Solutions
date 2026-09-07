package com.dataentry.dto;

import java.util.List;

public class ProjectFolderDtos {

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

    public record FolderDetail(
            Long projectId,
            String projectName,
            String projectNameEn,
            String projectNameAr,
            List<TicketDtos.TicketResponse> tickets
    ) {}

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
