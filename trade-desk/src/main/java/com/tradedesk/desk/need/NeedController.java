package com.tradedesk.desk.need;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The café's needs and the quotes they get. Routes are listed in contracts/desk-api.md. */
@RestController
@RequestMapping("/api/needs")
public class NeedController {

	private final NeedParser parser;

	private final NeedService needs;

	public NeedController(NeedParser parser, NeedService needs) {
		this.parser = parser;
		this.needs = needs;
	}

	/** The buyer agent's reading of the owner's text. No side effects. */
	@PostMapping("/parse")
	public ParseResult parse(@RequestBody ParseRequest request) {
		return parser.parse(request == null ? null : request.text());
	}

	/** Stores a confirmed need and sends the RFQs. 201 when created, 200 for a retry with the same key. */
	@PostMapping
	public ResponseEntity<NeedRow> post(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
			@RequestBody PurchaseRequest request) {
		NeedService.Result result = needs.post(idempotencyKey, request);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.need());
	}

	@GetMapping
	public List<NeedRow> list() {
		return needs.needs();
	}

	@GetMapping("/{id}/quotes")
	public List<QuoteRow> quotes(@PathVariable UUID id) {
		return needs.quotes(id);
	}

	public record ParseRequest(String text) {
	}

}
