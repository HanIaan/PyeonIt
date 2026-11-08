package com.pyeonit.community;

import static org.assertj.core.api.Assertions.*;

import com.pyeonit.TestcontainersConfiguration;
import com.pyeonit.config.ApiException;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "pyeonit.community.orphan-image-cleanup-enabled=false")
@Import(TestcontainersConfiguration.class)
class OrphanImageCleanupTests {
  static final Instant NOW = Instant.parse("2030-03-01T00:00:00Z");
  static final Instant CUTOFF = NOW.minus(Duration.ofHours(24));
  @TempDir Path directory;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager transactions;
  @Autowired CommunityService community;
  @Autowired Environment environment;
  @Autowired ApplicationContext context;
  TestStorage storage;
  OrphanImageCleanup cleanup;
  long user;

  @BeforeEach
  void setup() {
    db.execute("truncate users cascade");
    user = db.queryForObject("insert into users(nickname) values ('P3테스트') returning id", Long.class);
    storage = new TestStorage(directory);
    cleanup = cleaner(Duration.ofHours(24));
  }

  OrphanImageCleanup cleaner(Duration retention) {
    return new OrphanImageCleanup(db, storage, Clock.fixed(NOW, ZoneOffset.UTC), transactions, retention);
  }

  long image(Instant createdAt) throws IOException {
    String key = storage.store(new byte[] {1}, "png");
    return db.queryForObject(
        "insert into post_images(uploader_id,storage_key,content_type,size_bytes,created_at)"
            + " values (?,?,'image/png',1,?) returning id",
        Long.class, user, key, Timestamp.from(createdAt));
  }

  String key(long id) {
    return db.queryForObject("select storage_key from post_images where id=?", String.class, id);
  }

  boolean exists(long id) {
    return db.queryForObject("select exists(select 1 from post_images where id=?)", Boolean.class, id);
  }

  long attach(long id) {
    int board = db.queryForObject("select id from boards where code='FREE'", Integer.class);
    var result = community.write(null,
        new CommunityRequests.PostWrite(board, "P3", "내용", null, List.of(id)),
        UsernamePasswordAuthenticationToken.authenticated("" + user, null, List.of()));
    return ((Number) result.get("id")).longValue();
  }

  @Test
  void deletesExpiredOrphanFileBeforeDeletingRow() throws Exception {
    long id = image(CUTOFF.minusSeconds(1));
    String key = key(id);
    storage.beforeDelete = () -> assertThat(exists(id)).isTrue();
    cleanup.cleanup();
    assertThat(directory.resolve(key)).doesNotExist();
    assertThat(exists(id)).isFalse();
  }

  @Test
  void preservesOldAttachedImage() throws Exception {
    long id = image(CUTOFF.minusSeconds(1));
    String key = key(id);
    attach(id);
    cleanup.cleanup();
    assertThat(exists(id)).isTrue();
    assertThat(directory.resolve(key)).exists();
    assertThat(storage.attempts.get()).isZero();
  }

  @Test
  void honorsMicrosecondBoundariesAndKeepsExactRetentionAge() throws Exception {
    long beforeExpiry = image(CUTOFF.plusNanos(1000));
    long exactExpiry = image(CUTOFF);
    long afterExpiry = image(CUTOFF.minusNanos(1000));
    String beforeKey = key(beforeExpiry);
    String exactKey = key(exactExpiry);
    String afterKey = key(afterExpiry);
    cleanup.cleanup();
    assertThat(exists(beforeExpiry)).isTrue();
    assertThat(exists(exactExpiry)).isTrue();
    assertThat(exists(afterExpiry)).isFalse();
    assertThat(directory.resolve(beforeKey)).exists();
    assertThat(directory.resolve(exactKey)).exists();
    assertThat(directory.resolve(afterKey)).doesNotExist();
  }

  @Test
  void deletesRowWhenFileIsAlreadyMissing() throws Exception {
    long id = image(CUTOFF.minusSeconds(1));
    storage.local.delete(key(id));
    cleanup.cleanup();
    assertThat(exists(id)).isFalse();
  }

  @Test
  void isolatesCheckedAndUncheckedFailuresAndRetriesNextCycle() throws Exception {
    long checked = image(CUTOFF.minusSeconds(3));
    long unchecked = image(CUTOFF.minusSeconds(2));
    long successful = image(CUTOFF.minusSeconds(1));
    String checkedKey = key(checked);
    String uncheckedKey = key(unchecked);
    String successfulKey = key(successful);
    storage.checkedFailures.add(checkedKey);
    storage.uncheckedFailures.add(uncheckedKey);
    cleanup.cleanup();
    assertThat(exists(checked)).isTrue();
    assertThat(exists(unchecked)).isTrue();
    assertThat(directory.resolve(checkedKey)).exists();
    assertThat(directory.resolve(uncheckedKey)).exists();
    assertThat(exists(successful)).isFalse();
    assertThat(directory.resolve(successfulKey)).doesNotExist();
    storage.checkedFailures.clear();
    storage.uncheckedFailures.clear();
    cleanup.cleanup();
    assertThat(exists(checked)).isFalse();
    assertThat(exists(unchecked)).isFalse();
    assertThat(directory.resolve(checkedKey)).doesNotExist();
    assertThat(directory.resolve(uncheckedKey)).doesNotExist();
  }

