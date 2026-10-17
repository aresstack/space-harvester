package com.aresstack.spaceharvester.application.jobs;

class InMemoryJobEventStoreTest extends JobEventStoreContract {

    @Override
    protected JobEventStore newStore() {
        return new InMemoryJobEventStore();
    }
}
