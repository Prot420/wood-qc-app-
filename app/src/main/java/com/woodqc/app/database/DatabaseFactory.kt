package com.woodqc.app.database

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.security.KeyStore
import javax.crypto.KeyGenerator

object DatabaseFactory {

    @Volatile
    private var INSTANCE: AppDatabase? = null
    private const val ALIAS = "wood_qc_db_alias"

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
                .openHelperFactory(SupportFactory(getEncryptionKey()))
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
            INSTANCE = instance
            instance
        }
    }

    private fun getEncryptionKey(): ByteArray {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(ALIAS)) {
                val spec = KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
                KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                    init(spec)
                    generateKey()
                }
            }
            val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey?.encoded?.takeIf { it.isNotEmpty() }
                ?: fallbackKey()
        } catch (e: Exception) {
            fallbackKey()
        }
    }

    private fun fallbackKey(): ByteArray =
        "MoradabadWoodQCSecretPassphraseKey123!".toByteArray(Charsets.UTF_8)
}
