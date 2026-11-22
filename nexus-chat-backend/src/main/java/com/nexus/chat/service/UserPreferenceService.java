package com.nexus.chat.service;

import com.nexus.chat.dto.UserPreferenceDTO;
import com.nexus.chat.model.UserPreference;
import com.nexus.chat.repository.UserPreferenceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Account-scoped preferences: read with defaults, write with merge semantics.
 *
 * Partial updates matter here — the client sends whichever fields the user just
 * changed, and a null means "leave it alone", not "set it to null".
 *
 * Two races are handled explicitly:
 * <ul>
 *   <li>First access from two requests at once: both see no row and both insert.
 *       {@code user_id} is unique, so the loser gets a constraint violation. Each
 *       attempt runs in its own transaction, so the loser can simply run again
 *       and find the winner's row.</li>
 *   <li>Two partial updates on different fields: the entity is
 *       {@code @DynamicUpdate}, so each writes only its own column instead of
 *       re-writing the whole row from a stale read.</li>
 * </ul>
 */
@Service
public class UserPreferenceService {

    /** One retry is enough: after a lost insert race the row is guaranteed to exist. */
    private static final int ATTEMPTS = 2;

    private final UserPreferenceRepository repository;
    private final TransactionTemplate transactions;

    public UserPreferenceService(UserPreferenceRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        // A failed unique-key insert must be rolled back before the next attempt,
        // even if a future caller already has an outer transaction.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Read the stored preferences, creating a default row on first access. */
    public UserPreferenceDTO getPreferences(Long userId) {
        return withInsertRetry(() -> {
            UserPreference entity = repository.findByUserId(userId)
                    .orElseGet(() -> repository.saveAndFlush(defaultsFor(userId)));
            return toDto(entity);
        });
    }

    /** Merge the supplied fields into the stored row. */
    public UserPreferenceDTO updatePreferences(Long userId, UserPreferenceDTO request) {
        return withInsertRetry(() -> {
            UserPreference entity = repository.findByUserId(userId).orElseGet(() -> defaultsFor(userId));

            if (request.getHourCycle() != null) {
                entity.setHourCycle(request.getHourCycle());
            }
            if (request.getGlassIntensity() != null) {
                entity.setGlassIntensity(request.getGlassIntensity());
            }
            if (request.getSoundEnabled() != null) {
                entity.setSoundEnabled(request.getSoundEnabled());
            }

            // Flush inside the transaction so a unique-key conflict surfaces here,
            // where it can be retried, rather than at commit.
            return toDto(repository.saveAndFlush(entity));
        });
    }

    private UserPreferenceDTO withInsertRetry(java.util.function.Supplier<UserPreferenceDTO> work) {
        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                return transactions.execute(status -> work.get());
            } catch (DataIntegrityViolationException e) {
                last = e; // another request inserted this user's row first
            }
        }
        throw last;
    }

    private UserPreference defaultsFor(Long userId) {
        UserPreference entity = new UserPreference();
        entity.setUserId(userId);
        entity.setHourCycle("24");
        entity.setGlassIntensity(100);
        entity.setSoundEnabled(true);
        return entity;
    }

    private UserPreferenceDTO toDto(UserPreference entity) {
        return new UserPreferenceDTO(
                entity.getHourCycle(),
                entity.getGlassIntensity(),
                entity.getSoundEnabled());
    }
}
