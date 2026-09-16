package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;
    private static final SecureRandom secureRandom = new SecureRandom();

    public AccountResponse createAccount(CreateAccountRequest request) {
        log.info("====================================================");
        log.info("🏦 ACCOUNT SERVICE - CREATE ACCOUNT REQUEST");
        log.info("Email: {}", request.getEmail());
        log.info("Account Type: {}", request.getAccountType());
        log.info("Initial Deposit: {}", request.getInitialDeposit());
        log.info("====================================================");

        if (accountRepository.existsByEmail(request.getEmail())) {
            log.error("❌ Account creation failed - Email already exists: {}", request.getEmail());
            throw new RuntimeException("Account already exists for this email: " + request.getEmail());
        }

        Account account = new Account();
                account.setAccountHolderName(request.getAccountHolderName());
                account.setEmail(request.getEmail());
                account.setPhone(request.getPhone());
                account.setAccountType(request.getAccountType());
                account.setStatus(AccountStatus.ACTIVE);
                account.setBalance(request.getInitialDeposit());
                account.setAccountNumber(generateAccountNumber());
                account.setDailyTransactionLimit(
                        request.getAccountType() == AccountType.SAVINGS
                                ? new BigDecimal("100000")
                                                                    :new BigDecimal("500000"));
        Account savedAccount = accountRepository.save(account);

        log.info("✅ Account created successfully");
        log.info("Account Number: {}", savedAccount.getAccountNumber());
        log.info("Balance: {}", savedAccount.getBalance());
        log.info("Daily Limit: {}", savedAccount.getDailyTransactionLimit());
        log.info("====================================================");

        return mapToResponse(savedAccount);
    }



    //Generate 12 digit unique acc no
    private String generateAccountNumber() {

        String accountNumber;

        do {
            long num = secureRandom.nextLong(1_000_000_000_000L);
            accountNumber =String.format("%012d",num);
        }while(accountRepository.existsByAccountNumber(accountNumber));

        return accountNumber;
    }

    private AccountResponse mapToResponse(Account account) {
            AccountResponse response = new AccountResponse();
            response.setId(account.getId());
            response.setAccountNumber(account.getAccountNumber());
            response.setAccountHolderName(account.getAccountHolderName());
            response.setEmail(account.getEmail());
            response.setPhone(account.getPhone());
            response.setAccountType(account.getAccountType());
            response.setStatus(account.getStatus());
            response.setBalance(account.getBalance());
            response.setDailyTransactionLimit(account.getDailyTransactionLimit());

            return response;
        }

    public AccountResponse getAccount(String accountNumber) {
        log.info("[ACCOUNT SERVICE] GET ACCOUNT: {}", accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account not found"));
        log.info("[SUCCESS] Account found - Balance: {}, Status: {}", account.getBalance(), account.getStatus());
        return mapToResponse(account);
    }

    public @Nullable BigDecimal getBalance(String accountNumber) {
        log.info("[ACCOUNT SERVICE] GET BALANCE: {}", accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account not found"));
        log.info("[SUCCESS] Current Balance: {}", account.getBalance());
        return account.getBalance();
    }

    /*
    Block account -- called by Fraud detection service via kafka
     */
    public void blockAccount(String accountNumber) {
        log.warn("====================================================");
        log.warn("🚨 ACCOUNT SERVICE - BLOCK ACCOUNT");
        log.warn("Account Number: {}", accountNumber);
        log.warn("====================================================");

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account not found"));
        account.setStatus(AccountStatus.BLOCKED);
        accountRepository.save(account);

        log.warn("❌ Account BLOCKED successfully: {}", accountNumber);
        log.warn("====================================================");
    }

    /*
    From sender account
    deduct Balance -- called by transaction service via kafka
  */
    public void deductBalance(String accountNumber, BigDecimal amount) {
        log.info("====================================================");
        log.info("💸 ACCOUNT SERVICE - DEDUCT BALANCE (SAGA STEP 1)");
        log.info("Account: {}", accountNumber);
        log.info("Amount to Deduct: {}", amount);
        log.info("====================================================");

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account not found"));

        log.info("Current Balance: {}", account.getBalance());
        log.info("Account Status: {}", account.getStatus());

        if(account.getStatus()!=AccountStatus.ACTIVE){
            log.error("❌ Deduction failed - Account is not ACTIVE: {}", account.getStatus());
            throw new RuntimeException("Account is not Active");
        }
        if(account.getBalance().compareTo(amount)<0){
            log.error("❌ Deduction failed - Insufficient funds. Required: {}, Available: {}", amount, account.getBalance());
            throw new RuntimeException("Insufficient Funds");
        }

        BigDecimal oldBalance = account.getBalance();
        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);

        log.info("✅ Balance deducted successfully");
        log.info("Previous Balance: {}", oldBalance);
        log.info("New Balance: {}", account.getBalance());
        log.info("====================================================");
    }


    /*
   credit Balance -- called by transaction service via kafka
 */
    public void creditBalance(String accountNumber, BigDecimal amount) {
        log.info("====================================================");
        log.info("💰 ACCOUNT SERVICE - CREDIT BALANCE (SAGA COMPENSATION/COMPLETION)");
        log.info("Account: {}", accountNumber);
        log.info("Amount to Credit: {}", amount);
        log.info("====================================================");

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account not found"));

        BigDecimal oldBalance = account.getBalance();
        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);

        log.info("✅ Balance credited successfully");
        log.info("Previous Balance: {}", oldBalance);
        log.info("New Balance: {}", account.getBalance());
        log.info("====================================================");
    }
}
