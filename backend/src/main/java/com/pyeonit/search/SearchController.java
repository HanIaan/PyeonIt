package com.pyeonit.search;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

@RestController
@Transactional(readOnly = true)
public class SearchController {
  private final PublicActivityData data;

  public SearchController(PublicActivityData data) { this.data = data; }

  @GetMapping("/api/posts/search")
  public Map<String, Object> posts(@RequestParam(defaultValue = "") String q,
      @RequestParam(required = false) Integer boardId,
      @RequestParam(required = false) Long universityId,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    String pattern = SearchQuery.pattern(q, 2);
    return data.posts("""
        from posts p where p.status='PUBLISHED'
        and (p.title ilike ? escape E'\\\\' or p.body ilike ? escape E'\\\\')
        and (?::int is null or p.board_id=?)
        and (?::bigint is null or exists(select 1 from post_universities pu
          where pu.post_id=p.id and pu.university_id=?))
        """, Arrays.asList(pattern, pattern, boardId, boardId, universityId, universityId), page, size);
  }

  @GetMapping("/api/users/search")
  public Map<String, Object> users(@RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    String pattern = SearchQuery.pattern(q, 2);
    return data.page("select id,nickname", """
        from users where status='ACTIVE' and nickname ilike ? escape E'\\\\'
        """, "id", List.of(pattern), page, size, row -> row);
  }
}
