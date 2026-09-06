package com.paicli.change;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

class ChangePersistenceContractTest {
    @TempDir Path root;

    @Test
    void sqliteSatisfiesSharedPersistenceContract() throws Exception {
        try (SqliteChangeStore store = new SqliteChangeStore(root.resolve("contract.db"))) {
            ChangePersistenceContract.exercise(store, root,
                    UUID.randomUUID().toString().replace("-", ""));
        }
    }
}
