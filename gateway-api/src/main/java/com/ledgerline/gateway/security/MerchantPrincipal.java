package com.ledgerline.gateway.security;

/** The authenticated merchant behind an API key. Controllers get it with {@code @AuthenticationPrincipal}. */
public record MerchantPrincipal(long merchantId, String name) {
}
