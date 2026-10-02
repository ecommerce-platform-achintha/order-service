package com.achintha.orderservice.idempotency;

import com.achintha.orderservice.exception.ApiError;
import com.achintha.orderservice.exception.ApiException;
import com.achintha.orderservice.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code Idempotency-Key} handling for checkout and payment submission (section 1): the outcome is stored per
 * (user, key) together with a hash of the request, and kept 24 h.
 *
 * <ul>
 *   <li>Same key, same request, finished: the stored response is replayed (header {@code Idempotent-Replayed}).</li>
 *   <li>Same key, different request: 422 {@code IDEMPOTENCY_KEY_REUSED}.</li>
 *   <li>Same key while the first request still runs: 409 {@code IDEMPOTENCY_REQUEST_IN_PROGRESS}.</li>
 * </ul>
 *
 * Business refusals (4xx) are stored and replayed like successes; server errors, 503s and lost races
 * ({@code CONCURRENT_MODIFICATION}) are not, so the client may retry them with the same key.
 */
@Slf4j
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration ttl;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager, Clock clock,
                              @Value("${app.orders.idempotency-ttl:PT24H}") Duration ttl) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * @param request what identifies the request (it is serialized and hashed together with {@code path})
     * @param action  runs at most once per key; its own transactions commit independently
     */
    public ResponseEntity<?> execute(UUID userId, String key, String path, Object request,
                                     Supplier<ResponseEntity<?>> action) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw ApiException.badRequest(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "Header " + HEADER + " is required: 8-100 letters, digits, '-' or '_' (e.g. a UUID)");
        }
        String hash = hash(path + "\n" + objectMapper.writeValueAsString(request));
        Optional<ResponseEntity<?>> replay = claim(userId, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ResponseEntity<?> response;
        try {
            response = action.get();
        } catch (ApiException e) {
            if (e.status().is4xxClientError() && e.code() != ErrorCode.CONCURRENT_MODIFICATION) {
                ApiError body = new ApiError(clock.instant(), e.status().value(), e.status().getReasonPhrase(),
                        e.code().name(), e.getMessage(), path, List.of());
                complete(userId, key, e.status().value(), objectMapper.writeValueAsString(body));
            } else {
                release(userId, key);
            }
            throw e;
        } catch (RuntimeException e) {
            release(userId, key);
            throw e;
        }
        complete(userId, key, response.getStatusCode().value(),
                response.getBody() == null ? null : objectMapper.writeValueAsString(response.getBody()));
        return response;
    }

    /** Hourly: drop records past their 24 h. */
    @Scheduled(fixedDelayString = "${app.scheduling.idempotency-cleanup.interval:PT1H}")
    @SchedulerLock(name = "order-service.idempotencyCleanup", lockAtMostFor = "PT10M")
    public void cleanup() {
        Integer removed = transactions.execute(tx -> repository.deleteExpired(clock.instant()));
        if (removed != null && removed > 0) {
            log.debug("Removed {} expired idempotency keys", removed);
        }
    }

    private Optional<ResponseEntity<?>> claim(UUID userId, String key, String hash) {
        try {
            return transactions.execute(tx -> {
                Instant now = clock.instant();
                Optional<IdempotencyRecord> existing = repository.findByUserIdAndKey(userId, key);
                if (existing.isPresent()) {
                    IdempotencyRecord record = existing.get();
                    if (record.getExpiresAt().isBefore(now)) {
                        repository.delete(record);
                        repository.flush();
                    } else {
                        return Optional.of(replay(record, hash));
                    }
                }
                IdempotencyRecord record = new IdempotencyRecord();
                record.setUserId(userId);
                record.setKey(key);
                record.setRequestHash(hash);
                record.setStatus(IdempotencyRecord.IN_PROGRESS);
                record.setCreatedAt(now);
                record.setExpiresAt(now.plus(ttl));
                repository.saveAndFlush(record);
                return Optional.<ResponseEntity<?>>empty();
            });
        } catch (DataIntegrityViolationException e) {
            // The same key was claimed concurrently
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                    "A request with this " + HEADER + " is already being processed");
        }
    }

    private static ResponseEntity<?> replay(IdempotencyRecord record, String hash) {
        if (!record.getRequestHash().equals(hash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This " + HEADER + " was already used for a different request");
        }
        if (!IdempotencyRecord.COMPLETED.equals(record.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                    "A request with this " + HEADER + " is already being processed");
        }
        return ResponseEntity.status(record.getResponseStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAYED_HEADER, "true")
                .body(record.getResponseBody());
    }

    private void complete(UUID userId, String key, int status, String body) {
        transactions.executeWithoutResult(tx -> repository.findByUserIdAndKey(userId, key).ifPresent(record -> {
            record.setStatus(IdempotencyRecord.COMPLETED);
            record.setResponseStatus(status);
            record.setResponseBody(body);
        }));
    }

    private void release(UUID userId, String key) {
        transactions.executeWithoutResult(tx -> repository.findByUserIdAndKey(userId, key)
                .ifPresent(repository::delete));
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
