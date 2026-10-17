package io.github.panuwattegif.readyproof

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import proof.*

// Separate database; old app files and database stay in place when installed over.
internal class ProofDb(context: Context) : SQLiteOpenHelper(context, "readyproof_v2.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ready(id TEXT PRIMARY KEY, day TEXT NOT NULL, gf TEXT NOT NULL, seen INTEGER NOT NULL, context TEXT NOT NULL, image TEXT)")
        db.execSQL("CREATE TABLE history(id TEXT PRIMARY KEY, day TEXT NOT NULL, gf TEXT NOT NULL, status TEXT NOT NULL, clock TEXT, amount TEXT, delayed INTEGER NOT NULL, captured INTEGER NOT NULL, image TEXT)")
        db.execSQL("CREATE TABLE audit(day TEXT PRIMARY KEY, total INTEGER, completed INTEGER, cancelled INTEGER, header_image TEXT, stuck INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX ready_day ON ready(day, gf)")
        db.execSQL("CREATE INDEX history_day ON history(day, gf)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations must be additive; never DROP old evidence.
    }
    fun ready(day: String): List<Ready> {
        val out = mutableListOf<Ready>()
        readableDatabase.rawQuery("SELECT id,day,gf,seen,context,image FROM ready WHERE day=?", arrayOf(day)).use { c ->
            while (c.moveToNext()) out += Ready(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getString(4),c.getString(5))
        }
        return out
    }
    fun history(day: String): List<History> {
        val out = mutableListOf<History>()
        readableDatabase.rawQuery("SELECT id,day,gf,status,clock,amount,delayed,captured,image FROM history WHERE day=?",
            arrayOf(day)).use { c -> while (c.moveToNext()) {
            val card = Card(c.getString(2),0,0,Status.valueOf(c.getString(3)),c.getString(4),c.getString(5),c.getInt(6)==1)
            out += History(c.getString(0),c.getString(1),card,c.getLong(7),c.getString(8))
        } }
        return out
    }
    fun putReady(row: Ready) {
        val v = ContentValues().apply {
            put("id",row.id);put("day",row.date);put("gf",row.gf);put("seen",row.firstSeen)
            put("context",row.context);put("image",row.image)
        }
        writableDatabase.insertWithOnConflict("ready",null,v,SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun putHistory(row: History) {
        val v = ContentValues().apply {
            put("id",row.id);put("day",row.date);put("gf",row.card.gf)
            put("status",row.card.status!!.name);put("clock",row.card.completedAt);put("amount",row.card.amount)
            put("delayed",if(row.card.delayed)1 else 0);put("captured",row.captured);put("image",row.image)
        }
        writableDatabase.insertWithOnConflict("history",null,v,SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun header(day: String): Header? {
        readableDatabase.rawQuery("SELECT total,completed,cancelled FROM audit WHERE day=?",arrayOf(day)).use { c ->
            if (!c.moveToFirst()) return null
            return Header(if(c.isNull(0))null else c.getInt(0),if(c.isNull(1))null else c.getInt(1),
                if(c.isNull(2))null else c.getInt(2))
        }
    }
    fun headerImage(day: String): String? {
        readableDatabase.rawQuery("SELECT header_image FROM audit WHERE day=?",arrayOf(day)).use { c ->
            return if(c.moveToFirst())c.getString(0) else null
        }
    }
    fun putHeader(day: String, h: Header, image: String?) {
        val v = ContentValues().apply {
            put("day",day);if(h.total != null)put("total",h.total)
            if(h.completed != null)put("completed",h.completed)
            if(h.cancelled != null)put("cancelled",h.cancelled)
            put("header_image",image)
        }
        writableDatabase.insertWithOnConflict("audit",null,v,SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun stuck(day: String): Boolean {
        readableDatabase.rawQuery("SELECT stuck FROM audit WHERE day=?",arrayOf(day)).use { c ->
            return c.moveToFirst() && c.getInt(0)==1
        }
    }
    fun setStuck(day: String, stuck: Boolean) {
        val v=ContentValues().apply { put("stuck",if(stuck)1 else 0) }
        writableDatabase.update("audit",v,"day=?",arrayOf(day))
    }
}
