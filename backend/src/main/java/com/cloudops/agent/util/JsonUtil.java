package com.cloudops.agent.util;

import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thin, exception-safe wrapper around Jackson.
 *
 * MySQL has no JSONB, so tag maps and action parameters are stored as TEXT columns;
 * this class is the single place that converts between the two representations.
 */
@Component
public class JsonUtil {

    private static final Logger log = LoggerFactory.getLogger(JsonUtil.class);

    private final ObjectMapper objectMapper;

    public JsonUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("Could not serialise {} to JSON: {}", value.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }

    public Map<String, Object> toMap(String json) {
        if (!Texts.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            log.warn("Could not parse JSON into a map: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }
}
