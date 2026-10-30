package com.tradedesk.desk.agent;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.tradedesk.desk.config.DeskProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The suppliers this desk sends RFQs to, from {@code DESK_PEERS} ({@code domain=base URL,...}), and the buyers a
 * supplier desk may send {@code invoice.status} to, from {@code DESK_BUYERS} (S3). Static lists until S4 replaces them
 * with a registry lookup. A malformed entry stops the desk at startup rather than being skipped.
 */
@Component
public class PeerDirectory {

	private final List<Peer> peers;

	private final List<Peer> buyers;

	public PeerDirectory(@Value("${desk.peers:}") String peers, @Value("${desk.buyers:}") String buyers,
			DeskProperties desk) {
		this.peers = parse(peers, desk.domain());
		this.buyers = parse(buyers, desk.domain());
	}

	public List<Peer> suppliers() {
		return peers;
	}

	/** A supplier this desk sent an RFQ to, by the domain in its envelopes. */
	public Optional<Peer> supplier(String domain) {
		return peers.stream().filter(peer -> peer.domain().equals(domain)).findFirst();
	}

	/** False on a desk with no {@code DESK_BUYERS} (the café): it has nobody to send {@code invoice.status} to. */
	public boolean hasBuyers() {
		return !buyers.isEmpty();
	}

	/**
	 * A buyer this supplier desk may notify. Only listed buyers: an address is never taken from a message, so a forged
	 * {@code from} can't make this desk call out to an arbitrary host.
	 */
	public Optional<Peer> buyer(String domain) {
		return buyers.stream().filter(peer -> peer.domain().equals(domain)).findFirst();
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
				throw new IllegalArgumentException("DESK_PEERS or DESK_BUYERS entry '" + trimmed + "' must be domain=base URL");
			}
			String domain = trimmed.substring(0, eq).strip();
			URI url = URI.create(trimmed.substring(eq + 1).strip().replaceAll("/+$", ""));
			if (!AgentInbox.DOMAIN.matcher(domain).matches()) {
				throw new IllegalArgumentException("DESK_PEERS or DESK_BUYERS domain '" + domain + "' is not a domain");
			}
			if (!"http".equals(url.getScheme()) && !"https".equals(url.getScheme()) || url.getHost() == null) {
				throw new IllegalArgumentException("DESK_PEERS or DESK_BUYERS URL for " + domain + " must be http(s)://host[:port]");
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
