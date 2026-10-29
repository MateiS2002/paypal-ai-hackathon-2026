package com.tradedesk.desk.agent;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.tradedesk.desk.config.DeskProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The suppliers this desk sends RFQs to, from {@code DESK_PEERS} ({@code domain=base URL,...}). A static list until
 * S4 replaces it with a registry lookup. A malformed entry stops the desk at startup rather than being skipped.
 */
@Component
public class PeerDirectory {

	private final List<Peer> peers;

	public PeerDirectory(@Value("${desk.peers:}") String peers, DeskProperties desk) {
		this.peers = parse(peers, desk.domain());
	}

	public List<Peer> suppliers() {
		return peers;
	}

	static List<Peer> parse(String value, String ownDomain) {
		List<Peer> parsed = new ArrayList<>();
		if (value == null || value.isBlank()) {
			return List.of();
		}
		for (String entry : value.split(",")) {
			String trimmed = entry.strip();
			if (trimmed.isEmpty()) {
				continue;
			}
			int eq = trimmed.indexOf('=');
			if (eq <= 0) {
				throw new IllegalArgumentException("DESK_PEERS entry '" + trimmed + "' must be domain=base URL");
			}
			String domain = trimmed.substring(0, eq).strip();
			URI url = URI.create(trimmed.substring(eq + 1).strip().replaceAll("/+$", ""));
			if (!AgentInbox.DOMAIN.matcher(domain).matches()) {
				throw new IllegalArgumentException("DESK_PEERS domain '" + domain + "' is not a domain");
			}
			if (!"http".equals(url.getScheme()) && !"https".equals(url.getScheme()) || url.getHost() == null) {
				throw new IllegalArgumentException("DESK_PEERS URL for " + domain + " must be http(s)://host[:port]");
			}
			if (!domain.equals(ownDomain) && parsed.stream().noneMatch(peer -> peer.domain().equals(domain))) {
				parsed.add(new Peer(domain, url));
			}
		}
		return List.copyOf(parsed);
	}

	/**
	 * @param domain goes in the envelope's {@code to}, and the reply's {@code from} must match it
	 * @param baseUrl where {@code POST /agent/messages} is sent inside compose
	 */
	public record Peer(String domain, URI baseUrl) {
	}

}
