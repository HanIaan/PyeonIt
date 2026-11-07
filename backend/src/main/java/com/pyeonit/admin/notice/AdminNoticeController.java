package com.pyeonit.admin.notice;

import static com.pyeonit.university.UniversityData.member;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/admission-documents")
public class AdminNoticeController {
  private final AdminNoticeService service;

  public AdminNoticeController(AdminNoticeService service) {
    this.service = service;
  }

  public record ApproveRequest(@NotNull Long extractionRunId) {}

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) Long universityId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(status, universityId, page, size);
  }

  @PostMapping("/{id}/approve")
  public Map<String, Object> approve(
      @PathVariable long id, @Valid @RequestBody ApproveRequest request, Authentication auth) {
    return service.review(id, request.extractionRunId(), member(auth, true), true);
  }

  @PostMapping("/{id}/reject")
  public Map<String, Object> reject(@PathVariable long id, Authentication auth) {
    return service.review(id, null, member(auth, true), false);
  }
}
