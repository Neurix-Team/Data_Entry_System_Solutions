package com.dataentry.controller;

import com.dataentry.dto.DatasetDtos;
import com.dataentry.service.DatasetService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/super/dataset")
public class DatasetController {
    private final DatasetService service;

    public DatasetController(DatasetService service) { this.service = service; }

    @GetMapping
    public DatasetDtos.Page list(@RequestParam(required = false) Long cursor,
                                 @RequestParam(required = false) Integer size) {
        return service.list(cursor, size);
    }

    @PostMapping("/publish")
    public DatasetDtos.PublishResult publish() { return service.publish(); }
}
