package com.dataentry.controller;

import com.dataentry.dto.SearchDtos;
import com.dataentry.model.User;
import com.dataentry.service.SearchService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    @GetMapping
    public SearchDtos.SearchResponse search(@RequestParam("q") String q,
                                            @AuthenticationPrincipal User current) {
        return service.search(current, q);
    }
}