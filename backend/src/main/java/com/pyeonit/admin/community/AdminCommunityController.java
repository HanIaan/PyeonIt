package com.pyeonit.admin.community;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
class AdminCommunityController {
  enum ReportStatus {
    PENDING,
    RESOLVED,
    DISMISSED
  }

  enum TargetType {
    POST,
    COMMENT,
    USER
  }

  enum Action {
    RESOLVE,
    DISMISS
  }

  enum SanctionType {
    WARNING,
    SUSPEND,
    BAN
  }

  record HandleRequest(@NotNull Action action, Boolean hideTarget) {}

  record CreateRequest(
      @NotNull Long userId,
      @NotNull SanctionType type,
      @NotBlank @Size(max = 500) String reason,
      OffsetDateTime endsAt) {}

  private final AdminCommunityService service;

  AdminCommunityController(AdminCommunityService service) {
    this.service = service;
  }

  @GetMapping("/reports")
  Map<String, Object> listReports(
      @RequestParam(defaultValue = "PENDING") ReportStatus status,
      @RequestParam(required = false) TargetType targetType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.listReports(status, targetType, page, size);
  }

  @PostMapping("/reports/{reportId}/handle")
  Map<String, Object> handleReport(
      @PathVariable long reportId, @Valid @RequestBody HandleRequest body, Authentication auth) {
    return service.handleReport(reportId, body, Long.parseLong(auth.getName()));
  }

  @GetMapping("/sanctions")
  Map<String, Object> listSanctions(
      @RequestParam(required = false) Long userId,
      @RequestParam(defaultValue = "false") boolean activeOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.listSanctions(userId, activeOnly, page, size);
  }

  @PostMapping("/sanctions")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> createSanction(@Valid @RequestBody CreateRequest body, Authentication auth) {
    return service.createSanction(body, Long.parseLong(auth.getName()));
  }

  @PostMapping("/sanctions/{sanctionId}/release")
  Map<String, Object> releaseSanction(@PathVariable long sanctionId) {
    return service.releaseSanction(sanctionId);
  }
}
