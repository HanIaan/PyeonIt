package com.pyeonit.search;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pyeonit.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "pyeonit.search.rate-limit.ip-requests=10000",
    "pyeonit.search.rate-limit.session-requests=10000"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class SearchProfileApiTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  long active, suspended, withdrawn, school, otherSchool;
  int board, otherBoard;

  @BeforeEach
  void setup() {
    active = person("SearchAlpha", "ACTIVE");
    suspended = person("SearchSuspended", "SUSPENDED");
    withdrawn = person("SearchWithdrawn", "WITHDRAWN");
    school = school("성균관대학교", "SEOUL");
    otherSchool = school("OtherUniversity", "GYEONGGI");
    db.update("insert into university_aliases(university_name,alias) values (?, 'SKKU')",
        "성균관대학교");
    board = db.queryForObject("select id from boards where code='QUESTION'", Integer.class);
    otherBoard = db.queryForObject("select id from boards where code='FREE'", Integer.class);
  }

  long person(String name, String status) {
    return db.queryForObject("insert into users(nickname,status) values (?,?) returning id",
        Long.class, name, status);
  }

  long school(String name, String region) {
    return db.queryForObject("insert into universities(name,region) values (?,?) returning id",
        Long.class, name, region);
  }

  long post(String title, String body, String status, int boardId, long author) {
    return db.queryForObject("""
        insert into posts(board_id,author_id,title,body,status,created_at)
        values (?,?,?,?,?,'2026-01-01T00:00:00Z') returning id
        """, Long.class, boardId, author, title, body, status);
  }

  void comment(long post, String status, String body) {
    db.update("insert into comments(post_id,author_id,body,status) values (?,?,?,?)",
        post, suspended, body, status);
  }

  @Test
  void postsMatchTitleBodyCaseAndExcludeInvisible() throws Exception {
    long title = post("Alpha title", "nothing", "PUBLISHED", board, active);
    long body = post("nothing", "ALPHA body", "PUBLISHED", board, active);
    post("Alpha hidden", "", "HIDDEN", board, active);
    post("Alpha deleted", "", "DELETED", board, active);
    post("unmatched", "", "PUBLISHED", board, active);
    mvc.perform(get("/api/posts/search").param("q", "alpha"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].id").value(body))
        .andExpect(jsonPath("$.content[1].id").value(title))
        .andExpect(jsonPath("$.content[0].board.id").value(board))
        .andExpect(jsonPath("$.content[0].author.id").value(active));
    mvc.perform(get("/api/posts/search").param("q", "absent"))
        .andExpect(jsonPath("$.totalElements").value(0));
    db.update("update posts set created_at='2026-02-01' where id=?", title);
    mvc.perform(get("/api/posts/search").param("q", "alpha"))
        .andExpect(jsonPath("$.content[0].id").value(title));
  }

  @Test
  void filtersPaginationAndBoundaries() throws Exception {
    long tagged = post("Filter target", "", "PUBLISHED", board, active);
    post("Filter other board", "", "PUBLISHED", otherBoard, active);
    post("Filter untagged", "", "PUBLISHED", board, active);
    db.update("insert into post_universities values (?,?),(?,?)", tagged, school, tagged, otherSchool);
    mvc.perform(get("/api/posts/search").param("q", "Filter")
        .param("boardId", "" + board).param("universityId", "" + school))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].universities.length()").value(2));
    mvc.perform(get("/api/posts/search").param("q", "Filter").param("size", "2").param("page", "1"))
        .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.content.length()").value(1));
    mvc.perform(get("/api/posts/search").param("q", "Filter").param("page", "2147483647"))
        .andExpect(jsonPath("$.content.length()").value(0));
    for (String path : List.of("/api/posts/search", "/api/users/search", "/api/users/" + active + "/posts",
        "/api/users/" + active + "/comments")) {
      for (String size : List.of("0", "101")) {
        mvc.perform(get(path).param("q", "Filter").param("size", size))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
      }
      mvc.perform(get(path).param("q", "Filter").param("page", "-1"))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
  }

  @Test
  void literalWildcardsAndBackslashInAllSearches() throws Exception {
    for (String literal : List.of("A%", "A_", "A\\")) {
      post(literal, "", "PUBLISHED", board, active);
      long user = person(literal, "ACTIVE");
      long schoolId = school(literal, "SEOUL");
      mvc.perform(get("/api/posts/search").param("q", literal))
          .andExpect(jsonPath("$.totalElements").value(1));
      mvc.perform(get("/api/users/search").param("q", literal))
          .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(user));
      mvc.perform(get("/api/universities").param("q", literal))
          .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(schoolId));
    }
  }

  @Test
  void queryValidation() throws Exception {
    for (String path : List.of("/api/posts/search", "/api/users/search")) {
      for (String q : List.of("", "a", "a".repeat(51), "  ")) {
        mvc.perform(get(path).param("q", q)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
      }
      mvc.perform(get(path)).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
      mvc.perform(get(path).param("q", "a".repeat(50))).andExpect(status().isOk());
    }
    for (String q : List.of("", " ", "a".repeat(51))) {
      mvc.perform(get("/api/universities").param("q", q)).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    mvc.perform(get("/api/universities").param("q", "성")).andExpect(status().isOk());
  }

  @Test
  void authorSearchAndProfileVisibility() throws Exception {
    mvc.perform(get("/api/users/search").param("q", "SEARCH"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(active));
    long second = person("SearchBeta", "ACTIVE");
    mvc.perform(get("/api/users/search").param("q", "search").param("size", "1").param("page", "1"))
        .andExpect(jsonPath("$.content[0].id").value(second)).andExpect(jsonPath("$.totalPages").value(2));
    for (String suffix : List.of("", "/posts", "/comments")) {
      mvc.perform(get("/api/users/" + suspended + suffix)).andExpect(status().isOk());
      for (long id : List.of(withdrawn, Long.MAX_VALUE)) {
        mvc.perform(get("/api/users/" + id + suffix)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
      }
    }
  }

  @Test
  void profileCountsAndActivitiesUseSameVisibilityRules() throws Exception {
    long published = post("public", "", "PUBLISHED", board, suspended);
    long deleted = post("deleted", "", "DELETED", board, suspended);
    long hidden = post("hidden", "", "HIDDEN", board, suspended);
    comment(published, "PUBLISHED", "가".repeat(201));
    comment(published, "DELETED", "deleted");
    comment(published, "HIDDEN", "hidden");
    comment(deleted, "PUBLISHED", "deleted post");
    comment(hidden, "PUBLISHED", "hidden post");
    comment(published, "PUBLISHED", "reply");
    db.update("""
        update comments set parent_id=(select min(id) from comments where author_id=?),
        created_at='2027-01-01' where author_id=? and body='reply'
        """, suspended, suspended);
    mvc.perform(get("/api/users/" + suspended))
        .andExpect(jsonPath("$.postCount").value(1)).andExpect(jsonPath("$.commentCount").value(2))
        .andExpect(jsonPath("$.nickname").value("SearchSuspended"))
        .andExpect(jsonPath("$.createdAt").isString());
    mvc.perform(get("/api/users/" + suspended + "/posts"))
        .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(published));
    mvc.perform(get("/api/users/" + suspended + "/comments"))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].bodyExcerpt").value("reply"))
        .andExpect(jsonPath("$.content[1].bodyExcerpt").value("가".repeat(200)))
        .andExpect(jsonPath("$.content[1].postTitle").value("public"))
        .andExpect(jsonPath("$.content[1].postId").value(published));
  }

  @Test
  void universityAliasesPartialCaseRegionAndUnfilteredList() throws Exception {
    for (String q : List.of("성대", "균관", "skku", "skk", "성")) {
      mvc.perform(get("/api/universities").param("q", q)).andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(school));
    }
    mvc.perform(get("/api/universities").param("q", "성대").param("region", "GYEONGGI"))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/universities").param("q", "ㅅㄷ")).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/universities")).andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(db.queryForObject(
            "select id from universities order by name,campus,id limit 1", Long.class)))
        .andExpect(jsonPath("$[0].favorite").value(false));
  }

  @Test
  void exactAliasRanksBeforeNameSubstring() throws Exception {
    long hansung = school("한성대학교", "SEOUL");
    mvc.perform(get("/api/universities").param("q", "성대"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(school))
        .andExpect(jsonPath("$[1].id").value(hansung));
  }

  @Test
  void exactThenPrefixThenSubstringWithNameOrderWithinGroup() throws Exception {
    long exact = school("서울", "SEOUL");
    long prefixOne = school("서울가대학교", "SEOUL");
    long prefixTwo = school("서울나대학교", "SEOUL");
    long partial = school("가서울대학교", "SEOUL");
    mvc.perform(get("/api/universities").param("q", "서울"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4))
        .andExpect(jsonPath("$[0].id").value(exact))
        .andExpect(jsonPath("$[1].id").value(prefixOne))
        .andExpect(jsonPath("$[2].id").value(prefixTwo))
        .andExpect(jsonPath("$[3].id").value(partial));
  }

  @Test
  void exactAliasIgnoresCaseAndRanksBeforeNamePrefixAndPartialAlias() throws Exception {
    long prefix = school("SKKU University", "SEOUL");
    long partialAlias = school("가대학교", "SEOUL");
    db.update("insert into university_aliases(university_name,alias) values ('가대학교','xSKKUx')");
    mvc.perform(get("/api/universities").param("q", "skku"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(school))
        .andExpect(jsonPath("$[1].id").value(prefix))
        .andExpect(jsonPath("$[2].id").value(partialAlias));
  }

  @Test
  void migrationSeedsAliasesBeforeSchoolsExistAndMatchesLaterCampuses() throws Exception {
    assertThat(db.queryForObject("select count(*) from pg_extension where extname='pg_trgm'", Long.class)).isEqualTo(1);
    for (String name : List.of("posts_title_trgm_idx", "posts_body_trgm_idx", "universities_name_trgm_idx",
        "university_aliases_alias_trgm_idx", "users_nickname_trgm_idx")) {
      assertThat(db.queryForObject("select indexdef from pg_indexes where indexname=?", String.class, name))
          .contains("USING gin", "gin_trgm_ops");
    }
    // Reapply the actual migration with no schools, just like a fresh database.
    db.update("delete from universities");
    assertThat(db.queryForObject("select count(*) from universities", Long.class)).isZero();
    db.execute("drop table university_aliases");
    for (String name : List.of("posts_title_trgm_idx", "posts_body_trgm_idx", "universities_name_trgm_idx",
        "users_nickname_trgm_idx")) db.execute("drop index " + name);
    var resource = new org.springframework.core.io.ClassPathResource(
        "db/migration/V10__search_indexes_and_university_aliases.sql");
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(resource)
        .execute(java.util.Objects.requireNonNull(db.getDataSource()));
    assertThat(db.queryForObject("select count(*) from university_aliases", Long.class)).isEqualTo(20);
    assertThat(db.queryForObject("""
        select alias from university_aliases where university_name='성균관대학교'
        """, String.class)).isEqualTo("성대");
    mvc.perform(get("/api/universities").param("q", "성대"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

    long laterSchool = school("성균관대학교", "SEOUL");
    db.update("""
        insert into universities(name,campus,region) values ('성균관대학교','자연과학','GYEONGGI')
        """);
    mvc.perform(get("/api/universities").param("q", "성대"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    mvc.perform(get("/api/universities").param("q", "성대").param("region", "SEOUL"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(laterSchool));
  }
}
