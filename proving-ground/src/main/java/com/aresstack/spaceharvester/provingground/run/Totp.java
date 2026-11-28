package com.aresstack.spaceharvester.provingground.run;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorConfig;
import com.warrenstrange.googleauth.HmacHashFunction;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Time-based one-time codes (RFC 6238) with the googleauth library, as WD4J's {@code TotpService} computes them
 * ({@code GoogleAuthenticator.getTotpPassword}). The secret is Base32 (googleauth's default), or an
 * {@code otpauth://totp/...} URI as authenticator apps and KeePass store it; its {@code digits}, {@code period} and
 * {@code algorithm} parameters go into googleauth's configuration. Unlike WD4J ({@code String.valueOf(int)}) the code
 * keeps its leading zeros: "012345" stays six digits, as the page expects.
 */
public final class Totp {

    /** RFC 6238 defaults (also googleauth's and every common authenticator's). */
    public static final int DEFAULT_DIGITS = 6;
    public static final int DEFAULT_PERIOD_SECONDS = 30;
    public static final String DEFAULT_ALGORITHM = "SHA1";

    private Totp() {
    }

    /** The code valid at {@code now}. */
    public static String code(String secretOrUri, Instant now) {
        String secret = secretOrUri.trim();
        int digits = DEFAULT_DIGITS;
        int period = DEFAULT_PERIOD_SECONDS;
        String algorithm = DEFAULT_ALGORITHM;
        if (secret.toLowerCase(Locale.ROOT).startsWith("otpauth://")) {
            String query = URI.create(secret).getRawQuery();
            secret = "";
            for (String pair : query == null ? new String[0] : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String key = pair.substring(0, equals).toLowerCase(Locale.ROOT);
                String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                switch (key) {
                    case "secret" -> secret = value;
                    case "digits" -> digits = Integer.parseInt(value);
                    case "period" -> period = Integer.parseInt(value);
                    case "algorithm" -> algorithm = value.toUpperCase(Locale.ROOT);
                    default -> { }
                }
            }
        }
        String clean = secret.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.ROOT);
        if (clean.isEmpty() || !clean.matches("[A-Z2-7]+")) {
            throw new IllegalArgumentException("OTP-Geheimnis ist kein Base32");
        }
        GoogleAuthenticator authenticator = new GoogleAuthenticator(new GoogleAuthenticatorConfig
                .GoogleAuthenticatorConfigBuilder()
                .setCodeDigits(digits)
                .setTimeStepSizeInMillis(TimeUnit.SECONDS.toMillis(period))
                .setHmacHashFunction(function(algorithm))
                .build());
        int code = authenticator.getTotpPassword(clean, now.toEpochMilli());
        return String.format("%0" + digits + "d", code);
    }

    private static HmacHashFunction function(String algorithm) {
        return switch (algorithm) {
            case "SHA1" -> HmacHashFunction.HmacSHA1;
            case "SHA256" -> HmacHashFunction.HmacSHA256;
            case "SHA512" -> HmacHashFunction.HmacSHA512;
            default -> throw new IllegalArgumentException("OTP-Verfahren nicht unterstützt: " + algorithm);
        };
    }
}
