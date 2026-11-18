package com.lemma.external.service;

import com.lemma.external.client.CoinGeckoClient;
import com.lemma.external.dto.MarketDataResponse;
import com.lemma.external.dto.PricePoint;
import com.lemma.external.exception.ApiException;

import java.math.BigDecimal;
import java.util.List;

/**
 * CT18 (seção 6 do desenho): assetId → CT19 (preço) → CT20 (série) → resposta.
 *
 * assetId fora de ^[a-z0-9-]{1,64}$ (sem normalizar caixa) é 404 ASSET_NOT_FOUND
 * sem chamar a CoinGecko. Ativo desconhecido no CT19 é 404 e o CT20 não é chamado
 * (poupa um crédito); 404 do CT20 também é 404. As demais falhas já chegam do
 * cliente como 502 MARKET_DATA_UNAVAILABLE, depois de logadas. ASSET_NOT_FOUND
 * não gera log.
 */
public class MarketDataService {

    static final String CURRENCY = "USD";

    private final CoinGeckoClient coinGecko;

    public MarketDataService(CoinGeckoClient coinGecko) {
        this.coinGecko = coinGecko;
    }

    public MarketDataResponse fetchMarketData(String assetId) {
        if (assetId == null || !CoinGeckoClient.ASSET_ID.matcher(assetId).matches()) {
            throw ApiException.assetNotFound();
        }
        BigDecimal currentPrice = coinGecko.simplePrice(assetId).orElseThrow(ApiException::assetNotFound);
        List<PricePoint> series = coinGecko.marketChart(assetId).orElseThrow(ApiException::assetNotFound);
        return new MarketDataResponse(assetId, CURRENCY, currentPrice, series);
    }
}
