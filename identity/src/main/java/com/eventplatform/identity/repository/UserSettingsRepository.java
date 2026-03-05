package com.eventplatform.identity.repository;

import com.eventplatform.identity.domain.UserSettings;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {

  @Query(
      """
        select us from UserSettings us
        left join fetch us.preferredCityOption city
        left join fetch us.genrePreferences gp
        left join fetch gp.preferenceOption genre
        where us.user.id = :userId
        """)
  Optional<UserSettings> findWithPreferencesByUserId(@Param("userId") Long userId);

  @Query("select us.fullName from UserSettings us where us.user.id = :userId")
  Optional<String> findFullNameByUserId(@Param("userId") Long userId);
}
