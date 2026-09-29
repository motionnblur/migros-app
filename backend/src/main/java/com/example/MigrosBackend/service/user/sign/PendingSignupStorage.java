package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.entity.user.PendingSignupEntity;
import com.example.MigrosBackend.repository.user.PendingSignupEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class PendingSignupStorage {

    private static final Logger log = LoggerFactory.getLogger(UserSignupService.class);

    private final PendingSignupEntityRepository repository;
    private final long confirmationTokenTtlMinutes;
    private final ConcurrentHashMap<String, PendingSignupEntity> fallbackPendingSignups = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean databaseStorageAvailable = true;

    PendingSignupStorage(PendingSignupEntityRepository repository, long confirmationTokenTtlMinutes) {
        this.repository = repository;
        this.confirmationTokenTtlMinutes = confirmationTokenTtlMinutes;
    }

    void store(PendingSignupEntity pendingSignup) {
        if (databaseStorageAvailable) {
            try {
                repository.deleteByUserMail(pendingSignup.getUserMail());
                repository.save(pendingSignup);
                return;
            } catch (RuntimeException ex) {
                databaseStorageAvailable = false;
                log.warn("Pending signup DB storage failed. Falling back to in-memory tokens.", ex);
            }
        }

        fallbackPendingSignups.entrySet().removeIf(entry
                -> pendingSignup.getUserMail().equals(entry.getValue().getUserMail()));
        fallbackPendingSignups.put(pendingSignup.getToken(), pendingSignup);
        scheduleFallbackTokenExpiry(pendingSignup.getToken());
    }

    PendingSignupEntity findByToken(String token) {
        if (databaseStorageAvailable) {
            try {
                PendingSignupEntity fromDatabase = repository.findById(token).orElse(null);
                if (fromDatabase != null) {
                    return fromDatabase;
                }
            } catch (RuntimeException ex) {
                databaseStorageAvailable = false;
                log.warn("Pending signup DB read failed. Falling back to in-memory tokens.", ex);
            }
        }

        return fallbackPendingSignups.get(token);
    }

    void delete(String token) {
        fallbackPendingSignups.remove(token);

        if (databaseStorageAvailable) {
            try {
                repository.deleteById(token);
            } catch (RuntimeException ex) {
                databaseStorageAvailable = false;
                log.warn("Pending signup DB delete failed. Falling back to in-memory tokens.", ex);
            }
        }
    }

    private void scheduleFallbackTokenExpiry(String token) {
        long ttlSeconds = Math.max(1, confirmationTokenTtlMinutes * 60);
        scheduler.schedule(() -> fallbackPendingSignups.remove(token), ttlSeconds, TimeUnit.SECONDS);
    }
}
