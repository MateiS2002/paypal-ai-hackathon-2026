package com.tradedesk.desk.buying;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The café's counter, recommendation, approval and payables routes (S3), listed in contracts/desk-api.md. */
@RestController
public class BuyingController {

	private final Negotiator negotiator;

	private final OrderDesk orders;

	private final RecommendationView views;

	public BuyingController(Negotiator negotiator, OrderDesk orders, RecommendationView views) {
		this.negotiator = negotiator;
		this.orders = orders;
		this.views = views;
	}

	/** 202 when this call started the round, 200 when it had already started. */
	@PostMapping("/api/needs/{id}/counter")
	public ResponseEntity<Recommendation> counter(@PathVariable UUID id) {
		boolean started = negotiator.start(id);
		return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.OK).body(views.of(id));
	}

	@GetMapping("/api/needs/{id}/recommendation")
	public Recommendation recommendation(@PathVariable UUID id) {
		return views.of(id);
	}

	@PostMapping("/api/approvals/{id}")
	public Approval decide(@PathVariable UUID id, @RequestBody DecisionRequest request) {
		if (request == null || request.decision() == null) {
			throw new InvalidDecisionException();
		}
		return orders.approve(id, request.decision());
	}

	@GetMapping("/api/payables")
	public List<PayableRow> payables() {
		return orders.payables();
	}

	public record DecisionRequest(OrderDesk.Decision decision) {
	}

	/** A missing decision (400); an unknown one fails to parse and gets the generic 400. */
	public static class InvalidDecisionException extends RuntimeException {

		InvalidDecisionException() {
			super("decision must be APPROVE or REJECT.");
		}

	}

}
