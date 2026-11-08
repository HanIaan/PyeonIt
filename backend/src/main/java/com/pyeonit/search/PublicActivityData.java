package com.pyeonit.search;

import java.sql.Timestamp;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Scalar read projections shared by search and public profiles; never writes domain data. */
@Repository
public class PublicActivityData {
  private final JdbcTemplate db;

  public PublicActivityData(JdbcTemplate db) { this.db = db; }

  public static Map<String, Object> map(Object... entries) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
    return result;
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return db.query(sql, (rs, n) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      var md = rs.getMetaData();
      for (int i = 1; i <= md.getColumnCount(); i++) {
        Object value = rs.getObject(i);
        if (value instanceof Timestamp t) value = t.toInstant().toString();
        row.put(md.getColumnLabel(i), value);
      }
      return row;
    }, args);
  }

  public long count(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  public Map<String, Object> page(String select, String from, String order, List<Object> args,
      int page, int size, Function<Map<String, Object>, Map<String, Object>> projection) {
    SearchQuery.page(page, size);
    long total = count("select count(*) " + from, args.toArray());
    var parameters = new ArrayList<>(args);
    parameters.add(size);
    parameters.add((long) page * size);
    var content = rows(select + " " + from + " order by " + order + " limit ? offset ?",
        parameters.toArray()).stream().map(projection).toList();
    return map("content", content, "page", page, "size", size,
        "totalElements", total, "totalPages", (total + size - 1) / size);
  }

  public Map<String, Object> posts(String from, List<Object> args, int page, int size) {
    return page("select p.*", from, "p.created_at desc,p.id desc", args, page, size, this::post);
  }

  private Map<String, Object> post(Map<String, Object> p) {
    Object id = p.get("id");
    return map("id", id,
        "board", rows("select id,code,name from boards where id=?", p.get("board_id")).getFirst(),
        "title", p.get("title"),
        "author", rows("select id,nickname from users where id=?", p.get("author_id")).getFirst(),
        "universities", rows("""
            select u.id,u.name,u.campus,u.region from universities u
            join post_universities pu on pu.university_id=u.id where pu.post_id=? order by u.id
            """, id),
        "viewCount", p.get("view_count"), "likeCount", p.get("like_count"),
        "commentCount", p.get("comment_count"),
        "hasImages", count("select count(*) from post_images where post_id=?", id) > 0,
        "createdAt", p.get("created_at"));
  }
}
