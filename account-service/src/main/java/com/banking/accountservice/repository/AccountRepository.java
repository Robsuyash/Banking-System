package com.banking.accountservice.repository;

import com.banking.accountservice.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account,String> {
    boolean existByEmail(String email);

    boolean existByAccountNumber(String accountNumber);

    Optional<Account> findByAccountNumber(String accountNumber);
}
