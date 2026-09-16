package com.banking.transactionservice.service;

import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.transactionservice.event.TransactionCompletedEvent;
import com.banking.transactionservice.event.TransactionInitiatedEvent;
import com.banking.transactionservice.repository.TransactionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {
    private static final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";
    private static final String TRANSACTION_COMPLETED_TOPIC = "transaction.completed";
    private static final String TRANSACTION_REFUNDED_TOPIC = "transaction.refund";
    private static final String FRAUD_DETECTED_TOPIC = "fraud.detected";
    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    /*

    SAGA STEP 1 Initiate transfer
    Deducts for sender via feign
    save transaction as PROCESSING
    publish event to kafka for fraud check
    returns.

     */
    public TransactionResponse transfer(@Valid TransferRequest request) {
        log.info("====================================================");
        log.info("🚀 TRANSACTION SERVICE - SAGA START");
        log.info("Transaction Type: TRANSFER");
        log.info("Sender: {}", request.getSenderAccountNumber());
        log.info("Receiver: {}", request.getReceiverAccountNumber());
        log.info("Amount: {}", request.getAmount());
        log.info("Description: {}", request.getDescription());
        log.info("====================================================");

        //SAGA step 1: deduct from sender
        log.info("📤 SAGA STEP 1: Calling Account Service to deduct balance");
        try {
            accountServiceClient.deductBalance(request.getSenderAccountNumber(), request.getAmount());
            log.info("✅ SAGA STEP 1 COMPLETED: Balance deducted from sender");
        } catch (Exception e) {
            log.error("❌ SAGA STEP 1 FAILED: Could not deduct balance - {}", e.getMessage());
            throw e;
        }

        Transaction transaction = new Transaction();
        transaction.setReceiverAccountNumber(request.getReceiverAccountNumber());
        transaction.setSenderAccountNumber(request.getSenderAccountNumber());
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setAmount(request.getAmount());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setDescription(request.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("💾 Transaction saved with status PROCESSING");
        log.info("Transaction ID: {}", savedTransaction.getId());
        log.info("Reference Number: {}", savedTransaction.getReferenceNumber());


        //SAGA step 2 publish for fraud check
        log.info("📤 SAGA STEP 2: Publishing transaction.initiated event to Kafka");
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                savedTransaction.getId(),
                savedTransaction.getSenderAccountNumber(),
                savedTransaction.getReceiverAccountNumber(),
                savedTransaction.getAmount(),
                savedTransaction.getDescription()
        );
        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC, savedTransaction.getId(), event);
        log.info("✅ SAGA STEP 2 COMPLETED: Event published to topic '{}'", TRANSACTION_INITIATED_TOPIC);
        log.info("====================================================");

        return mapToResponse(savedTransaction);
    }

    private TransactionResponse mapToResponse(Transaction transaction) {
        TransactionResponse response = new TransactionResponse();
        response.setId(transaction.getId());
        response.setSenderAccountNumber(
                transaction.getSenderAccountNumber());
        response.setReceiverAccountNumber(
                transaction.getReceiverAccountNumber());
        response.setAmount(transaction.getAmount());
        response.setType(transaction.getType());
        response.setStatus(transaction.getStatus());
        response.setDescription(transaction.getDescription());
        response.setReferenceNumber(transaction.getReferenceNumber());
        response.setFailureReason(transaction.getFailureReason());
        response.setCreatedAt(transaction.getCreatedAt());
        response.setCompletedAt(transaction.getCompletedAt());

        return response;
    }

    public TransactionResponse getTransaction(String transactionId) {
        return mapToResponse(transactionRepository.findById(transactionId).orElseThrow(() -> new RuntimeException("Transaction not found" + transactionId)));
    }

    public @Nullable List<TransactionResponse> getTransactionHistory(String accountNumber) {
        return transactionRepository.findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public TransactionResponse verifyOTP(String transactionId, String otp) {
        log.info("====================================================");
        log.info("🔐 TRANSACTION SERVICE - OTP VERIFICATION");
        log.info("Transaction ID: {}", transactionId);
        log.info("====================================================");

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found" + transactionId));
        String temp=otp;
        System.out.println(temp);
        String otpKey = "verification:otp" + transactionId;
        String storedOtp = redisTemplate.opsForValue().get(otpKey);

        if (storedOtp == null) {
            //OTP EXPIRED
            log.warn("❌ OTP EXPIRED for transaction: {}", transactionId);
            log.warn("🔄 Initiating SAGA COMPENSATION");
            compensateTransaction(transaction, "OTP Expired - transaction cancelled and amount refunded");
            return mapToResponse(transaction);
        }

        if (!storedOtp.equals(otp)) {
            //BLOCK ACCOUNT AND REFUND WHEN WRONG OTP ENTERED
            log.warn("====================================================");
            log.warn("❌ WRONG OTP ENTERED - Transaction: {}", transactionId);
            log.warn("🚨 Blocking account and initiating SAGA COMPENSATION");
            log.warn("====================================================");
            redisTemplate.delete(otpKey);
            blockAccountAndCompensate(transaction, "Wrong OTP - transaction cancelled, " + "account blocked");
            return mapToResponse(transaction);

        }

        // OTP CORRECT - complete transaction
        log.info("✅ OTP VERIFIED SUCCESSFULLY");
        log.info("📤 Proceeding to complete transaction: {}", transactionId);
        log.info("====================================================");
        redisTemplate.delete(otpKey);
        completeTransaction(transaction);
        return mapToResponse(transaction);


    }

    private void completeTransaction(Transaction transaction) {
        log.info("====================================================");
        log.info("✅ TRANSACTION SERVICE - COMPLETING TRANSACTION");
        log.info("Transaction ID: {}", transaction.getId());
        log.info("====================================================");

        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(transaction);

        log.info("💾 Transaction status updated to COMPLETED");

        TransactionCompletedEvent completedEvent = new TransactionCompletedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber(),
                transaction.getAmount(),
                transaction.getDescription()
        );
        kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC,transaction.getId(),completedEvent);
        log.info("📤 Published transaction.completed event to Kafka");
        log.info("✅ SAGA COMPLETED SUCCESSFULLY");
        log.info("====================================================");

    }

    private void blockAccountAndCompensate(Transaction transaction, String reason) {
        log.warn("====================================================");
        log.warn("🚨 SAGA COMPENSATION - BLOCKING ACCOUNT");
        log.warn("Account: {}", transaction.getSenderAccountNumber());
        log.warn("Reason: {}", reason);
        log.warn("====================================================");

        //Publish fraud.detected -> Account service will block account
        Map<String,Object> fraudEvent= new HashMap<>();
        fraudEvent.put("transactionId",transaction.getId());
        fraudEvent.put("accountNumber",transaction.getSenderAccountNumber());
        fraudEvent.put("reason",reason);

        kafkaTemplate.send(FRAUD_DETECTED_TOPIC,transaction.getSenderAccountNumber(),fraudEvent);
        log.warn("📤 Published fraud.detected event to Kafka");

        //SAGA COMPENSATION - refund sender
        compensateTransaction(transaction,reason);
    }

    private void compensateTransaction(Transaction transaction, String reason) {
        log.warn("====================================================");
        log.warn("🔄 SAGA COMPENSATION - REFUNDING AMOUNT");
        log.warn("Transaction ID: {}", transaction.getId());
        log.warn("Account: {}", transaction.getSenderAccountNumber());
        log.warn("Amount: {}", transaction.getAmount());
        log.warn("Reason: {}", reason);
        log.warn("====================================================");

        //CREDIT MONEY BACK TO SENDER SYNCHRONOUSLY
        try {
            accountServiceClient.creditBalance(transaction.getSenderAccountNumber(),transaction.getAmount());
            log.warn("✅ Amount refunded successfully to sender");
        } catch (Exception e) {
            log.error("❌ COMPENSATION FAILED: Could not refund amount - {}", e.getMessage());
        }

        transaction.setStatus(TransactionStatus.FLAGGED);
        transaction.setFailureReason(reason+" - SAGA Compensation completed and amount refunded");
        transactionRepository.save(transaction);
        log.warn("💾 Transaction marked as FLAGGED");

        //PUBLISH refund event - NOTIFICATION service will alert user

        Map<String,Object> refundEvent=new HashMap<>();
        refundEvent.put("transactionId", transaction.getId());
        refundEvent.put("accountNumber", transaction.getSenderAccountNumber());
        refundEvent.put("reason", reason);
        refundEvent.put("amount", transaction.getAmount());

        kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC,transaction.getId(),refundEvent);
        log.warn("📤 Published transaction.refunded event to Kafka");
        log.warn("✅ SAGA COMPENSATION COMPLETED");
        log.warn("====================================================");



    }

    public void processCleanResult(String transactionId) {
        log.info("====================================================");
        log.info("✅ TRANSACTION SERVICE - FRAUD CHECK CLEAN");
        log.info("Transaction ID: {}", transactionId);
        log.info("====================================================");

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found" + transactionId));

        if (transaction.getStatus() != TransactionStatus.PROCESSING) {
            log.warn("⚠️ Transaction not in PROCESSING status - Current status: {}", transaction.getStatus());
            log.warn("Skipping completion");
            log.warn("====================================================");
            return;
        }

        log.info("✅ No fraud detected - Completing transaction");
        completeTransaction(transaction);
    }
}
