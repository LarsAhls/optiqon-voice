package se.optiqon.voice.di

import android.content.Context
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import se.optiqon.voice.data.db.OptiqonVoiceDatabase
import se.optiqon.voice.data.db.dao.DictationDao
import se.optiqon.voice.data.db.dao.LifetimeStatsDao
import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.data.db.dao.PostProcessingPromptDao
import se.optiqon.voice.data.db.dao.ProfileDao
import se.optiqon.voice.data.db.dao.TextReplacementRuleDao
import se.optiqon.voice.data.storage.StorageRoot
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private val migration1To2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dictations_timestamp` ON `dictations` (`timestamp`)"
            )
        }
    }

    private val migration2To3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE `dictations` ADD COLUMN `historyVisible` INTEGER NOT NULL DEFAULT 1"
            )
        }
    }

    private val migration3To4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `dictations` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'SUCCESS'")
            database.execSQL("ALTER TABLE `dictations` ADD COLUMN `errorMessage` TEXT")
            database.execSQL("ALTER TABLE `dictations` ADD COLUMN `profileId` INTEGER")
            database.execSQL("ALTER TABLE `dictations` ADD COLUMN `audioPath` TEXT")
            database.execSQL("DROP TABLE IF EXISTS `dictionary_words`")
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `profiles` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `isActive` INTEGER NOT NULL,
                    `asrModel` TEXT NOT NULL,
                    `language` TEXT,
                    `llmEnabled` INTEGER NOT NULL,
                    `llmModel` TEXT NOT NULL,
                    `profilePrompt` TEXT NOT NULL,
                    `selectedRuleIds` TEXT NOT NULL,
                    `selectedPromptIds` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_profiles_isActive` ON `profiles` (`isActive`)")
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `text_replacement_rules` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `pattern` TEXT NOT NULL,
                    `replacement` TEXT NOT NULL,
                    `isRegex` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `post_processing_prompts` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `prompt` TEXT NOT NULL,
                    `builtIn` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private val migration4To5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `dictations` ADD COLUMN `sourceAppPackage` TEXT")
        }
    }

    private val migration5To6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `profiles` ADD COLUMN `outputStyle` TEXT NOT NULL DEFAULT 'STANDARD'")
            database.execSQL("ALTER TABLE `profiles` ADD COLUMN `rewriteMode` TEXT NOT NULL DEFAULT 'FIX'")
            database.execSQL("ALTER TABLE `profiles` ADD COLUMN `summarizeMode` TEXT NOT NULL DEFAULT 'NONE'")
            database.execSQL("ALTER TABLE `profiles` ADD COLUMN `emojiAllowed` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Lifetime counters replace the old "sum every dictation row" totals, which
     * shrank whenever retention pruning deleted rows. Seed them from the rows
     * still present so existing installs keep the figures they can see today.
     */
    private val migration6To7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lifetime_stats` (
                    `id` INTEGER NOT NULL,
                    `dictationCount` INTEGER NOT NULL,
                    `wordCount` INTEGER NOT NULL,
                    `durationMs` INTEGER NOT NULL,
                    `firstDictationAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT OR REPLACE INTO `lifetime_stats`
                    (`id`, `dictationCount`, `wordCount`, `durationMs`, `firstDictationAt`)
                SELECT 0,
                       COUNT(*),
                       COALESCE(SUM(wordCount), 0),
                       COALESCE(SUM(durationMs), 0),
                       MIN(timestamp)
                FROM `dictations`
                WHERE status = 'SUCCESS'
                """.trimIndent()
            )
        }
    }

    /**
     * Adds the outbox. This is a real migration rather than a destructive fallback because
     * version 7 databases hold the only copy of a tester profile, API keys and dictation
     * history; dropping them to make room for a queue would be an absurd trade.
     */
    private val migration7To8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `outbox` (
                    `id` TEXT NOT NULL,
                    `ownerUid` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `payload` TEXT NOT NULL,
                    `createdAtMs` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    `attempts` INTEGER NOT NULL,
                    `lastError` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
        }
    }

    /**
     * Every migration, in order. Exposed rather than inlined so the migration tests upgrade a
     * real version 7 file through the same chain a phone does.
     */
    internal val ALL_MIGRATIONS = arrayOf(
        migration1To2,
        migration2To3,
        migration3To4,
        migration4To5,
        migration5To6,
        migration6To7,
        migration7To8
    )

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        storageRoot: StorageRoot
    ): OptiqonVoiceDatabase {
        return Room.databaseBuilder(
            context,
            OptiqonVoiceDatabase::class.java,
            storageRoot.databaseName
        ).addMigrations(*ALL_MIGRATIONS)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }

    @Provides
    fun provideDictationDao(db: OptiqonVoiceDatabase): DictationDao = db.dictationDao()

    @Provides
    fun provideProfileDao(db: OptiqonVoiceDatabase): ProfileDao = db.profileDao()

    @Provides
    fun provideTextReplacementRuleDao(db: OptiqonVoiceDatabase): TextReplacementRuleDao = db.textReplacementRuleDao()

    @Provides
    fun providePostProcessingPromptDao(db: OptiqonVoiceDatabase): PostProcessingPromptDao = db.postProcessingPromptDao()

    @Provides
    fun provideLifetimeStatsDao(db: OptiqonVoiceDatabase): LifetimeStatsDao = db.lifetimeStatsDao()

    @Provides
    fun provideOutboxDao(db: OptiqonVoiceDatabase): OutboxDao = db.outboxDao()
}
