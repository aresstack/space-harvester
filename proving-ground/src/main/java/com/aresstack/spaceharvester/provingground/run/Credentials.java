package com.aresstack.spaceharvester.provingground.run;

import com.aresstack.spaceharvester.provingground.model.TestUser;

/**
 * Where the access data of test users comes from: the application answers from KeePass (the user's vault entry).
 * Tests and the test project never hold it.
 */
@FunctionalInterface
public interface Credentials {

    /** No vault: users have no access data. */
    Credentials NONE = user -> null;

    /** The access data of {@code user}, or null when the vault has none. */
    UserCredentials of(TestUser user);
}
