package com.absmartly.android.sdk.cache;


import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.absmartly.sdk.cache.LocalCache;
import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.PublishEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SqliteAndroidLocalCache extends SQLiteOpenHelper implements LocalCache {

    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "absmartly.db";

    private final ObjectMapper mapper;

    public SqliteAndroidLocalCache(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.mapper = new ObjectMapper();
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        sqLiteDatabase.execSQL(
                "create table if not exists  events (id INTEGER PRIMARY KEY AUTOINCREMENT, event text)");

        sqLiteDatabase.execSQL(
                "create table if not exists  context (id INTEGER PRIMARY KEY AUTOINCREMENT, context text)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int i, int i1) {

    }

    public String serializeEvent(PublishEvent event) {
        try {
            return this.mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public PublishEvent deserializeEvent(String eventStr) {
        try {
            return this.mapper.readValue(eventStr, PublishEvent.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String serializeContext(ContextData context) {
        try {
            return this.mapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public ContextData deserializeContext(String eventStr) {
        try {
            return this.mapper.readValue(eventStr, ContextData.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public void writeEvent(PublishEvent publishEvent) {
        this.getWritableDatabase().execSQL("insert into events (event) values (?)", new Object[] { serializeEvent(publishEvent) });
    }

    @Override
    public List<PublishEvent> retrieveEvents() {
        Cursor cursor = this.getWritableDatabase().rawQuery("select event from events", null);
        List<PublishEvent> events = new ArrayList<PublishEvent>();
        while(cursor.moveToNext()){
            String eventStr = cursor.getString(0);
            events.add(this.deserializeEvent(eventStr));
        }
        this.getWritableDatabase().execSQL("DELETE FROM events");
        return events;
    }

    @Override
    public void writeContextData(ContextData contextData) {
        this.getWritableDatabase().execSQL("insert into context (context) values (?)", new Object[] { serializeContext(contextData) });

    }

    @Override
    public ContextData getContextData() {
        Cursor cursor = this.getWritableDatabase().rawQuery("select context from context", null);
        ContextData contextData = null;
        if(cursor.moveToNext()){
            String contextStr = cursor.getString(0);
            contextData = this.deserializeContext(contextStr);
        }
        return contextData;
    }
}