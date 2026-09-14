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
        log.info("SAGA START - Transfer: {} -> {} amount: {}",
                request.getSenderAccountNumber(),
                request.getReceiverAccountNumber(),
                request.getAmount());

        //SAGA step 1: deduct from sender

        accountServiceClient.deductBalance(request.getSenderAccountNumber(), request.getAmount());
        Transaction transaction = new Transaction();
        transaction.setReceiverAccountNumber(request.getReceiverAccountNumber());
        transaction.setSenderAccountNumber(request.getSenderAccountNumber());
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setAmount(request.getAmount());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setDescription(request.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Transaction saved as Processing: {}", savedTransaction.getId());


        //SAGA step 2 publish for fraud check
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                savedTransaction.getId(),
                savedTransaction.getSenderAccountNumber(),
                savedTransaction.getReceiverAccountNumber(),
                savedTransaction.getAmount(),
                savedTransaction.getDescription()
        );
        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC, savedTransaction.getId(), event);
        log.info("SAGA step 2 TransactionInitiatedEvent published: {}", savedTransaction.getId());

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
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found" + transactionId));

        String otpKey = "verification:otp" + transactionId;
        String storedOtp = redisTemplate.opsForValue().get(otpKey);
        if (storedOtp == null) {
            //OTP EXPIRED
            log.warn("OTP Expired");
            compensateTransaction(transaction, "OTP Expired - transaction cancelled and amount refunded");
            return mapToResponse(transaction);
        }
        if (!storedOtp.equals(otp)) {
            //BLOCK ACCOUNT AND REFUND WHEN WRONG OTP ENTERED
            log.warn("Wrong OTP blocking account and refunding", transactionId);
            redisTemplate.delete(otpKey);
            blockAccountAndCompensate(transaction, "Wrong OTP - transaction cancelled, " + "account blocked");
            return mapToResponse(transaction);

        }

        // OTP CORRECT - complete transaction
        log.info("OTP verified");
        redisTemplate.delete(otpKey);
        completeTransaction(transaction);
        return mapToResponse(transaction);


    }

    private void completeTransaction(Transaction transaction) {
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(transaction);

        TransactionCompletedEvent completedEvent = new TransactionCompletedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber(),
                transaction.getAmount(),
                transaction.getDescription()
        );
        kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC,transaction.getId(),completedEvent);

    }

    private void blockAccountAndCompensate(Transaction transaction, String reason) {
        //Publish fraud.detected -> Account service will block account
        Map<String,Object> fraudEvent= new HashMap<>();
        fraudEvent.put("transactionId",transaction.getId());
        fraudEvent.put("accountNumber",transaction.getSenderAccountNumber());
        fraudEvent.put("reason",reason);
       kafkaTemplate.send(FRAUD_DETECTED_TOPIC,transaction.getSenderAccountNumber(),fraudEvent);
       //SAGA COMPENSATION - refund sender
        compensateTransaction(transaction,reason);
    }

    private void compensateTransaction(Transaction transaction, String reason) {
        log.warn("SAFA COMPENSATION REFUNDING amount");

        //CREDIT MONEY BACK TO SENDER SYNCHRONOUSLY
        accountServiceClient.creditBalance(transaction.getSenderAccountNumber(),transaction.getAmount());
        
        transaction.setStatus(TransactionStatus.FLAGGED);
        transaction.setFailureReason(reason+"SAGA Compensation completed and amount refunded");
        transactionRepository.save(transaction);
        
        //PUBLISH refund event - NOTIFICATION service will alert user

        Map<String,Object> refundEvent=new HashMap<>();
        refundEvent.put("transactionId", transaction.getId());
        refundEvent.put("accountNumber", transaction.getSenderAccountNumber());
        refundEvent.put("reason", reason);
        refundEvent.put("amount", transaction.getAmount());

        kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC,transaction.getId(),refundEvent);

        

    }

    public void processCleanResult(String transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found" + transactionId));
        if (transaction.getStatus() != TransactionStatus.PROCESSING) {
            log.warn("Transaction not processing - skipping");
        }
        completeTransaction(transaction);
    }
}
