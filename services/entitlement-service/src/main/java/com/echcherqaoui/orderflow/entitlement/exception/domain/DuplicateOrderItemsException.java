package com.echcherqaoui.orderflow.entitlement.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;

import java.util.List;

import static com.echcherqaoui.orderflow.entitlement.exception.code.InventoryErrorCode.DUPLICATE_ORDER_ITEMS;

public class DuplicateOrderItemsException extends BaseCustomException {
    public DuplicateOrderItemsException(List<String> duplicateItemIds) {
        super(DUPLICATE_ORDER_ITEMS, duplicateItemIds);
    }
}