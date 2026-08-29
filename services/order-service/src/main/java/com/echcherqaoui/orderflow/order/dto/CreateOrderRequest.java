package com.echcherqaoui.orderflow.order.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * userId is the email-shaped identifier (see Order.userId doc comment on the userEmail -> userId rename).
 */
public record CreateOrderRequest(@NotBlank String cartId,
                                 @NotBlank @Email String userId,
                                 @NotEmpty List<@NotBlank String> itemIds) {
}
