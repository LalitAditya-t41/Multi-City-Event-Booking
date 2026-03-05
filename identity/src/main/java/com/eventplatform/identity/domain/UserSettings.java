package com.eventplatform.identity.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "user_settings")
public class UserSettings extends BaseEntity {

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "full_name")
  private String fullName;

  @Column(name = "phone")
  private String phone;

  @Column(name = "dob")
  private LocalDate dob;

  @Column(name = "address")
  private String address;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "preferred_city_option_id")
  private PreferenceOption preferredCityOption;

  @Column(name = "notification_opt_in", nullable = false)
  private boolean notificationOptIn;

  @OneToMany(mappedBy = "userSettings", cascade = CascadeType.ALL, orphanRemoval = true)
  private final List<UserGenrePreference> genrePreferences = new ArrayList<>();

  protected UserSettings() {}

  public UserSettings(User user) {
    this.user = user;
    this.notificationOptIn = true;
  }

  public User getUser() {
    return user;
  }

  public String getFullName() {
    return fullName;
  }

  public String getPhone() {
    return phone;
  }

  public LocalDate getDob() {
    return dob;
  }

  public String getAddress() {
    return address;
  }

  public PreferenceOption getPreferredCityOption() {
    return preferredCityOption;
  }

  public boolean isNotificationOptIn() {
    return notificationOptIn;
  }

  public List<UserGenrePreference> getGenrePreferences() {
    return genrePreferences;
  }

  public void updateProfile(
      String fullName,
      String phone,
      LocalDate dob,
      String address,
      PreferenceOption preferredCityOption,
      boolean notificationOptIn) {
    if (preferredCityOption == null) {
      throw new BusinessRuleException("Invalid preference", "INVALID_PREFERENCE");
    }
    this.fullName = fullName;
    this.phone = phone;
    this.dob = dob;
    this.address = address;
    this.preferredCityOption = preferredCityOption;
    this.notificationOptIn = notificationOptIn;
  }

  public void replaceGenrePreferences(List<PreferenceOption> genreOptions) {
    if (genreOptions.size() > 3) {
      throw new BusinessRuleException("Invalid preference", "INVALID_PREFERENCE");
    }
    Set<Long> uniqueIds = new HashSet<>();
    for (PreferenceOption option : genreOptions) {
      Long optionId = option.getId();
      if (optionId != null && !uniqueIds.add(optionId)) {
        throw new BusinessRuleException("Invalid preference", "INVALID_PREFERENCE");
      }
    }
    genrePreferences.clear();
    for (PreferenceOption genreOption : genreOptions) {
      genrePreferences.add(new UserGenrePreference(this, genreOption));
    }
  }
}
