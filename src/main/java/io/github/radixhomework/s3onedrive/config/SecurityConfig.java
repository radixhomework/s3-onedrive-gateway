package io.github.radixhomework.s3onedrive.config;

import io.github.radixhomework.s3onedrive.auth.AwsSigV4AuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AwsSigV4AuthFilter sigV4AuthFilter;

    public SecurityConfig(AwsSigV4AuthFilter sigV4AuthFilter) {
        this.sigV4AuthFilter = sigV4AuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Health-check endpoints (unauthenticated)
                .requestMatchers("/health", "/actuator/health").permitAll()
                // All S3 paths require SigV4 authentication
                .anyRequest().authenticated()
            )
            .addFilterBefore(sigV4AuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
