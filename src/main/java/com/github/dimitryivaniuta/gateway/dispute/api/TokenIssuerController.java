package com.github.dimitryivaniuta.gateway.dispute.api;

import com.github.dimitryivaniuta.gateway.dispute.domain.CaseTeam;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.*;

/**
 * Demo-only endpoint to issue JWTs for local testing (e.g., Postman).
 *
 * <p>In production, tokens must be issued by your Identity Provider (OIDC).
 * Disable this endpoint via {@code app.security.token-issuer.enabled=false}.</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class TokenIssuerController {

  private final JwtEncoder jwtEncoder;

  @Value("${app.security.token-issuer.enabled:true}")
  private boolean enabled;

  @PostMapping("/token")
  public Dtos.IssueTokenResponse issue(@Valid @RequestBody Dtos.IssueTokenRequest req) {
    if (!enabled) {
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    Instant now = Instant.now();
    HashMap<String, Object> claims = new HashMap<>();
    claims.put("team", req.team().name());
    claims.put("roles", req.roles());

    JwtClaimsSet set = JwtClaimsSet.builder()
        .issuer("local-demo")
        .issuedAt(now)
        .expiresAt(now.plus(60, ChronoUnit.MINUTES))
        .subject(req.subject())
        .claims(c -> c.putAll(claims))
        .build();

    String token = jwtEncoder.encode(JwtEncoderParameters.from(set)).getTokenValue();
    return new Dtos.IssueTokenResponse(token);
  }
}
