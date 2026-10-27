package com.velora.api.catalog.domain;

/**
 * What a category is for. The two scopes never mix: a child has its parent's scope, a product sits
 * in a PRODUCT category, a portfolio item in a PORTFOLIO one. Only PRODUCT categories reach the
 * shop navigation and the product filters.
 */
public enum CategoryScope {
    PRODUCT,
    PORTFOLIO
}
