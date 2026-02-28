package com.acme.finops.gateway.policy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class PathStringPool {
    private final ConcurrentHashMap<String, Integer> stringToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> idToString = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public int intern(String value) {
        return stringToId.computeIfAbsent(value, v -> {
            int id = nextId.getAndIncrement();
            idToString.put(id, v);
            return id;
        });
    }

    public String resolve(int id) {
        if (id <= 0) {
            return null;
        }
        return idToString.get(id);
    }
}
