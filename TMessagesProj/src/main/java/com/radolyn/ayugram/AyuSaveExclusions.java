package com.radolyn.ayugram;

import android.content.SharedPreferences;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.TLRPC;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Per-chat rules for keeping deleted messages and edit history.
 *
 * <p>Two modes decide what happens to chats without an explicit rule: "always save, except the
 * excluded chats" (the default) or "never save, except the included chats". Explicit rules live in
 * two id sets, and in addition each set carries a bitmask of chat kinds so whole categories (bot
 * chats, groups, channels, secret chats…) can be saved or skipped without listing every chat.
 */
public final class AyuSaveExclusions {

    public static final int TYPE_DEFAULT = 0;
    public static final int TYPE_NEVER = 1;
    public static final int TYPE_ALWAYS = 2;

    public static final int FLAG_BOT_USER = 1;
    public static final int FLAG_BOT_CHAT = 1 << 1;
    public static final int FLAG_USER = 1 << 2;
    public static final int FLAG_PUBLIC_CHANNEL = 1 << 3;
    public static final int FLAG_PRIVATE_CHANNEL = 1 << 4;
    public static final int FLAG_PUBLIC_GROUP = 1 << 5;
    public static final int FLAG_PRIVATE_GROUP = 1 << 6;
    public static final int FLAG_SECRET = 1 << 7;

    public static final int FLAG_GROUPS = FLAG_PUBLIC_GROUP | FLAG_PRIVATE_GROUP;
    public static final int FLAG_CHANNELS = FLAG_PUBLIC_CHANNEL | FLAG_PRIVATE_CHANNEL;

    private static final String KEY_WHITELIST = "ayuSaveExclusionsWhitelist";
    private static final String KEY_ALWAYS = "ayuSaveExclusionsAlways";
    private static final String KEY_NEVER = "ayuSaveExclusionsNever";
    private static final String KEY_ALWAYS_FLAGS = "ayuSaveExclusionsAlwaysFlags";
    private static final String KEY_NEVER_FLAGS = "ayuSaveExclusionsNeverFlags";
    private static final String KEY_MIGRATED_UNSIGNED = "ayuSaveExclusionsMigrated";
    private static final String KEY_MIGRATED = "ayuSaveExclusionsMigratedSigned";
    private static final String LEGACY_PREFIX = "saveDeletedExclusion_";

    private AyuSaveExclusions() {
    }

    /** Immutable view of everything {@link #isSavingAllowedFor} reads from the preferences. */
    private static final class Snapshot {
        final Set<String> never;
        final Set<String> always;
        final int neverFlags;
        final int alwaysFlags;
        final boolean whitelist;

        Snapshot(Set<String> never, Set<String> always, int neverFlags, int alwaysFlags, boolean whitelist) {
            this.never = Collections.unmodifiableSet(never);
            this.always = Collections.unmodifiableSet(always);
            this.neverFlags = neverFlags;
            this.alwaysFlags = alwaysFlags;
            this.whitelist = whitelist;
        }
    }

    private static final Object snapshotLock = new Object();
    private static volatile Snapshot snapshot;
    private static int snapshotGeneration;
    private static SharedPreferences listenedPreferences;
    // SharedPreferences keeps listeners weakly, so this has to stay referenced from a static field.
    private static final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (prefs, key) -> {
        // Also catches writes made outside this class (settings restore), key is null on clear().
        if (key == null || key.startsWith("ayuSaveExclusions") || key.startsWith(LEGACY_PREFIX)) {
            invalidateSnapshot();
        }
    };

    /** Drops the cached snapshot; call after anything wrote the exclusion preferences directly. */
    public static void invalidateSnapshot() {
        synchronized (snapshotLock) {
            snapshotGeneration++;
            snapshot = null;
        }
    }

