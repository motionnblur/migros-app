package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
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

    private final ConcurrentHashMap<String, UserEntity> tokenToUserMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    public UserSignupService(UserEntityRepository userEntityRepository, EncryptService encryptService,
                             MailService mailService, TokenService tokenService) {
        this.userEntityRepository = userEntityRepository;
        this.encryptService = encryptService;
        this.mailService = mailService;
        this.tokenService = tokenService;
    }

    public boolean isPasswordStrongEnough(String password) {
        // Define the password policy
        int minPasswordLength = 8;
        boolean requiresUppercase = true;
        boolean requiresLowercase = true;
        boolean requiresNumber = true;
        boolean requiresSpecialChar = true;

        // Check the password length
        if (password.length() < minPasswordLength) {
            return false;
        }

        // Check for uppercase letter
        if (requiresUppercase && !password.matches(".*[A-Z].*")) {
            return false;
        }

        // Check for lowercase letter
        if (requiresLowercase && !password.matches(".*[a-z].*")) {
            return false;
        }

        // Check for number
        if (requiresNumber && !password.matches(".*\\d.*")) {
            return false;
        }

        // Check for special character
        if (requiresSpecialChar && !password.matches(".*[^A-Za-z0-9].*")) {
            return false;
        }

        return true;
    }
    public void signup(UserSignDto userSignDto) throws MessagingException {
        if(userEntityRepository.existsByUserMail(userSignDto.getUserMail()))
            throw new RuntimeException("User with that email: "+ userSignDto.getUserMail()+" already exists.");

        if(!isPasswordStrongEnough(userSignDto.getUserPassword()))
            throw new RuntimeException("Password is not strong enough.");
        
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
        mailService.sendMimeMessage(userSignDto.getUserMail(), "Migros", "<html><h1>Welcome to Migros!</h1><br>Please confirm your email address by clicking the link below in 5 minutes:<br>" + confirmationLinkHtml + "<br><br>If you did not request this email, please ignore it.</html>");
    }
    public String login(UserSignDto userSignDto) {
        UserEntity userEntity = userEntityRepository.findByUserMail(userSignDto.getUserMail());
        if (userEntity == null) throw new RuntimeException("User with that email: " + userSignDto.getUserMail() + " could not be found.");

        if(!encryptService.checkIfPasswordMatches(userSignDto.getUserPassword(), userEntity.getUserPassword()))
            throw new RuntimeException("Wrong password.");

        return tokenService.generateToken(userEntity.getUserMail());
    }
    public void confirm(String token) {
        UserEntity userEntity = tokenToUserMap.get(token);
        if (userEntity != null) {
            // User confirmed, save to database
            userEntityRepository.save(userEntity);
            tokenToUserMap.remove(token);
        } else {
            // Token not found or expired
            throw new RuntimeException("Token not found or expired");
        }
    }
}
