package com.tradedesk.desk.agent;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The desk's agent endpoint: other desks post envelopes here and get the reply in the response
 * (contracts/agent-messages.md). The body is taken as text so the exact bytes received can be stored.
 */
@RestController
public class AgentController {

	private final AgentInbox inbox;

	public AgentController(AgentInbox inbox) {
		this.inbox = inbox;
	}

	// TODO S5: require a scoped token (aud = this desk, scope for the envelope type: rfq:send, quote:counter,
	// quote:accept, invoice:notify) and a signed envelope whose from matches the token. Until then anyone who can
	// reach the desk can call this unauthenticated: other containers on the compose network, and any local process via
	// the desk's 127.0.0.1 port (8081 to 8085). Browsers are refused (RefuseBrowserRequests); the frontend's nginx never
	// proxies /agent.
	@PostMapping(path = "/agent/messages", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public String receive(@RequestBody String envelope) {
		return inbox.receive(envelope);
	}

}
