package com.example.insurance.payment;

import com.example.insurance.policy.Policy;
import com.example.insurance.policy.PolicyRepository;

import jakarta.ws.rs.NotFoundException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentService}.
 *
 * Mocking strategy — and why the proxy boundary matters for this test:
 *
 * In production, {@code @Retry(maxRetries=2, delay=200)} lives on
 * {@link PaymentGatewayInvoker#charge}, NOT on {@link PaymentService}. The
 * interceptor only fires across the CDI proxy boundary; PaymentService
 * calls {@code gatewayInvoker.charge(...)} through that proxy, so the
 * retry is real. The slice-7 bug shipped without this split — the @Retry
 * was on a same-bean method and the interceptor never fired.
 *
 * For this unit test, we mock {@code PaymentGatewayInvoker} directly. The
 * mock returns or throws once per call — there is no CDI container, no
 * interceptor chain. That's intentional: testing what mpFaultTolerance
 * does on top of an invoker is a job for an integration test against the
 * real Liberty runtime (or arquillian-style), and is out of scope here.
 *
 * The contract verified at the service boundary is "after the invoker
 * gives up (whether it tried once or four times), the service persists a
 * FAILED row, dead-letters it with attempts=3, and publishes the event."
 * The "3" is hard-wired in {@link PaymentService#process} as "1 initial +
 * maxRetries=2"; if that pin drifts apart from {@code PaymentGatewayInvoker}'s
 * annotation, the assertion below catches it.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository       repo;
    @Mock PolicyRepository        policyRepo;
    @Mock PaymentDlqPublisher     dlq;
    @Mock IdempotencyStore        idem;
    @Mock PaymentGatewayInvoker   gatewayInvoker;
    @Mock PaymentEventPublisher   eventPublisher;

    @InjectMocks PaymentService service;

    private static PaymentRequest req() {
        return new PaymentRequest("POL-001", new BigDecimal("125.00"), "USD");
    }

    private static Policy policy() {
        Policy p = new Policy();
        p.setPolicyNumber("POL-001");
        return p;
    }

    /**
     * Stub repo.save() to mirror production: persist + flush, returning
     * the same entity with id=1L. Without an id, downstream saveSuccess /
     * saveFailure can't look the row back up.
     */
    private void stubRepoSaveAssignsId() {
        when(repo.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) p.setId(1L);
            return p;
        });
        when(repo.findById(1L)).thenAnswer(inv -> {
            // Mirror "the row we just flushed is now retrievable."
            Payment p = new Payment();
            p.setId(1L);
            p.setPolicyNumber("POL-001");
            p.setAmount(new BigDecimal("125.00"));
            p.setCurrency("USD");
            p.setIdempotencyKey("key-A");
            p.setStatus("PENDING");
            return p;
        });
    }

    // ------------------------------------------------------------------
    // Idempotency replay path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Replay from Redis returns the cached payment and skips the gateway entirely")
    void replaysFromRedisCache() {
        Payment cached = new Payment();
        cached.setId(99L);
        cached.setStatus("SUCCEEDED");
        when(idem.lookup("key-replay")).thenReturn(cached);

        PaymentService.Result result = service.process("key-replay", req());

        assertThat(result.replayed()).isTrue();
        assertThat(result.payment()).isSameAs(cached);
        verifyNoInteractions(gatewayInvoker, dlq, eventPublisher, repo, policyRepo);
    }

    @Test
    @DisplayName("Cache miss + DB hit re-warms the cache and short-circuits the gateway")
    void rewarmsCacheOnDbHit() {
        Payment fromDb = new Payment();
        fromDb.setId(77L);
        fromDb.setStatus("SUCCEEDED");
        when(idem.lookup("key-db")).thenReturn(null);
        when(repo.findByIdempotencyKey("key-db")).thenReturn(fromDb);

        PaymentService.Result result = service.process("key-db", req());

        assertThat(result.replayed()).isTrue();
        assertThat(result.payment()).isSameAs(fromDb);
        verify(idem).store("key-db", fromDb);
        verifyNoInteractions(gatewayInvoker, dlq, eventPublisher);
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Fresh charge: PENDING → SUCCEEDED, event emitted, idempotency cache populated, no DLQ")
    void successfullyChargesAndStoresSuccess() {
        stubRepoSaveAssignsId();
        when(idem.lookup("key-ok")).thenReturn(null);
        when(repo.findByIdempotencyKey("key-ok")).thenReturn(null);
        when(policyRepo.findByNumber("POL-001")).thenReturn(policy());
        PaymentGatewayResponse ok = new PaymentGatewayResponse();
        ok.setExternalRef("ext-123");
        ok.setStatus("OK");
        when(gatewayInvoker.charge(any(PaymentGatewayChargeRequest.class))).thenReturn(ok);

        PaymentService.Result result = service.process("key-ok", req());

        assertThat(result.replayed()).isFalse();
        assertThat(result.payment().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.payment().getExternalRef()).isEqualTo("ext-123");
        verify(eventPublisher).publish(result.payment());
        verify(idem).store(eq("key-ok"), eq(result.payment()));
        verify(dlq, never()).publish(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Unknown policy throws NotFoundException before any gateway call")
    void rejectsUnknownPolicy() {
        when(idem.lookup("key-noplicy")).thenReturn(null);
        when(repo.findByIdempotencyKey("key-noplicy")).thenReturn(null);
        when(policyRepo.findByNumber("POL-001")).thenReturn(null);

        assertThatThrownBy(() -> service.process("key-noplicy", req()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("policy POL-001");

        verifyNoInteractions(gatewayInvoker, dlq, eventPublisher);
    }

    // ------------------------------------------------------------------
    // The DLQ / failure path — the core ask in the prompt.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Gateway exhausts retries → FAILED row persisted, DLQ event with attempts=3, event emitted")
    void onGatewayExhaustionPersistsFailedAndDeadLetters() {
        stubRepoSaveAssignsId();
        when(idem.lookup("key-A")).thenReturn(null);
        when(repo.findByIdempotencyKey("key-A")).thenReturn(null);
        when(policyRepo.findByNumber("POL-001")).thenReturn(policy());
        // Simulate "@Retry exhausted at the proxy boundary": the invoker
        // is what surfaces the final exception to the caller. The unit
        // test treats the invoker as opaque — testing that mpFaultTolerance
        // actually fires three times is an IT against Liberty.
        when(gatewayInvoker.charge(any(PaymentGatewayChargeRequest.class)))
                .thenThrow(new RuntimeException("gateway 503"));

        PaymentService.Result result = service.process("key-A", req());

        assertThat(result.replayed()).isFalse();
        assertThat(result.payment().getStatus()).isEqualTo("FAILED");
        assertThat(result.payment().getFailureReason()).contains("gateway 503");

        // The "3" is the contract-level pin: 1 initial + maxRetries=2.
        // If @Retry's maxRetries on PaymentGatewayInvoker changes, this
        // is the assertion that lights up.
        ArgumentCaptor<Integer> attempts = ArgumentCaptor.forClass(Integer.class);
        verify(dlq).publish(eq(result.payment()), anyString(), attempts.capture());
        assertThat(attempts.getValue())
                .as("PaymentService hard-codes 1 initial + maxRetries=2 = 3 calls. "
                  + "If PaymentGatewayInvoker.@Retry.maxRetries changes, update this too.")
                .isEqualTo(3);

        verify(eventPublisher).publish(result.payment());
        verify(idem).store("key-A", result.payment());
    }

    @Test
    @DisplayName("Failure reason is truncated to 250 chars to fit the failure_reason column")
    void truncatesFailureReasonToColumnWidth() {
        stubRepoSaveAssignsId();
        when(idem.lookup("key-long")).thenReturn(null);
        when(repo.findByIdempotencyKey("key-long")).thenReturn(null);
        when(policyRepo.findByNumber("POL-001")).thenReturn(policy());
        String huge = "x".repeat(500);
        when(gatewayInvoker.charge(any(PaymentGatewayChargeRequest.class)))
                .thenThrow(new RuntimeException(huge));

        PaymentService.Result result = service.process("key-long", req());

        assertThat(result.payment().getFailureReason()).hasSize(250);
    }

    @Test
    @DisplayName("Gateway exception with null message falls back to the exception class name")
    void usesClassNameWhenMessageIsNull() {
        stubRepoSaveAssignsId();
        when(idem.lookup("key-nullmsg")).thenReturn(null);
        when(repo.findByIdempotencyKey("key-nullmsg")).thenReturn(null);
        when(policyRepo.findByNumber("POL-001")).thenReturn(policy());
        when(gatewayInvoker.charge(any(PaymentGatewayChargeRequest.class)))
                .thenThrow(new RuntimeException((String) null));

        PaymentService.Result result = service.process("key-nullmsg", req());

        assertThat(result.payment().getStatus()).isEqualTo("FAILED");
        assertThat(result.payment().getFailureReason()).isEqualTo("RuntimeException");
    }

    @Test
    @DisplayName("Default currency is USD when the request omits one")
    void defaultsCurrencyToUsd() {
        stubRepoSaveAssignsId();
        when(idem.lookup("key-nocur")).thenReturn(null);
        when(repo.findByIdempotencyKey("key-nocur")).thenReturn(null);
        when(policyRepo.findByNumber("POL-001")).thenReturn(policy());
        PaymentGatewayResponse ok = new PaymentGatewayResponse();
        ok.setExternalRef("ext-USD");
        when(gatewayInvoker.charge(any(PaymentGatewayChargeRequest.class))).thenReturn(ok);

        PaymentService.Result result = service.process(
                "key-nocur",
                new PaymentRequest("POL-001", new BigDecimal("50.00"), null));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(repo, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getCurrency()).isEqualTo("USD");
        assertThat(result.payment().getStatus()).isEqualTo("SUCCEEDED");
    }
}
