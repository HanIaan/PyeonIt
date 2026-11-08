package com.pyeonit.profile;

import com.pyeonit.config.ApiException;
import com.pyeonit.search.PublicActivityData;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@Transactional(readOnly = true)
@RequestMapping("/api/users/{userId}")
public class ProfileController {
  private static final String POSTS = "from posts p where p.author_id=? and p.status='PUBLISHED'";
  private static final String COMMENTS = """
      from comments c join posts p on p.id=c.post_id
      where c.author_id=? and c.status='PUBLISHED' and p.status='PUBLISHED'
      """;
  private final PublicActivityData data;

  public ProfileController(PublicActivityData data) { this.data = data; }

  private Map<String, Object> user(long id) {
    var users = data.rows("""
        select id,nickname,created_at as "createdAt" from users where id=? and status<>'WITHDRAWN'
        """, id);
    if (users.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "사용자를 찾을 수 없습니다");
    return users.getFirst();
  }

  @GetMapping
  public Map<String, Object> profile(@PathVariable long userId) {
    var result = user(userId);
    result.put("postCount", data.count("select count(*) " + POSTS, userId));
    result.put("commentCount", data.count("select count(*) " + COMMENTS, userId));
    return result;
  }

  @GetMapping("/posts")
  public Map<String, Object> posts(@PathVariable long userId,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    user(userId);
    return data.posts(POSTS, List.of(userId), page, size);
  }

  @GetMapping("/comments")
  public Map<String, Object> comments(@PathVariable long userId,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    user(userId);
    // OpenAPI: first 200 characters; the web client adds an ellipsis.
    return data.page("""
        select c.id as "commentId",left(c.body,200) as "bodyExcerpt",
        c.post_id as "postId",p.title as "postTitle",c.created_at as "createdAt"
        """,
        COMMENTS, "c.created_at desc,c.id desc", List.of(userId), page, size, row -> row);
  }
}
