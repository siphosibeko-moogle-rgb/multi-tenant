package com.example.inventory.auth;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import com.example.inventory.web.SecurityProblems;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.util.ArrayList;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/register-tenant", "/auth/login", "/auth/refresh")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // A temporary-password token may do these two things and nothing else.
                        .requestMatchers("/auth/change-password", "/auth/logout").authenticated()
                        .anyRequest().access(new WebExpressionAuthorizationManager(
                                "isAuthenticated() and !hasAuthority('" + PASSWORD_CHANGE_ONLY + "')")))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemEntryPoint())
                        .accessDeniedHandler(new SecurityProblems.Denied()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemEntryPoint())
                        .accessDeniedHandler(new SecurityProblems.Denied()));
        return http.build();
    }

    private static AuthenticationEntryPoint problemEntryPoint() {
        return new SecurityProblems.EntryPoint(new BearerTokenAuthenticationEntryPoint());
    }

    static final String PASSWORD_CHANGE_ONLY = "PASSWORD_CHANGE_ONLY";

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            String role = jwt.getClaimAsString(JwtService.ROLE_CLAIM);
            if (role != null && !role.isBlank()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            if (Boolean.TRUE.equals(jwt.getClaimAsBoolean(JwtService.PASSWORD_CHANGE_CLAIM))) {
                authorities.add(new SimpleGrantedAuthority(PASSWORD_CHANGE_ONLY));
            }
            return authorities;
        });
        return converter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    @Bean
    @Primary
    JwtDecoder jwtDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(signingKey(properties))
                .macAlgorithm(AuthProperties.SIGNING_ALGORITHM)
                .build();

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                accessTokensOnly()));

        return decoder;
    }

    @Bean
    JwtDecoder refreshTokenDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(signingKey(properties))
                .macAlgorithm(AuthProperties.SIGNING_ALGORITHM)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> accessTokensOnly() {
        OAuth2Error error = new OAuth2Error(
                "invalid_token", "A refresh token cannot be used as an access token", null);

        return jwt -> JwtService.REFRESH_TOKEN_TYPE.equals(
                jwt.getClaimAsString(JwtService.TOKEN_TYPE_CLAIM))
                ? OAuth2TokenValidatorResult.failure(error)
                : OAuth2TokenValidatorResult.success();
    }

    private static SecretKeySpec signingKey(AuthProperties properties) {
        String key = properties.signingKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "app.auth.signing-key must be set. There is deliberately no default: a "
                            + "shipped signing key is a key every deployment shares.");
        }
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < AuthProperties.MINIMUM_KEY_BYTES) {
            throw new IllegalStateException(
                    "app.auth.signing-key must be at least " + AuthProperties.MINIMUM_KEY_BYTES
                            + " bytes for " + AuthProperties.SIGNING_ALGORITHM.getName()
                            + "; forging this key means forging the tid claim, which is the whole "
                            + "of tenant isolation");
        }
        return new SecretKeySpec(bytes, AuthProperties.SIGNING_ALGORITHM.getName());
    }
}