  @Test
  void limitsEachCycleAndProcessesRemainderNextCycle() throws Exception {
    for (int i = 0; i < OrphanImageCleanup.BATCH_SIZE + 1; i++) image(CUTOFF.minusSeconds(1));
    cleanup.cleanup();
    assertThat(storage.attempts.get()).isEqualTo(OrphanImageCleanup.BATCH_SIZE);
    assertThat(db.queryForObject("select count(*) from post_images", Integer.class)).isOne();
    cleanup.cleanup();
    assertThat(db.queryForObject("select count(*) from post_images", Integer.class)).isZero();
    try (var files = java.nio.file.Files.list(directory)) {
      assertThat(files.count()).isZero();
    }
  }

  @Test
  void usesConfiguredRetention() throws Exception {
    long id = image(NOW.minus(Duration.ofHours(2)));
    cleanup.cleanup();
    assertThat(exists(id)).isTrue();
    cleaner(Duration.ofHours(1)).cleanup();
    assertThat(exists(id)).isFalse();
  }

  @Test
  void skipsAttachmentLockAndPreservesImageAfterAttachmentCommits() throws Exception {
    long id = image(CUTOFF.minusSeconds(1));
    String key = key(id);
    try (var pool = Executors.newSingleThreadExecutor()) {
      new TransactionTemplate(transactions).executeWithoutResult(status -> {
        attach(id);
        try {
          pool.submit(cleanup::cleanup).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
        assertThat(storage.attempts.get()).isZero();
      });
    }
    cleanup.cleanup();
    assertThat(exists(id)).isTrue();
    assertThat(directory.resolve(key)).exists();
  }

  @Test
  void attachmentCannotCommitAfterCleanupHasLockedImage() throws Exception {
    long id = image(CUTOFF.minusSeconds(1));
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    storage.beforeDelete = () -> {
      locked.countDown();
      try {
        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var cleaning = pool.submit(cleanup::cleanup);
      try {
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        var attaching = pool.submit(() -> attach(id));
        assertThatThrownBy(() -> attaching.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        release.countDown();
        cleaning.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> attaching.get(10, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ApiException.class);
      } finally {
        release.countDown();
      }
    }
    assertThat(exists(id)).isFalse();
    assertThat(db.queryForObject("select count(*) from posts", Integer.class)).isZero();
  }

  @Test
  void defaultsAre24HoursAndOneHourAndSchedulingCanBeDisabled() {
    var binder = Binder.get(environment);
    assertThat(binder.bind("pyeonit.community.orphan-image-retention", Duration.class).get())
        .isEqualTo(Duration.ofHours(24));
    assertThat(binder.bind("pyeonit.community.orphan-image-cleanup-interval", Duration.class).get())
        .isEqualTo(Duration.ofHours(1));
    assertThat(context.getBeansOfType(OrphanImageScheduledTasks.class)).isEmpty();
  }

  @Test
  void schedulingIsEnabledByDefaultAndInvokesCleanup() throws Exception {
    var defaults = new YamlPropertySourceLoader()
        .load("application", new ClassPathResource("application.yml")).getFirst();
    var worker = org.mockito.Mockito.mock(OrphanImageCleanup.class);
    new ApplicationContextRunner()
        .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addLast(defaults))
        .withBean(OrphanImageCleanup.class, () -> worker)
        .withUserConfiguration(OrphanImageScheduledTasks.class)
        .run(ctx -> {
          assertThat(ctx).hasSingleBean(OrphanImageScheduledTasks.class);
          assertThat(ctx.getEnvironment().getProperty(
              "pyeonit.community.orphan-image-cleanup-enabled", Boolean.class)).isTrue();
          ctx.getBean(OrphanImageScheduledTasks.class).deleteOrphanImages();
          org.mockito.Mockito.verify(worker).cleanup();
        });
  }

  static class TestStorage implements ImageStorage {
    final LocalImageStorage local;
    final Set<String> checkedFailures = ConcurrentHashMap.newKeySet();
    final Set<String> uncheckedFailures = ConcurrentHashMap.newKeySet();
    final AtomicInteger attempts = new AtomicInteger();
    Runnable beforeDelete = () -> {};

    TestStorage(Path directory) {
      local = new LocalImageStorage(directory.toString());
    }

    @Override
    public String store(byte[] bytes, String extension) throws IOException {
      return local.store(bytes, extension);
    }

    @Override
    public Resource read(String key) {
      return local.read(key);
    }

    @Override
    public String url(String key) {
      return local.url(key);
    }

    @Override
    public void delete(String key) throws IOException {
      attempts.incrementAndGet();
      beforeDelete.run();
      if (checkedFailures.contains(key)) throw new IOException("Test deletion failure");
      if (uncheckedFailures.contains(key)) throw new IllegalStateException("Test provider failure");
      local.delete(key);
    }
  }
}
