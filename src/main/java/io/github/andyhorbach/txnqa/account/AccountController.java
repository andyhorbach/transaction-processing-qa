package io.github.andyhorbach.txnqa.account;

import io.github.andyhorbach.txnqa.auth.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    public record CreateAccountRequest(String currency) {
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> create(@RequestBody CreateAccountRequest request,
                                                  AuthenticatedUser user) {
        Account account = accountService.create(user, request.currency());
        return ResponseEntity.status(201).body(AccountResponse.from(account));
    }

    @GetMapping("/accounts/{accountId}")
    public AccountResponse get(@PathVariable String accountId, AuthenticatedUser user) {
        return AccountResponse.from(accountService.getOwned(user, accountId));
    }
}
