package com.banking.paymentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreatePaymentRequest {

    @NotBlank(message = "Account Number is required")
    private String accountNumber;

    @NotBlank(message = "Amount is required")
    @Positive(message = "Amount must be +ve")
    private BigDecimal amount;

    private String description;

}
