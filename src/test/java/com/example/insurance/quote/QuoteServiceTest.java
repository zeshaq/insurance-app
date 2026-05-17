package com.example.insurance.quote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuoteService}.
 *
 * Scope: premium-calculation arithmetic, the credit-bureau fallback path,
 * and the read-through cache wiring. The IT
 * ({@link QuoteRoundTripIT}) covers the JPA flush + real Redis side.
 *
 * Two specific bugs these tests catch:
 *   1. If {@code QuoteRepository.save()} regresses and returns a Quote with
 *      a null id, {@link QuoteCache#put} ends up writing to {@code quote:null}.
 *      We feed the mock a saved Quote with id=42L and assert the cache sees
 *      the same instance — guarding the "saved.getId() must be non-null"
 *      contract that the production repo enforces via {@code em.flush()}.
 *   2. If the bureau lookup throws and the fallback path stops returning
 *      1.0, premium silently drifts. The fallback test pins the exact
 *      premium so a regression is loud.
 */
@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

    @Mock QuoteRepository    repo;
    @Mock QuoteCache         cache;
    @Mock QuotePublisher     publisher;
    @Mock CreditBureauClient creditBureau;
    @Mock CreditScoreCache   creditCache;

    @InjectMocks QuoteService service;

    /**
     * QuoteRepository.save() in production runs em.persist() + em.flush()
     * so the returned entity has a non-null id. The mock has to mirror
     * that contract — the cache write that follows would otherwise key on
     * "quote:null" and the bug would slip past.
     *
     * lenient() because the getById and rejectsUnknownCoverageType tests
     * never invoke repo.save(); Mockito's default STRICT mode would fail
     * them with UnnecessaryStubbingException. The createQuote tests still
     * rely on this stub.
     */
    @BeforeEach
    void stubRepoToAssignId() {
        lenient().when(repo.save(any(Quote.class))).thenAnswer(inv -> {
            Quote q = inv.getArgument(0);
            q.setId(42L);
            return q;
        });
    }

    @Test
    @DisplayName("STANDARD coverage, in-bracket age, 720 credit → 500 * 1.5 * 1.0 * 1.0 = 750.00")
    void calculatesPremiumForStandardCoverage() {
        when(creditCache.get("VIN-STD-001")).thenReturn(new CreditScore("VIN-STD-001", 720));

        Quote saved = service.createQuote(new QuoteRequest("VIN-STD-001", 35, "STANDARD"));

        assertThat(saved.getPremium()).isEqualByComparingTo("750.00");
        assertThat(saved.getId()).isEqualTo(42L);
        assertThat(saved.getStatus()).isEqualTo("CALCULATED");
        assertThat(saved.getVehicleVin()).isEqualTo("VIN-STD-001");
        assertThat(saved.getValidUntil()).isAfter(saved.getCreatedAt());
    }

    @Test
    @DisplayName("Age < 25 triggers 1.4 ageFactor")
    void appliesYoungDriverFactor() {
        when(creditCache.get("VIN-YOUNG")).thenReturn(new CreditScore("VIN-YOUNG", 720));

        Quote saved = service.createQuote(new QuoteRequest("VIN-YOUNG", 22, "BASIC"));

        // 500 * 1.0 * 1.4 * 1.0 = 700.00
        assertThat(saved.getPremium()).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("Age > 70 also triggers 1.4 ageFactor")
    void appliesElderlyDriverFactor() {
        when(creditCache.get("VIN-ELDER")).thenReturn(new CreditScore("VIN-ELDER", 720));

        Quote saved = service.createQuote(new QuoteRequest("VIN-ELDER", 75, "PREMIUM"));

        // 500 * 2.0 * 1.4 * 1.0 = 1400.00
        assertThat(saved.getPremium()).isEqualByComparingTo("1400.00");
    }

    @ParameterizedTest(name = "credit {0} → creditFactor {1} → premium {2}")
    @CsvSource({
            "750, 1.0, 750.00",  // >=700 → 1.0
            "650, 1.2, 900.00",  // >=600 → 1.2
            "550, 1.5, 1125.00"  // <600  → 1.5
    })
    @DisplayName("Credit score buckets map to the right premium factor")
    void appliesCreditFactorBuckets(int score, BigDecimal factor, BigDecimal expectedPremium) {
        when(creditCache.get("VIN-CREDIT")).thenReturn(new CreditScore("VIN-CREDIT", score));

        Quote saved = service.createQuote(new QuoteRequest("VIN-CREDIT", 35, "STANDARD"));

        // 500 * 1.5 (STANDARD) * 1.0 (age) * factor
        assertThat(saved.getPremium()).isEqualByComparingTo(expectedPremium);
    }

    @Test
    @DisplayName("Cache miss + bureau success populates the credit cache before computing")
    void warmsCreditCacheOnBureauHit() {
        when(creditCache.get("VIN-MISS")).thenReturn(null);
        when(creditBureau.lookup("VIN-MISS")).thenReturn(new CreditScore("VIN-MISS", 710));

        service.createQuote(new QuoteRequest("VIN-MISS", 35, "BASIC"));

        verify(creditCache).put(eq("VIN-MISS"), any(CreditScore.class));
    }

    @Test
    @DisplayName("Bureau exception falls back to neutral 1.0 — no creditCache.put, no propagation")
    void fallsBackToNeutralFactorOnBureauFailure() {
        when(creditCache.get("VIN-BOOM")).thenReturn(null);
        when(creditBureau.lookup("VIN-BOOM"))
                .thenThrow(new RuntimeException("bureau 502"));

        Quote saved = service.createQuote(new QuoteRequest("VIN-BOOM", 35, "STANDARD"));

        // 500 * 1.5 * 1.0 * 1.0 (neutral) = 750.00
        assertThat(saved.getPremium()).isEqualByComparingTo("750.00");
        verify(creditCache, never()).put(anyString(), any());
    }

    @Test
    @DisplayName("Unknown coverageType is rejected before any IO")
    void rejectsUnknownCoverageType() {
        assertThatThrownBy(() ->
                service.createQuote(new QuoteRequest("VIN-X", 35, "GOLD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown coverageType");

        verifyNoInteractions(repo, cache, publisher);
    }

    @Test
    @DisplayName("createQuote writes through to the cache with the saved entity (the 'quote:null' guard)")
    void writesSavedEntityToCacheAfterPersist() {
        when(creditCache.get("VIN-CACHE")).thenReturn(new CreditScore("VIN-CACHE", 720));

        service.createQuote(new QuoteRequest("VIN-CACHE", 35, "STANDARD"));

        ArgumentCaptor<Quote> captor = ArgumentCaptor.forClass(Quote.class);
        verify(cache).put(captor.capture());
        // If the repo regresses and forgets to flush, getId() here is null
        // and the production cache would write to "quote:null". This pins it.
        assertThat(captor.getValue().getId())
                .as("Cached quote must have a non-null id; otherwise the Redis "
                  + "key becomes 'quote:null'. See QuoteRepository.save()'s "
                  + "em.flush() comment.")
                .isNotNull()
                .isEqualTo(42L);
        verify(publisher).publishCalculated(captor.getValue());
    }

    @Test
    @DisplayName("getById hits the cache first and skips the repo on a cache hit")
    void getByIdReturnsCachedQuoteWithoutDbCall() {
        Quote cached = new Quote();
        cached.setId(7L);
        when(cache.get(7L)).thenReturn(cached);

        Quote got = service.getById(7L);

        assertThat(got).isSameAs(cached);
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("getById on a cache miss queries the repo and populates the cache")
    void getByIdPopulatesCacheOnMiss() {
        Quote fromDb = new Quote();
        fromDb.setId(9L);
        when(cache.get(9L)).thenReturn(null);
        when(repo.findById(9L)).thenReturn(fromDb);

        Quote got = service.getById(9L);

        assertThat(got).isSameAs(fromDb);
        verify(cache).put(fromDb);
    }

    @Test
    @DisplayName("getById on a cache miss + DB miss returns null and does not poison the cache")
    void getByIdReturnsNullOnDoubleMiss() {
        when(cache.get(404L)).thenReturn(null);
        when(repo.findById(404L)).thenReturn(null);

        assertThat(service.getById(404L)).isNull();
        verify(cache, never()).put(any(Quote.class));
    }
}
