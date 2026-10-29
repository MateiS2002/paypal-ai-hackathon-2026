package com.tradedesk.desk.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The agent endpoint's CSRF defence. Only other desks call {@code /agent/**}, server to server, and they send no
 * {@code Origin} or {@code Sec-Fetch-Site} header. Browsers send one of them on every cross-site POST, so any request
 * carrying either is refused (403) before it is read. A web page open on the same laptop therefore can't post a
 * forged RFQ to a desk's port, with or without a session. S5 adds the registry token on top.
 */
class RefuseBrowserRequests extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getHeader("Origin") != null || request.getHeader("Sec-Fetch-Site") != null) {
			response.sendError(HttpStatus.FORBIDDEN.value(), "Agent endpoints don't accept browser requests.");
			return;
		}
		chain.doFilter(request, response);
	}

}
