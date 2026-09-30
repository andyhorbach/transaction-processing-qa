package io.github.andyhorbach.txnqa.account;

import io.github.andyhorbach.txnqa.common.Money;

public record AccountResponse(String id, String currency, String balance, String status) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.id().toString(),
                account.currency().name(),
                Money.format(account.balance()),
                account.status());
    }
}
