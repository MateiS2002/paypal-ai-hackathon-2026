# S6: Elasticsearch supplier search

**Owner:** Robert · **Estimate:** 1.5 days · **Depends on:** S4

## Goal

The registry finds suppliers by meaning and by delivery area, and the buyer agent calls that search as a tool. Postgres stays the source of truth; if Elasticsearch is down, S4's Postgres search answers instead.

## Demo moment

"50 kg Brazilian beans, deliver to me" finds "Santos natural, Cerrado Mineiro" from a supplier whose listing never says "Brazilian", and leaves out a supplier that doesn't deliver to the café's address.

## In scope

- `elasticsearch` service in `docker-compose.yml`: official single-node image, security on, `ELASTIC_PASSWORD` from `.env`, heap 512 MB, health check. README memory note updated.
- Index `catalogue`: description text (BM25), `embedding` as `dense_vector` (Spring AI embedding model via OpenAI), `delivery_area` as `geo_shape`, `min_order_qty`, `stock_available`, `verified`, `simulated`.
- Reindex from Postgres on registration and catalogue change; a full reindex on registry start.
- Query: hybrid kNN + keyword, `geo_shape` filter on the café's point, filters on quantity and verified, boost by priority.
- **Fallback:** on any Elasticsearch error, log once and use the S4 Postgres query. Search results say which engine answered (useful in the demo and in tests).
- **Agent tool:** a `findSuppliers` `@Tool` on the café desk calls the registry search. Java validates the tool's arguments; results are typed records.
- Seed delivery areas as simple polygons (e.g. a state or a circle around a city) per supplier.

## Out of scope

Searching buyers in any way (hard rule). Search UI beyond the existing Network page filter.

## Contracts

`GET /api/suppliers?q=&lat=&lon=&minQty=&priority=` returns `{ "engine": "elasticsearch | postgres", "results": [{ "domain", "name", "sku", "description", "score", "simulated" }] }`. Same shape as S4. `lat` and `lon` are required (the café desk sends its configured location, `DESK_LOCATION` in compose); missing or out-of-range values return 400. `minQty` and `priority` are optional.

## Data

Elasticsearch index only; mapping kept in `registry/src/main/resources/elasticsearch/catalogue.json`. Postgres `catalogue_entry` gains `delivery_area` (GeoJSON).

## Acceptance criteria

1. The demo query returns the Cerrado listing in the top 3 with no shared keywords.
2. A supplier whose area excludes the café's point is never returned.
3. An exact SKU query ranks that SKU first (keyword wins).
4. `docker compose stop elasticsearch` and the same query still returns results with `engine: postgres`; the demo flow still runs.
5. Fresh `docker compose up` stays within the README's stated memory.
6. The `findSuppliers` tool is used in the café flow and its arguments are validated (test with a stubbed LLM sending bad arguments).

## Review-sensitive

- Whether Elasticsearch is worth its memory (about 1 GB) for the demo. The fallback keeps this slice cuttable.
- Embedding model choice and dimension.
