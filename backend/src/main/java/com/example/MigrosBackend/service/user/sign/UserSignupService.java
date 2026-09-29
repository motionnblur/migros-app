package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.dto.user.sign.ResetPasswordDto;
import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.user.PendingSignupEntity;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class UserSignupService {

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
            @Value("${app.signup.confirmation.ttl-minutes:15}") long confirmationTokenTtlMinutes) {
        this.userEntityRepository = userEntityRepository;
        this.pendingSignupStorage = new PendingSignupStorage(pendingSignupEntityRepository, confirmationTokenTtlMinutes);
        this.encryptService = encryptService;
        this.mailService = mailService;
        this.tokenService = tokenService;
        this.passwordValidator = passwordValidator;
        this.publicUrlProperties = publicUrlProperties;
        this.confirmationTokenTtlMinutes = confirmationTokenTtlMinutes;
    }

    @Transactional
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

        String key = UUID.randomUUID().toString().replace("-", "");
        String confirmationLink = publicUrlProperties.normalizedBackendBaseUrl()
                + "/user/signup/confirm?token=" + UriUtils.encodeQueryParam(key, StandardCharsets.UTF_8);
        PendingSignupEntity pendingSignup = new PendingSignupEntity(
                key,
                userEntityToCreate.getUserMail(),
                userEntityToCreate.getUserPassword(),
                LocalDateTime.now().plusMinutes(confirmationTokenTtlMinutes)
        );
        pendingSignupStorage.store(pendingSignup);

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
        if (userEntity == null) {
            throw new UserMailNotFoundException(userSignDto.getUserMail());
        }

        if (!encryptService.checkIfPasswordMatches(userSignDto.getUserPassword(), userEntity.getUserPassword())) {
            throw new WrongPasswordException();
        }

        return tokenService.generateUserToken(userEntity.getUserMail());
    }

    @Transactional
    public void confirm(String token) {
        PendingSignupEntity pendingSignup = pendingSignupStorage.findByToken(token);
        if (pendingSignup == null) {
            throw new TokenNotFoundException();
        }

        if (pendingSignup.getExpiresAt() == null || pendingSignup.getExpiresAt().isBefore(LocalDateTime.now())) {
            pendingSignupStorage.delete(token);
            throw new TokenNotFoundException();
        }

        UserEntity userEntity = new UserEntity();
        userEntity.setUserMail(pendingSignup.getUserMail());
        userEntity.setUserPassword(pendingSignup.getUserPassword());

        userEntityRepository.save(userEntity);
        pendingSignupStorage.delete(token);
    }

    @Transactional
    public void confirmUserMail(String token) {
        PendingSignupEntity pendingSignup = pendingSignupStorage.findByToken(token);
        if (pendingSignup == null) {
            throw new TokenNotFoundException();
        }

        if (pendingSignup.getExpiresAt() == null || pendingSignup.getExpiresAt().isBefore(LocalDateTime.now())) {
            pendingSignupStorage.delete(token);
            throw new TokenNotFoundException();
        }
    }

    @Transactional
    public void resetPassword(ResetPasswordDto resetPasswordDto) {
        if (resetPasswordDto == null
                || resetPasswordDto.getToken() == null
                || resetPasswordDto.getToken().isBlank()) {
            throw new TokenNotFoundException();
        }

        String token = resetPasswordDto.getToken();
        PendingSignupEntity pendingSignup = pendingSignupStorage.findByToken(token);
        if (pendingSignup == null) {
            throw new TokenNotFoundException();
        }

        if (pendingSignup.getExpiresAt() == null
                || pendingSignup.getExpiresAt().isBefore(LocalDateTime.now())) {
            pendingSignupStorage.delete(token);
            throw new TokenNotFoundException();
        }

        if (!passwordValidator.isPasswordStrongEnough(resetPasswordDto.getUserPassword())) {
            throw new WeakPasswordException();
        }

        UserEntity userEntity = userEntityRepository.findByUserMail(pendingSignup.getUserMail());
        if (userEntity == null) {
            pendingSignupStorage.delete(token);
            throw new UserMailNotFoundException(pendingSignup.getUserMail());
        }

        userEntity.setUserPassword(encryptService.getEncryptedPassword(resetPasswordDto.getUserPassword()));
        userEntityRepository.save(userEntity);

        pendingSignupStorage.delete(token);
    }

    public void verifyUserMail(String userMail) {
        UserEntity userEntity = userEntityRepository.findByUserMail(userMail);
        if (userEntity == null) {
            throw new UserMailNotFoundException(userMail);
        }

        String key = UUID.randomUUID().toString().replace("-", "");
        String confirmationLink = publicUrlProperties.normalizedFrontendBaseUrl()
                + "/reset-password/" + UriUtils.encodePathSegment(key, StandardCharsets.UTF_8);

        PendingSignupEntity pendingSignup = new PendingSignupEntity();
        pendingSignup.setToken(key);
        pendingSignup.setUserMail(userEntity.getUserMail()); // Burada userEntity kullanıldı

        // Şifreyi de veritabanından gelen kullanıcıdan alıyoruz
        pendingSignup.setUserPassword(userEntity.getUserPassword());
        pendingSignup.setExpiresAt(LocalDateTime.now().plusMinutes(confirmationTokenTtlMinutes));

        // 3. Kaydet
        pendingSignupStorage.store(pendingSignup);

        Context context = new Context();
        context.setVariable("confirmationLink", confirmationLink);

        try {
            mailService.sendMimeMessage(userMail, "Welcome to Migros!", "confirmation-email", context);
        } catch (MessagingException e) {
            throw new MailSendingFailedException();
        }
    }
}
