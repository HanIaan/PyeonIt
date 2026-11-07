package com.pyeonit.university;

import com.pyeonit.config.ApiException;
import java.sql.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Repository;

/** B2 read projections; cross-domain references remain scalar IDs. */
@Repository
public class UniversityData {
  private final JdbcTemplate jdbc;

  public UniversityData(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  public static ApiException invalid() {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요");
  }

  public static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 대상을 찾을 수 없습니다");
  }

  public static Long member(Authentication a, boolean required) {
    if (a == null || a instanceof AnonymousAuthenticationToken || !a.isAuthenticated()) {
      if (required) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다");
      return null;
    }
    try {
      return Long.parseLong(a.getName());
    } catch (NumberFormatException e) {
      if (required) throw new ApiException(HttpStatus.FORBIDDEN, "SIGNUP_REQUIRED", "가입을 완료해 주세요");
      return null;
    }
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        (rs, n) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          var md = rs.getMetaData();
          for (int i = 1; i <= md.getColumnCount(); i++) {
            Object v = rs.getObject(i);
            if (v instanceof Timestamp t) v = t.toInstant().toString();
            if (v instanceof java.time.OffsetDateTime t) v = t.toString();
            m.put(md.getColumnLabel(i), v);
          }
          return m;
        },
        args);
  }

  public Map<String, Object> one(String sql, Object... args) {
    var r = rows(sql, args);
    if (r.isEmpty()) throw missing();
    return r.getFirst();
  }

  public static Map<String, Object> map(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
    return m;
  }

  public static void region(String r) {
    if (r != null && !Set.of("SEOUL", "GYEONGGI", "INCHEON").contains(r)) throw invalid();
  }

  public static void year(Integer y) {
    if (y != null && (y < 2000 || y > 2100)) throw invalid();
  }

  public static Map<String, Object> page(List<Map<String, Object>> items, int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw invalid();
    long start = (long) page * size;
    return map(
        "content",
        items.subList(
            (int) Math.min(start, items.size()), (int) Math.min(start + size, items.size())),
        "page",
        page,
        "size",
        size,
        "totalElements",
        items.size(),
        "totalPages",
        (items.size() + size - 1) / size);
  }

  public Map<String, Object> university(long id, Long user) {
    var m =
        one(
            "select id,name,campus,region,admission_url as \"admissionUrl\" from universities where"
                + " id=?",
            id);
    m.put(
        "favorite",
        user != null
            && Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "select exists(select 1 from favorite_universities where user_id=? and"
                        + " university_id=?)",
                    Boolean.class,
                    user,
                    id)));
    return m;
  }

  public Map<String, Object> summary(long id) {
    return one("select id,name,campus,region from universities where id=?", id);
  }

  // Reviewed time defines the latest approval; ID resolves equal timestamps deterministically.
  public static final String PUBLISHED =
      "select distinct on (university_id,academic_year) * from admission_documents where"
          + " status='APPROVED' order by university_id,academic_year,reviewed_at desc nulls last,id"
          + " desc";

  public List<Map<String, Object>> published(
      Integer year, String region, Long user, boolean favorites) {
    region(region);
    year(year);
    return rows(
        "select d.* from ("
            + PUBLISHED
            + ") d join universities u on u.id=d.university_id where (?::int is null or"
            + " d.academic_year=?) and (?::text is null or u.region=?) and (not ? or exists(select"
            + " 1 from favorite_universities f where f.university_id=u.id and f.user_id=?)) order"
            + " by d.reviewed_at desc nulls last,d.id desc",
        year,
        year,
        region,
        region,
        favorites,
        user);
  }

  public List<Map<String, Object>> schedules(long document) {
    return rows(
        "select id,type,raw_label as \"rawLabel\",starts_at as \"startsAt\",ends_at as"
            + " \"endsAt\",all_day as \"allDay\",note from schedules where document_id=? order by"
            + " starts_at,id",
        document);
  }

  public Map<String, Object> guide(long id) {
    var g =
        one(
            "select id as \"documentId\",academic_year as \"academicYear\",title,page_url as"
                + " \"pageUrl\",file_url as \"fileUrl\",reviewed_at as \"approvedAt\" from"
                + " admission_documents where id=? and status='APPROVED'",
            id);
    var nodes =
        rows(
            "select id,parent_id,level,name,previous_department_id as \"previousDepartmentId\" from"
                + " departments where id in (select department_id from quotas where document_id=?)"
                + " or id in (select parent_id from departments where id in (select department_id"
                + " from quotas where document_id=?)) order by id",
            id,
            id);
    Map<Object, Map<String, Object>> index = new LinkedHashMap<>();
    Map<Object, List<Map<String, Object>>> children = new HashMap<>();
    for (var n : nodes) {
      var quotas =
          rows(
              "select q.id,q.raw_track_name as \"rawTrackName\",q.kind,q.count,q.raw_text as"
                  + " \"rawText\",t.id as track_id,t.code,t.name from quotas q left join tracks t"
                  + " on t.id=q.track_id where q.document_id=? and q.department_id=? order by q.id",
              id,
              n.get("id"));
      n.put("quotas", quotas);
      for (var q : quotas) {
        Object tid = q.remove("track_id"), code = q.remove("code"), name = q.remove("name");
        q.put("track", tid == null ? null : map("id", tid, "code", code, "name", name));
      }
      List<Map<String, Object>> childNodes = new ArrayList<>();
      children.put(n.get("id"), childNodes);
      n.put("children", childNodes);
      index.put(n.get("id"), n);
    }
    List<Map<String, Object>> roots = new ArrayList<>();
    for (var n : nodes) {
      var parent = index.get(n.remove("parent_id"));
      if (parent == null) roots.add(n);
      else children.get(parent.get("id")).add(n);
    }
    g.put("departments", roots);
    g.put("schedules", schedules(id));
    return g;
  }
}
