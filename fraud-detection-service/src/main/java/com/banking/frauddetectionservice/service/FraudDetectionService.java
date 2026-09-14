package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.AccountServiceClient;
import com.banking.frauddetectionservice.model.FraudChecksResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class FraudDetectionService {

    private static final String VERIFICATION_REQUIRED_TOPIC = "verification_required";
    private static final String FRAUD_CHECK_CLEAN_RESULT_TOPIC = "fraud_check_clean";
    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${fraud.max-transactions-per-min}")
    private int maxTransactionPerMin;


    @Value("${fraud.suspicious-amount-multiplier}")
    private double suspiciousAmountMultiplier;

    @Value("${fraud.max-balance-percentage}")
    private double maxBalancePercentage;

    public void checkTransaction(Map<String, Object> payload) {
        String transactionId = (String) payload.get("transactionId");
        String accountNumber = (String) payload.get("senderAccountNumber");
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());

        // Fetch real balance from account service
        BigDecimal senderBalance = accountServiceClient.getBalance(accountNumber);

        log.info("Checking transaction: {} account: {} balance: {}", transactionId, accountNumber, senderBalance);

        FraudChecksResult result = performFraudChecks(accountNumber, amount, senderBalance);

        if (result.isFraud()) {
            log.info("Sus activity detected - account: {}" + "reason: {} - required otp verification", accountNumber, result.getReason());

            //verification required now coz sus

            Map<String, Object> verificationEvent = new HashMap<>();
            verificationEvent.put("transactionId", transactionId);
            verificationEvent.put("amount", amount);
            verificationEvent.put("accountNumber", accountNumber);
            verificationEvent.put("reason", result.getReason());

            kafkaTemplate.send(VERIFICATION_REQUIRED_TOPIC, transactionId, verificationEvent);
        } else {
            log.info("Transaction clean");
            Map<String, Object> transactionCleanEvent = new HashMap<>();
            transactionCleanEvent.put("transactionId", transactionId);
            transactionCleanEvent.put("isFraud", false);
            transactionCleanEvent.put("reason", null);
            kafkaTemplate.send(FRAUD_CHECK_CLEAN_RESULT_TOPIC, transactionId, transactionCleanEvent);

        }

    }

    private FraudChecksResult performFraudChecks(String accountNumber, BigDecimal amount, BigDecimal senderBalance) {

        // Pattern 1: Velocity Check
        if (isVelocityExceeded(accountNumber)) {
            return new FraudChecksResult(
                    true, " - Velocity limit exceeded" + "Too many transactions in 60 seconds");
        }
        // Pattern 2: Amount check
        if (isAmountSuspicious(accountNumber, amount)) {
            return new FraudChecksResult(true,
                    "Unusual transaction amount " +
                            " - exceeds 3x your average");
        }
        // Pattern 3: Balance Check
        if (senderBalance.compareTo(BigDecimal.ZERO) > 0
                && isBalanceCheckFailed(senderBalance, amount)) {
            return new FraudChecksResult(true,
                    "Transaction exceeds 90% of account balance");
        }

        return new FraudChecksResult(false, null);
    }


    private boolean isVelocityExceeded(String accountNumber) {
        String key = "fraud:velocity:" + accountNumber;
        Long count = redisTemplate.opsForValue().increment(key);
        /*
        so basically ham yaha pr check kr rahe h redis ke help se
        har ek transaction ki key bana rahe h acc no se and then uske key me increment kr rahe h
        and limit dale h expire ka 60 sec ka uske bad wo key urr jayega and hence uske help se check kr rahe h
         */
        if (count != null && count == 1) {
            redisTemplate.expire(key, 60, TimeUnit.SECONDS);
        }

        log.info("velocity check account: {} , count: {}", accountNumber, count);

        return count != null && count > maxTransactionPerMin;
    }

    private boolean isAmountSuspicious(String accountNumber, BigDecimal amount) {
        String avgKey = "fraud:avg_amount:" + accountNumber;
        String avgStr = redisTemplate.opsForValue().get(avgKey);
        if (avgStr == null) {
            redisTemplate.opsForValue().set(avgKey, amount.toString());
            return false;
        }
        BigDecimal avgAmount = new BigDecimal(avgStr);
        BigDecimal threshold = avgAmount.multiply(BigDecimal.valueOf(suspiciousAmountMultiplier));


        //updating running average
        BigDecimal newAvg = avgAmount.add(amount).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        redisTemplate.opsForValue().set(avgKey, newAvg.toString());

        return amount.compareTo(threshold) > 0;


    }

    private boolean isBalanceCheckFailed(BigDecimal senderBalance, BigDecimal amount) {

        BigDecimal maxAllowed = senderBalance.multiply(BigDecimal.valueOf(maxBalancePercentage));

        return amount.compareTo(maxAllowed) > 0;
    }


}