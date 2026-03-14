package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.user.PendingSignupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import com.example.MigrosBackend.exception.shared.WrongPasswordException;
import com.example.MigrosBackend.exception.user.MailSendingFailedException;
import com.example.MigrosBackend.exception.user.UserAlreadyExistsException;
import com.example.MigrosBackend.exception.user.UserMailNotFoundException;
import com.example.MigrosBackend.exception.user.WeakPasswordException;
import com.example.MigrosBackend.helper.PasswordValidator;
import com.example.MigrosBackend.repository.user.PendingSignupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.EncryptService;
import com.example.MigrosBackend.service.global.MailService;
import com.example.MigrosBackend.service.global.TokenService;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class UserSignupService {
    private static final Logger log = LoggerFactory.getLogger(UserSignupService.class);

    private final UserEntityRepository userEntityRepository;
    private final PendingSignupEntityRepository pendingSignupEntityRepository;
    private final EncryptService encryptService;
    private final MailService mailService;
    private final TokenService tokenService;
    private final PasswordValidator passwordValidator;
    private final String publicBaseUrl;
    private final long confirmationTokenTtlMinutes;
    private final ConcurrentHashMap<String, PendingSignupEntity> fallbackPendingSignups = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean pendingSignupStorageAvailable = true;

    @Autowired
    public UserSignupService(UserEntityRepository userEntityRepository,
                             PendingSignupEntityRepository pendingSignupEntityRepository,
                             EncryptService encryptService,
                             MailService mailService, TokenService tokenService,
                             PasswordValidator passwordValidator,
                             @Value("${app.public-base-url}") String publicBaseUrl,
                             @Value("${app.signup.confirmation.ttl-minutes:15}") long confirmationTokenTtlMinutes) {
        this.userEntityRepository = userEntityRepository;
        this.pendingSignupEntityRepository = pendingSignupEntityRepository;
        this.encryptService = encryptService;
        this.mailService = mailService;
        this.tokenService = tokenService;
        this.passwordValidator = passwordValidator;
        this.publicBaseUrl = normalizeBaseUrl(publicBaseUrl);
        this.confirmationTokenTtlMinutes = confirmationTokenTtlMinutes;
    }

    @Transactional
    public void signup(UserSignDto userSignDto) {
        if (userEntityRepository.existsByUserMail(userSignDto.getUserMail()))
            throw new UserAlreadyExistsException(userSignDto.getUserMail());

        if (!passwordValidator.isPasswordStrongEnough(userSignDto.getUserPassword()))
            throw new WeakPasswordException();

        UserEntity userEntityToCreate = new UserEntity();
        userEntityToCreate.setUserMail(userSignDto.getUserMail());
        userEntityToCreate.setUserPassword(encryptService.getEncryptedPassword(userSignDto.getUserPassword()));

        String key = UUID.randomUUID().toString().replace("-", "");
        String confirmationLink = publicBaseUrl + "/user/signup/confirm?token=" + key;
        PendingSignupEntity pendingSignup = new PendingSignupEntity(
                key,
                userEntityToCreate.getUserMail(),
                userEntityToCreate.getUserPassword(),
                LocalDateTime.now().plusMinutes(confirmationTokenTtlMinutes)
        );
        storePendingSignup(pendingSignup);

        Context context = new Context();
        context.setVariable("confirmationLink", confirmationLink);

        try {
            mailService.sendMimeMessage(userSignDto.getUserMail(), "Welcome to Migros!", "confirmation-email", context);
        } catch (MessagingException e) {
            throw new MailSendingFailedException();
        }
    }

    public String login(UserSignDto userSignDto) {
        UserEntity userEntity = userEntityRepository.findByUserMail(userSignDto.getUserMail());
        if (userEntity == null) throw new UserMailNotFoundException(userSignDto.getUserMail());

        if (!encryptService.checkIfPasswordMatches(userSignDto.getUserPassword(), userEntity.getUserPassword()))
            throw new WrongPasswordException();

        return tokenService.generateToken(userEntity.getUserMail());
    }

    @Transactional
    public void confirm(String token) {
        PendingSignupEntity pendingSignup = findPendingSignupByToken(token);
        if (pendingSignup == null) {
            throw new TokenNotFoundException();
        }

        if (pendingSignup.getExpiresAt() == null || pendingSignup.getExpiresAt().isBefore(LocalDateTime.now())) {
            deletePendingSignup(token);
            throw new TokenNotFoundException();
        }

        UserEntity userEntity = new UserEntity();
        userEntity.setUserMail(pendingSignup.getUserMail());
        userEntity.setUserPassword(pendingSignup.getUserPassword());

        userEntityRepository.save(userEntity);
        deletePendingSignup(token);
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalStateException("app.public-base-url must be configured");
        }

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private void storePendingSignup(PendingSignupEntity pendingSignup) {
        if (pendingSignupStorageAvailable) {
            try {
                pendingSignupEntityRepository.deleteByUserMail(pendingSignup.getUserMail());
                pendingSignupEntityRepository.save(pendingSignup);
                return;
            } catch (RuntimeException ex) {
                pendingSignupStorageAvailable = false;
                log.warn("Pending signup DB storage failed. Falling back to in-memory tokens.", ex);
            }
        }

        fallbackPendingSignups.entrySet().removeIf(entry ->
                pendingSignup.getUserMail().equals(entry.getValue().getUserMail()));
        fallbackPendingSignups.put(pendingSignup.getToken(), pendingSignup);
        scheduleFallbackTokenExpiry(pendingSignup.getToken());
    }

    private PendingSignupEntity findPendingSignupByToken(String token) {
        if (pendingSignupStorageAvailable) {
            try {
                PendingSignupEntity fromDatabase = pendingSignupEntityRepository.findById(token).orElse(null);
                if (fromDatabase != null) {
                    return fromDatabase;
                }
            } catch (RuntimeException ex) {
                pendingSignupStorageAvailable = false;
                log.warn("Pending signup DB read failed. Falling back to in-memory tokens.", ex);
            }
        }

        return fallbackPendingSignups.get(token);
    }

    private void deletePendingSignup(String token) {
        fallbackPendingSignups.remove(token);

        if (pendingSignupStorageAvailable) {
            try {
                pendingSignupEntityRepository.deleteById(token);
            } catch (RuntimeException ex) {
                pendingSignupStorageAvailable = false;
                log.warn("Pending signup DB delete failed. Falling back to in-memory tokens.", ex);
            }
        }
    }

    private void scheduleFallbackTokenExpiry(String token) {
        long ttlSeconds = Math.max(1, confirmationTokenTtlMinutes * 60);
        scheduler.schedule(() -> fallbackPendingSignups.remove(token), ttlSeconds, TimeUnit.SECONDS);
    }
}
