package com.tradedesk.desk.api;

import java.security.Principal;

import com.tradedesk.desk.config.DeskProperties;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Login support for the React app. Routes are listed in contracts/desk-api.md. */
@RestController
@RequestMapping("/api")
public class SessionController {

	private final DeskProperties desk;

	public SessionController(DeskProperties desk) {
		this.desk = desk;
	}

	/** The CSRF token for this session. Call it before login and again after, because login rotates it. */
	@GetMapping("/csrf")
	public CsrfResponse csrf(CsrfToken token) {
		return new CsrfResponse(token.getHeaderName(), token.getToken());
	}

	/** The logged-in desk user. 401 before login. */
	@GetMapping("/me")
	public MeResponse me(Principal principal) {
		return new MeResponse(principal.getName(), desk.id(), desk.name(), desk.domain(), desk.simulated());
	}

	public record CsrfResponse(String headerName, String token) {
	}

	public record MeResponse(String username, String deskId, String deskName, String domain, boolean simulated) {
	}

}
