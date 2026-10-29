package com.tradedesk.desk.need;

import java.util.List;

/**
 * What {@code POST /api/needs/parse} answers: the buyer agent's proposal after Java has checked it.
 *
 * @param complete true only when {@code request} passes {@link PurchaseRequestRules#check} as is
 * @param request every field the text supported; the rest are null
 * @param missing fields the owner still has to fill in
 * @param question written by Java from {@code missing}, never by the LLM; null when complete
 */
public record ParseResult(boolean complete, PurchaseRequest request, List<String> missing, String question) {

}
