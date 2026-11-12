package com.likkho.app.storage

/**
 * Vault identifier with neutral directory names (v1, v2) as specified in STORAGE_SPEC Section 1.
 * Disk files, keys, and metadata never reveal "decoy" or roles.
 */
enum class VaultId(val dirName: String, val keyAliasSuffix: String) {
    PRIMARY("v1", "v1"),
    DECOY("v2", "v2");

    companion object {
        fun fromDirName(name: String): VaultId? = entries.find { it.dirName == name }
    }
}
