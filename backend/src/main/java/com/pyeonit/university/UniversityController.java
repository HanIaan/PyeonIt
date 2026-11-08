package com.pyeonit.university;

import static com.pyeonit.university.UniversityData.*;

import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class UniversityController {
  private final UniversityData data;

  public UniversityController(UniversityData data) {
    this.data = data;
  }

  @GetMapping("/api/universities")
  public List<Map<String, Object>> list(
      @RequestParam(required = false) String region,
      @RequestParam(required = false) String q, Authentication auth) {
    region(region);
    String pattern = q == null ? null : com.pyeonit.search.SearchQuery.pattern(q, 1);
    Long user = member(auth, false);
    return data
        .rows(
            """
            select id from universities u where (?::text is null or region=?)
            and (?::text is null or u.name ilike ? escape E'\\\\'
              or exists(select 1 from university_aliases a where a.university_name=u.name
                and a.alias ilike ? escape E'\\\\'))
            order by case
              when lower(u.name)=lower(?::text) or exists(
                select 1 from university_aliases a where a.university_name=u.name
                  and lower(a.alias)=lower(?::text)) then 0
              when u.name ilike ? escape E'\\\\' then 1
              else 2 end, name,campus,id
            """,
            region, region, pattern, pattern, pattern, q, q,
            pattern == null ? null : pattern.substring(1))
        .stream()
        .map(r -> data.university(((Number) r.get("id")).longValue(), user))
        .toList();
  }

  @GetMapping("/api/universities/{id}")
  public Map<String, Object> detail(
      @PathVariable long id,
      @RequestParam(required = false) Integer academicYear,
      Authentication auth) {
    year(academicYear);
    var u = data.university(id, member(auth, false));
    var docs =
        data.rows(
            "select * from (" + PUBLISHED + ") d where university_id=? order by academic_year desc",
            id);
    u.put("academicYears", docs.stream().map(d -> d.get("academic_year")).toList());
    u.put(
        "guide",
        docs.stream()
            .filter(
                d ->
                    academicYear == null
                        || ((Number) d.get("academic_year")).intValue() == academicYear)
            .findFirst()
            .map(d -> data.guide(((Number) d.get("id")).longValue()))
            .orElse(null));
    return u;
  }

  @GetMapping("/api/me/favorite-universities")
  public List<Map<String, Object>> favorites(Authentication a) {
    long user = member(a, true);
    return data
        .rows(
            "select university_id from favorite_universities where user_id=? order by"
                + " created_at,university_id",
            user)
        .stream()
        .map(r -> data.university(((Number) r.get("university_id")).longValue(), user))
        .toList();
  }

  @PutMapping("/api/me/favorite-universities/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void add(@PathVariable long id, Authentication a) {
    long user = member(a, true);
    data.summary(id);
    data.jdbc()
        .update(
            "insert into favorite_universities(user_id,university_id) values (?,?) on conflict do"
                + " nothing",
            user,
            id);
  }

  @DeleteMapping("/api/me/favorite-universities/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void remove(@PathVariable long id, Authentication a) {
    data.jdbc()
        .update(
            "delete from favorite_universities where user_id=? and university_id=?",
            member(a, true),
            id);
  }
}
