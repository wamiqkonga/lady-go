package com.ladygo.support.service;

import com.ladygo.config.ConfigurationService;
import com.ladygo.support.web.dto.SupportContactResponse;
import org.springframework.stereotype.Service;

/** Reads the support contact numbers from admin config; blank or unset means "not configured" (null). */
@Service
public class SupportContactService {

    public static final String PHONE_KEY = "support.phone";
    public static final String WHATSAPP_KEY = "support.whatsapp";

    private final ConfigurationService config;

    public SupportContactService(ConfigurationService config) {
        this.config = config;
    }

    public SupportContactResponse contact() {
        return new SupportContactResponse(
                config.findString(PHONE_KEY).orElse(null), config.findString(WHATSAPP_KEY).orElse(null));
    }
}
