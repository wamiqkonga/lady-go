package com.ladygo.admin.web.dto;

/** A minimal, PII-minimised reference to a customer or driver shown anywhere in admin ride/flag
 *  oversight (docs/product/admin-web-design.md section 4) - a name to tell people apart in a list and
 *  a masked phone (last 4 digits only), never a full phone number, never a live location. */
public record AdminPersonRefResponse(String name, String phoneMasked) {
}
