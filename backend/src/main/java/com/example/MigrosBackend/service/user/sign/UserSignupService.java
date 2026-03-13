package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import com.example.MigrosBackend.exception.shared.WrongPasswordException;
import com.example.MigrosBackend.exception.user.MailSendingFailedException;
import com.example.MigrosBackend.exception.user.UserAlreadyExistsException;
import com.example.MigrosBackend.exception.user.UserMailNotFoundException;
import com.example.MigrosBackend.exception.user.WeakPasswordException;
import com.example.MigrosBackend.helper.PasswordValidator;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.EncryptService;
import com.example.MigrosBackend.service.global.MailService;
import com.example.MigrosBackend.service.global.TokenService;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class UserSignupService {
    private final UserEntityRepository userEntityRepository;
    private final EncryptService encryptService;
    private final MailService mailService;
    private final TokenService tokenService;
    private final PasswordValidator passwordValidator;
    private final String publicBaseUrl;

    private final ConcurrentHashMap<String, UserEntity> tokenToUserMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    public UserSignupService(UserEntityRepository userEntityRepository, EncryptService encryptService,
                             MailService mailService, TokenService tokenService,
                             PasswordValidator passwordValidator,
                             @Value("${app.public-base-url:https://migros-app.onrender.com}") String publicBaseUrl) {
        this.userEntityRepository = userEntityRepository;
        this.encryptService = encryptService;
        this.mailService = mailService;
        this.tokenService = tokenService;
        this.passwordValidator = passwordValidator;
        this.publicBaseUrl = normalizeBaseUrl(publicBaseUrl);
    }

    public void signup(UserSignDto userSignDto) {
        if (userEntityRepository.existsByUserMail(userSignDto.getUserMail()))
            throw new UserAlreadyExistsException(userSignDto.getUserMail());

        if (!passwordValidator.isPasswordStrongEnough(userSignDto.getUserPassword()))
            throw new WeakPasswordException();

        UserEntity userEntityToCreate = new UserEntity();
        userEntityToCreate.setUserMail(userSignDto.getUserMail());
        userEntityToCreate.setUserPassword(encryptService.getEncryptedPassword(userSignDto.getUserPassword()));

        String key = Long.toHexString(Double.doubleToLongBits(Math.random()));
        String confirmationLink = publicBaseUrl + "/user/signup/confirm?token=" + key;
        tokenToUserMap.put(key, userEntityToCreate);

        scheduler.schedule(() -> {
            tokenToUserMap.remove(key);
        }, 5, TimeUnit.MINUTES);

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

    public void confirm(String token) {
        UserEntity userEntity = tokenToUserMap.get(token);
        if (userEntity != null) {
            userEntityRepository.save(userEntity);
            tokenToUserMap.remove(token);
        } else {
            throw new TokenNotFoundException();
        }
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return "https://migros-app.onrender.com";
        }

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }
}
