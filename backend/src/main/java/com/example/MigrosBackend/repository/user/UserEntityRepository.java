package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.UserEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * The user row carries several independently owned concerns: the cart list, the
 * profile columns, the password hash and the moderation ban flag. Every write
 * below therefore names the columns it owns instead of saving a whole entity.
 *
 * <p>A {@code save} of a previously loaded {@link UserEntity} writes every
 * column, so a profile edit that read the row before a concurrent cart change
 * would put the cart it never looked at back at its stale value. Naming the
 * columns makes unrelated updates incapable of overwriting one another.
 */
public interface UserEntityRepository extends JpaRepository<UserEntity, Long>  {
    boolean existsByUserMail(String userMail);

    UserEntity findByUserMail(String userMail);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.userMail = :userMail")
    Optional<UserEntity> findByUserMailForUpdate(@Param("userMail") String userMail);

    /**
     * Writes the profile columns and nothing else.
     *
     * <p>{@code flushAutomatically} flushes whatever the transaction already has
     * pending before the statement runs, so a cart mutation staged earlier in
     * the same transaction is written first and the profile columns are then
     * applied on top of it instead of being reverted by it.
     *
     * <p>{@code clearAutomatically} stays off: clearing would detach unrelated
     * pending work in the same persistence context, which is a far larger blast
     * radius than the staleness it would prevent. Callers therefore must not
     * {@code save} a user they loaded before this call — see the class note.
     *
     * @return the number of rows updated; {@code 0} means the mailbox is unknown
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE UserEntity u
            SET u.userName = :userName,
                u.userLastName = :userLastName,
                u.userAddress = :userAddress,
                u.userAddress2 = :userAddress2,
                u.userTown = :userTown,
                u.userCountry = :userCountry,
                u.userPostalCode = :userPostalCode
            WHERE u.userMail = :userMail
            """)
    int updateProfileColumnsByUserMail(@Param("userMail") String userMail,
                                       @Param("userName") String userName,
                                       @Param("userLastName") String userLastName,
                                       @Param("userAddress") String userAddress,
                                       @Param("userAddress2") String userAddress2,
                                       @Param("userTown") String userTown,
                                       @Param("userCountry") String userCountry,
                                       @Param("userPostalCode") String userPostalCode);

    /**
     * Rotates only the password hash, leaving the cart, the profile and the ban
     * flag of a possibly concurrently edited account untouched.
     *
     * @return the number of rows updated; {@code 0} means the mailbox is unknown
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE UserEntity u SET u.userPassword = :userPassword WHERE u.userMail = :userMail")
    int updatePasswordByUserMail(@Param("userMail") String userMail,
                                 @Param("userPassword") String userPassword);

    /**
     * Writes only the moderation ban flag.
     *
     * @return the number of rows updated; {@code 0} means the mailbox is unknown
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE UserEntity u SET u.banned = :banned WHERE u.userMail = :userMail")
    int updateBannedByUserMail(@Param("userMail") String userMail, @Param("banned") boolean banned);

    List<UserEntity> findByBannedTrueOrderByUserMailAsc();

    @Query("""
            SELECT u
            FROM UserEntity u
            WHERE (
                :query IS NULL OR :query = '' OR
                LOWER(u.userMail) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(u.userName, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(u.userLastName, '')) LIKE LOWER(CONCAT('%', :query, '%'))
            )
            ORDER BY u.userMail ASC
            """)
    List<UserEntity> searchForSupportCustomers(@Param("query") String query, Pageable pageable);
}
