package com.agenthub.orchestrator.shared.multitenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TokenExtractorUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern REALM_PATTERN = Pattern.compile("/realms/([^/]+)");

    /**
     * Extracts the tenant ID from the JWT Bearer token by parsing the realm name from the 'iss' claim.
     *
     * @param token JWT token string (without "Bearer " prefix)
     * @return tenant ID (realm name from iss claim), or null on error
     */
    public static String getTenantIdFromToken(String token) {
        try {
            JsonNode payload = parseJwtPayload(token);
            if (payload == null) {
                return null;
            }
            JsonNode issNode = payload.get("iss");
            if (issNode == null || issNode.isNull()) {
                return null;
            }
            String iss = issNode.asText();
            Matcher matcher = REALM_PATTERN.matcher(iss);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to extract tenant ID from token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the user ID from the JWT Bearer token by reading the 'sub' claim.
     *
     * @param token JWT token string (without "Bearer " prefix)
     * @return user ID (sub claim), or null on error
     */
    public static String getUserIdFromToken(String token) {
        try {
            JsonNode payload = parseJwtPayload(token);
            if (payload == null) {
                return null;
            }
            JsonNode subNode = payload.get("sub");
            if (subNode == null || subNode.isNull()) {
                return null;
            }
            return subNode.asText();
        } catch (Exception e) {
            log.debug("Failed to extract user ID from token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parses the JWT payload (second part) from a dot-separated JWT string.
     *
     * @param token JWT token string
     * @return parsed JSON payload node, or null on error
     */
    private static JsonNode parseJwtPayload(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            return OBJECT_MAPPER.readTree(payloadBytes);
        } catch (Exception e) {
            log.debug("Failed to parse JWT payload: {}", e.getMessage());
            return null;
        }
    }
}
