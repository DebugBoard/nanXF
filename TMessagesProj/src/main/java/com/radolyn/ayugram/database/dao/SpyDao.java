
package com.radolyn.ayugram.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.radolyn.ayugram.database.entities.SpyLastSeen;
import com.radolyn.ayugram.database.entities.SpyMessageContentsRead;
import com.radolyn.ayugram.database.entities.SpyMessageRead;

import java.util.List;

@Dao
public interface SpyDao {
    @Insert
    void insert(SpyMessageRead read);

    @Insert
    void insert(SpyMessageContentsRead contentsRead);

    @Query("SELECT * FROM SpyMessageRead WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId LIMIT 1")
    SpyMessageRead getMessageRead(long userId, long dialogId, int messageId);

    @Query("SELECT * FROM SpyMessageContentsRead WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId LIMIT 1")
    SpyMessageContentsRead getMessageContentsRead(long userId, long dialogId, int messageId);

    /** 同一事务内逐条“不存在才插入”，语义与逐条 getMessageRead + insert 相同。 */
    @Transaction
    default void insertReadsIfAbsent(long userId, long dialogId, List<Integer> mids, int ts) {
        for (int i = 0; i < mids.size(); i++) {
            int mid = mids.get(i);
            if (getMessageRead(userId, dialogId, mid) == null) {
                SpyMessageRead entry = new SpyMessageRead();
                entry.userId = userId;
                entry.dialogId = dialogId;
                entry.messageId = mid;
                entry.entityCreateDate = ts;
                insert(entry);
            }
        }
    }

    @Transaction
    default void insertContentsReadsIfAbsent(long userId, long dialogId, List<Integer> mids, int ts) {
        for (int i = 0; i < mids.size(); i++) {
            int mid = mids.get(i);
            if (getMessageContentsRead(userId, dialogId, mid) == null) {
                SpyMessageContentsRead entry = new SpyMessageContentsRead();
                entry.userId = userId;
                entry.dialogId = dialogId;
                entry.messageId = mid;
                entry.entityCreateDate = ts;
                insert(entry);
            }
        }
    }

    @Query("DELETE FROM SpyMessageRead WHERE entityCreateDate < :cutoff")
    void deleteOldReads(int cutoff);

    @Query("DELETE FROM SpyMessageContentsRead WHERE entityCreateDate < :cutoff")
    void deleteOldContentsRead(int cutoff);

    @Query("DELETE FROM SpyMessageRead WHERE userId = :userId AND dialogId = :dialogId")
    void deleteForDialog(long userId, long dialogId);

    @Query("DELETE FROM SpyMessageContentsRead WHERE userId = :userId AND dialogId = :dialogId")
    void deleteContentsForDialog(long userId, long dialogId);

    @Query("DELETE FROM SpyMessageRead")
    void deleteAll();

    @Query("DELETE FROM SpyMessageContentsRead")
    void deleteAllContents();

    @Query("SELECT COUNT(*) FROM SpyMessageRead")
    int getReadCount();

    @Query("SELECT COUNT(*) FROM SpyMessageContentsRead")
    int getContentsReadCount();

    @Query("SELECT * FROM SpyLastSeen")
    List<SpyLastSeen> getAllLastSeen();

    @Query("DELETE FROM SpyLastSeen WHERE lastSeenDate < :cutoff")
    void deleteOldLastSeen(int cutoff);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertLastSeenAll(List<SpyLastSeen> entities);

    @Query("SELECT COUNT(*) FROM SpyLastSeen")
    int getLastSeenCount();

    @Query("DELETE FROM SpyLastSeen")
    void deleteAllLastSeen();
}