    private static Snapshot getSnapshot() {
        Snapshot current = snapshot;
        if (current != null) {
            return current;
        }
        ensureMigrated();
        int generation;
        synchronized (snapshotLock) {
            generation = snapshotGeneration;
        }
        SharedPreferences preferences = NekoConfig.getPreferences();
        current = new Snapshot(
                getStoredIds(preferences, KEY_NEVER),
                getStoredIds(preferences, KEY_ALWAYS),
                preferences.getInt(KEY_NEVER_FLAGS, 0),
                preferences.getInt(KEY_ALWAYS_FLAGS, 0),
                preferences.getBoolean(KEY_WHITELIST, false));
        synchronized (snapshotLock) {
            if (generation == snapshotGeneration) {
                snapshot = current;
                if (listenedPreferences != preferences) {
                    listenedPreferences = preferences;
                    preferences.registerOnSharedPreferenceChangeListener(prefsListener);
                }
            }
        }
        return current;
    }

    /** True when saving only happens in the included chats. */
    public static boolean isWhitelist() {
        return getSnapshot().whitelist;
    }

    public static void setWhitelist(boolean whitelist) {
        NekoConfig.getPreferences().edit().putBoolean(KEY_WHITELIST, whitelist).apply();
        invalidateSnapshot();
    }

    /** Ids explicitly saved ({@code always}) or skipped ({@code always == false}). */
    public static Set<String> getIds(boolean always) {
        Snapshot current = getSnapshot();
        return new HashSet<>(always ? current.always : current.never);
    }

    public static void setIds(boolean always, Set<String> ids) {
        NekoConfig.getPreferences().edit()
                .putStringSet(always ? KEY_ALWAYS : KEY_NEVER, new HashSet<>(ids))
                .apply();
        invalidateSnapshot();
    }

    /** Category bitmask that applies to the given set. */
    public static int getFlags(boolean always) {
        Snapshot current = getSnapshot();
        return always ? current.alwaysFlags : current.neverFlags;
    }

    public static void setFlags(boolean always, int flags) {
        NekoConfig.getPreferences().edit()
                .putInt(always ? KEY_ALWAYS_FLAGS : KEY_NEVER_FLAGS, flags)
                .apply();
        invalidateSnapshot();
    }

    /** Applies a category bit to the given set. */
    public static void setFlag(boolean always, int flag, boolean enabled) {
        int flags = getFlags(always);
        setFlags(always, enabled ? (flags | flag) : (flags & ~flag));
    }

    /**
     * True when deleted messages and edit history should be kept for {@code dialogId}.
     * {@code botUserId} is the sender when the caller knows it, so bot chats can follow their bit.
     */
    public static boolean isSavingAllowedFor(int accountId, long dialogId, long botUserId) {
        final String key = String.valueOf(dialogId);
        final Snapshot current = getSnapshot();
        if (current.never.contains(key)) {
            return false;
        }
        if (current.always.contains(key)) {
            return true;
        }
        final int alwaysFlags = current.alwaysFlags;
        final int neverFlags = current.neverFlags;
        final int union = alwaysFlags | neverFlags;
        if ((union & FLAG_BOT_CHAT) != 0 && DialogObject.isChatDialog(dialogId) && botUserId > 0) {
            TLRPC.User user = getUser(accountId, botUserId);
            if (user != null && user.bot) {
                return (neverFlags & FLAG_BOT_CHAT) == 0;
            }
        }
        if ((union & ~FLAG_BOT_CHAT) != 0) {
            int flag = getDialogFlag(accountId, dialogId);
            if ((neverFlags & flag) != 0) {
                return false;
            }
            if ((alwaysFlags & flag) != 0) {
                return true;
            }
        }
        return !current.whitelist;
    }

    /** Explicit rule for a chat: {@link #TYPE_DEFAULT}, {@link #TYPE_NEVER} or {@link #TYPE_ALWAYS}. */
    public static int getChatSavingType(long dialogId) {
        String key = String.valueOf(dialogId);
        Snapshot current = getSnapshot();
        if (current.never.contains(key)) {
            return TYPE_NEVER;
        }
        return current.always.contains(key) ? TYPE_ALWAYS : TYPE_DEFAULT;
    }

