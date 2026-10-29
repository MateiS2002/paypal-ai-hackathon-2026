package com.tradedesk.desk.supplier;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.tradedesk.desk.config.DeskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Seeds this desk's catalogue from {@code classpath:catalogue/<persona>.json} on first start (an empty table), so the
 * numbers are seeded data, not something an LLM makes up. Desks without a persona (the café) sell nothing.
 */
@Component
class CatalogueSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(CatalogueSeeder.class);

	private final CatalogueRepository catalogue;

	private final DeskProperties desk;

	private final JsonMapper json;

	CatalogueSeeder(CatalogueRepository catalogue, DeskProperties desk, JsonMapper json) {
		this.catalogue = catalogue;
		this.desk = desk;
		this.json = json;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		Persona persona = Persona.fromWire(desk.persona());
		if (persona == null || !catalogue.isEmpty()) {
			return;
		}
		List<CatalogueItem> items = load(persona);
		for (CatalogueItem item : items) {
			check(item);
			catalogue.insert(item);
		}
		log.info("Seeded {} catalogue items for persona {}", items.size(), persona.wireName());
	}

	List<CatalogueItem> load(Persona persona) throws IOException {
		try (InputStream in = new ClassPathResource("catalogue/" + persona.wireName() + ".json").getInputStream()) {
			return json.readValue(in, new TypeReference<List<CatalogueItem>>() {
			});
		}
	}

	/** A broken seed file must stop the desk, not quote below a floor. */
	static void check(CatalogueItem item) {
		boolean ok = item.sku() != null && item.description() != null && item.product() != null && item.unit() != null
				&& "USD".equals(item.currency()) && item.floorPrice() != null && item.listPrice() != null
				&& item.floorPrice().signum() > 0 && item.floorPrice().compareTo(item.listPrice()) <= 0
				&& item.stock() != null && item.stock().signum() >= 0 && item.leadDays() >= 0;
		if (!ok) {
			throw new IllegalStateException("Catalogue item " + item.sku() + " breaks the seed rules");
		}
	}

}
