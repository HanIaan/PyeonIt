package com.pyeonit.community;

import static com.pyeonit.community.CommunityRequests.*;

import com.pyeonit.auth.ActiveUserGuard;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.*;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class CommunityController {
  private final CommunityService service;
  private final ActiveUserGuard guard;
  private final ImageStorage storage;
  private final JdbcTemplate db;

  public CommunityController(
      CommunityService service, ActiveUserGuard guard, ImageStorage storage, JdbcTemplate db) {
    this.service = service;
    this.guard = guard;
    this.storage = storage;
    this.db = db;
  }

  @GetMapping("/api/boards")
  public List<Map<String, Object>> listBoards() {
    return service.boards();
  }

  @GetMapping("/api/posts")
  public Map<String, Object> listPosts(
      @RequestParam(required = false) Integer boardId,
      @RequestParam(required = false) Long universityId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.posts(boardId, universityId, page, size);
  }

  @GetMapping("/api/posts/{postId}")
  public Map<String, Object> getPost(@PathVariable long postId, Authentication auth) {
    return service.get(postId, auth);
  }

  @PostMapping("/api/posts")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createPost(
      @Valid @RequestBody PostWrite request, Authentication auth) {
    guard.requireActive(auth);
    return service.write(null, request, auth);
  }

  @PutMapping("/api/posts/{postId}")
  public Map<String, Object> updatePost(
      @PathVariable long postId, @Valid @RequestBody PostWrite request, Authentication auth) {
    guard.requireActive(auth);
    return service.write(postId, request, auth);
  }

  @DeleteMapping("/api/posts/{postId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePost(@PathVariable long postId, Authentication auth) {
    service.deletePost(postId, auth);
  }

  @GetMapping("/api/posts/{postId}/comments")
  public Map<String, Object> listComments(
      @PathVariable long postId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Authentication auth) {
    return service.comments(postId, page, size, auth);
  }

  @PostMapping("/api/posts/{postId}/comments")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createComment(
      @PathVariable long postId, @Valid @RequestBody CommentCreate request, Authentication auth) {
    guard.requireActive(auth);
    return service.createComment(postId, request, auth);
  }

  @PatchMapping("/api/comments/{commentId}")
  public Map<String, Object> updateComment(
      @PathVariable long commentId,
      @Valid @RequestBody CommentUpdate request,
      Authentication auth) {
    guard.requireActive(auth);
    return service.updateComment(commentId, request.body(), auth);
  }

  @DeleteMapping("/api/comments/{commentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteComment(@PathVariable long commentId, Authentication auth) {
    service.deleteComment(commentId, auth);
  }

  @PutMapping("/api/posts/{postId}/like")
  public Map<String, Object> putLike(@PathVariable long postId, Authentication auth) {
    guard.requireActive(auth);
    return service.like(postId, true, auth);
  }

  @DeleteMapping("/api/posts/{postId}/like")
  public Map<String, Object> deleteLike(@PathVariable long postId, Authentication auth) {
    guard.requireActive(auth);
    return service.like(postId, false, auth);
  }

  @PostMapping("/api/reports")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createReport(
      @Valid @RequestBody ReportCreate request, Authentication auth) {
    guard.requireActive(auth);
    return service.report(request, auth);
  }

  @PostMapping(value = "/api/post-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> uploadPostImage(@RequestPart MultipartFile file, Authentication auth)
      throws IOException {
    guard.requireActive(auth);
    return service.upload(file, auth);
  }

  @GetMapping("/api/post-images/files/{key}")
  public ResponseEntity<Resource> image(@PathVariable String key) {
    var rows = db.queryForList("select content_type from post_images where storage_key=?", key);
    if (rows.isEmpty()) throw CommunityService.missing();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType((String) rows.getFirst().get("content_type")))
        .header("X-Content-Type-Options", "nosniff")
        .body(storage.read(key));
  }
}
