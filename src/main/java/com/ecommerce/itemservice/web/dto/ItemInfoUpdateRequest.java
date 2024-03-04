package com.ecommerce.itemservice.web.dto;

import lombok.Data;

@Data
public class ItemInfoUpdateRequest {

    private Long itemId;
    private String name;
    private Long price;
    private Integer discountPercentage;
    private Long additionalQuantity;

    private Long accountId;
}