package com.pyeonit.notice;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pyeonit.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@Transactional
class NoticeApiTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  long university, other, member, admin;

  @BeforeEach
  void setup() {
    university =
        db.queryForObject(
            "insert into universities(name,region) values ('서강대학교','SEOUL') returning id",
            Long.class);
    other =
        db.queryForObject(
            "insert into universities(name,region) values ('다른학교','GYEONGGI') returning id",
            Long.class);
    member =
        db.queryForObject("insert into users(nickname) values ('회원') returning id", Long.class);
    admin =
        db.queryForObject(
            "insert into users(nickname,role) values ('관리자','ADMIN') returning id", Long.class);
  }

  RequestPostProcessor csrf() throws Exception {
    Cookie c = mvc.perform(get("/api/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    return r -> {
      r.setCookies(c);
      r.addHeader("X-XSRF-TOKEN", c.getValue());
      return r;
    };
  }

  long document(long school, int year, String status, String approved) {
    return db.queryForObject(
        "insert into"
            + " admission_documents(university_id,academic_year,title,file_sha256,storage_key,status,reviewed_at,page_url,file_url)"
            + " values"
            + " (?,?,?,md5(random()::text)||md5(random()::text),'test',?,?::timestamptz,'https://school/guide','https://school/guide.pdf')"
            + " returning id",
        Long.class,
        school,
        year,
        status,
        status,
        approved);
  }

  long extraction(long doc, String result) {
    return db.queryForObject(
        "insert into extraction_runs(document_id,model,result,validation) values"
            + " (?,'test',?::jsonb,'{\"ok\":true}') returning id",
        Long.class,
        doc,
        result);
  }

  static final String RESULT =
      """
      {"학교":"서강대학교","학년도":2026,"모집인원":[
        {"계열":"공과대학","단위":"학과","모집단위":"컴퓨터공학과","인원":[
          {"전형":"일반","인원":3,"원문":"3"},
          {"전형":"새 전형","인원":null,"원문":"약간명"},
          {"전형":"학사","인원":0,"원문":"미선발"}]}],
       "총계":[],"일정":[{"항목":"원서접수","시작":"2025-12-16 10:00","종료":"2025-12-18 18:00"},
       {"항목":"면접","시작":"2026-01-10"}]}
      """;

  @Test
  void latestApprovedOnlyAndUnapprovedHidden() throws Exception {
    long old = document(university, 2026, "APPROVED", "2026-01-01T00:00Z");
    long latest = document(university, 2026, "APPROVED", "2026-01-02T00:00Z");
    document(university, 2026, "NEEDS_REVIEW", null);
    document(university, 2027, "EXTRACTED", null);
    mvc.perform(get("/api/notices"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].documentId").value(latest));
    mvc.perform(get("/api/universities/" + university))
        .andExpect(jsonPath("$.academicYears.length()").value(1))
        .andExpect(jsonPath("$.guide.documentId").value(latest))
        .andExpect(jsonPath("$.guide.fileUrl").value("https://school/guide.pdf"));
    mvc.perform(get("/api/universities/" + university).param("academicYear", "2027"))
        .andExpect(jsonPath("$.guide").isEmpty());
    mvc.perform(get("/api/universities/999999")).andExpect(status().isNotFound());
  }

  @Test
  void filtersPaginationAndPersonalization() throws Exception {
    document(university, 2026, "APPROVED", "2026-01-01T00:00Z");
    document(university, 2025, "APPROVED", "2025-01-01T00:00Z");
    document(other, 2026, "APPROVED", "2026-01-02T00:00Z");
    db.update(
        "insert into favorite_universities(user_id,university_id) values (?,?)",
        member,
        university);
    mvc.perform(
            get("/api/notices")
                .param("academicYear", "2026")
                .param("region", "SEOUL")
                .param("size", "1"))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].university.id").value(university));
    mvc.perform(get("/api/notices").param("size", "1").param("page", "1"))
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.totalPages").value(3));
    mvc.perform(get("/api/notices").param("favoritesOnly", "true"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/notices").param("favoritesOnly", "true").with(user("" + member)))
        .andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(get("/api/universities").param("region", "SEOUL").with(user("" + member)))
        .andExpect(jsonPath("$[0].favorite").value(true));
    mvc.perform(get("/api/universities/" + university).with(user("" + member)))
        .andExpect(jsonPath("$.favorite").value(true));
    mvc.perform(get("/api/notices").param("size", "101")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/notices").param("academicYear", "1999"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/universities").param("region", "BUSAN"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void calendarUsesPublishedOverlapAndHundredDayLimit() throws Exception {
    long old = document(university, 2026, "APPROVED", "2026-01-01T00:00Z"),
        current = document(university, 2026, "APPROVED", "2026-01-02T00:00Z"),
        hidden = document(other, 2026, "EXTRACTED", null);
    for (long id : new long[] {old, current, hidden})
      db.update(
          "insert into schedules(document_id,type,raw_label,starts_at,ends_at) values"
              + " (?,'APPLICATION','접수','2025-12-01T00:00Z','2026-01-10T00:00Z')",
          id);
    db.update(
        "insert into schedules(document_id,type,raw_label,starts_at) values"
            + " (?,'EXAM','시험','2026-01-11T00:00Z')",
        current);
    mvc.perform(
            get("/api/calendar")
                .param("from", "2026-01-10T00:00Z")
                .param("to", "2026-01-11T00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].documentId").value(current));
    mvc.perform(
            get("/api/calendar")
                .param("from", "2026-01-01T00:00Z")
                .param("to", "2026-04-11T00:00Z"))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/calendar")
                .param("from", "2026-01-01T00:00Z")
                .param("to", "2026-04-11T00:00:01Z"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(
            get("/api/calendar")
                .param("from", "2026-01-01T00:00Z")
                .param("to", "2026-01-01T00:00Z"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void favoritesAreIdempotent() throws Exception {
    for (int i = 0; i < 2; i++)
      mvc.perform(
              put("/api/me/favorite-universities/" + university)
                  .with(user("" + member))
                  .with(csrf()))
          .andExpect(status().isNoContent());
    mvc.perform(get("/api/me/favorite-universities").with(user("" + member)))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].favorite").value(true));
    for (int i = 0; i < 2; i++)
      mvc.perform(
              delete("/api/me/favorite-universities/" + university)
                  .with(user("" + member))
                  .with(csrf()))
          .andExpect(status().isNoContent());
    mvc.perform(get("/api/me/favorite-universities").with(user("" + member)))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(put("/api/me/favorite-universities/999999").with(user("" + member)).with(csrf()))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(username = "pending:kakao:123")
  void pendingSignupGetsDomainError() throws Exception {
    mvc.perform(get("/api/me/favorite-universities"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
    mvc.perform(put("/api/me/favorite-universities/" + university).with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
    mvc.perform(delete("/api/me/favorite-universities/" + university).with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
    mvc.perform(
            post("/api/admin/admission-documents/1/approve")
                .contentType("application/json")
                .content("{\"extractionRunId\":1}")
                .with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void adminAuthorization() throws Exception {
    mvc.perform(get("/api/admin/admission-documents")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/admission-documents").with(user("" + member)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(
            post("/api/admin/admission-documents/1/approve")
                .with(csrf())
                .contentType("application/json")
                .content("{\"extractionRunId\":1}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/admin/admission-documents/1/reject").with(user("" + member)).with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  void approvalMaterializesHierarchyTracksQuotasSchedulesAndReviewMetadata() throws Exception {
    long doc = document(university, 2026, "EXTRACTED", null), run = extraction(doc, RESULT);
    db.update(
        "insert into track_aliases(university_id,raw_name,track_id) select ?,'일반',id from tracks"
            + " where code='BACHELOR'",
        university);
    mvc.perform(
            post("/api/admin/admission-documents/" + doc + "/approve")
                .with(user("" + admin).roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"extractionRunId\":" + run + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.reviewedBy.id").value(admin))
        .andExpect(jsonPath("$.reviewedAt").isNotEmpty());
    mvc.perform(get("/api/universities/" + university))
        .andExpect(jsonPath("$.guide.departments[0].level").value("COLLEGE"))
        .andExpect(jsonPath("$.guide.departments[0].children[0].level").value("MAJOR"))
        .andExpect(
            jsonPath("$.guide.departments[0].children[0].quotas[0].track.code").value("BACHELOR"))
        .andExpect(jsonPath("$.guide.departments[0].children[0].quotas[1].track").isEmpty())
        .andExpect(jsonPath("$.guide.departments[0].children[0].quotas[1].kind").value("RANGE"))
        .andExpect(
            jsonPath("$.guide.departments[0].children[0].quotas[2].kind").value("NOT_RECRUITING"))
        .andExpect(jsonPath("$.guide.schedules[1].allDay").value(true));
    mvc.perform(
            post("/api/admin/admission-documents/" + doc + "/approve")
                .with(user("" + admin).roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"extractionRunId\":" + run + "}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DOCUMENT_ALREADY_REVIEWED"));
    assertThat(
            db.queryForObject(
                "select count(*) from quotas where document_id=?", Integer.class, doc))
        .isEqualTo(3);
  }

  @Test
  void reviewQueueDefaultsLatestExtractionAndRejection() throws Exception {
    document(university, 2026, "DETECTED", null);
    long doc = document(university, 2026, "NEEDS_REVIEW", null);
    extraction(doc, "{}");
    long latest = extraction(doc, RESULT);
    mvc.perform(get("/api/admin/admission-documents").with(user("" + admin).roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].latestExtraction.id").value(latest))
        .andExpect(jsonPath("$.content[0].latestExtraction.validation.ok").value(true))
        .andExpect(jsonPath("$.content[0].fileUrl").isNotEmpty());
    mvc.perform(
            get("/api/admin/admission-documents")
                .param("status", "DETECTED")
                .with(user("" + admin).roles("ADMIN")))
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            post("/api/admin/admission-documents/" + doc + "/reject")
                .with(user("" + admin).roles("ADMIN"))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
    mvc.perform(
            post("/api/admin/admission-documents/" + doc + "/reject")
                .with(user("" + admin).roles("ADMIN"))
                .with(csrf()))
        .andExpect(status().isConflict());
  }

  @Test
  void unusableExtractionAndWrongDocumentAre422() throws Exception {
    long doc = document(university, 2026, "EXTRACTED", null),
        otherDoc = document(other, 2026, "EXTRACTED", null);
    long empty = extraction(doc, "{}"), foreign = extraction(otherDoc, RESULT);
    for (long run : new long[] {empty, foreign, 999999})
      mvc.perform(
              post("/api/admin/admission-documents/" + doc + "/approve")
                  .with(user("" + admin).roles("ADMIN"))
                  .with(csrf())
                  .contentType("application/json")
                  .content("{\"extractionRunId\":" + run + "}"))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.code").value("EXTRACTION_UNUSABLE"));
    assertThat(
            db.queryForObject(
                "select status from admission_documents where id=?", String.class, doc))
        .isEqualTo("EXTRACTED");
  }
}
