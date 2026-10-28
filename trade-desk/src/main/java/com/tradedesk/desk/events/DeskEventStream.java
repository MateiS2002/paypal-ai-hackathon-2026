package com.tradedesk.desk.events;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.tradedesk.desk.config.DeskProperties;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Fans desk events out to every open {@code GET /api/events} stream. Slices call {@link #publish(DeskEvent)}. */
@Component
public class DeskEventStream {

	private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

	private final DeskProperties desk;

	public DeskEventStream(DeskProperties desk) {
		this.desk = desk;
	}

	SseEmitter subscribe() {
		// No server-side timeout; the heartbeat keeps proxies from closing idle streams.
		SseEmitter emitter = new SseEmitter(0L);
		emitters.add(emitter);
		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(error -> emitters.remove(emitter));
		send(emitter, new DeskEvent("desk.connected", Map.of("deskId", desk.id())));
		return emitter;
	}

	public void publish(DeskEvent event) {
		emitters.forEach(emitter -> send(emitter, event));
	}

	@Scheduled(fixedRate = 25_000)
	void heartbeat() {
		emitters.forEach(emitter -> {
			try {
				emitter.send(SseEmitter.event().comment("heartbeat"));
			}
			catch (IOException | IllegalStateException ex) {
				emitters.remove(emitter);
			}
		});
	}

	private void send(SseEmitter emitter, DeskEvent event) {
		try {
			emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
		}
		catch (IOException | IllegalStateException ex) {
			emitters.remove(emitter);
		}
	}

}
