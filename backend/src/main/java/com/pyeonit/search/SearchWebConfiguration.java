package com.pyeonit.search;

import com.pyeonit.config.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SearchWebConfiguration implements WebMvcConfigurer {
  private final SearchRateLimiter limiter;

  public SearchWebConfiguration(
      @Value("${pyeonit.search.rate-limit.window:60s}") Duration window,
      @Value("${pyeonit.search.rate-limit.ip-requests:60}") int ipRequests,
      @Value("${pyeonit.search.rate-limit.session-requests:30}") int sessionRequests) {
    this.limiter = new SearchRateLimiter(Clock.systemUTC(), window, ipRequests, sessionRequests);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HandlerInterceptor() {
      @Override
      public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"GET".equals(request.getMethod())) return true;
        if (request.getRequestURI().equals(request.getContextPath() + "/api/universities")
            && request.getParameter("q") == null) return true;
        var session = request.getSession(false);
        long retry = limiter.acquire(request.getRemoteAddr(), session == null ? null : session.getId());
        if (retry > 0) {
          response.setHeader("Retry-After", Long.toString(retry));
          throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "검색 요청이 너무 많습니다");
        }
        return true;
      }
    }).addPathPatterns("/api/posts/search", "/api/users/search", "/api/universities");
  }
}
