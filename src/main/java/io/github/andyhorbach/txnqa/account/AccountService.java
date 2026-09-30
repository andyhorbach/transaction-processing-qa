package io.github.andyhorbach.txnqa.account;

import io.github.andyhorbach.txnqa.auth.AuthenticatedUser;
import io.github.andyhorbach.txnqa.common.ApiException;
import io.github.andyhorbach.txnqa.common.Currency;
import io.github.andyhorbach.txnqa.common.Ids;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Account create(AuthenticatedUser user, String rawCurrency) {
        Currency currency = Currency.parse(rawCurrency);
        return accountRepository.insert(user.id(), currency);
    }

    public Account getOwned(AuthenticatedUser user, String rawAccountId) {
        UUID accountId = Ids.parsePathId(rawAccountId);
        return accountRepository.findOwned(accountId, user.id())
                .orElseThrow(ApiException::notFound);
    }
}
