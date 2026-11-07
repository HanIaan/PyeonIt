package com.pyeonit.admin.notice;

import com.pyeonit.config.ApiException;
import com.pyeonit.university.UniversityData;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict v2 extraction validation. Any failure rolls back the entire approval. */
@Component
public class ExtractionMaterializer {
  private final UniversityData data;
  private final JsonMapper json = JsonMapper.builder().build();

  public ExtractionMaterializer(UniversityData data) {
    this.data = data;
  }

  static ApiException unusable() {
    return new ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY, "EXTRACTION_UNUSABLE", "추출 결과를 반영할 수 없습니다");
  }

  public Object parse(Object value) {
    return value == null ? null : json.readTree(value.toString());
  }

  private String text(JsonNode n, String key, int max) {
    var v = n.get(key);
    if (v == null || !v.isString() || v.asText().isBlank() || v.asText().length() > max)
      throw unusable();
    return v.asText();
  }

  private long department(long university, Long parent, String level, String name) {
    return data.jdbc()
        .queryForObject(
            "insert into departments(university_id,parent_id,level,name) values (?,?,?,?) on"
                + " conflict(university_id,level,name) do update set parent_id=excluded.parent_id"
                + " returning id",
            Long.class,
            university,
            parent,
            level,
            name);
  }

  public static String kind(Integer count, String raw) {
    if (count == null) return "RANGE";
    return count == 0 && (raw.isBlank() || Set.of("미선발", "-", "미모집", "선발하지 않음").contains(raw))
        ? "NOT_RECRUITING"
        : "EXACT";
  }

  public static String scheduleType(String label) {
    if (label.contains("원서") || label.contains("접수")) return "APPLICATION";
    if (label.contains("서류") && label.contains("제출")) return "DOCUMENT_SUBMISSION";
    if (label.contains("면접")) return "INTERVIEW";
    if (label.contains("발표")) return label.contains("1단계") ? "RESULT_FIRST_STAGE" : "RESULT_FINAL";
    if (label.contains("등록")) return "REGISTRATION";
    if (label.contains("시험") || label.contains("필기") || label.contains("고사")) return "EXAM";
    if (label.contains("추가")) return "ADDITIONAL";
    return "OTHER";
  }

  public static OffsetDateTime date(String value) {
    if (value.length() == 10)
      return LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.ofHours(9));
    try {
      return OffsetDateTime.parse(value);
    } catch (Exception ignored) {
      return LocalDateTime.parse(
              value,
              DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                  .withResolverStyle(ResolverStyle.STRICT))
          .atOffset(ZoneOffset.ofHours(9));
    }
  }

  public void apply(long document, long university, int year, Object result) {
    try {
      var root = json.readTree(result.toString());
      if (!root.isObject()
          || !root.path("학년도").isIntegralNumber()
          || !root.path("학년도").canConvertToInt()
          || root.path("학년도").asInt() != year) throw unusable();
      text(root, "학교", 100);
      var units = root.get("모집인원");
      var schedules = root.get("일정");
      if (units == null
          || !units.isArray()
          || schedules == null
          || !schedules.isArray()
          || !root.path("총계").isArray()
          || (units.isEmpty() && schedules.isEmpty())) throw unusable();
      Set<String> seen = new HashSet<>();
      data.jdbc().update("delete from quotas where document_id=?", document);
      data.jdbc().update("delete from schedules where document_id=?", document);
      for (var unit : units) {
        String level = text(unit, "단위", 10), name = text(unit, "모집단위", 100);
        if (!Set.of("학과", "단과대학").contains(level)) throw unusable();
        var group = unit.get("계열");
        if (group == null || !group.isString() || group.asText().length() > 100) throw unusable();
        Long parent =
            level.equals("학과") && !group.asText().isBlank()
                ? department(university, null, "COLLEGE", group.asText())
                : null;
        long department =
            department(university, parent, level.equals("학과") ? "MAJOR" : "COLLEGE", name);
        var quotas = unit.get("인원");
        if (quotas == null || !quotas.isArray() || quotas.isEmpty()) throw unusable();
        for (var q : quotas) {
          String track = text(q, "전형", 100);
          var rawNode = q.get("원문");
          if (rawNode == null || !rawNode.isString() || rawNode.asText().length() > 50)
            throw unusable();
          String raw = rawNode.asText();
          var number = q.get("인원");
          if (number == null
              || (!number.isNull()
                  && (!number.isIntegralNumber()
                      || !number.canConvertToInt()
                      || number.asInt() < 0))) throw unusable();
          Integer count = number.isNull() ? null : number.asInt();
          if (!seen.add(department + ":" + track)) throw unusable();
          var mappings =
              data.rows(
                  "select track_id from track_aliases where raw_name=? and (university_id=? or"
                      + " university_id is null) order by university_id nulls last limit 1",
                  track,
                  university);
          Object trackId = mappings.isEmpty() ? null : mappings.getFirst().get("track_id");
          data.jdbc()
              .update(
                  "insert into"
                      + " quotas(document_id,department_id,track_id,raw_track_name,kind,count,raw_text)"
                      + " values (?,?,?,?,?,?,?)",
                  document,
                  department,
                  trackId,
                  track,
                  kind(count, raw),
                  count,
                  raw);
        }
      }
      for (var s : schedules) {
        String label = text(s, "항목", 300), start = text(s, "시작", 100);
        var begins = date(start);
        var end = s.get("종료");
        OffsetDateTime ends =
            end == null || end.isNull() || end.asText().isBlank() ? null : date(end.asText());
        if (ends != null && ends.isBefore(begins)) throw unusable();
        var note = s.get("비고");
        if (note != null && !note.isNull() && !note.isString()) throw unusable();
        data.jdbc()
            .update(
                "insert into schedules(document_id,type,raw_label,starts_at,ends_at,all_day,note)"
                    + " values (?,?,?,?,?,?,?)",
                document,
                scheduleType(label),
                label,
                begins,
                ends,
                start.length() == 10,
                note == null || note.isNull() ? null : note.asText());
      }
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw unusable();
    }
  }
}
