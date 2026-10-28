package com.tradedesk.desk.events;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The desk's one SSE stream for its own UI. */
@RestController
public class EventsController {

	private final DeskEventStream events;

	public EventsController(DeskEventStream events) {
		this.events = events;
	}

	@GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter events() {
		return events.subscribe();
	}

}
