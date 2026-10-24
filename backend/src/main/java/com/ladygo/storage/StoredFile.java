package com.ladygo.storage;

public record StoredFile(String key, long sizeBytes, String contentType) {
}
