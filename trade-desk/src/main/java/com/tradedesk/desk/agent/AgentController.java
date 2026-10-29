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

	// TODO S5: require scoped token (rfq:send, aud = this desk) and a matching envelope from. Until then this is only
	// reachable inside the compose network; the frontend's nginx never proxies /agent.
	@PostMapping(path = "/agent/messages", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public String receive(@RequestBody String envelope) {
		return inbox.receive(envelope);
	}

}
