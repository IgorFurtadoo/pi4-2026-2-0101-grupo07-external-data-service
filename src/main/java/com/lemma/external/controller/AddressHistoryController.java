package com.lemma.external.controller;

import com.lemma.external.service.AddressHistoryService;
import io.javalin.http.Context;

/**
 * CT24 fetchAddressTransactions: GET /addresses/{ethereumAddress}/transactions,
 * protegido pelo ServiceKeyFilter (a chave é conferida antes deste handler). Erros
 * saem pelo ErrorHandler.
 */
public class AddressHistoryController {

    public static final String PATH = "/addresses/{ethereumAddress}/transactions";

    private final AddressHistoryService service;

    public AddressHistoryController(AddressHistoryService service) {
        this.service = service;
    }

    public void fetchAddressTransactions(Context ctx) {
        ctx.json(service.fetchAddressTransactions(ctx.pathParam("ethereumAddress")));
    }
}
