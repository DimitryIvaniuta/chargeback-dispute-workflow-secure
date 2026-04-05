package com.github.dimitryivaniuta.gateway.dispute.config;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration using JWT Resource Server.
 *
 * <p>Tokens must include:</p>
 * <ul>
 *   <li>{@code sub} - user identifier</li>
 *   <li>{@code team} - assigned team (e.g. CHARGEBACK)</li>
 *   <li>{@code roles} - list of role strings (e.g. DISPUTE_VIEW, DISPUTE_EDIT, DISPUTE_ADMIN)</li>
 * </ul>
 *
 * <p>NOTE: For simplicity this project uses an HMAC secret for local/demo. In production,
 * prefer asymmetric keys and an external IdP (OIDC).</p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable());

    http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/actuator/**").permitAll()
        .requestMatchers("/api/auth/**").permitAll() // local/demo token issuer
        .anyRequest().authenticated()
    );

    http.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  @Bean
  JwtDecoder jwtDecoder(@Value("${app.security.jwt.secret}") String base64Secret) {
    byte[] keyBytes = java.util.Base64.getDecoder().decode(base64Secret);
    SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
    return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  }

  @Bean
  JwtEncoder jwtEncoder(@Value("${app.security.jwt.secret}") String base64Secret) {
    byte[] keyBytes = java.util.Base64.getDecoder().decode(base64Secret);
    SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  Converter<Jwt, ? extends org.springframework.security.core.Authentication> jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new RolesAndTeamConverter());
    return converter;
  }

  /**
   * Maps {@code roles[]} and {@code team} claims to {@link GrantedAuthority}.
   */
  static final class RolesAndTeamConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
      List<String> roles = jwt.getClaimAsStringList("roles");
      String team = jwt.getClaimAsString("team");

      java.util.ArrayList<GrantedAuthority> authorities = new java.util.ArrayList<>();
      if (roles != null) {
        for (String r : roles) {
          if (r != null && !r.isBlank()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + r.trim()));
          }
        }
      }
      if (team != null && !team.isBlank()) {
        authorities.add(new SimpleGrantedAuthority("TEAM_" + team.trim()));
      }
      return authorities;
    }
  }
}
