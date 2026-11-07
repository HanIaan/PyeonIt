package com.pyeonit.community;

import static com.pyeonit.community.CommunityRequests.*;

import com.pyeonit.config.ApiException;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional
public class CommunityService {
  private final JdbcTemplate db;
  private final ImageStorage storage;
  private final long maxBytes;

  public CommunityService(
      JdbcTemplate db,
      ImageStorage storage,
      @Value("${pyeonit.community.max-image-bytes:5242880}") long maxBytes) {
    this.db = db;
    this.storage = storage;
    this.maxBytes = maxBytes;
  }

  static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다");
  }

  static ApiException invalid() {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요");
  }

  static ApiException reference() {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REFERENCE", "참조할 수 없는 대상입니다");
  }

  static long member(Authentication auth) {
    Long id = viewer(auth);
    if (id != null) return id;
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
      throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다");
    throw new ApiException(HttpStatus.FORBIDDEN, "SIGNUP_REQUIRED", "가입을 완료해 주세요");
  }

  static Long viewer(Authentication auth) {
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
      return null;
    try {
      return Long.parseLong(auth.getName());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static Map<String, Object> map(Object... entries) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) m.put((String) entries[i], entries[i + 1]);
    return m;
  }

  long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw missing();
    return rows.getFirst();
  }

  Object time(Map<String, Object> row, String key) {
    return ((java.sql.Timestamp) row.get(key)).toInstant().toString();
  }

  Map<String, Object> user(long id) {
    return one("select id,nickname from users where id=?", id);
  }

  public List<Map<String, Object>> boards() {
    return db.queryForList("select id,code,name from boards order by sort_order,id");
  }

  // 댓글·추천 변경은 먼저 글 행을 잠가 상태 확인과 카운터 갱신을 직렬화한다.
  Map<String, Object> post(long id, boolean lock) {
    return one(
        "select * from posts where id=? and status='PUBLISHED'" + (lock ? " for update" : ""), id);
  }

  void owner(Map<String, Object> row, Authentication auth, boolean admin) {
    long id = member(auth);
    if (number(row, "author_id") != id
        && !(admin
            && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))))
      throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "작성자만 처리할 수 있습니다");
  }

  void pageCheck(int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw invalid();
  }

  Map<String, Object> page(List<?> content, int page, int size, long total) {
    return map(
        "content",
        content,
        "page",
        page,
        "size",
        size,
        "totalElements",
        total,
        "totalPages",
        (total + size - 1) / size);
  }

  public Map<String, Object> posts(Integer boardId, Long universityId, int page, int size) {
    pageCheck(page, size);
    String where = " from posts p where p.status='PUBLISHED'";
    List<Object> args = new ArrayList<>();
    if (boardId != null) {
      where += " and p.board_id=?";
      args.add(boardId);
    }
    if (universityId != null) {
      where +=
          " and exists(select 1 from post_universities pu where pu.post_id=p.id and"
              + " university_id=?)";
      args.add(universityId);
    }
    long total = db.queryForObject("select count(*)" + where, Long.class, args.toArray());
    args.add(size);
    args.add((long) page * size);
    var content =
        db
            .queryForList(
                "select p.*" + where + " order by p.created_at desc,p.id desc limit ? offset ?",
                args.toArray())
            .stream()
            .map(p -> summary(p))
            .toList();
    return page(content, page, size, total);
  }

  Map<String, Object> summary(Map<String, Object> p) {
    long id = number(p, "id");
    return map(
        "id",
        id,
        "board",
        one("select id,code,name from boards where id=?", p.get("board_id")),
        "title",
        p.get("title"),
        "author",
        user(number(p, "author_id")),
        "universities",
        db.queryForList(
            "select u.id,u.name,u.campus,u.region from universities u join post_universities pu on"
                + " pu.university_id=u.id where pu.post_id=? order by u.id",
            id),
        "viewCount",
        p.get("view_count"),
        "likeCount",
        p.get("like_count"),
        "commentCount",
        p.get("comment_count"),
        "hasImages",
        db.queryForObject(
            "select exists(select 1 from post_images where post_id=?)", Boolean.class, id),
        "createdAt",
        time(p, "created_at"));
  }

  Map<String, Object> image(Map<String, Object> i) {
    return map(
        "id",
        i.get("id"),
        "url",
        storage.url((String) i.get("storage_key")),
        "contentType",
        i.get("content_type"),
        "sizeBytes",
        i.get("size_bytes"));
  }

  Map<String, Object> detail(Map<String, Object> p, Authentication auth) {
    long id = number(p, "id");
    Long me = viewer(auth);
    var result = summary(p);
    result.putAll(
        map(
            "body",
            p.get("body"),
            "images",
            db
                .queryForList(
                    "select * from post_images where post_id=? order by sort_order,id", id)
                .stream()
                .map(this::image)
                .toList(),
            "likedByMe",
            me != null
                && db.queryForObject(
                    "select exists(select 1 from post_likes where post_id=? and user_id=?)",
                    Boolean.class,
                    id,
                    me),
            "mine",
            Objects.equals(me, number(p, "author_id")),
            "updatedAt",
            time(p, "updated_at")));
    return result;
  }

  public Map<String, Object> get(long id, Authentication auth) {
    post(id, true);
    db.update("update posts set view_count=view_count+1 where id=?", id);
    return detail(post(id, false), auth);
  }

  public Map<String, Object> write(Long id, PostWrite r, Authentication auth) {
    long me = member(auth);
    if (id != null) owner(post(id, true), auth, false);
    if (new HashSet<>(r.universityIds()).size() != r.universityIds().size()
        || new HashSet<>(r.imageIds()).size() != r.imageIds().size()) throw invalid();
    if (!db.queryForObject(
        "select exists(select 1 from boards where id=?)", Boolean.class, r.boardId()))
      throw reference();
    for (long u : r.universityIds())
      if (!db.queryForObject(
          "select exists(select 1 from universities where id=?)", Boolean.class, u))
        throw reference();
    // 요청 순서와 무관하게 잠금 순서를 고정한다. 현재 글의 기존 이미지는 유지할 수 있다.
    for (long i : r.imageIds().stream().sorted().toList()) {
      var rows = db.queryForList("select * from post_images where id=? for update", i);
      if (rows.isEmpty()) throw reference();
      var row = rows.getFirst();
      if (number(row, "uploader_id") != me
          || (row.get("post_id") != null && !Objects.equals(number(row, "post_id"), id)))
        throw reference();
    }
    if (id == null)
      id =
          db.queryForObject(
              "insert into posts(board_id,author_id,title,body) values(?,?,?,?) returning id",
              Long.class,
              r.boardId(),
              me,
              r.title(),
              r.body());
    else
      db.update(
          "update posts set board_id=?,title=?,body=?,updated_at=now() where id=?",
          r.boardId(),
          r.title(),
          r.body(),
          id);
    db.update("delete from post_universities where post_id=?", id);
    for (long u : r.universityIds()) db.update("insert into post_universities values(?,?)", id, u);
    for (var old : db.queryForList("select * from post_images where post_id=?", id)) {
      if (!r.imageIds().contains(number(old, "id"))) {
        db.update("delete from post_images where id=?", old.get("id"));
        afterCommit(() -> storage.delete((String) old.get("storage_key")));
      }
    }
    for (int i = 0; i < r.imageIds().size(); i++)
      db.update(
          "update post_images set post_id=?,sort_order=? where id=?", id, i, r.imageIds().get(i));
    return detail(post(id, false), auth);
  }

  public void deletePost(long id, Authentication auth) {
    owner(post(id, true), auth, true);
    db.update("update posts set status='DELETED',updated_at=now() where id=?", id);
  }

  Map<String, Object> comment(long id) {
    return one("select * from comments where id=? and status='PUBLISHED'", id);
  }

  Map<String, Object> commentView(Map<String, Object> c, Authentication auth, List<?> replies) {
    boolean deleted = c.get("status").equals("DELETED");
    return map(
        "id",
        c.get("id"),
        "postId",
        c.get("post_id"),
        "parentId",
        c.get("parent_id"),
        "author",
        deleted ? null : user(number(c, "author_id")),
        "body",
        deleted ? null : c.get("body"),
        "status",
        c.get("status"),
        "mine",
        Objects.equals(viewer(auth), number(c, "author_id")),
        "createdAt",
        time(c, "created_at"),
        "updatedAt",
        time(c, "updated_at"),
        "replies",
        replies);
  }

  public Map<String, Object> comments(long postId, int page, int size, Authentication auth) {
    pageCheck(page, size);
    post(postId, false);
    String where =
        " from comments c where c.post_id=? and c.parent_id is null and (c.status='PUBLISHED' or"
            + " (c.status='DELETED' and exists(select 1 from comments r where r.parent_id=c.id and"
            + " r.status='PUBLISHED')))";
    long total = db.queryForObject("select count(*)" + where, Long.class, postId);
    var content =
        db
            .queryForList(
                "select c.*" + where + " order by c.created_at,c.id limit ? offset ?",
                postId,
                size,
                (long) page * size)
            .stream()
            .map(
                c ->
                    commentView(
                        c,
                        auth,
                        db
                            .queryForList(
                                "select * from comments where parent_id=? and status='PUBLISHED'"
                                    + " order by created_at,id",
                                c.get("id"))
                            .stream()
                            .map(r -> commentView(r, auth, List.of()))
                            .toList()))
            .toList();
    return page(content, page, size, total);
  }

  void countComments(long postId) {
    db.update(
        "update posts set comment_count=(select count(*) from comments where post_id=? and"
            + " status='PUBLISHED') where id=?",
        postId,
        postId);
  }

  public Map<String, Object> createComment(long postId, CommentCreate r, Authentication auth) {
    long me = member(auth);
    post(postId, true);
    if (r.parentId() != null) {
      var parents = db.queryForList("select * from comments where id=?", r.parentId());
      if (parents.isEmpty()
          || number(parents.getFirst(), "post_id") != postId
          || parents.getFirst().get("parent_id") != null)
        throw new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY, "REPLY_DEPTH_EXCEEDED", "같은 글의 최상위 댓글에만 답글을 쓸 수 있습니다");
    }
    long id =
        db.queryForObject(
            "insert into comments(post_id,parent_id,author_id,body) values(?,?,?,?) returning id",
            Long.class,
            postId,
            r.parentId(),
            me,
            r.body());
    countComments(postId);
    return commentView(comment(id), auth, List.of());
  }

  public Map<String, Object> updateComment(long id, String body, Authentication auth) {
    var c = comment(id);
    post(number(c, "post_id"), true);
    c = comment(id);
    owner(c, auth, false);
    db.update("update comments set body=?,updated_at=now() where id=?", body, id);
    return commentView(comment(id), auth, List.of());
  }

  public void deleteComment(long id, Authentication auth) {
    var c = comment(id);
    long postId = number(c, "post_id");
    post(postId, true);
    c = comment(id);
    owner(c, auth, true);
    db.update("update comments set status='DELETED',updated_at=now() where id=?", id);
    countComments(postId);
  }

  public Map<String, Object> like(long id, boolean liked, Authentication auth) {
    long me = member(auth);
    post(id, true);
    if (liked)
      db.update(
          "insert into post_likes(post_id,user_id) values(?,?) on conflict do nothing", id, me);
    else db.update("delete from post_likes where post_id=? and user_id=?", id, me);
    db.update(
        "update posts set like_count=(select count(*) from post_likes where post_id=?) where id=?",
        id,
        id);
    return map("liked", liked, "likeCount", post(id, false).get("like_count"));
  }

  public Map<String, Object> report(ReportCreate r, Authentication auth) {
    long me = member(auth);
    switch (r.targetType()) {
      case POST -> post(r.targetId(), false);
      case COMMENT -> {
        var c = comment(r.targetId());
        post(number(c, "post_id"), false);
      }
      case USER -> one("select id from users where id=? and status<>'WITHDRAWN'", r.targetId());
    }
    var result =
        db.queryForList(
            "insert into reports(reporter_id,target_type,target_id,reason,detail) values(?,?,?,?,?)"
                + " on conflict(reporter_id,target_type,target_id) do nothing returning *",
            me,
            r.targetType().name(),
            r.targetId(),
            r.reason().name(),
            r.detail());
    if (result.isEmpty())
      throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REPORTED", "이미 신고한 대상입니다");
    var row = result.getFirst();
    return map(
        "id", row.get("id"), "status", row.get("status"), "createdAt", time(row, "created_at"));
  }

  interface IoAction {
    void run() throws IOException;
  }

  void afterCommit(IoAction action) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          public void afterCommit() {
            try {
              action.run();
            } catch (IOException e) {
              org.slf4j.LoggerFactory.getLogger(CommunityService.class).warn("이미지 파일 삭제 실패", e);
            }
          }
        });
  }

  public Map<String, Object> upload(MultipartFile file, Authentication auth) throws IOException {
    long me = member(auth);
    if (file.getSize() > maxBytes)
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "이미지 크기 제한을 초과했습니다");
    byte[] bytes;
    try (var input = file.getInputStream()) {
      bytes = input.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
    }
    if (bytes.length > maxBytes)
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "이미지 크기 제한을 초과했습니다");
    String ext = detect(bytes);
    String type = "image/" + (ext.equals("jpg") ? "jpeg" : ext);
    if (!type.equals(file.getContentType())) throw invalid();
    String key = storage.store(bytes, ext);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED)
              try {
                storage.delete(key);
              } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger(CommunityService.class).warn("롤백 이미지 삭제 실패", e);
              }
          }
        });
    long id =
        db.queryForObject(
            "insert into post_images(uploader_id,storage_key,content_type,size_bytes)"
                + " values(?,?,?,?) returning id",
            Long.class,
            me,
            key,
            type,
            bytes.length);
    return image(one("select * from post_images where id=?", id));
  }

  static String detect(byte[] b) {
    if (b.length >= 3 && (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255)
      return "jpg";
    if (b.length >= 8
        && Arrays.equals(Arrays.copyOf(b, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      return "png";
    if (b.length >= 6
        && (new String(b, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF87a")
            || new String(b, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF89a")))
      return "gif";
    if (b.length >= 12
        && new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
        && new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))
      return "webp";
    throw invalid();
  }
}
