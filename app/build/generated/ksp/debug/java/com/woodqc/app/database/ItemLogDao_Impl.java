package com.woodqc.app.database;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class ItemLogDao_Impl implements ItemLogDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<ItemLog> __insertionAdapterOfItemLog;

  private final SharedSQLiteStatement __preparedStmtOfClearLogs;

  public ItemLogDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfItemLog = new EntityInsertionAdapter<ItemLog>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `item_logs` (`id`,`category`,`woodType`,`verdict`,`defectType`,`confidence`,`timestamp`,`photoPath`) VALUES (nullif(?, 0),?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final ItemLog entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getCategory());
        statement.bindString(3, entity.getWoodType());
        statement.bindString(4, entity.getVerdict());
        statement.bindString(5, entity.getDefectType());
        statement.bindDouble(6, entity.getConfidence());
        statement.bindLong(7, entity.getTimestamp());
        statement.bindString(8, entity.getPhotoPath());
      }
    };
    this.__preparedStmtOfClearLogs = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM item_logs";
        return _query;
      }
    };
  }

  @Override
  public Object insertLog(final ItemLog log, final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfItemLog.insertAndReturnId(log);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object clearLogs(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClearLogs.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfClearLogs.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<ItemLog>> getAllLogs() {
    final String _sql = "SELECT * FROM item_logs ORDER BY timestamp DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"item_logs"}, new Callable<List<ItemLog>>() {
      @Override
      @NonNull
      public List<ItemLog> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfCategory = CursorUtil.getColumnIndexOrThrow(_cursor, "category");
          final int _cursorIndexOfWoodType = CursorUtil.getColumnIndexOrThrow(_cursor, "woodType");
          final int _cursorIndexOfVerdict = CursorUtil.getColumnIndexOrThrow(_cursor, "verdict");
          final int _cursorIndexOfDefectType = CursorUtil.getColumnIndexOrThrow(_cursor, "defectType");
          final int _cursorIndexOfConfidence = CursorUtil.getColumnIndexOrThrow(_cursor, "confidence");
          final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
          final int _cursorIndexOfPhotoPath = CursorUtil.getColumnIndexOrThrow(_cursor, "photoPath");
          final List<ItemLog> _result = new ArrayList<ItemLog>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final ItemLog _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpCategory;
            _tmpCategory = _cursor.getString(_cursorIndexOfCategory);
            final String _tmpWoodType;
            _tmpWoodType = _cursor.getString(_cursorIndexOfWoodType);
            final String _tmpVerdict;
            _tmpVerdict = _cursor.getString(_cursorIndexOfVerdict);
            final String _tmpDefectType;
            _tmpDefectType = _cursor.getString(_cursorIndexOfDefectType);
            final float _tmpConfidence;
            _tmpConfidence = _cursor.getFloat(_cursorIndexOfConfidence);
            final long _tmpTimestamp;
            _tmpTimestamp = _cursor.getLong(_cursorIndexOfTimestamp);
            final String _tmpPhotoPath;
            _tmpPhotoPath = _cursor.getString(_cursorIndexOfPhotoPath);
            _item = new ItemLog(_tmpId,_tmpCategory,_tmpWoodType,_tmpVerdict,_tmpDefectType,_tmpConfidence,_tmpTimestamp,_tmpPhotoPath);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getAllLogsOnce(final Continuation<? super List<ItemLog>> $completion) {
    final String _sql = "SELECT * FROM item_logs ORDER BY timestamp DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<ItemLog>>() {
      @Override
      @NonNull
      public List<ItemLog> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfCategory = CursorUtil.getColumnIndexOrThrow(_cursor, "category");
          final int _cursorIndexOfWoodType = CursorUtil.getColumnIndexOrThrow(_cursor, "woodType");
          final int _cursorIndexOfVerdict = CursorUtil.getColumnIndexOrThrow(_cursor, "verdict");
          final int _cursorIndexOfDefectType = CursorUtil.getColumnIndexOrThrow(_cursor, "defectType");
          final int _cursorIndexOfConfidence = CursorUtil.getColumnIndexOrThrow(_cursor, "confidence");
          final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
          final int _cursorIndexOfPhotoPath = CursorUtil.getColumnIndexOrThrow(_cursor, "photoPath");
          final List<ItemLog> _result = new ArrayList<ItemLog>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final ItemLog _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpCategory;
            _tmpCategory = _cursor.getString(_cursorIndexOfCategory);
            final String _tmpWoodType;
            _tmpWoodType = _cursor.getString(_cursorIndexOfWoodType);
            final String _tmpVerdict;
            _tmpVerdict = _cursor.getString(_cursorIndexOfVerdict);
            final String _tmpDefectType;
            _tmpDefectType = _cursor.getString(_cursorIndexOfDefectType);
            final float _tmpConfidence;
            _tmpConfidence = _cursor.getFloat(_cursorIndexOfConfidence);
            final long _tmpTimestamp;
            _tmpTimestamp = _cursor.getLong(_cursorIndexOfTimestamp);
            final String _tmpPhotoPath;
            _tmpPhotoPath = _cursor.getString(_cursorIndexOfPhotoPath);
            _item = new ItemLog(_tmpId,_tmpCategory,_tmpWoodType,_tmpVerdict,_tmpDefectType,_tmpConfidence,_tmpTimestamp,_tmpPhotoPath);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<Integer> getPassCount() {
    final String _sql = "SELECT COUNT(*) FROM item_logs WHERE verdict = 'PASS'";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"item_logs"}, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<Integer> getRejectCount() {
    final String _sql = "SELECT COUNT(*) FROM item_logs WHERE verdict = 'REJECT'";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"item_logs"}, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getRejectCountSince(final long sinceTimestamp,
      final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM item_logs WHERE verdict = 'REJECT' AND timestamp >= ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, sinceTimestamp);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getTotalCountSince(final long sinceTimestamp,
      final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM item_logs WHERE timestamp >= ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, sinceTimestamp);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
