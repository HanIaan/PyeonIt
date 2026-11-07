package com.pyeonit.community;

import jakarta.validation.constraints.*;
import java.util.List;

public final class CommunityRequests {
  private CommunityRequests() {}

  public record PostWrite(
      @NotNull Integer boardId,
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 20000) String body,
      @Size(max = 5) List<@NotNull Long> universityIds,
      @Size(max = 10) List<@NotNull Long> imageIds) {
    public PostWrite {
      universityIds = universityIds == null ? List.of() : universityIds;
      imageIds = imageIds == null ? List.of() : imageIds;
    }
  }

  public record CommentCreate(@NotBlank @Size(max = 2000) String body, Long parentId) {}

  public record CommentUpdate(@NotBlank @Size(max = 2000) String body) {}

  public enum Target {
    POST,
    COMMENT,
    USER
  }

  public enum Reason {
    SPAM,
    ABUSE,
    ADVERTISEMENT,
    FALSE_INFO,
    OTHER
  }

  public record ReportCreate(
      @NotNull Target targetType,
      @NotNull Long targetId,
      @NotNull Reason reason,
      @Size(max = 500) String detail) {}
}
