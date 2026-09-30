package io.github.andyhorbach.txnqa.transaction;

import io.github.andyhorbach.txnqa.auth.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    public record CreateTransactionRequest(String type, String amount, String currency,
                                           String originalTransactionId) {
    }

    public record TransitionRequest(String to) {
    }

    @PostMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> create(@PathVariable String accountId,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                      @RequestBody CreateTransactionRequest request,
                                                      AuthenticatedUser user) {
        TransactionService.CreateResult result =
                transactionService.create(user, accountId, idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(201);
        if (result.replayed()) {
            response.header("Idempotency-Replay", "true");
        }
        return response.body(TransactionResponse.from(result.transaction()));
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public List<TransactionResponse> list(@PathVariable String accountId, AuthenticatedUser user) {
        return transactionService.listForAccount(user, accountId).stream()
                .map(TransactionResponse::from)
                .toList();
    }

    @GetMapping("/transactions/{transactionId}")
    public TransactionResponse get(@PathVariable String transactionId, AuthenticatedUser user) {
        return TransactionResponse.from(transactionService.getOwned(user, transactionId));
    }

    @PostMapping("/transactions/{transactionId}/transitions")
    public TransactionResponse transition(@PathVariable String transactionId,
                                          @RequestBody TransitionRequest request,
                                          AuthenticatedUser user) {
        return TransactionResponse.from(transactionService.transition(user, transactionId, request.to()));
    }
}
