package com.pyeonit.admin.community;

import static com.pyeonit.admin.community.AdminCommunityController.*;

import com.pyeonit.config.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class AdminCommunityService {
  private final JdbcTemplate db;
  private final Clock clock;

  AdminCommunityService(JdbcTemplate db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  private static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다");
  }

  private static ApiException invalid() {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요");
  }

  static Map<String, Object> map(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw missing();
    return rows.getFirst();
  }

  private static long id(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static Object time(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : ((Timestamp) row.get(key)).toInstant().toString();
  }

  private Map<String, Object> user(long userId) {
    return one("select id,nickname from users where id=?", userId);
  }

  private void lockUser(long userId) {
    one("select id from users where id=? for update", userId);
  }

  private void pageCheck(int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw invalid();
  }

  private Map<String, Object> page(List<?> content, int page, int size, long count) {
    return map(
        "content",
        content,
        "page",
        page,
        "size",
        size,
        "totalElements",
        count,
        "totalPages",
        (count + size - 1) / size);
  }

  Map<String, Object> listReports(ReportStatus status, TargetType targetType, int page, int size) {
    pageCheck(page, size);
    String where = " where status=?" + (targetType == null ? "" : " and target_type=?");
    List<Object> args = new ArrayList<>(List.of(status.name()));
    if (targetType != null) args.add(targetType.name());
    long count =
        db.queryForObject("select count(*) from reports" + where, Long.class, args.toArray());
    args.add(size);
    args.add((long) page * size);
    var content =
        db
            .queryForList(
                "select * from reports" + where + " order by created_at,id limit ? offset ?",
                args.toArray())
            .stream()
            .map(this::reportView)
            .toList();
    return page(content, page, size, count);
  }

  Map<String, Object> handleReport(long reportId, HandleRequest request, long adminId) {
    var row = one("select * from reports where id=? for update", reportId);
    if (!row.get("status").equals("PENDING"))
      throw new ApiException(HttpStatus.CONFLICT, "REPORT_ALREADY_HANDLED", "이미 처리한 신고입니다");
    db.update(
        "update reports set status=?,handled_by=?,handled_at=? where id=?",
        request.action() == Action.RESOLVE ? "RESOLVED" : "DISMISSED",
        adminId,
        now(),
        reportId);
    if (Boolean.TRUE.equals(request.hideTarget())) {
      String table =
          switch ((String) row.get("target_type")) {
            case "POST" -> "posts";
            case "COMMENT" -> "comments";
            default -> null;
          };
      if (table != null)
        db.update(
            "update "
                + table
                + " set status='HIDDEN',updated_at=? where id=? and status='PUBLISHED'",
            now(),
            row.get("target_id"));
    }
    return reportView(one("select * from reports where id=?", reportId));
  }

  private Map<String, Object> reportView(Map<String, Object> row) {
    long targetId = id(row, "target_id");
    String type = (String) row.get("target_type");
    Map<String, Object> target =
        map("author", null, "title", null, "excerpt", null, "status", null);
    if (type.equals("USER")) {
      var users = db.queryForList("select id,nickname from users where id=?", targetId);
      if (!users.isEmpty()) target.put("author", users.getFirst());
    } else {
      String table = type.equals("POST") ? "posts" : "comments";
      var targets = db.queryForList("select * from " + table + " where id=?", targetId);
      if (!targets.isEmpty()) {
        var item = targets.getFirst();
        target.put("author", user(id(item, "author_id")));
        target.put("status", item.get("status"));
        if (type.equals("POST")) target.put("title", item.get("title"));
        else {
          String body = (String) item.get("body");
          target.put(
              "excerpt",
              body.substring(
                  0,
                  body.offsetByCodePoints(
                      0, Math.min(200, body.codePointCount(0, body.length())))));
        }
      }
    }
    return map(
        "id",
        row.get("id"),
        "reporter",
        user(id(row, "reporter_id")),
        "targetType",
        type,
        "targetId",
        targetId,
        "target",
        target,
        "reason",
        row.get("reason"),
        "detail",
        row.get("detail"),
        "status",
        row.get("status"),
        "createdAt",
        time(row, "created_at"),
        "handledBy",
        row.get("handled_by") == null ? null : user(id(row, "handled_by")),
        "handledAt",
        time(row, "handled_at"));
  }

  Map<String, Object> listSanctions(Long userId, boolean activeOnly, int page, int size) {
    pageCheck(page, size);
    String where = " where 1=1";
    List<Object> args = new ArrayList<>();
    if (userId != null) {
      where += " and user_id=?";
      args.add(userId);
    }
    OffsetDateTime at = now();
    if (activeOnly) {
      where += " and type in ('SUSPEND','BAN') and starts_at<=? and (ends_at is null or ends_at>?)";
      args.add(at);
      args.add(at);
    }
    long count =
        db.queryForObject("select count(*) from sanctions" + where, Long.class, args.toArray());
    args.add(size);
    args.add((long) page * size);
    return page(
        db
            .queryForList(
                "select * from sanctions"
                    + where
                    + " order by created_at desc,id desc limit ? offset ?",
                args.toArray())
            .stream()
            .map(row -> sanctionView(row, at))
            .toList(),
        page,
        size,
        count);
  }

  Map<String, Object> createSanction(CreateRequest request, long adminId) {
    OffsetDateTime at = now();
    if (request.type() == SanctionType.SUSPEND
        ? request.endsAt() == null || !request.endsAt().isAfter(at)
        : request.endsAt() != null) throw invalid();
    lockUser(request.userId());
    long sanctionId =
        db.queryForObject(
            """
            insert into sanctions(user_id,type,reason,starts_at,ends_at,created_by,created_at)
            values (?,?,?,?,?,?,?) returning id
            """,
            Long.class,
            request.userId(),
            request.type().name(),
            request.reason(),
            at,
            request.endsAt(),
            adminId,
            at);
    if (request.type() != SanctionType.WARNING) syncUser(request.userId(), at);
    return sanctionView(one("select * from sanctions where id=?", sanctionId), at);
  }

  Map<String, Object> releaseSanction(long sanctionId) {
    var initial = one("select user_id from sanctions where id=?", sanctionId);
    lockUser(id(initial, "user_id"));
    var row = one("select * from sanctions where id=? for update", sanctionId);
    OffsetDateTime at = now();
    if (!active(row, at))
      throw new ApiException(HttpStatus.CONFLICT, "SANCTION_NOT_ACTIVE", "활성 제재가 아닙니다");
    db.update("update sanctions set ends_at=? where id=?", at, sanctionId);
    syncUser(id(row, "user_id"), at);
    return sanctionView(one("select * from sanctions where id=?", sanctionId), at);
  }

  private boolean active(Map<String, Object> row, OffsetDateTime at) {
    return !row.get("type").equals("WARNING")
        && !((Timestamp) row.get("starts_at")).toInstant().isAfter(at.toInstant())
        && (row.get("ends_at") == null
            || ((Timestamp) row.get("ends_at")).toInstant().isAfter(at.toInstant()));
  }

  private Map<String, Object> sanctionView(Map<String, Object> row, OffsetDateTime at) {
    return map(
        "id",
        row.get("id"),
        "user",
        user(id(row, "user_id")),
        "type",
        row.get("type"),
        "reason",
        row.get("reason"),
        "startsAt",
        time(row, "starts_at"),
        "endsAt",
        time(row, "ends_at"),
        "active",
        active(row, at),
        "createdBy",
        user(id(row, "created_by")),
        "createdAt",
        time(row, "created_at"));
  }

  private void syncUser(long userId, OffsetDateTime at) {
    db.update(
        """
        update users set status=case when exists (
          select 1 from sanctions where user_id=? and type in ('SUSPEND','BAN')
          and starts_at<=? and (ends_at is null or ends_at>?)
        ) then 'SUSPENDED' else 'ACTIVE' end where id=? and status<>'WITHDRAWN'
        """,
        userId,
        at,
        at,
        userId);
  }

  public void expireSanctions() {
    // Lock users in the same order for all workers, serializing with withdrawal/create/release.
    var ids =
        db.queryForList(
            """
            select id from users where status<>'WITHDRAWN' and
            (status='SUSPENDED' or exists(select 1 from sanctions s where s.user_id=users.id
            and s.type in ('SUSPEND','BAN'))) order by id for update
            """,
            Long.class);
    OffsetDateTime at = now();
    ids.forEach(userId -> syncUser(userId, at));
  }
}
