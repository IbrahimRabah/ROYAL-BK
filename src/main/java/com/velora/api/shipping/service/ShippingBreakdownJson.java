package com.velora.api.shipping.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.api.shipping.dto.ShippingBreakdownLine;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The shipping breakdown as stored on the order ({@code customer_order.shipping_breakdown}).
 *
 * <p>Read side is forgiving: orders placed before the breakdown existed have a null
 * column, and a row that cannot be parsed must not make the whole order unreadable —
 * the final {@code shipping_cost} figure is stored separately and is still correct.
 */
@Component
public class ShippingBreakdownJson {

    private static final Logger log = LoggerFactory.getLogger(ShippingBreakdownJson.class);
    private static final TypeReference<List<ShippingBreakdownLine>> LINES = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public ShippingBreakdownJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(List<ShippingBreakdownLine> breakdown) {
        try {
            return objectMapper.writeValueAsString(breakdown);
        } catch (JsonProcessingException e) {
            // Cannot happen for this record, and an order must not fail over a snapshot.
            log.error("Could not serialize shipping breakdown", e);
            return null;
        }
    }

    /** Null in, null out: "no breakdown recorded" is different from "empty breakdown". */
    public List<ShippingBreakdownLine> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, LINES);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable shipping breakdown on an order: {}", e.getMessage());
            return null;
        }
    }
}
