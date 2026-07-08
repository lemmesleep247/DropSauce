package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration31To32 : Migration(31, 32) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE track_logs ADD COLUMN chapter_ids TEXT NOT NULL DEFAULT ''")
	}
}
