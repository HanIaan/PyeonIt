package com.pyeonit.community;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pyeonit.TestcontainersConfiguration;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
    "pyeonit.community.retention-cleanup-cron=-",
    "pyeonit.community.orphan-image-cleanup-enabled=false"})
@AutoConfigureMockMvc
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
class RetentionScheduledTasksTests {
  static final Instant NOW = Instant.parse("2030-11-08T03:00:00Z");
  static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager transactions;
  @Autowired MockMvc mvc;
  @Autowired CommunityService community;
  @TempDir Path directory;
  OrphanImageCleanupTests.TestStorage storage;
  RetentionScheduledTasks tasks;
  long user;

  @BeforeEach
  void setup() {
    db.execute("truncate users cascade");
    user = db.queryForObject("insert into users(nickname) values ('B8') returning id", Long.class);
    storage = new OrphanImageCleanupTests.TestStorage(directory);
    tasks = cleaner(30, 30, 200);
  }

  RetentionScheduledTasks cleaner(int bodies, int images, int batch) {
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    var cleanup = new OrphanImageCleanup(db, storage, clock, transactions, Duration.ofHours(24));
    return new RetentionScheduledTasks(db, clock, cleanup, bodies, images, batch);
  }

  void clear() {
    new TransactionTemplate(transactions).executeWithoutResult(s -> tasks.clearExpiredBodies());
  }

  long post(String status, Instant at) {
    return db.queryForObject("""
        insert into posts(board_id,author_id,title,body,status,updated_at)
        select id,?,'title','body',?,? from boards limit 1 returning id
        """, Long.class, user, status, Timestamp.from(at));
  }

  long comment(long post, Long parent, String status, Instant at) {
    return db.queryForObject("""
        insert into comments(post_id,parent_id,author_id,body,status,updated_at)
        values (?,?,?,'body',?,?) returning id
        """, Long.class, post, parent, user, status, Timestamp.from(at));
  }

  long image(long post) throws Exception {
    String key = storage.store(new byte[] {1}, "png");
    return db.queryForObject("""
        insert into post_images(post_id,uploader_id,storage_key,content_type,size_bytes,created_at)
        values (?,?,?,'image/png',1,?) returning id
        """, Long.class, post, user, key, Timestamp.from(NOW));
  }

  String key(long id) {
    return db.queryForObject("select storage_key from post_images where id=?", String.class, id);
  }

  boolean imageExists(long id) {
    return db.queryForObject("select exists(select 1 from post_images where id=?)", Boolean.class, id);
  }

