package com.codex.yybpoints;

import android.content.Context;
import android.content.SharedPreferences;

/** Nickname and avatar URL only; Android removes this app-private data on uninstall. */
final class AccountProfileStore {
    static final class Profile {
        final boolean known;
        final boolean loggedIn;
        final String nickname;
        final String avatarUrl;

        Profile(boolean known, boolean loggedIn, String nickname, String avatarUrl) {
            this.known = known;
            this.loggedIn = loggedIn;
            this.nickname = nickname;
            this.avatarUrl = avatarUrl;
        }
    }

    private final SharedPreferences preferences;

    AccountProfileStore(Context context) {
        preferences = context.getSharedPreferences("account_profile", Context.MODE_PRIVATE);
    }

    Profile read() {
        return new Profile(preferences.getBoolean("known", false),
                preferences.getBoolean("logged_in", false),
                preferences.getString("nickname", ""),
                preferences.getString("avatar_url", ""));
    }

    void update(boolean loggedIn, String nickname, String avatarUrl) {
        preferences.edit().putBoolean("known", true).putBoolean("logged_in", loggedIn)
                .putString("nickname", loggedIn && nickname != null ? nickname : "")
                .putString("avatar_url", loggedIn && avatarUrl != null ? avatarUrl : "")
                .apply();
    }
}
