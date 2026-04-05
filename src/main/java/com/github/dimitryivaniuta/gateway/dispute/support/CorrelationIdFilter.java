package com.github.dimitryivaniuta.gateway.dispute.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds a correlation id to every request, propagating it to logs and audit records.
 *
 * <p>Reads header {@code X-Correlation-Id} if present, otherwise generates a UUID.</p>
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {

    String correlationId = Optional.ofNullable(request.getHeader(HEADER))
        .filter(s -> !s.isBlank())
        .orElse(UUID.randomUUID().toString());

    MDC.put(MDC_KEY, correlationId);
    request.setAttribute(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);

    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
