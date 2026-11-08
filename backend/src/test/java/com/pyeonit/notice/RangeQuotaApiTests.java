package com.pyeonit.notice;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pyeonit.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RangeQuotaApiTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;

  @Test
  void universityDetailReturnsRangeWithNullCountAndOriginalText() throws Exception {
    long university = db.queryForObject(
        "select id from universities where name='서강대학교' and campus=''", Long.class);
    String range = "$.guide.departments[*].children[*].quotas[?(@.kind == 'RANGE')]";

    mvc.perform(get("/api/universities/" + university).param("academicYear", "2026"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(range + ".kind").value(contains("RANGE")))
        .andExpect(jsonPath(range + ".count").value(contains(nullValue())))
        .andExpect(jsonPath(range + ".rawText").value(contains("1명 이내")));
  }
}
