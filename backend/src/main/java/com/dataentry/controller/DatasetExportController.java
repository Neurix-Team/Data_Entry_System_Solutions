package com.dataentry.controller;

import com.dataentry.dto.DatasetDtos;
import com.dataentry.service.DatasetService;
import org.springframework.web.bind.annotation.*;

/** Read-only token-authenticated endpoint for downstream AI/data projects. */
@RestController
@RequestMapping("/api/v1/export/dataset")
public class DatasetExportController {
    private final DatasetService service;

    public DatasetExportController(DatasetService service) { this.service = service; }

    @GetMapping
    public DatasetDtos.Page list(@RequestParam(required = false) Long cursor,
                                 @RequestParam(required = false) Integer size) {
        return service.list(cursor, size);
    }
}
