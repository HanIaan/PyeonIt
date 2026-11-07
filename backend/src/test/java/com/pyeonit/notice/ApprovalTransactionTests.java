package com.pyeonit.notice;

import static org.assertj.core.api.Assertions.*;

import com.pyeonit.TestcontainersConfiguration;
import com.pyeonit.admin.notice.AdminNoticeService;
import com.pyeonit.config.ApiException;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApprovalTransactionTests {
  @Autowired JdbcTemplate db;
  @Autowired AdminNoticeService service;
  long university, reviewer, document;

  @BeforeEach
  void setup() {
    university =
        db.queryForObject(
            "insert into universities(name,region) values ('트랜잭션 검증','SEOUL') returning id",
            Long.class);
    reviewer =
        db.queryForObject(
            "insert into users(nickname,role) values ('트랜잭션 관리자','ADMIN') returning id",
            Long.class);
    document =
        db.queryForObject(
            "insert into"
                + " admission_documents(university_id,academic_year,title,file_sha256,storage_key,status)"
                + " values"
                + " (?,2026,'검증',md5(random()::text)||md5(random()::text),'test','EXTRACTED')"
                + " returning id",
            Long.class,
            university);
  }

  @AfterEach
  void cleanup() {
    db.update("delete from admission_documents where id=?", document);
    db.update("delete from departments where university_id=? and level='MAJOR'", university);
    db.update("delete from departments where university_id=?", university);
    db.update("delete from universities where id=?", university);
    db.update("delete from users where id=?", reviewer);
  }

  long run(String result) {
    return db.queryForObject(
        "insert into extraction_runs(document_id,model,result) values (?,'test',?::jsonb) returning"
            + " id",
        Long.class,
        document,
        result);
  }

  @Test
  void invalidScheduleRollsBackAlreadyInsertedDepartmentsAndQuotas() {
    long run = run(NoticeApiTests.RESULT.replace("2026-01-10", "not-a-date"));
    assertThatThrownBy(() -> service.review(document, run, reviewer, true))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EXTRACTION_UNUSABLE"));
    assertThat(
            db.queryForObject(
                "select count(*) from departments where university_id=?",
                Integer.class,
                university))
        .isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from quotas where document_id=?", Integer.class, document))
        .isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from schedules where document_id=?", Integer.class, document))
        .isZero();
    assertThat(
            db.queryForObject(
                "select status from admission_documents where id=?", String.class, document))
        .isEqualTo("EXTRACTED");
    assertThat(
            db.queryForObject(
                "select reviewed_at from admission_documents where id=?", Object.class, document))
        .isNull();
  }

  @Test
  void concurrentReviewsCommitOnlyOnce() throws Exception {
    long run = run(NoticeApiTests.RESULT);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<String> review =
          () -> {
            gate.await();
            try {
              service.review(document, run, reviewer, true);
              return "APPROVED";
            } catch (ApiException e) {
              return e.getCode();
            }
          };
      var first = executor.submit(review);
      var second = executor.submit(review);
      gate.countDown();
      assertThat(
              java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("APPROVED", "DOCUMENT_ALREADY_REVIEWED");
    }
    assertThat(
            db.queryForObject(
                "select count(*) from quotas where document_id=?", Integer.class, document))
        .isEqualTo(3);
  }
}
