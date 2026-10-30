package com.tradedesk.desk.agent;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Posts an envelope to another desk's {@code /agent/messages} and returns whatever came back, as text. The reply is
 * untrusted: callers parse and check it. TODO S5: attach a registry token scoped to the receiver.
 */
@Component
public class AgentClient {

	/** Long enough for the slowest SIMULATED persona plus its LLM note, short enough to fill the grid in ~10 s. */
	static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

	/** An accept waits for the supplier to create and send a PayPal invoice, which takes a few sandbox calls. */
	static final Duration SLOW_READ_TIMEOUT = Duration.ofSeconds(30);

	private final RestClient http;

	private final RestClient slowHttp;

	public AgentClient() {
		this.http = client(READ_TIMEOUT);
		this.slowHttp = client(SLOW_READ_TIMEOUT);
	}

	private static RestClient client(Duration readTimeout) {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
		requestFactory.setReadTimeout(readTimeout);
		return RestClient.builder().requestFactory(requestFactory).build();
	}

	/** Never throws for a bad peer: a timeout, refusal or error status comes back as a failed {@link Reply}. */
	public Reply send(PeerDirectory.Peer peer, String envelope) {
		return send(http, peer, envelope);
	}

	/** {@link #send} with {@link #SLOW_READ_TIMEOUT}, for messages whose reply waits on PayPal ({@code accept}). */
	public Reply sendSlow(PeerDirectory.Peer peer, String envelope) {
		return send(slowHttp, peer, envelope);
	}

	private static Reply send(RestClient http, PeerDirectory.Peer peer, String envelope) {
		try {
			return http.post()
				.uri(peer.baseUrl().resolve(peer.baseUrl().getPath() + "/agent/messages"))
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(envelope)
				.exchange((request, response) -> {
					byte[] body = response.getBody().readNBytes(ConversationLog.MAX_RAW);
					return new Reply(response.getStatusCode().value(), new String(body,
							java.nio.charset.StandardCharsets.UTF_8), null);
				});
		}
		catch (RestClientException | IllegalArgumentException ex) {
			return new Reply(0, null, ex.getClass().getSimpleName());
		}
	}

	/**
	 * @param status the HTTP status, or 0 if the peer couldn't be reached
	 * @param body the reply text, or null
	 * @param failure why the call failed, or null
	 */
	public record Reply(int status, String body, String failure) {

		public boolean ok() {
			return status == 200 && body != null;
		}

	}

}
