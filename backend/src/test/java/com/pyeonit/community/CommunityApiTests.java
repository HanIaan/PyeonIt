package com.pyeonit.community;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.pyeonit.TestcontainersConfiguration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "pyeonit.community.max-image-bytes=1024")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CommunityApiTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  long me, other, suspended, school, school2;
  int board, board2;

  @BeforeEach
  void setup() {
    me = person("B3작성자", "ACTIVE");
    other = person("B3다른사람", "ACTIVE");
    suspended = person("B3정지회원", "SUSPENDED");
    school =
        db.queryForObject(
            "insert into universities(name,region) values('B3서강','SEOUL') returning id",
            Long.class);
    school2 =
        db.queryForObject(
            "insert into universities(name,region) values('B3성균관','SEOUL') returning id",
            Long.class);
    board = db.queryForObject("select id from boards where code='QUESTION'", Integer.class);
    board2 = db.queryForObject("select id from boards where code='FREE'", Integer.class);
  }

  long person(String nickname, String status) {
    return db.queryForObject(
        "insert into users(nickname,status) values(?,?) returning id",
        Long.class,
        nickname,
        status);
  }

  AbstractMockHttpServletRequestBuilder<?> as(
      AbstractMockHttpServletRequestBuilder<?> r, long user) {
    return r.with(user(Long.toString(user)).roles("USER")).with(csrf());
  }

  String write(String title, String universities, String images) {
    return "{\"boardId\":"
        + board
        + ",\"title\":\""
        + title
        + "\",\"body\":\"본문\",\"universityIds\":"
        + universities
        + ",\"imageIds\":"
        + images
        + "}";
  }

  String write() {
    return write("제목", "[" + school + "," + school2 + "]", "[]");
  }

  AbstractMockHttpServletRequestBuilder<?> json(
      AbstractMockHttpServletRequestBuilder<?> r, String body) {
    return r.contentType("application/json").content(body);
  }

  long id(MvcResult result) throws Exception {
    return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
  }

  long newPost() throws Exception {
    return id(
        mvc.perform(as(json(post("/api/posts"), write()), me))
            .andExpect(status().isCreated())
            .andReturn());
  }

  long comment(long post, Long parent) throws Exception {
    return id(
        mvc.perform(
                as(
                    json(
                        post("/api/posts/" + post + "/comments"),
                        "{\"body\":\"댓글\",\"parentId\":" + parent + "}"),
                    me))
            .andExpect(status().isCreated())
            .andReturn());
  }

  void error(AbstractMockHttpServletRequestBuilder<?> request, int status, String code)
      throws Exception {
    mvc.perform(request).andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
  }

  @Test
  void postCrudFilteringPaginationAndVisibility() throws Exception {
    mvc.perform(get("/api/boards"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("QUESTION"));
    long p = newPost();
    mvc.perform(get("/api/posts").param("universityId", "" + school).param("boardId", "" + board))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].universities.length()").value(2));
    mvc.perform(get("/api/posts").param("boardId", "" + board2))
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/api/posts").param("page", "1").param("size", "1"))
        .andExpect(jsonPath("$.content").isEmpty());
    error(get("/api/posts").param("size", "101"), 400, "VALIDATION_FAILED");
    error(get("/api/posts").param("page", "-1"), 400, "VALIDATION_FAILED");
    mvc.perform(get("/api/posts/" + p))
        .andExpect(jsonPath("$.viewCount").value(1))
        .andExpect(jsonPath("$.mine").value(false))
        .andExpect(jsonPath("$.likedByMe").value(false));
    mvc.perform(as(get("/api/posts/" + p), me))
        .andExpect(jsonPath("$.mine").value(true))
        .andExpect(jsonPath("$.viewCount").value(2));
    error(as(json(put("/api/posts/" + p), write()), other), 403, "FORBIDDEN");
    mvc.perform(as(json(put("/api/posts/" + p), write("수정", "[]", "[]")), me))
        .andExpect(jsonPath("$.title").value("수정"))
        .andExpect(jsonPath("$.universities").isEmpty());
    error(as(delete("/api/posts/" + p), other), 403, "FORBIDDEN");
    mvc.perform(as(delete("/api/posts/" + p), me)).andExpect(status().isNoContent());
    assertThat(db.queryForObject("select status from posts where id=?", String.class, p))
        .isEqualTo("DELETED");
    error(get("/api/posts/" + p), 404, "NOT_FOUND");
    error(get("/api/posts/" + p + "/comments"), 404, "NOT_FOUND");
    mvc.perform(get("/api/posts")).andExpect(jsonPath("$.totalElements").value(0));
    long hidden = newPost();
    db.update("update posts set status='HIDDEN' where id=?", hidden);
    error(get("/api/posts/" + hidden), 404, "NOT_FOUND");
    mvc.perform(get("/api/posts")).andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void referencesAndValidation() throws Exception {
    error(
        as(json(post("/api/posts"), write("제목", "[999999999]", "[]")), me),
        422,
        "INVALID_REFERENCE");
    error(
        as(
            json(post("/api/posts"), write().replace("\"boardId\":" + board, "\"boardId\":9999")),
            me),
        422,
        "INVALID_REFERENCE");
    error(
        as(json(post("/api/posts"), write("제목", "[" + school + "," + school + "]", "[]")), me),
        400,
        "VALIDATION_FAILED");
    error(as(json(post("/api/posts"), write("", "[]", "[]")), me), 400, "VALIDATION_FAILED");
    assertThat(db.queryForObject("select count(*) from posts", Integer.class)).isZero();
  }

  @Test
  void repliesDepthSoftDeletionAndCounts() throws Exception {
    long p = newPost(), root = comment(p, null), reply = comment(p, root);
    assertThat(db.queryForObject("select comment_count from posts where id=?", Integer.class, p))
        .isEqualTo(2);
    error(
        as(
            json(
                post("/api/posts/" + p + "/comments"),
                "{\"body\":\"깊은 답글\",\"parentId\":" + reply + "}"),
            me),
        422,
        "REPLY_DEPTH_EXCEEDED");
    long p2 = newPost();
    error(
        as(
            json(
                post("/api/posts/" + p2 + "/comments"),
                "{\"body\":\"다른 글\",\"parentId\":" + root + "}"),
            me),
        422,
        "REPLY_DEPTH_EXCEEDED");
    error(as(json(patch("/api/comments/" + root), "{\"body\":\"수정\"}"), other), 403, "FORBIDDEN");
    error(as(delete("/api/comments/" + root), other), 403, "FORBIDDEN");
    mvc.perform(as(json(patch("/api/comments/" + reply), "{\"body\":\"답글 수정\"}"), me))
        .andExpect(jsonPath("$.body").value("답글 수정"));
    mvc.perform(as(delete("/api/comments/" + root), me)).andExpect(status().isNoContent());
    mvc.perform(get("/api/posts/" + p + "/comments").param("size", "1"))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].body").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.content[0].author").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.content[0].status").value("DELETED"))
        .andExpect(jsonPath("$.content[0].replies[0].body").value("답글 수정"));
    assertThat(db.queryForObject("select comment_count from posts where id=?", Integer.class, p))
        .isEqualTo(1);
    mvc.perform(as(delete("/api/comments/" + reply), me)).andExpect(status().isNoContent());
    mvc.perform(get("/api/posts/" + p + "/comments"))
        .andExpect(jsonPath("$.totalElements").value(0));
    assertThat(db.queryForObject("select comment_count from posts where id=?", Integer.class, p))
        .isZero();
    long c = comment(p, null);
    db.update("update comments set status='HIDDEN' where id=?", c);
    mvc.perform(get("/api/posts/" + p + "/comments")).andExpect(jsonPath("$.content").isEmpty());
  }

  @Test
  void adminCanDeleteButCannotEdit() throws Exception {
    long p = newPost(), c = comment(p, null);
    error(
        json(put("/api/posts/" + p), write()).with(user("" + other).roles("ADMIN")).with(csrf()),
        403,
        "FORBIDDEN");
    error(
        json(patch("/api/comments/" + c), "{\"body\":\"수정\"}")
            .with(user("" + other).roles("ADMIN"))
            .with(csrf()),
        403,
        "FORBIDDEN");
    mvc.perform(delete("/api/comments/" + c).with(user("" + other).roles("ADMIN")).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/api/posts/" + p).with(user("" + other).roles("ADMIN")).with(csrf()))
        .andExpect(status().isNoContent());
  }

  @Test
  void likesAreIdempotent() throws Exception {
    long p = newPost();
    for (int i = 0; i < 2; i++)
      mvc.perform(as(put("/api/posts/" + p + "/like"), me))
          .andExpect(jsonPath("$.liked").value(true))
          .andExpect(jsonPath("$.likeCount").value(1));
    mvc.perform(as(get("/api/posts/" + p), me)).andExpect(jsonPath("$.likedByMe").value(true));
    mvc.perform(as(put("/api/posts/" + p + "/like"), other))
        .andExpect(jsonPath("$.likeCount").value(2));
    for (int i = 0; i < 2; i++)
      mvc.perform(as(delete("/api/posts/" + p + "/like"), me))
          .andExpect(jsonPath("$.liked").value(false))
          .andExpect(jsonPath("$.likeCount").value(1));
    assertThat(
            db.queryForObject("select count(*) from post_likes where post_id=?", Integer.class, p))
        .isEqualTo(1);
    assertThat(db.queryForObject("select like_count from posts where id=?", Integer.class, p))
        .isEqualTo(1);
  }

  @Test
  void reportsAllTargetsAndDuplicatesIncludingSelf() throws Exception {
    long p = newPost(), c = comment(p, null);
    for (var target : List.of("POST:" + p, "COMMENT:" + c, "USER:" + me)) {
      String[] t = target.split(":");
      String body =
          "{\"targetType\":\"" + t[0] + "\",\"targetId\":" + t[1] + ",\"reason\":\"SPAM\"}";
      mvc.perform(as(json(post("/api/reports"), body), me))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("PENDING"));
      error(as(json(post("/api/reports"), body), me), 409, "ALREADY_REPORTED");
      mvc.perform(as(json(post("/api/reports"), body), other)).andExpect(status().isCreated());
    }
    error(
        as(
            json(
                post("/api/reports"),
                "{\"targetType\":\"USER\",\"targetId\":99999999,\"reason\":\"SPAM\"}"),
            me),
        404,
        "NOT_FOUND");
  }

  byte[] png() {
    return ImageFixtures.read("clean.png");
  }

  long upload(long user, String type, byte[] bytes) throws Exception {
    return id(
        mvc.perform(
                as(
                    multipart("/api/post-images")
                        .file(new MockMultipartFile("file", "../../escape", type, bytes)),
                    user))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contentType").value(type))
            .andReturn());
  }

  @Test
  void imageMagicSizeStorageOwnershipAndReplacement() throws Exception {
    long image = upload(me, "image/png", png());
    for (var type : List.of("image/jpeg", "image/gif", "image/webp")) {
      byte[] b = ImageFixtures.read("clean." + (type.equals("image/jpeg") ? "jpg" : type.substring(6)));
      upload(me, type, b);
    }
    String key =
        db.queryForObject("select storage_key from post_images where id=?", String.class, image);
    assertThat(key).matches("[a-f0-9-]{36}\\.png");
    assertThat(db.queryForObject("select post_id from post_images where id=?", Long.class, image))
        .isNull();
    mvc.perform(get("/api/post-images/files/" + key))
        .andExpect(status().isOk())
        .andExpect(content().bytes(png()));
    error(
        as(
            multipart("/api/post-images")
                .file(
                    new MockMultipartFile("file", "fake.png", "image/png", "<script/>".getBytes())),
            me),
        400,
        "VALIDATION_FAILED");
    error(
        as(
            multipart("/api/post-images")
                .file(new MockMultipartFile("file", "fake.jpg", "image/jpeg", png())),
            me),
        400,
        "VALIDATION_FAILED");
    error(
        as(
            multipart("/api/post-images")
                .file(new MockMultipartFile("file", "big.png", "image/png", new byte[1025])),
            me),
        413,
        "PAYLOAD_TOO_LARGE");
    error(
        as(
            multipart("/api/post-images")
                .file(new MockMultipartFile("file", "empty", "image/png", new byte[0])),
            me),
        400,
        "VALIDATION_FAILED");
    error(
        as(json(post("/api/posts"), write("남의 이미지", "[]", "[" + image + "]")), other),
        422,
        "INVALID_REFERENCE");
    long p =
        id(
            mvc.perform(as(json(post("/api/posts"), write("이미지", "[]", "[" + image + "]")), me))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasImages").value(true))
                .andReturn());
    error(
        as(json(post("/api/posts"), write("재사용", "[]", "[" + image + "]")), me),
        422,
        "INVALID_REFERENCE");
    mvc.perform(as(json(put("/api/posts/" + p), write("유지", "[]", "[" + image + "]")), me))
        .andExpect(status().isOk());
    mvc.perform(as(json(put("/api/posts/" + p), write("제거", "[]", "[]")), me))
        .andExpect(jsonPath("$.images").isEmpty());
    assertThat(
            db.queryForObject("select count(*) from post_images where id=?", Integer.class, image))
        .isZero();
  }

  List<AbstractMockHttpServletRequestBuilder<?>> blockedRequests(long p, long c) {
    return List.of(
        json(post("/api/posts"), write()),
        json(put("/api/posts/" + p), write()),
        json(post("/api/posts/" + p + "/comments"), "{\"body\":\"댓글\"}"),
        json(patch("/api/comments/" + c), "{\"body\":\"수정\"}"),
        put("/api/posts/" + p + "/like"),
        delete("/api/posts/" + p + "/like"),
        json(
            post("/api/reports"),
            "{\"targetType\":\"POST\",\"targetId\":" + p + ",\"reason\":\"SPAM\"}"),
        multipart("/api/post-images")
            .file(new MockMultipartFile("file", "a.png", "image/png", png())));
  }

  @Test
  void allSuspendedBlockedOperationsAndPendingSessions() throws Exception {
    long p = newPost(), c = comment(p, null);
    for (var r : blockedRequests(p, c)) error(as(r, suspended), 403, "ACCOUNT_SUSPENDED");
    for (var r : blockedRequests(p, c))
      error(
          r.with(user("pending:kakao:123").authorities(List.of())).with(csrf()),
          403,
          "SIGNUP_REQUIRED");
    for (var r : blockedRequests(p, c))
      error(r.with(user("pending:kakao:123").roles("USER")).with(csrf()), 403, "SIGNUP_REQUIRED");
    // Controller/service guard도 숫자가 아닌 principal을 ApiException으로 처리한다.
    assertThatThrownBy(
            () ->
                CommunityService.member(
                    org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                        .authenticated("pending:kakao:1", null, List.of())))
        .isInstanceOfSatisfying(
            com.pyeonit.config.ApiException.class,
            e -> assertThat(e.getCode()).isEqualTo("SIGNUP_REQUIRED"));
    error(json(post("/api/posts"), write()).with(csrf()), 401, "UNAUTHORIZED");
    error(json(post("/api/posts"), write()).with(user("" + me)), 403, "CSRF_INVALID");
    db.update("update users set status='SUSPENDED' where id=?", me);
    mvc.perform(as(delete("/api/comments/" + c), me)).andExpect(status().isNoContent());
    mvc.perform(as(delete("/api/posts/" + p), me)).andExpect(status().isNoContent());
  }
}
