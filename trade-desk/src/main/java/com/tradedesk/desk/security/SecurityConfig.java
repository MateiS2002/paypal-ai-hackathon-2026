package com.tradedesk.desk.security;

import com.tradedesk.desk.config.DeskProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Users of this desk's UI: one demo login per desk, form login with a session cookie, CSRF on.
 *
 * <p>The React app shows its own login screen, so nothing here redirects: unauthenticated {@code /api/**} calls get
 * 401, login and logout answer with a status code. The SPA reads the CSRF token from {@code GET /api/csrf} and sends
 * it back in a header. Agent-to-agent calls get their own token-based chain in S5.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain deskUiSecurity(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				// Only compose health checks use this; the frontend nginx never proxies /actuator.
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/csrf").permitAll()
				// Lets a 403 (for example a missing CSRF token) reach the client as 403, not as a 401 for /error.
				.requestMatchers("/error").permitAll()
				.anyRequest().authenticated())
			// Plain (non-XOR) handler: the SPA sends the raw token it got from /api/csrf.
			.csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
			.formLogin(form -> form
				.loginPage("/api/login")
				.loginProcessingUrl("/api/login")
				.successHandler((request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value()))
				.failureHandler((request, response, exception) -> response.setStatus(HttpStatus.UNAUTHORIZED.value()))
				.permitAll())
			.logout(logout -> logout
				.logoutUrl("/api/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
			.exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.requestCache(cache -> cache.disable())
			.httpBasic(AbstractHttpConfigurer::disable);
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	UserDetailsService deskUsers(DeskProperties desk, PasswordEncoder passwordEncoder) {
		return new InMemoryUserDetailsManager(User.withUsername(desk.id())
			.password(passwordEncoder.encode(desk.demoPassword()))
			.roles("DESK_OWNER")
			.build());
	}

}
