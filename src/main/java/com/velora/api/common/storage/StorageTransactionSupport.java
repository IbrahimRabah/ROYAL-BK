package com.velora.api.common.storage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Keeps the disk and the database agreeing when a file is written inside a transaction.
 *
 * <p>A file cannot be rolled back with the transaction, so two things are tied to its outcome:
 * <ul>
 *   <li>a file written by a transaction is deleted if that transaction does NOT commit, whether the failure
 *       is an exception in the method or the commit itself;</li>
 *   <li>a file that a committed transaction no longer references (a replaced image) is deleted only AFTER the
 *       commit, so a rollback never loses a picture the database still points at.</li>
 * </ul>
 * What it cannot cover is the process dying between writing the file and finishing the transaction: that leaves
 * an unreferenced file, which only a sweep of storage against the database can find.
 */
@Component
public class StorageTransactionSupport {

    private final StorageService storageService;

    public StorageTransactionSupport(StorageService storageService) {
        this.storageService = storageService;
    }

    public void deleteUnlessCommitted(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storageService.delete(key);
                }
            }
        });
    }

    public void deleteAfterCommit(String key) {
        if (key == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            storageService.delete(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storageService.delete(key);
            }
        });
    }
}
