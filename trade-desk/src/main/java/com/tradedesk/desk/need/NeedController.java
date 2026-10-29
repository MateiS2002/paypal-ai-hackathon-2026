package com.tradedesk.desk.need;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The café's needs and the quotes they get. Routes are listed in contracts/desk-api.md. */
@RestController
@RequestMapping("/api/needs")
public class NeedController {

	private final NeedParser parser;

	public NeedController(NeedParser parser) {
		this.parser = parser;
	}

	/** The buyer agent's reading of the owner's text. No side effects. */
	@PostMapping("/parse")
	public ParseResult parse(@RequestBody ParseRequest request) {
		return parser.parse(request == null ? null : request.text());
	}

	public record ParseRequest(String text) {
	}

}