    public static void setChatSavingType(long dialogId, int type) {
        final String key = String.valueOf(dialogId);
        Set<String> always = getIds(true);
        Set<String> never = getIds(false);
        if (type == TYPE_ALWAYS) {
            always.add(key);
            never.remove(key);
        } else if (type == TYPE_NEVER) {
            never.add(key);
            always.remove(key);
        } else {
            always.remove(key);
            never.remove(key);
        }
        setIds(true, always);
        setIds(false, never);
    }

    /** Moves {@code ids} into the given set and applies {@code flags} to it. */
    public static void setExclusions(boolean always, Set<String> ids, int flags) {
        Set<String> target = getIds(always);
        Set<String> other = getIds(!always);
        target.addAll(ids);
        other.removeAll(ids);
        setIds(always, target);
        setFlags(always, flags);
        setIds(!always, other);
        setFlags(!always, getFlags(!always) & ~flags);
    }

    public static void reset(boolean always) {
        setIds(always, new HashSet<>());
        setFlags(always, 0);
    }

    /** Bit that describes the kind of dialog, or 0 when the peer is not known yet. */
    private static int getDialogFlag(int accountId, long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return FLAG_SECRET;
        }
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = getUser(accountId, dialogId);
            return user != null && user.bot ? FLAG_BOT_USER : FLAG_USER;
        }
        TLRPC.Chat chat = getChat(accountId, -dialogId);
        if (chat == null) {
            return 0;
        }
        boolean isPublic = ChatObject.isPublic(chat);
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            return isPublic ? FLAG_PUBLIC_CHANNEL : FLAG_PRIVATE_CHANNEL;
        }
        return isPublic ? FLAG_PUBLIC_GROUP : FLAG_PRIVATE_GROUP;
    }

    private static TLRPC.User getUser(int accountId, long userId) {
        MessagesController messagesController = MessagesController.getInstance(accountId);
        TLRPC.User user = messagesController.getUser(userId);
        if (user != null) {
            return user;
        }
        user = MessagesStorage.getInstance(accountId).getUserSync(userId);
        if (user != null) {
            messagesController.putUser(user, true);
        }
        return user;
    }

    private static TLRPC.Chat getChat(int accountId, long chatId) {
        MessagesController messagesController = MessagesController.getInstance(accountId);
        TLRPC.Chat chat = messagesController.getChat(chatId);
        if (chat != null) {
            return chat;
        }
        chat = MessagesStorage.getInstance(accountId).getChatSync(chatId);
        if (chat != null) {
            messagesController.putChat(chat, true);
        }
        return chat;
    }

    /** Moves the old per-chat booleans into the excluded set once. */
    private static synchronized void ensureMigrated() {
        SharedPreferences preferences = NekoConfig.getPreferences();
        if (preferences.getBoolean(KEY_MIGRATED, false)) {
            return;
        }
        Set<String> never = getStoredIds(preferences, KEY_NEVER);
        addLegacyIds(preferences.getAll(), never, getStoredIds(preferences, KEY_ALWAYS), preferences.getBoolean(KEY_MIGRATED_UNSIGNED, false));
        preferences.edit()
                .putStringSet(KEY_NEVER, never)
                .putBoolean(KEY_MIGRATED, true)
                .apply();
        invalidateSnapshot();
    }

    private static Set<String> getStoredIds(SharedPreferences preferences, String key) {
        Set<String> stored = preferences.getStringSet(key, null);
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    /**
     * Legacy keys held Math.abs(dialogId), so one entry covers both the user and the chat. The first
     * migration stored only the unsigned id: on repair one the user has since removed stays removed.
     */
    static void addLegacyIds(Map<String, ?> preferences, Set<String> never, Set<String> always, boolean repair) {
        for (Map.Entry<String, ?> entry : preferences.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(LEGACY_PREFIX) || !Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(key.substring(LEGACY_PREFIX.length()));
            } catch (NumberFormatException e) {
                continue;
            }
            if (repair && !never.contains(String.valueOf(id))) {
                continue;
            }
            for (String dialogId : new String[]{String.valueOf(id), String.valueOf(-id)}) {
                if (!always.contains(dialogId)) {
                    never.add(dialogId);
                }
            }
        }
    }
}
