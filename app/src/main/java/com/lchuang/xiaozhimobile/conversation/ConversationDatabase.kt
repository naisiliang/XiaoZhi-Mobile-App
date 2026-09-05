package com.lchuang.xiaozhimobile.conversation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ConversationDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DB_NAME,
    null,
    VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        CREATE_TABLE_STATEMENTS.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the initial private conversation schema.
    }

    companion object {
        const val DB_NAME = "xiaozhi_conversations.db"
        const val VERSION = 1

        private const val CREATE_SESSIONS = """
            CREATE TABLE conversation_sessions (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                status TEXT NOT NULL,
                assistant_name TEXT NOT NULL
            )
        """

        private const val CREATE_MESSAGES = """
            CREATE TABLE conversation_messages (
                id TEXT PRIMARY KEY NOT NULL,
                session_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                status TEXT NOT NULL,
                FOREIGN KEY(session_id) REFERENCES conversation_sessions(id)
            )
        """

        @JvmField
        val CREATE_TABLE_STATEMENTS = arrayOf(CREATE_SESSIONS, CREATE_MESSAGES)
    }
}
