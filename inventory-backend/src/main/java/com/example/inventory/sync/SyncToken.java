package com.example.inventory.sync;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.example.inventory.web.BadRequestException;

record SyncToken(long xactId, long seq) {

    static final SyncToken START = new SyncToken(0L, 0L);

    private static final String VERSION = "v1";

    String encode() {
        String raw = VERSION + ":" + xactId + ":" + seq;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static SyncToken decode(String token) {
        if (token == null || token.isBlank()) {
            return START;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("unexpected token layout");
            }
            long xactId = Long.parseLong(parts[1]);
            long seq = Long.parseLong(parts[2]);
            if (xactId < 0 || seq < 0) {
                throw new IllegalArgumentException("negative position");
            }
            return new SyncToken(xactId, seq);
        } catch (RuntimeException e) {
            throw new BadRequestException(
                    "The `since` token could not be read. Omit it to resynchronise "
                            + "from scratch.");
        }
    }
}
