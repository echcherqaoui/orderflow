package com.echcherqaoui.orderflow.inventory.exception.code;

import com.echcherqaoui.orderflow.exception.core.IErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum InventoryErrorCode implements IErrorCode {
    EMPTY_ITEM_LIST("EMPTY_LIST_400", "Requested item set cannot be empty.", 400),
    ITEMS_NOT_FOUND("ITEM_404", "One or more requested items do not exist.", 404),
    ITEMS_OUT_OF_STOCK("ITEMS_409", "One or more requested items are out of stock.", 409),
    ITEM_ALREADY_EXISTS("ITEM_409_EXISTS", "Item with name %s already exists", 422),
    CONCURRENT_RESERVATION_ATTEMPT("RESERVATION_409", "A reservation for this cart is already being processed.", 409),;

    private final String code;
    private final String message;
    private final int httpStatus;
}