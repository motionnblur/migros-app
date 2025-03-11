package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.EncryptService;
import com.example.MigrosBackend.service.global.MailService;
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

    private final ConcurrentHashMap<String, UserEntity> tokenToUserMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    public UserSignupService(UserEntityRepository userEntityRepository, EncryptService encryptService, MailService mailService) {
        this.userEntityRepository = userEntityRepository;
        this.encryptService = encryptService;
        this.mailService = mailService;
    }
    public void signup(UserSignDto userSignDto) throws MessagingException {
        UserEntity userEntityToCreate = new UserEntity();
        userEntityToCreate.setUserMail(userSignDto.getUserMail());
        userEntityToCreate.setUserPassword(encryptService.getEncryptedPassword(userSignDto.getUserPassword()));

        if(userEntityRepository.existsByUserMail(userSignDto.getUserMail()))
            throw new RuntimeException("User with that email: "+ userSignDto.getUserMail()+" already exists.");

        String key = Long.toHexString(Double.doubleToLongBits(Math.random()));
        String confirmationLink = "http://localhost:8080/user/signup/confirm?token=" + key;
        tokenToUserMap.put(key, userEntityToCreate);

        scheduler.schedule(() -> {
            tokenToUserMap.remove(key);
        }, 5, TimeUnit.MINUTES);

        String confirmationLinkHtml = "<a href=\"" + confirmationLink + "\">" + confirmationLink + "</a>";
        mailService.sendMimeMessage(userSignDto.getUserMail(), "Migros", "<html><h1>Welcome to Migros!</h1><br>Please confirm your email address by clicking the link below in 5 minutes:<br>" + confirmationLinkHtml + "<br><br>If you did not request this email, please ignore it.</html>");
    }
    public void login(UserSignDto userSignDto) {
        UserEntity userEntity = userEntityRepository.findByUserMail(userSignDto.getUserMail());
        if (userEntity == null) throw new RuntimeException("User with that email: " + userSignDto.getUserMail() + " could not be found.");

        if(!encryptService.checkIfPasswordMatches(userSignDto.getUserPassword(), userEntity.getUserPassword()))
            throw new RuntimeException("Wrong password.");
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
