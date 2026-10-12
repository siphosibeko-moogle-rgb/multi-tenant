package com.example.inventory.catalog;

import com.example.inventory.web.PreconditionFailedException;

final class ETags {

    private ETags() {
    }

    static String of(long version) {
        return "\"" + version + "\"";
    }

    static Long parse(String ifMatch) {
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "").trim();

        if ("*".equals(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new PreconditionFailedException("If-Match is not an ETag this server issued");
        }
    }
}
