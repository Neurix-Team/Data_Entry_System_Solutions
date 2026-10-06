package com.dataentry.controller;

import com.dataentry.dto.CleanedFileDtos;
import com.dataentry.model.CleanedFile.Status;
import com.dataentry.service.CleanedFileService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/super/cleaned-files")
public class CleanedFileController {
    private final CleanedFileService service;
    public CleanedFileController(CleanedFileService service) { this.service = service; }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<java.util.Map<String, String>> invalidInput(Exception exception) {
        return ResponseEntity.badRequest().body(java.util.Map.of("message", "Invalid or missing file metadata, filter, or status"));
    }
    @GetMapping("/options")
    public CleanedFileDtos.Options options() { return service.options(); }
    @GetMapping
    public CleanedFileDtos.Page list(@RequestParam(required = false) Long projectId, @RequestParam(required = false) Long departmentId,
                                    @RequestParam(required = false) Status status, @RequestParam(required = false) LocalDate from,
                                    @RequestParam(required = false) LocalDate to, @RequestParam(required = false) String search,
                                    @RequestParam(defaultValue = "0") int page) {
        return service.list(projectId, departmentId, status, from, to, search, page);
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CleanedFileDtos.Row> upload(@RequestParam Long projectId, @RequestParam Long departmentId,
                                                    @Valid @RequestPart CleanedFileDtos.Metadata metadata, @RequestPart MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(projectId, departmentId, metadata, file));
    }
    @PutMapping("/{id}")
    public CleanedFileDtos.Row update(@PathVariable Long id, @Valid @RequestBody CleanedFileDtos.Update update) { return service.update(id, update); }
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        var file = service.download(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(file.resource());
    }
}