  @Test
  void strictBodyBoundariesStatusAndApiPlaceholdersRemainIntact() throws Exception {
    long published = post("PUBLISHED", CUTOFF.minusSeconds(1));
    long hidden = post("HIDDEN", CUTOFF.minusSeconds(1));
    long expired = post("DELETED", CUTOFF.minusNanos(1000));
    long exact = post("DELETED", CUTOFF);
    long recent = post("DELETED", CUTOFF.plusNanos(1000));
    long root = comment(published, null, "DELETED", CUTOFF.minusNanos(1000));
    long reply = comment(published, root, "PUBLISHED", CUTOFF.minusSeconds(1));
    long hiddenComment = comment(published, null, "HIDDEN", CUTOFF.minusSeconds(1));
    long exactComment = comment(published, null, "DELETED", CUTOFF);
    long recentComment = comment(published, null, "DELETED", CUTOFF.plusNanos(1000));
    long empty = post("DELETED", CUTOFF.minusSeconds(1));
    db.update("update posts set body='' where id=?", empty);
    String emptyVersion = db.queryForObject("select xmin::text from posts where id=?", String.class, empty);
    clear();
    for (long id : List.of(expired, empty)) {
      assertThat(db.queryForMap("select body,status,title from posts where id=?", id))
          .containsEntry("body", "").containsEntry("status", "DELETED").containsEntry("title", "title");
    }
    assertThat(db.queryForObject("select updated_at from posts where id=?", Timestamp.class, expired).toInstant())
        .isEqualTo(CUTOFF.minusNanos(1000));
    assertThat(db.queryForObject("select xmin::text from posts where id=?", String.class, empty)).isEqualTo(emptyVersion);
    for (long id : List.of(published, hidden, exact, recent))
      assertThat(db.queryForObject("select body from posts where id=?", String.class, id)).isEqualTo("body");
    for (long id : List.of(reply, hiddenComment, exactComment, recentComment))
      assertThat(db.queryForObject("select body from comments where id=?", String.class, id)).isEqualTo("body");
    assertThat(db.queryForObject("select body from comments where id=?", String.class, root)).isEmpty();
    assertThat(db.queryForObject("select parent_id from comments where id=?", Long.class, reply)).isEqualTo(root);
    mvc.perform(get("/api/posts/" + expired)).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(get("/api/posts/" + published + "/comments"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(root))
        .andExpect(jsonPath("$.content[0].status").value("DELETED"))
        .andExpect(jsonPath("$.content[0].body").isEmpty())
        .andExpect(jsonPath("$.content[0].replies[0].id").value(reply));
    String version = db.queryForObject("select xmin::text from comments where id=?", String.class, root);
    clear();
    assertThat(db.queryForObject("select xmin::text from comments where id=?", String.class, root)).isEqualTo(version);
  }

  @Test
  void deletionTimestampSurvivesCounterChangesAndDeletedContentCannotBeEdited() {
    var auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken
        .authenticated("" + user, null, List.of());
    long p = post("PUBLISHED", CUTOFF);
    long c = comment(p, null, "PUBLISHED", CUTOFF);
    community.deleteComment(c, auth);
    Timestamp deleted = db.queryForObject("select updated_at from comments where id=?", Timestamp.class, c);
    community.createComment(p, new CommunityRequests.CommentCreate("reply", c), auth);
    community.like(p, true, auth);
    assertThat(db.queryForObject("select updated_at from comments where id=?", Timestamp.class, c)).isEqualTo(deleted);
    assertThat(db.queryForObject("select updated_at from posts where id=?", Timestamp.class, p).toInstant()).isEqualTo(CUTOFF);
    assertThatThrownBy(() -> community.updateComment(c, "edit", auth))
        .isInstanceOf(com.pyeonit.config.ApiException.class);
    community.deletePost(p, auth);
    Timestamp postDeleted = db.queryForObject("select updated_at from posts where id=?", Timestamp.class, p);
    assertThatThrownBy(() -> community.like(p, false, auth))
        .isInstanceOf(com.pyeonit.config.ApiException.class);
    assertThatThrownBy(() -> community.deletePost(p, auth))
        .isInstanceOf(com.pyeonit.config.ApiException.class);
    assertThat(db.queryForObject("select updated_at from posts where id=?", Timestamp.class, p)).isEqualTo(postDeleted);
  }

  @Test
  void imageBoundaryUsesPostDeletionTimeAndNeverDeletesPublishedOrHiddenImages() throws Exception {
    long expired = image(post("DELETED", CUTOFF.minusNanos(1000)));
    String removed = key(expired);
    var kept = List.of(image(post("DELETED", CUTOFF)), image(post("DELETED", CUTOFF.plusNanos(1000))),
        image(post("PUBLISHED", CUTOFF.minusSeconds(1))), image(post("HIDDEN", CUTOFF.minusSeconds(1))));
    tasks.deleteExpiredImages();
    assertThat(imageExists(expired)).isFalse();
    assertThat(directory.resolve(removed)).doesNotExist();
    for (long id : kept) {
      assertThat(imageExists(id)).isTrue();
      assertThat(directory.resolve(key(id))).exists();
    }
  }

  @Test
  void imageFailuresAreIsolatedRetriedAndMissingFilesAreSuccessful(
      org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    long post = post("DELETED", CUTOFF.minusSeconds(1));
    long checked = image(post), unchecked = image(post), successful = image(post), missing = image(post);
    String checkedKey = key(checked), uncheckedKey = key(unchecked);
    storage.checkedFailures.add(checkedKey);
    storage.uncheckedFailures.add(uncheckedKey);
    storage.local.delete(key(missing));
    storage.beforeDelete = () -> assertThat(db.queryForObject("select count(*) from post_images", Integer.class)).isPositive();
    tasks.deleteExpiredImages();
    assertThat(imageExists(checked)).isTrue();
    assertThat(imageExists(unchecked)).isTrue();
    assertThat(imageExists(successful)).isFalse();
    assertThat(imageExists(missing)).isFalse();
    assertThat(output.getOut()).doesNotContain(checkedKey, uncheckedKey,
        "Test deletion failure", "Test provider failure");
    storage.checkedFailures.clear();
    storage.uncheckedFailures.clear();
    tasks.deleteExpiredImages();
    assertThat(imageExists(checked)).isFalse();
    assertThat(imageExists(unchecked)).isFalse();
    assertThat(directory.resolve(checkedKey)).doesNotExist();
    assertThat(directory.resolve(uncheckedKey)).doesNotExist();
  }

  @Test
  void boundedBatchesAndIndependentConfiguredRetentions() throws Exception {
    long p = post("DELETED", NOW.minus(Duration.ofDays(2)));
    long c = comment(p, null, "DELETED", NOW.minus(Duration.ofDays(2)));
    long i = image(p), j = image(p);
    clear(); tasks.deleteExpiredImages();
    assertThat(imageExists(i)).isTrue();
    tasks = cleaner(1, 3, 1);
    clear(); tasks.deleteExpiredImages();
    assertThat(db.queryForObject("select body from posts where id=?", String.class, p)).isEmpty();
    assertThat(db.queryForObject("select body from comments where id=?", String.class, c)).isEqualTo("body");
    assertThat(imageExists(i)).isTrue();
    tasks = cleaner(1, 1, 1);
    clear(); tasks.deleteExpiredImages();
    assertThat(db.queryForObject("select body from comments where id=?", String.class, c)).isEmpty();
    assertThat(imageExists(i)).isFalse();
    assertThat(imageExists(j)).isTrue();
    tasks.deleteExpiredImages();
    assertThat(imageExists(j)).isFalse();
  }

  @Test
  void lockedRowsAreSkippedAndEligibilityIsRecheckedBeforeStorageDeletion() throws Exception {
    long p = post("DELETED", CUTOFF.minusSeconds(1));
    long i = image(p);
    try (var executor = Executors.newSingleThreadExecutor()) {
      new TransactionTemplate(transactions).executeWithoutResult(s -> {
        db.queryForList("select id from posts where id=? for update", p);
        try {
          executor.submit(() -> { clear(); tasks.deleteExpiredImages(); }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) { throw new RuntimeException(e); }
        assertThat(storage.attempts.get()).isZero();
        db.update("update posts set status='PUBLISHED' where id=?", p);
      });
    }
    clear(); tasks.deleteExpiredImages();
    assertThat(imageExists(i)).isTrue();
    assertThat(storage.attempts.get()).isZero();
  }

  @Test
  void schedulingCanBeDisabledAndInvalidSettingsFailFast() {
    new ApplicationContextRunner().withUserConfiguration(RetentionScheduledTasks.class)
        .withPropertyValues("pyeonit.community.retention-cleanup-enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(RetentionScheduledTasks.class));
    assertThatThrownBy(() -> cleaner(0, 30, 200)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cleaner(30, 0, 200)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cleaner(30, 30, 0)).isInstanceOf(IllegalArgumentException.class);
  }
}
