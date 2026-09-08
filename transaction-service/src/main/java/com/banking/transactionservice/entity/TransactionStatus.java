package com.banking.transactionservice.entity;


/*
Transaction lifecycle flow

PENDING -> PROCESSING -> COMPLETED (Clean transaction)
PENDING -> PROCESSING -> PENDING_VERIFICATION (suspicious transaction)
                      -> COMPLETED (verified)
                      -> FLAGGED (SAGA REFUND)

                      ->FAILED
                      ->FLAGGED
 */
public enum TransactionStatus {
    PENDING,
    PROCESSING,
    PENDING_VERIFICATION,
    COMPLETED,
    FAILED,
    FLAGGED
}
