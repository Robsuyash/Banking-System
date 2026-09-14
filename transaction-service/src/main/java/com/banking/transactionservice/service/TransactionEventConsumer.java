package com.banking.transactionservice.service;

import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private static final long OTP_EXPIRY_MINUTES = 5;
    private final TransactionService transactionService;
    private static final String TRANSACTION_OTP_GENERATED_TOPIC_ = "transaction.otp.generated";
    private final TransactionRepository transactionRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /*
    consume verification required
    generate otp and ask user to verify
     */
    @KafkaListener(topics = "verification_required")
    public void consumeVerificationRequired(@Payload Map<String, Object> payload) {
        try {
            String transactionId = (String) payload.get("transactionId");
            String accountNumber = (String) payload.get("accountNumber");
            String reason = (String) payload.get("reason");
            log.info("verification required");

            Transaction transaction = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new RuntimeException("transaction not found" + transactionId));

            //for checking idempotency that these no two credit done
            if (transaction.getStatus() != TransactionStatus.PROCESSING) {
                log.warn("Transaction not processing - skipping");
            }
            //Generate 6 digit otp
            String otp = String.format("%06d", (int) (Math.random() * 900000) + 100000);

            //Store OTP in redis and expires in 5 min
            String otpKey = "verification:otp" + transactionId;
            redisTemplate.opsForValue().set(otpKey, otp, OTP_EXPIRY_MINUTES, TimeUnit.MINUTES);

            //Update Status
            transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);
            transactionRepository.save(transaction);

            //notify user
            Map<String, Object> otpEvent = new HashMap<>();
            otpEvent.put("transactionId", transactionId);
            otpEvent.put("accountNumber", accountNumber);
            otpEvent.put("reason", reason);
            otpEvent.put("otp", otp);
            otpEvent.put("amount", payload.get("amount"));

            kafkaTemplate.send(TRANSACTION_OTP_GENERATED_TOPIC_, transactionId, otpEvent);

        } catch (Exception e) {
            log.error("Error handling verification required");
        }
    }
    @KafkaListener(topics = "fraud_check_clean")
    public  void comsumeFraudCheckCleanResult(
            @Payload Map<String,Object>payload
    ){
        try {
            String transactionId = (String) payload.get("transactionId");
            transactionService.processCleanResult(transactionId);



        } catch (Exception e) {
            log.error("Error handling verification required");
        }
    }


}
