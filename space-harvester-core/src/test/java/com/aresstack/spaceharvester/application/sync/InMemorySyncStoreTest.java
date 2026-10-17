package com.aresstack.spaceharvester.application.sync;

class InMemorySyncStoreTest extends SyncBaselineStoreContract {

    @Override
    protected Fixture empty() {
        InMemorySyncStore store = new InMemorySyncStore();
        return new Fixture(store, store.repository);
    }
}
