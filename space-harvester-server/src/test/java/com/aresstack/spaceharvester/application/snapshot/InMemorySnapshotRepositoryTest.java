package com.aresstack.spaceharvester.application.snapshot;

class InMemorySnapshotRepositoryTest extends SnapshotRepositoryContract {

    @Override
    protected SnapshotRepository emptyRepository() {
        return new InMemorySnapshotRepository();
    }
}
