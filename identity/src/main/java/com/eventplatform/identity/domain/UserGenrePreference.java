package com.eventplatform.identity.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_genre_preferences")
public class UserGenrePreference extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_settings_id", nullable = false)
    private UserSettings userSettings;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "preference_option_id", nullable = false)
    private PreferenceOption preferenceOption;

    protected UserGenrePreference() {
    }

    public UserGenrePreference(UserSettings userSettings, PreferenceOption preferenceOption) {
        this.userSettings = userSettings;
        this.preferenceOption = preferenceOption;
    }

    public UserSettings getUserSettings() {
        return userSettings;
    }

    public PreferenceOption getPreferenceOption() {
        return preferenceOption;
    }
}
