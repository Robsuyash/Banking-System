package com.banking.frauddetectionservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FraudChecksResult {
    private boolean fraud;
    private String reason;
}
