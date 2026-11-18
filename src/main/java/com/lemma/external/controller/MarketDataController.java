package com.lemma.external.controller;

import com.lemma.external.service.MarketDataService;
import io.javalin.http.Context;

/**
 * CT18 fetchMarketData: GET /market-data/{assetId}, protegido pelo ServiceKeyFilter
 * (a chave é conferida antes deste handler). Erros saem pelo ErrorHandler.
 */
public class MarketDataController {

    public static final String PATH = "/market-data/{assetId}";

    private final MarketDataService service;

    public MarketDataController(MarketDataService service) {
        this.service = service;
    }

    public void fetchMarketData(Context ctx) {
        ctx.json(service.fetchMarketData(ctx.pathParam("assetId")));
    }
}
