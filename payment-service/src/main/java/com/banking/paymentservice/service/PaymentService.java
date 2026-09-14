package com.banking.paymentservice.service;

import com.banking.paymentservice.dto.CreatePaymentRequest;
import com.banking.paymentservice.dto.PaymentOrderResponse;
import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.repository.PaymentRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String PAYMENT_COMPLETED_TOPIC = "payment.completed";
    private static final String PAYMENT_FAILED_TOPIC = "payment.failed";
    private final PaymentRepository paymentRepository;
    @Value("${razorpay.key-id}")
    private String keyId;
    @Value("${razorpay.key-secret}")
    private String keySecret;

    /*
    create razorpay payment order

    flow
    1. create order in razorpay
    2. save the payment record in DB
    3. Return order details to frontend
    4. frontend shows Razorpay checkout page
    5. user pays
    6. razorpay calls webhook

     */
    public @Nullable PaymentOrderResponse createPaymentOrder(@Valid CreatePaymentRequest request) throws RazorpayException {

        log.info("Creating payment order for account: {} amount: {}", request.getAccountNumber(), request.getAmount());

        RazorpayClient razorpayClient = new RazorpayClient(keyId, keySecret);

        //converted amount

        int convertedAmount = request.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .intValue();

        JSONObject orderRequest = new JSONObject();
        orderRequest.put("amount", convertedAmount);
        orderRequest.put("currency", "USD/INR");
        orderRequest.put("receipt", "rcpt_" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 10));

        //create order in razorpay
        Order razorpayOrder = razorpayClient.orders.create(orderRequest);

        //save the payment record in DB
        Payment payment = new Payment();
        payment.setRazorpayOrderId(razorpayOrder.get("id").toString());
        payment.setAccountNumber(request.getAccountNumber());
        payment.setAmount(request.getAmount());
        payment.setCurrency("USD/INR");
        payment.setStatus(PaymentStatus.CREATED);
        payment.setDescription(request.getDescription());

        Payment savedPayment = paymentRepository.save(payment);

        return new PaymentOrderResponse(
                savedPayment.getId(),
                razorpayOrder.get("id").toString(),
                request.getAmount(),
                "USD/INR",
                "CREATED",
                keyId
        );
    }

    /*
    webhook - what it does is that it capture the event from the payment service
    it is not mandatory, but it is recommended to use this

    now for real payment
    it captures events there are 100+ events like payment failed, payment success, pending, order created and so on
    now webhook captures these events and notify to our backend
     */
    public void handleWebhook(Map<String, Object> payload) {
        String event = (String) payload.get("event");

        if ("payment.capture".equals(event)) {
            handlePaymentSuccess(payload);
        } else if ("payment.failed".equals(event)) {
            handlePaymentFailed(payload);
        }

    }

    private void handlePaymentFailed(Map<String, Object> payload) {

        try {
            Map<String, Object> paymentData = extractPaymentData(payload);
            String orderId = (String) paymentData.get("order_id");
            String paymentId = (String) paymentData.get("id");

            Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                    .orElseThrow(()-> new RuntimeException("payment not found for order"));

            payment.setRazorpayPaymentId(paymentId);
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Payment failed via razorpay");
            paymentRepository.save(payment);

            //publish payment completed event
            Map<String , Object> event = new HashMap<>();
            event.put("paymentId",payment.getId());
            event.put("accountNumber", payment.getAccountNumber());
            event.put("amount",payment.getAmount());
            event.put("reason","Payment failed via razorpay");

            kafkaTemplate.send(PAYMENT_FAILED_TOPIC,payment.getId(),event);

        } catch (Exception e) {
            log.error("Error handling payment failed");
        }
    }

    private void handlePaymentSuccess(Map<String, Object> payload) {
        try {
            Map<String, Object> paymentData = extractPaymentData(payload);
            String orderId = (String) paymentData.get("order_id");
            String paymentId = (String) paymentData.get("id");

            Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                    .orElseThrow(()-> new RuntimeException("payment not found for order"));

            payment.setRazorpayPaymentId(paymentId);
            payment.setStatus(PaymentStatus.COMPLETED);
            paymentRepository.save(payment);

            //publish payment completed event
            Map<String , Object> event = new HashMap<>();
            event.put("paymentId",payment.getId());
            event.put("accountNumber", payment.getAccountNumber());
            event.put("amount",payment.getAmount());
            event.put("razorpayPaymentId",paymentId);

            kafkaTemplate.send(PAYMENT_COMPLETED_TOPIC,payment.getId(),event);

        } catch (Exception e) {
            log.error("Error handling payment success");
        }
    }

    private Map<String, Object> extractPaymentData(Map<String, Object> payload) {
        Map<String,Object> entity = (Map<String,Object>)payload.get("payload");
        Map<String,Object> paymentWrapper=(Map<String,Object>)entity.get("payment");
        return (Map<String,Object>)paymentWrapper.get("entity");
    }
}
