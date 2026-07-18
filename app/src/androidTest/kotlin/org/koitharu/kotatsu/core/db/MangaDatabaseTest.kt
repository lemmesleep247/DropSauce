package org.koitharu.kotatsu.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R

@RunWith(AndroidJUnit4::class)
class MangaDatabaseTest {

	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val migrations = getDatabaseMigrations(context)

	@Test
	fun versions() {
		assertEquals(1, migrations.first().startVersion)
		repeat(migrations.size) { i ->
			assertEquals(i + 1, migrations[i].startVersion)
			assertEquals(i + 2, migrations[i].endVersion)
		}
		assertEquals(DATABASE_VERSION, migrations.last().endVersion)
	}

	@Test
	fun currentSchemaOpens() {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
		try {
			assertTrue(database.openHelper.writableDatabase.isOpen)
		} finally {
			database.close()
		}
	}

	@Test
	fun prePopulate() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
			.addCallback(DatabasePrePopulateCallback(context.resources))
			.build()
		try {
			assertEquals(
				listOf(context.getString(R.string.read_later)),
				database.getFavouriteCategoriesDao().findAll().map { category -> category.title },
			)
		} finally {
			database.close()
		}
	}
}
