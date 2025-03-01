package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.EncryptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserSignupService {
    private final UserEntityRepository userEntityRepository;
    private final EncryptService encryptService;

    @Autowired
    public UserSignupService(UserEntityRepository userEntityRepository, EncryptService encryptService) {
        this.userEntityRepository = userEntityRepository;
        this.encryptService = encryptService;
    }

    public void signup(UserSignDto userSignDto) {
        UserEntity userEntityToCreate = new UserEntity();
        userEntityToCreate.setUserMail(userSignDto.getUserMail());
        userEntityToCreate.setUserPassword(encryptService.getEncryptedPassword(userSignDto.getUserPassword()));

        if(userEntityRepository.existsByUserMail(userSignDto.getUserMail()))
            throw new RuntimeException("User with that email: "+ userSignDto.getUserMail()+" already exists.");

        userEntityRepository.save(userEntityToCreate);
    }
    public void login(UserSignDto userSignDto) {
        UserEntity userEntity = userEntityRepository.findByUserMail(userSignDto.getUserMail());
        if (userEntity == null) throw new RuntimeException("User with that email: " + userSignDto.getUserMail() + " could not be found.");

        if(!encryptService.checkIfPasswordMatches(userSignDto.getUserPassword(), userEntity.getUserPassword()))
            throw new RuntimeException("Wrong password.");
    }
}
