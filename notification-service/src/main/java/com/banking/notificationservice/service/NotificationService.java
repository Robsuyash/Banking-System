package com.banking.notificationservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class NotificationService {


    @KafkaListener(topics = "transaction.otp.generated")
    public void consumeOtpGenerated(@Payload Map<String ,Object>payload){
        try{
            String accountNumber=(String)payload.get("accountNumber");
            String otp=(String)payload.get("otp");
            String transactionId=(String)payload.get("transactionId");
            String amount = payload.get("amount").toString();
            String reason=(String)payload.get("reason");

            sendAlert(accountNumber,"TRANSACTION VERIFICATION REQUIRED", String.format("Suspicious activity detected on your account"
                    +" Reason: %s"+" A transaction of %s is pending verification"+ " Your OTP is %s"+" If this wasn't you then ignore this message",
                    reason, amount, otp)

            );
        } catch (Exception e) {
            log.error("Error during sending otp notification");
        }
    }

    @KafkaListener(topics = "transaction.completed")
    public void consumeTransactionCompleted(@Payload Map<String,Object>payload){
        try{

            String senderAccount = (String)payload.get("senderAccountNumber");
            String receiverAccount=  (String)payload.get("receiverAccountNumber");
            String amount = payload.get("amount").toString();

            //DEBIT ALERT
            sendAlert(senderAccount, "DEBIT ALERT",String.format("%s DEBITED from account %s",amount,receiverAccount));
         //CREDIT ALERT
            sendAlert(senderAccount, "CREDIT ALERT",String.format("%s CREDIT from account %s",amount,receiverAccount));


        } catch (Exception e) {
            log.error("error sending notification");
        }
    }

    @KafkaListener(topics = "fraud.detected")
    public void consumeFraudDetected(@Payload Map<String,Object>payload){
        try{

            String accountNumber = (String)payload.get("accountNumber");
            String reason=  (String)payload.get("reason");

            //Fraud ALERT
            sendAlert(accountNumber, "SUS ACTIVITY DETECTED",String.format("your account %s has been blocked "+"Reason: %s",accountNumber,reason));


        } catch (Exception e) {
            log.error("error sending notification when fraud");
        }
    }

    @KafkaListener(topics = "transaction.refunded")
    public void consumeTransactionRefunded(@Payload Map<String,Object>payload){
        try{

            String senderAccount = (String)payload.get("senderAccountNumber");
            String amount = payload.get("amount").toString();
            String reason=  (String)payload.get("reason");

            //REFUND ALERT
            sendAlert(senderAccount, "REFUND PROCESSED",String.format("your account %s has been refunded as your transaction was cancelled  "+"Reason: %s" + "amount : %s",senderAccount,reason,amount));


        } catch (Exception e) {
            log.error("error sending notification when refund");
        }
    }

    @KafkaListener(topics = "payment.completed")
    public void consumePaymentCompleted(@Payload Map<String,Object>payload){
        try{

            String accountNumber = (String)payload.get("accountNumber");
            String amount = payload.get("amount").toString();
            //SUCCESSFUL PAYEMNT ALERT
            sendAlert(accountNumber, "PAYMENT SUCCESSFUL",String.format("Your payment of %s competed "+"Razorpay ID: %s ",amount,payload.get("razorpayPaymentId")));


        } catch (Exception e) {
            log.error("error sending notification when payemnt successful");
        }
    }

    @KafkaListener(topics = "payment.failed")
    public void consumePaymentFailed(@Payload Map<String,Object>payload){
        try{

            String accountNumber = (String)payload.get("accountNumber");
            String amount = payload.get("amount").toString();
            //Failed PAYEMNT ALERT
            sendAlert(accountNumber, "PAYMENT FAILED",String.format("Your payment of %s is not completed, please try again "+"Razorpay ID: %s ",amount,payload.get("razorpayPaymentId")));


        } catch (Exception e) {
            log.error("error sending notification when payemnt failed");
        }
    }


    private void sendAlert(String accountNumber,String subject, String message) {
        log.info("---------------------------------------------------------------------");
        log.info("Account: {}",accountNumber);
        log.info("subject: {}",subject);
        log.info("message: {}",message);
        log.info("---------------------------------------------------------------------");
    }
}
