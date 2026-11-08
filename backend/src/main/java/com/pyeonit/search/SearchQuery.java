package com.pyeonit.search;

import com.pyeonit.config.ApiException;
import org.springframework.http.HttpStatus;

public final class SearchQuery {
  private SearchQuery() {}

  public static String pattern(String q, int minimum) {
    if (q == null || q.isBlank() || q.codePointCount(0, q.length()) < minimum
        || q.codePointCount(0, q.length()) > 50) throw invalid();
    return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  public static void page(int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw invalid();
  }

  public static ApiException invalid() {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요");
  }
}
