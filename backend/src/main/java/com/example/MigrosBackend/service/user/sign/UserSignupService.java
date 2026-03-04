package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.*;
import com.example.MigrosBackend.helper.PasswordValidator;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.EncryptService;
import com.example.MigrosBackend.service.global.MailService;
import com.example.MigrosBackend.service.global.TokenService;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    private final ConcurrentHashMap<String, UserEntity> tokenToUserMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    public UserSignupService(UserEntityRepository userEntityRepository, EncryptService encryptService,
                             MailService mailService, TokenService tokenService,
                             PasswordValidator passwordValidator) {
        this.userEntityRepository = userEntityRepository;
        this.encryptService = encryptService;
        this.mailService = mailService;
        this.tokenService = tokenService;
        this.passwordValidator = passwordValidator;
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
        String confirmationLink = "http://localhost:8080/user/signup/confirm?token=" + key;
        tokenToUserMap.put(key, userEntityToCreate);

        scheduler.schedule(() -> {
            tokenToUserMap.remove(key);
        }, 5, TimeUnit.MINUTES);

        String confirmationLinkHtml = "<a href=\"" + confirmationLink + "\">" + confirmationLink + "</a>";

        try {
            mailService.sendMimeMessage(userSignDto.getUserMail(), "Migros", "<html><h1>Welcome to Migros!</h1><br>Please confirm your email address by clicking the link below in 5 minutes:<br>" + confirmationLinkHtml + "<br><br>If you did not request this email, please ignore it.</html>");
        } catch (MessagingException e) {
            throw new MailSendingFailedException();
        }
    }

    public String login(UserSignDto userSignDto) {
        UserEntity userEntity = userEntityRepository.findByUserMail(userSignDto.getUserMail());
        if (userEntity == null) throw new UserMailCouldNotFoundException(userSignDto.getUserMail());

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
}
