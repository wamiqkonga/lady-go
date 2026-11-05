package com.ladygo.admin.web.dto;

import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * Money is whole paise (ADR-007). Jackson's default would silently truncate {@code 12.5} to {@code 12} and accept the
 * string {@code "12"}; for a fare that is a wrong price reaching riders, so only a JSON integer is accepted here.
 */
public class WholePaiseDeserializer extends JsonDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
        return parser.getLongValue();
    }
}
