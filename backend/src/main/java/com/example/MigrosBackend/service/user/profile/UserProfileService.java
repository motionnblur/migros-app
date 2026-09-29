package com.example.MigrosBackend.service.user.profile;

import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and edits the customer's own profile.
 *
 * <p>The user row also holds the cart list, the password hash and the
 * moderation ban flag, each written by a different request that may run
 * concurrently. The update therefore names the profile columns instead of
 * saving a whole entity: loading the row and writing it back would put back
 * every unrelated column as it was read, silently undoing whatever the cart,
 * the password reset or a moderator did in between.
 */
@Service
public class UserProfileService {
    private final UserEntityRepository userEntityRepository;

    public UserProfileService(UserEntityRepository userEntityRepository) {
        this.userEntityRepository = userEntityRepository;
    }

    /**
     * Writes only the profile columns.
     *
     * <p>Runs in its own transaction so the update is the only write in it, and
     * never loads a {@link UserEntity}: a loaded instance would be the one thing
     * able to write the columns this path does not own.
     */
    @Transactional
    public void uploadUserProfileTable(String userFirstName, String userLastName, String userAddress, String userAddress2,
                                       String userTown, String userCountry, String userPostalCode, String userMail) {
        int updated = userEntityRepository.updateProfileColumnsByUserMail(
                userMail,
                userFirstName,
                userLastName,
                userAddress,
                userAddress2,
                userTown,
                userCountry,
                userPostalCode);

        if (updated == 0) {
            // The account the session points at is gone. Reported as the
            // existing not-found error rather than as a silent success, which
            // is what dereferencing the missing row used to do (an NPE).
            throw new UserNotFoundException(userMail);
        }
    }

    @Transactional(readOnly = true)
    public UserProfileTableDto getUserProfileTable(String userMail) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        UserProfileTableDto userProfileTableDto = new UserProfileTableDto();
        userProfileTableDto.setUserFirstName(user.getUserName());
        userProfileTableDto.setUserLastName(user.getUserLastName());
        userProfileTableDto.setUserAddress(user.getUserAddress());
        userProfileTableDto.setUserAddress2(user.getUserAddress2());
        userProfileTableDto.setUserTown(user.getUserTown());
        userProfileTableDto.setUserCountry(user.getUserCountry());
        userProfileTableDto.setUserPostalCode(user.getUserPostalCode());

        return userProfileTableDto;
    }
}
