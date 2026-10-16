package com.aresstack.spaceharvester.application.jobs;

class InMemoryJobStoreTest extends JobStoreContract {

    @Override
    protected JobStore newStore() {
        return new InMemoryJobStore();
    }
}
