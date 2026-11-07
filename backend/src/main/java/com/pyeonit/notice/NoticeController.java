package com.pyeonit.notice;

import static com.pyeonit.university.UniversityData.*;

import com.pyeonit.university.UniversityData;
import java.time.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class NoticeController {
  private final UniversityData data;

  public NoticeController(UniversityData data) {
    this.data = data;
  }

  @GetMapping("/api/notices")
  public Map<String, Object> list(
      @RequestParam(required = false) Integer academicYear,
      @RequestParam(required = false) String region,
      @RequestParam(defaultValue = "false") boolean favoritesOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Authentication auth) {
    var docs = data.published(academicYear, region, member(auth, favoritesOnly), favoritesOnly);
    var result =
        docs.stream()
            .map(
                d -> {
                  long id = ((Number) d.get("id")).longValue();
                  var n =
                      map(
                          "documentId",
                          id,
                          "university",
                          data.summary(((Number) d.get("university_id")).longValue()),
                          "academicYear",
                          d.get("academic_year"),
                          "title",
                          d.get("title"),
                          "approvedAt",
                          d.get("reviewed_at"),
                          "pageUrl",
                          d.get("page_url"),
                          "fileUrl",
                          d.get("file_url"),
                          "applicationStartsAt",
                          null,
                          "applicationEndsAt",
                          null);
                  data.schedules(id).stream()
                      .filter(s -> s.get("type").equals("APPLICATION"))
                      .findFirst()
                      .ifPresent(
                          s -> {
                            n.put("applicationStartsAt", s.get("startsAt"));
                            n.put("applicationEndsAt", s.get("endsAt"));
                          });
                  return n;
                })
            .toList();
    return page(result, page, size);
  }

  @GetMapping("/api/calendar")
  public List<Map<String, Object>> calendar(
      @RequestParam OffsetDateTime from,
      @RequestParam OffsetDateTime to,
      @RequestParam(required = false) String region,
      @RequestParam(defaultValue = "false") boolean favoritesOnly,
      Authentication auth) {
    if (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(100)) > 0)
      throw invalid();
    List<Map<String, Object>> result = new ArrayList<>();
    for (var d : data.published(null, region, member(auth, favoritesOnly), favoritesOnly))
      for (var s : data.schedules(((Number) d.get("id")).longValue())) {
        Instant start = Instant.parse(s.get("startsAt").toString()),
            end = s.get("endsAt") == null ? start : Instant.parse(s.get("endsAt").toString());
        if (start.isBefore(to.toInstant()) && !end.isBefore(from.toInstant())) {
          s.put("documentId", d.get("id"));
          s.put("academicYear", d.get("academic_year"));
          s.put("university", data.summary(((Number) d.get("university_id")).longValue()));
          result.add(s);
        }
      }
    result.sort(Comparator.comparing(s -> Instant.parse(s.get("startsAt").toString())));
    return result;
  }
}
