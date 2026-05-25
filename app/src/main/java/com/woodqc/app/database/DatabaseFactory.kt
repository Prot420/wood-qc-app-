package com.woodqc.app.database

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportFactory
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
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
                keyGenerator.init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                keyGenerator.generateKey()
            }
            val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
            val encoded = entry?.secretKey?.encoded
            if (!encoded.isNullOrEmpty()) return encoded
        } catch (e: Exception) {
            // Fallback for emulator / keystore failures
        }
        return "MoradabadWoodQCSecretPassphraseKey123!".toByteArray(Charsets.UTF_8)
    }
}
