package com.corpedia.security;

import com.corpedia.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtil {

    private final JwtProperties props;
    private final SecretKey key;

    public JwtUtil(JwtProperties props) {
        this.props = props;
        this.key = buildKey(props.getSecret());
    }

    /** 构造签名密钥：JWT 要求 HMAC-SHA 至少 256 位，过短时用 SHA-256 派生，保证跨重启稳定。 */
    private static SecretKey buildKey(String secret) {
        byte[] raw = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            try {
                byte[] derived = java.security.MessageDigest.getInstance("SHA-256").digest(raw);
                return Keys.hmacShaKeyFor(derived);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 算法不可用", e);
            }
        }
        return Keys.hmacShaKeyFor(raw);
    }

    public String generate(UserContext ctx) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + props.getExpireSeconds() * 1000);
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", ctx.userId());
        claims.put("username", ctx.username());
        claims.put("deptId", ctx.deptId());
        claims.put("roleId", ctx.roleId());
        claims.put("roleCode", ctx.roleCode());
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(ctx.userId()))
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    public UserContext parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        Number uid = claims.get("uid", Number.class);
        Number deptId = claims.get("deptId", Number.class);
        Number roleId = claims.get("roleId", Number.class);
        Long dept = deptId == null ? null : deptId.longValue();
        Long role = roleId == null ? null : roleId.longValue();
        return new UserContext(uid.longValue(), claims.get("username", String.class),
                dept, role, claims.get("roleCode", String.class));
    }
}