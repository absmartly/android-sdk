package com.absmartly.android.sdk.cache;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.PublishEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SqliteAndroidLocalCache extends SQLiteOpenHelper {

    private static final String TAG = "ABSmartlyCache";
    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "absmartly.db";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public SqliteAndroidLocalCache(@NonNull Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(@NonNull SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, event TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS context (id INTEGER PRIMARY KEY AUTOINCREMENT, context TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.e(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion + " — dropping all cached data");
        db.execSQL("DROP TABLE IF EXISTS events");
        db.execSQL("DROP TABLE IF EXISTS context");
        onCreate(db);
    }

    @Nullable
    private <T> T deserialize(@Nullable String json, @NonNull Class<T> clazz) {
        if (json == null) {
            Log.w(TAG, "Attempted to deserialize null JSON for " + clazz.getSimpleName());
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (IOException e) {
            // Do not log the exception object: Jackson's message can embed the input payload
            // (the cached JSON), so log only its type and the input length.
            Log.e(TAG, "Failed to deserialize " + clazz.getSimpleName() + " (length=" + json.length()
                    + "): " + e.getClass().getName());
            return null;
        }
    }

    // Public serialization helpers kept from the original SqliteAndroidLocalCache API, with its
    // contract: a null value serializes to the JSON text "null" and deserializes back to null, a
    // Java null input is rejected by Jackson with IllegalArgumentException, and malformed JSON
    // throws a RuntimeException whose cause is Jackson's exception. The cache reads below use the
    // tolerant deserialize instead, so a corrupted row is skipped rather than failing the read.

    @NonNull
    public String serializeEvent(@Nullable PublishEvent event) {
        return serializeOrThrow(event);
    }

    @Nullable
    public PublishEvent deserializeEvent(@NonNull String eventStr) {
        return deserializeOrThrow(eventStr, PublishEvent.class);
    }

    @NonNull
    public String serializeContext(@Nullable ContextData context) {
        return serializeOrThrow(context);
    }

    @Nullable
    public ContextData deserializeContext(@NonNull String contextStr) {
        return deserializeOrThrow(contextStr, ContextData.class);
    }

    @NonNull
    private static String serializeOrThrow(@Nullable Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    private static <T> T deserializeOrThrow(String json, @NonNull Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writePublishEvent(@NonNull PublishEvent publishEvent) {
        try {
            final SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.execSQL("INSERT INTO events (event) VALUES (?)", new Object[]{serializeOrThrow(publishEvent)});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to write publish event", e);
            throw e;
        }
    }

    @NonNull
    public List<PublishEvent> retrievePublishEvents() {
        Cursor cursor = null;
        try {
            final SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                cursor = db.rawQuery("SELECT event FROM events ORDER BY id ASC", null);
                List<PublishEvent> events = new ArrayList<>();
                while (cursor.moveToNext()) {
                    String eventStr = cursor.getString(0);
                    PublishEvent event = deserialize(eventStr, PublishEvent.class);
                    if (event != null) {
                        events.add(event);
                    } else {
                        Log.w(TAG, "Skipping corrupted event data");
                    }
                }
                db.execSQL("DELETE FROM events");
                db.setTransactionSuccessful();
                return events;
            } finally {
                db.endTransaction();
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to retrieve publish events", e);
            throw e;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public void writeContextData(@NonNull ContextData contextData) {
        try {
            final SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM context");
                db.execSQL("INSERT INTO context (context) VALUES (?)", new Object[]{serializeOrThrow(contextData)});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to write context data", e);
            throw e;
        }
    }

    @Nullable
    public ContextData getContextData() {
        Cursor cursor = null;
        try {
            final SQLiteDatabase db = getReadableDatabase();
            cursor = db.rawQuery("SELECT context FROM context ORDER BY id DESC LIMIT 1", null);
            if (cursor.moveToNext()) {
                String contextStr = cursor.getString(0);
                return deserialize(contextStr, ContextData.class);
            }
            return null;
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to get context data", e);
            throw e;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
}
