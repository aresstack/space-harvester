package com.aresstack.spaceharvester.provingground.run;

import java.util.function.Supplier;

/**
 * What the vault holds for a test user, read only when a step needs it and never written anywhere.
 *
 * @param login     the user name to log in with ({@code {{user.login}}}), or null
 * @param password  the password ({@code {{secret.<field>}}}); asked on use, may answer null
 * @param otpSecret the one-time secret (Base32, or an {@code otpauth://} URI) for {@code {{otp()}}}; asked on use,
 *                  may answer null
 */
public record UserCredentials(String login, Supplier<String> password, Supplier<String> otpSecret) {

    public UserCredentials {
        password = password == null ? () -> null : password;
        otpSecret = otpSecret == null ? () -> null : otpSecret;
    }
}
