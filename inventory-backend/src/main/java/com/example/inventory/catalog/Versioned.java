package com.example.inventory.catalog;

record Versioned<T>(T value, long version) {

    String etag() {
        return ETags.of(version);
    }
}
