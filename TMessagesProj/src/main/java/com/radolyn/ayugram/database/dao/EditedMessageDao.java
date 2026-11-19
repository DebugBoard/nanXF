/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;
import com.radolyn.ayugram.database.entities.EditedMessage;

import java.util.ArrayList;
import java.util.List;

@Dao
public interface EditedMessageDao {
    @Query("SELECT * FROM editedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId ORDER BY entityCreateDate")
    List<EditedMessage> getAllRevisions(long userId, long dialogId, long messageId);

    @Query("UPDATE editedmessage SET mediaPath = :newPath WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId AND mediaPath = :oldPath")
    void updateAttachmentForRevisionsBetweenDates(long userId, long dialogId, long messageId, String oldPath, String newPath);

    @Query("SELECT * FROM editedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId ORDER BY entityCreateDate DESC LIMIT 1")
    EditedMessage getLastRevision(long userId, long dialogId, long messageId);

    @Query("SELECT EXISTS(SELECT * FROM editedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId)")
    boolean hasAnyRevisions(long userId, long dialogId, long messageId);

    @Query("DELETE FROM editedmessage WHERE userId = :userId AND dialogId = :dialogId")
    void delete(long userId, long dialogId);

    @Query("DELETE FROM editedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId IN (:messageIds)")
    void deleteByDialogIdAndMessageIds(long userId, long dialogId, List<Integer> messageIds);

    @Query("DELETE FROM editedmessage WHERE fakeId = :fakeId")
    int deleteByFakeId(long fakeId);

    @Query("SELECT mediaPath FROM editedmessage WHERE fakeId = :fakeId LIMIT 1")
    String getMediaPathByFakeId(long fakeId);

    @Query("UPDATE editedmessage SET mediaPath = NULL WHERE mediaPath = :mediaPath")
    void clearMediaPath(String mediaPath);

    @Query("UPDATE editedmessage SET mediaPath = NULL WHERE mediaPath IN (:paths)")
    void clearMediaPaths(List<String> paths);

    /** 分块（每块 <= 500，低于 SQLite 999 变量上限）在同一事务内批量清除 mediaPath。 */
    @Transaction
    default void clearMediaPathsChunked(List<String> paths) {
        for (int from = 0; from < paths.size(); from += 500) {
            clearMediaPaths(new ArrayList<>(paths.subList(from, Math.min(paths.size(), from + 500))));
        }
    }

    @Query("UPDATE editedmessage SET mediaPath = NULL, documentType = 0 WHERE mediaPath IS NOT NULL")
    void clearAllMediaPaths();

    @Query("SELECT DISTINCT mediaPath FROM editedmessage WHERE mediaPath IS NOT NULL AND mediaPath != '' AND mediaPath != '/'")
    List<String> getAllMediaPaths();

    @Insert
    void insert(EditedMessage revision);

    @Query("SELECT COUNT(*) FROM editedmessage")
    int getTotalCount();
}
