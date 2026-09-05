package se.optiqon.voice.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import se.optiqon.voice.data.db.dao.DictationDao
import se.optiqon.voice.data.db.dao.LifetimeStatsDao
import se.optiqon.voice.data.db.dao.PostProcessingPromptDao
import se.optiqon.voice.data.db.dao.ProfileDao
import se.optiqon.voice.data.db.dao.TextReplacementRuleDao
import se.optiqon.voice.data.db.entity.Dictation
import se.optiqon.voice.data.db.entity.LifetimeStatsEntity
import se.optiqon.voice.data.db.entity.PostProcessingPromptEntity
import se.optiqon.voice.data.db.entity.ProfileEntity
import se.optiqon.voice.data.db.entity.TextReplacementRuleEntity

@Database(
    entities = [
        Dictation::class,
        ProfileEntity::class,
        TextReplacementRuleEntity::class,
        PostProcessingPromptEntity::class,
        LifetimeStatsEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class OptiqonVoiceDatabase : RoomDatabase() {
    abstract fun dictationDao(): DictationDao
    abstract fun profileDao(): ProfileDao
    abstract fun textReplacementRuleDao(): TextReplacementRuleDao
    abstract fun postProcessingPromptDao(): PostProcessingPromptDao
    abstract fun lifetimeStatsDao(): LifetimeStatsDao
}
