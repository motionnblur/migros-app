package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.dto.user.sign.ResetPasswordDto;
import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.user.PendingSignupEntity;
import com.example.MigrosBackend.entity.user.PendingTokenPurpose;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import com.example.MigrosBackend.exception.shared.WrongPasswordException;
import com.example.MigrosBackend.exception.user.MailSendingFailedException;
import com.example.MigrosBackend.exception.user.UserAlreadyExistsException;
import com.example.MigrosBackend.exception.user.UserMailNotFoundException;
import com.example.MigrosBackend.exception.user.WeakPasswordException;
import com.example.MigrosBackend.config.PublicUrlProperties;
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
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class UserSignupService {

    private static final Logger LOG = LoggerFactory.getLogger(UserSignupService.class);

    private final UserEntityRepository userEntityRepository;
    private final PendingSignupStorage pendingSignupStorage;
    private final EncryptService encryptService;
    private final MailService mailService;
    private final TokenService tokenService;
    private final PasswordValidator passwordValidator;
    private final PublicUrlProperties publicUrlProperties;
    private final long confirmationTokenTtlMinutes;

    @Autowired
    public UserSignupService(UserEntityRepository userEntityRepository,
            PendingSignupEntityRepository pendingSignupEntityRepository,
            EncryptService encryptService,
            MailService mailService, TokenService tokenService,
            PasswordValidator passwordValidator,
            PublicUrlProperties publicUrlProperties,
            PlatformTransactionManager transactionManager,
            @Value("${app.signup.confirmation.ttl-minutes:15}") long confirmationTokenTtlMinutes) {
        this.userEntityRepository = userEntityRepository;
        this.pendingSignupStorage = new PendingSignupStorage(pendingSignupEntityRepository, transactionManager);
        this.encryptService = encryptService;
        this.mailService = mailService;
        this.tokenService = tokenService;
        this.passwordValidator = passwordValidator;
        this.publicUrlProperties = publicUrlProperties;
        this.confirmationTokenTtlMinutes = confirmationTokenTtlMinutes;
    }

    /**
     * Registers a pending signup.
     *
     * <p>Ordering is load-bearing. The token is committed to the database
     * <em>before</em> the mail is sent, so a delivered link can never reference a
     * token that was never stored or that a later failure removed. This method
     * is deliberately not transactional: a caller-managed transaction would hold
     * the insert open across the send, which is exactly the window this ordering
     * exists to close.
     *
     * <p>A mail failure is reported exactly as before, and the token is revoked
     * explicitly so no undeliverable credential is left behind.
     */
    public void signup(UserSignDto userSignDto) {
        if (userEntityRepository.existsByUserMail(userSignDto.getUserMail())) {
            throw new UserAlreadyExistsException(userSignDto.getUserMail());
        }

        if (!passwordValidator.isPasswordStrongEnough(userSignDto.getUserPassword())) {
            throw new WeakPasswordException();
        }

        UserEntity userEntityToCreate = new UserEntity();
        userEntityToCreate.setUserMail(userSignDto.getUserMail());
        userEntityToCreate.setUserPassword(encryptService.getEncryptedPassword(userSignDto.getUserPassword()));

        String key = newToken();
        String confirmationLink = publicUrlProperties.normalizedBackendBaseUrl()
                + "/user/signup/confirm?token=" + UriUtils.encodeQueryParam(key, StandardCharsets.UTF_8);
        pendingSignupStorage.store(new PendingSignupEntity(
                key,
                userEntityToCreate.getUserMail(),
                userEntityToCreate.getUserPassword(),
                expiresAt(),
                PendingTokenPurpose.SIGNUP
        ));

        sendLinkOrRevoke(userSignDto.getUserMail(), confirmationLink, key);
    }

    public String login(UserSignDto userSignDto) {
        UserEntity userEntity = userEntityRepository.findByUserMail(userSignDto.getUserMail());
        if (userEntity == null) {
            throw new UserMailNotFoundException(userSignDto.getUserMail());
        }

        if (!encryptService.checkIfPasswordMatches(userSignDto.getUserPassword(), userEntity.getUserPassword())) {
            throw new WrongPasswordException();
        }

        return tokenService.generateUserToken(userEntity.getUserMail());
    }

    /**
     * Consumes a signup token. A token issued for any other purpose (a
     * password reset, for example) is rejected exactly like an unknown token.
     *
     * <p>The token is redeemed (deleted) first and the account is inserted in
     * the same transaction. If the insert fails the redemption rolls back with
     * it and the token stays usable; if two callers race, only the one whose
     * conditional DELETE matched a row reaches the insert, so a token can never
     * create two accounts.
     */
    @Transactional
    public void confirm(String token) {
        PendingSignupEntity pendingSignup =
                pendingSignupStorage.redeem(token, PendingTokenPurpose.SIGNUP);
        if (pendingSignup == null) {
            throw new TokenNotFoundException();
        }

        UserEntity userEntity = new UserEntity();
        userEntity.setUserMail(pendingSignup.getUserMail());
        userEntity.setUserPassword(pendingSignup.getUserPassword());

        userEntityRepository.save(userEntity);
    }

    @Transactional
    public void confirmUserMail(String token) {
        requireToken(token, PendingTokenPurpose.SIGNUP);
    }

    /**
     * Consumes a password-reset token. A signup token is rejected exactly like
     * an unknown token, so a signup link can never be used to choose a
     * password.
     *
     * <p>As in {@link #confirm(String)}, the token is redeemed first and the
     * password change happens in the same transaction, so a weak password or a
     * missing account rolls the redemption back instead of burning the token.
     */
    @Transactional
    public void resetPassword(ResetPasswordDto resetPasswordDto) {
        if (resetPasswordDto == null
                || resetPasswordDto.getToken() == null
                || resetPasswordDto.getToken().isBlank()) {
            throw new TokenNotFoundException();
        }

        String token = resetPasswordDto.getToken();
        PendingSignupEntity pendingSignup =
                pendingSignupStorage.redeem(token, PendingTokenPurpose.PASSWORD_RESET);
        if (pendingSignup == null) {
            throw new TokenNotFoundException();
        }

        if (!passwordValidator.isPasswordStrongEnough(resetPasswordDto.getUserPassword())) {
            throw new WeakPasswordException();
        }

        UserEntity userEntity = userEntityRepository.findByUserMail(pendingSignup.getUserMail());
        if (userEntity == null) {
            throw new UserMailNotFoundException(pendingSignup.getUserMail());
        }

        userEntity.setUserPassword(encryptService.getEncryptedPassword(resetPasswordDto.getUserPassword()));
        userEntityRepository.save(userEntity);
    }

    /**
     * Issues a password-reset token. As with signup, the token is committed
     * before the mail is sent and revoked when the mail cannot be delivered.
     */
    public void verifyUserMail(String userMail) {
        UserEntity userEntity = userEntityRepository.findByUserMail(userMail);
        if (userEntity == null) {
            throw new UserMailNotFoundException(userMail);
        }

        String key = newToken();
        String confirmationLink = publicUrlProperties.normalizedFrontendBaseUrl()
                + "/reset-password/" + UriUtils.encodePathSegment(key, StandardCharsets.UTF_8);

        pendingSignupStorage.store(new PendingSignupEntity(
                key,
                userEntity.getUserMail(),
                userEntity.getUserPassword(),
                expiresAt(),
                PendingTokenPurpose.PASSWORD_RESET
        ));

        sendLinkOrRevoke(userMail, confirmationLink, key);
    }

    private PendingSignupEntity requireToken(String token, PendingTokenPurpose purpose) {
        PendingSignupEntity pendingSignup = pendingSignupStorage.findActiveToken(token, purpose);
        if (pendingSignup == null) {
            throw new TokenNotFoundException();
        }
        return pendingSignup;
    }

    /**
     * Sends the link and, when the send fails, revokes the token that was
     * already committed for it.
     *
     * <p>The token can no longer be rolled back with the rest of the operation,
     * because it was deliberately committed first. It therefore has to be
     * deleted explicitly, otherwise an undeliverable credential would stay
     * redeemable for its whole lifetime. The reported failure is always the mail
     * failure: a cleanup problem is logged - never the token itself - and must
     * not replace the response the caller has to report.
     */
    private void sendLinkOrRevoke(String userMail, String link, String issuedToken) {
        try {
            sendLink(userMail, link);
        } catch (MailSendingFailedException ex) {
            try {
                pendingSignupStorage.deleteCommitted(issuedToken);
            } catch (RuntimeException cleanupFailure) {
                LOG.warn("Failed to revoke a pending token whose mail could not be delivered: {}",
                        cleanupFailure.getClass().getSimpleName());
            }
            throw ex;
        }
    }

    /**
     * Sends the link, translating every way the send can fail into the single
     * exception the caller reports.
     *
     * <p>Spring's mail support reports most failures unchecked.
     * {@code JavaMailSenderImpl.send} wraps an SMTP refusal in
     * {@code MailSendException}, and a message it cannot even assemble becomes a
     * {@code MailPreparationException}; both are {@link MailException} and
     * neither is a {@code MessagingException}. Catching only the checked
     * exception therefore let the common failure escape as an unexpected
     * runtime error, which skipped the revocation in
     * {@link #sendLinkOrRevoke} and left a credential nobody ever received
     * redeemable for its whole lifetime.
     */
    private void sendLink(String userMail, String link) {
        Context context = new Context();
        context.setVariable("confirmationLink", link);

        try {
            mailService.sendMimeMessage(userMail, "Welcome to Migros!", "confirmation-email", context);
        } catch (MessagingException | MailException e) {
            throw new MailSendingFailedException();
        }
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private LocalDateTime expiresAt() {
        return LocalDateTime.now().plusMinutes(confirmationTokenTtlMinutes);
    }
}
