package com.tradedesk.desk.buying;

import java.util.UUID;

import com.tradedesk.desk.events.DeskEvent;
import com.tradedesk.desk.events.DeskEventStream;
import com.tradedesk.desk.need.NeedNotFoundException;
import com.tradedesk.desk.need.NeedRepository;
import com.tradedesk.desk.need.NeedRow;
import com.tradedesk.desk.need.NeedService;
import com.tradedesk.desk.need.QuoteRow;

import org.springframework.stereotype.Component;

/** Builds a need's {@link Recommendation} from what is stored, and publishes it as {@code recommendation.updated}. */
@Component
public class RecommendationView {

	private final NeedRepository needs;

	private final NeedService needService;

	private final NegotiationRepository negotiations;

	private final DeskEventStream events;

	public RecommendationView(NeedRepository needs, NeedService needService, NegotiationRepository negotiations,
			DeskEventStream events) {
		this.needs = needs;
		this.needService = needService;
		this.negotiations = negotiations;
		this.events = events;
	}

	/** @throws NeedNotFoundException for an unknown need */
	public Recommendation of(UUID needId) {
		NeedRow need = needs.find(needId).orElseThrow(NeedNotFoundException::new);
		NegotiationRepository.Negotiation negotiation = negotiations.find(needId).orElse(null);
		if (negotiation == null) {
			Recommendation.Stage stage = need.status() == NeedRow.Status.COLLECTING
					? Recommendation.Stage.WAITING_FOR_QUOTES : Recommendation.Stage.READY_TO_COUNTER;
			return new Recommendation(needId, stage, null, null, null, null);
		}
		return switch (negotiation.stage()) {
			case COUNTERING -> new Recommendation(needId, Recommendation.Stage.COUNTERING, null, null, null, null);
			case NO_OFFER -> new Recommendation(needId, Recommendation.Stage.NO_OFFER, null, null, null, null);
			case READY -> {
				QuoteRow pick = needService.quotes(needId)
					.stream()
					.filter(q -> q.id().equals(negotiation.pickQuoteId()))
					.findFirst()
					.orElse(null);
				yield new Recommendation(needId, Recommendation.Stage.READY, pick, negotiation.reason(),
						negotiation.reasonSource(), negotiations.approvalForNeed(needId).orElse(null));
			}
		};
	}

	public void publish(UUID needId) {
		events.publish(new DeskEvent("recommendation.updated", of(needId)));
	}

}
