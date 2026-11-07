package com.pyeonit.community;

import static com.pyeonit.community.CommunityRequests.*;
import static org.assertj.core.api.Assertions.*;

import com.pyeonit.TestcontainersConfiguration;
import com.pyeonit.config.ApiException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "pyeonit.community.max-image-bytes=2048")
@Import(TestcontainersConfiguration.class)
class CommunityTransactionTests {
  static final Path directory;

  static {
    try {
      directory = Files.createTempDirectory("pyeonit-b3-test-");
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("pyeonit.community.image-directory", directory::toString);
  }

  @AfterAll
  static void cleanup() throws Exception {
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  @Autowired CommunityService service;
  @Autowired JdbcTemplate db;

  Authentication auth(long id) {
    return UsernamePasswordAuthenticationToken.authenticated("" + id, null, List.of());
  }

  long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  void concurrently(IntConsumer action) throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        final int index = i;
        futures.add(
            pool.submit(
                () -> {
                  try {
                    start.await();
                    action.accept(index);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                  }
                }));
      }
      start.countDown();
      for (var future : futures) future.get(20, TimeUnit.SECONDS);
    }
  }

  @Test
  void concurrentLikesCommentsAndReportsRemainConsistent() throws Exception {
    long me =
        db.queryForObject("insert into users(nickname) values('B3동시성') returning id", Long.class);
    int board = db.queryForObject("select id from boards where code='FREE'", Integer.class);
    long p =
        number(service.write(null, new PostWrite(board, "동시성", "내용", null, null), auth(me)), "id");
    concurrently(i -> service.like(p, true, auth(me)));
    assertThat(db.queryForObject("select like_count from posts where id=?", Integer.class, p))
        .isEqualTo(1);
    assertThat(
            db.queryForObject("select count(*) from post_likes where post_id=?", Integer.class, p))
        .isEqualTo(1);
    concurrently(i -> service.like(p, false, auth(me)));
    assertThat(db.queryForObject("select like_count from posts where id=?", Integer.class, p))
        .isZero();
    concurrently(i -> service.createComment(p, new CommentCreate("댓글" + i, null), auth(me)));
    assertThat(db.queryForObject("select comment_count from posts where id=?", Integer.class, p))
        .isEqualTo(8);
    var ids = db.queryForList("select id from comments where post_id=? order by id", Long.class, p);
    concurrently(i -> service.deleteComment(ids.get(i), auth(me)));
    assertThat(db.queryForObject("select comment_count from posts where id=?", Integer.class, p))
        .isZero();
    concurrently(
        i -> {
          try {
            service.report(new ReportCreate(Target.POST, p, Reason.SPAM, null), auth(me));
          } catch (ApiException e) {
            assertThat(e.getCode()).isEqualTo("ALREADY_REPORTED");
          }
        });
    assertThat(
            db.queryForObject(
                "select count(*) from reports where target_type='POST' and target_id=?",
                Integer.class,
                p))
        .isEqualTo(1);
  }

  @Test
  void imageConnectionIsAtomicAndRemovalDeletesCommittedFile() throws Exception {
    long me =
        db.queryForObject("insert into users(nickname) values('B3파일검증') returning id", Long.class);
    int board = db.queryForObject("select id from boards where code='FREE'", Integer.class);
    var image =
        service.upload(
            new MockMultipartFile("file", "../../unsafe", "image/gif", "GIF89atest".getBytes()),
            auth(me));
    long id = number(image, "id");
    String key =
        db.queryForObject("select storage_key from post_images where id=?", String.class, id);
    assertThat(directory.resolve(key)).exists();
    assertThatThrownBy(
            () ->
                service.write(
                    null,
                    new PostWrite(board, "실패", "내용", List.of(), List.of(id, Long.MAX_VALUE)),
                    auth(me)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_REFERENCE"));
    assertThat(db.queryForObject("select post_id from post_images where id=?", Long.class, id))
        .isNull();
    long p =
        number(
            service.write(null, new PostWrite(board, "성공", "내용", null, List.of(id)), auth(me)),
            "id");
    assertThat(db.queryForObject("select post_id from post_images where id=?", Long.class, id))
        .isEqualTo(p);
    service.write(p, new PostWrite(board, "제거", "내용", null, null), auth(me));
    assertThat(directory.resolve(key)).doesNotExist();
  }
}
