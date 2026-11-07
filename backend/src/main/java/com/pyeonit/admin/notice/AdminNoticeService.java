package com.pyeonit.admin.notice;

import static com.pyeonit.university.UniversityData.*;

import com.pyeonit.config.ApiException;
import com.pyeonit.university.UniversityData;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminNoticeService {
  private final UniversityData data;
  private final ExtractionMaterializer extraction;

  public AdminNoticeService(UniversityData data, ExtractionMaterializer extraction) {
    this.data = data;
    this.extraction = extraction;
  }

  Map<String, Object> document(long id) {
    var d =
        data.one(
            "select id,university_id,academic_year as \"academicYear\",title,status,page_url as"
                + " \"pageUrl\",file_url as \"fileUrl\",detected_at as"
                + " \"detectedAt\",reviewed_by,reviewed_at as \"reviewedAt\" from"
                + " admission_documents where id=?",
            id);
    d.put("university", data.summary(((Number) d.remove("university_id")).longValue()));
    Object reviewer = d.remove("reviewed_by");
    d.put(
        "reviewedBy",
        reviewer == null ? null : data.one("select id,nickname from users where id=?", reviewer));
    var runs =
        data.rows(
            "select id,model,result,validation,error,created_at as \"createdAt\" from"
                + " extraction_runs where document_id=? order by created_at desc,id desc limit 1",
            id);
    if (!runs.isEmpty()) {
      var r = runs.getFirst();
      r.put("result", extraction.parse(r.get("result")));
      r.put("validation", extraction.parse(r.get("validation")));
    }
    d.put("latestExtraction", runs.isEmpty() ? null : runs.getFirst());
    return d;
  }

  public Map<String, Object> list(List<String> statuses, Long university, int page, int size) {
    if (statuses == null) statuses = List.of("EXTRACTED", "NEEDS_REVIEW");
    for (String s : statuses)
      if (!Set.of("DETECTED", "EXTRACTED", "NEEDS_REVIEW", "APPROVED", "REJECTED", "FAILED")
          .contains(s)) throw invalid();
    final var requested = statuses;
    var docs =
        data
            .rows(
                "select id,status from admission_documents where (?::bigint is null or"
                    + " university_id=?) order by detected_at,id",
                university,
                university)
            .stream()
            .filter(d -> requested.contains(d.get("status")))
            .map(d -> document(((Number) d.get("id")).longValue()))
            .toList();
    return page(docs, page, size);
  }

  @Transactional
  public Map<String, Object> review(long id, Long run, long reviewer, boolean approve) {
    var d = data.one("select * from admission_documents where id=? for update", id);
    if (Set.of("APPROVED", "REJECTED").contains(d.get("status")))
      throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_REVIEWED", "이미 검토한 요강입니다");
    if (approve) {
      var runs =
          data.rows(
              "select result,error from extraction_runs where id=? and document_id=?", run, id);
      if (runs.isEmpty()
          || runs.getFirst().get("error") != null
          || runs.getFirst().get("result") == null) throw ExtractionMaterializer.unusable();
      extraction.apply(
          id,
          ((Number) d.get("university_id")).longValue(),
          ((Number) d.get("academic_year")).intValue(),
          runs.getFirst().get("result"));
    }
    data.jdbc()
        .update(
            "update admission_documents set status=?,reviewed_by=?,reviewed_at=now() where id=?",
            approve ? "APPROVED" : "REJECTED",
            reviewer,
            id);
    return document(id);
  }
}
