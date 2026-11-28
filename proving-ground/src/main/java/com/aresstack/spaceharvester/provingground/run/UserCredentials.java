package com.aresstack.spaceharvester.provingground.run;

import java.util.function.Supplier;

/**
 * What the vault holds for a test user, read only when a step needs it and never written anywhere.
 *
 * @param login     the user name to log in with ({@code {{user.login}}}), or null
 * @param password  the password ({@code {{secret.<field>}}} when the entry has no field of that name); asked on use,
 *                  may answer null
 * @param otpSecret the one-time secret (Base32, or an {@code otpauth://} URI) for {@code {{otp()}}}; asked on use,
 *                  may answer null
 * @param fields    further fields of the entry by name, for more passwords on one page ({@code {{secret.PIN}}} = the
 *                  field "PIN"); asked on use, answers null for a field the entry does not have
 */
public record UserCredentials(String login, Supplier<String> password, Supplier<String> otpSecret,
                              java.util.function.Function<String, String> fields) {

    public UserCredentials {
        password = password == null ? () -> null : password;
        otpSecret = otpSecret == null ? () -> null : otpSecret;
        fields = fields == null ? name -> null : fields;
    }

    public UserCredentials(String login, Supplier<String> password, Supplier<String> otpSecret) {
        this(login, password, otpSecret, null);
    }

    /** {@code {{secret.<field>}}}: the entry's field of that name, otherwise its password. */
    public String secret(String field) {
        String own = field == null || field.isBlank() ? null : fields.apply(field);
        return own != null ? own : password.get();
    }
}
