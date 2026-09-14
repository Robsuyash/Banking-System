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
        log.info("Creating account for : {}", request.getEmail());
        if (accountRepository.existsByEmail(request.getEmail())) {
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
        log.info("Account created: {}", savedAccount.getAccountNumber());

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
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account not found"));

        return mapToResponse(account);
    }

    public @Nullable BigDecimal getBalance(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account not found"));

        return account.getBalance();
    }

    /*
    Block account -- called by Fraud detection service via kafka
     */
    public void blockAccount(String accountNumber) {
        log.info("Blocking account: {}",accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account not found"));
        account.setStatus(AccountStatus.BLOCKED);
        accountRepository.save(account);
        log.info("Account Blocked: {}",accountNumber);
    }

    /*
    From sender account
    deduct Balance -- called by transaction service via kafka
  */
    public void deductBalance(String accountNumber, BigDecimal amount) {
        log.info("Deducting amount from account: {}",accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account not found"));
        if(account.getStatus()!=AccountStatus.ACTIVE){
            throw new RuntimeException("Account is not Active");
        }
        if(account.getBalance().compareTo(amount)<0){
            throw new RuntimeException("Insufficient Funds");
        }
        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);
        log.info("Balance Updated, New Balance: {}",account.getBalance());
    }


    /*
   credit Balance -- called by transaction service via kafka
 */
    public void creditBalance(String accountNumber, BigDecimal amount) {
        log.info("Crediting amount to account: {}",accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account not found"));
        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);
        log.info("Balance Updated, New Balance: {}",account.getBalance());
    }
}
