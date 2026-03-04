package com.eventplatform.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private final ResourceLoader resourceLoader;
    private final String privateKeyPath;
    private final String publicKeyPath;

    private PrivateKey privateKey;
    private PublicKey publicKey;

    public JwtTokenProvider(
        ResourceLoader resourceLoader,
        @Value("${jwt.private-key-path}") String privateKeyPath,
        @Value("${jwt.public-key-path}") String publicKeyPath
    ) {
        this.resourceLoader = resourceLoader;
        this.privateKeyPath = privateKeyPath;
        this.publicKeyPath = publicKeyPath;
    }

    @PostConstruct
    void init() {
        this.privateKey = loadPrivateKey(privateKeyPath);
        this.publicKey = loadPublicKey(publicKeyPath);
    }

    public String generateToken(Long userId, String role) {
        return generateToken(userId, role, null);
    }

    public String generateToken(Long userId, String role, Long orgId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
            .subject(String.valueOf(userId))
            .claim(SecurityConstants.TOKEN_CLAIM_ROLE, role)
            .claim(SecurityConstants.TOKEN_CLAIM_TYPE, SecurityConstants.TOKEN_TYPE_ACCESS)
            .id(UUID.randomUUID().toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(SecurityConstants.ACCESS_TOKEN_TTL_S)));
        if (orgId != null) {
            builder.claim("orgId", orgId);
        }
        return builder.signWith(privateKey, Jwts.SIG.RS256).compact();
    }

    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    public Long extractUserId(String token) {
        Claims claims = parseClaims(token);
        return Long.parseLong(claims.getSubject());
    }

    public String extractRole(String token) {
        Claims claims = parseClaims(token);
        return claims.get(SecurityConstants.TOKEN_CLAIM_ROLE, String.class);
    }

    public Long extractOrgId(String token) {
        Claims claims = parseClaims(token);
        Object raw = claims.get("orgId");
        if (raw instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();
    }

    private PrivateKey loadPrivateKey(String location) {
        byte[] keyBytes = readPemContent(location, "PRIVATE KEY");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load RSA private key", ex);
        }
    }

    private PublicKey loadPublicKey(String location) {
        byte[] keyBytes = readPemContent(location, "PUBLIC KEY");
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        try {
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load RSA public key", ex);
        }
    }

    private byte[] readPemContent(String location, String keyType) {
        Resource resource = resourceLoader.getResource(location);
        try (InputStream inputStream = resource.getInputStream()) {
            String pem = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            String sanitized = pem
                .replace("-----BEGIN " + keyType + "-----", "")
                .replace("-----END " + keyType + "-----", "")
                .replaceAll("\\s", "");
            return Base64.getDecoder().decode(sanitized);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read PEM resource: " + location, ex);
        }
    }
}
