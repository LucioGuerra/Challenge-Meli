package com.meli.challenge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health/**").permitAll()
                .requestMatchers("/metrics").hasRole("MONITORING")
                .requestMatchers("/api/v1/**").hasRole("API_USER")
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(
            @Value("${api.security.username}") String username,
            @Value("${api.security.password}") String password,
            @Value("${api.security.monitoring-username}") String monitoringUsername,
            @Value("${api.security.monitoring-password}") String monitoringPassword,
            PasswordEncoder passwordEncoder) {
        UserDetails apiUser = User.builder()
            .username(username)
            .password(passwordEncoder.encode(password))
            .roles("API_USER")
            .build();
        UserDetails prometheusUser = User.builder()
            .username(monitoringUsername)
            .password(passwordEncoder.encode(monitoringPassword))
            .roles("MONITORING")
            .build();
        return new InMemoryUserDetailsManager(apiUser, prometheusUser);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
