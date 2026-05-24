package com.woodqc.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportFactory
import java.security.KeyStore
import javax.crypto.KeyGenerator

@Database(
    entities = [ItemLog::class],
    version = 2,           // Bumped from 1 → 2 for photoPath column
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemLogDao(): ItemLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private const val ALIAS = "wood_qc_db_alias"

        // Migration 1 → 2: Add photoPath column (empty string default)
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE item_logs ADD COLUMN photoPath TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wood_qc_secure.db"
                )
                    .openHelperFactory(getSecureHelperFactory())
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private fun getSecureHelperFactory(): SupportFactory {
            val keyBytes = getOrCreateDatabaseEncryptionKey()
            return SupportFactory(keyBytes)
        }

        private fun getOrCreateDatabaseEncryptionKey(): ByteArray {
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (!keyStore.containsAlias(ALIAS)) {
                    val keyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
                    val keyGenParameterSpec =
                        android.security.keystore.KeyGenParameterSpec.Builder(
                            ALIAS,
                            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                        )
                            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    keyGenerator.init(keyGenParameterSpec)
                    keyGenerator.generateKey()
                }
                val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    val encoded = entry.secretKey.encoded
                    if (encoded != null && encoded.isNotEmpty()) return encoded
                }
            } catch (e: Exception) {
                // Fallback for emulators / keystore failures
            }
            return "MoradabadWoodQCSecretPassphraseKey123!".toByteArray(Charsets.UTF_8)
        }
    }
}