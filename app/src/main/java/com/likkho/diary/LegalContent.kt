package com.likkho.diary

// Legal, Privacy and About information for RamLal
data class LegalLink(val label: String, val url: String)
data class LegalSection(
    val id: String,
    val heading: String,
    val lastUpdated: String? = null,
    val paragraphs: List<String>,
    val bulletPoints: List<String> = emptyList(),
    val links: List<LegalLink> = emptyList(),
)

val LEGAL_SECTIONS: List<LegalSection> = listOf(
    LegalSection(
        id = "privacy-policy",
        heading = "Privacy Policy",
        lastUpdated = "October 2026",
        paragraphs = listOf(
            "RamLal (रामलाल) is built on a strict Local-First, Zero-Telemetry Privacy Architecture. All diary entries, rich text formatting, LaTeX mathematical formulas, profile pictures, canvas backgrounds, floating images, audio recordings, custom .ttf fonts, and passcodes are stored locally on your device inside isolated local storage vaults.",
            "We do not operate central cloud databases to collect, read, profile, or monetize your personal writings. No analytics scripts, advertising trackers, or background telemetry services are embedded in RamLal.",
            "Network requests are only initiated when you explicitly use optional online research tools in the Canvas Toolbar: (1) the Wikipedia Tool (which queries the official Wikimedia REST API for English and Hindi articles) and (2) the Word Analysis Tool (which queries Free Dictionary API, Datamuse API, LanguageTool API, and Wikipedia/TextRazor Knowledge Graph endpoints for the specific word you analyze).",
        ),
        bulletPoints = listOf(
            "Local-Only Storage: Primary Vault (real_vault.db) and Covert Secondary Vault (decoy_vault.db) remain strictly on your device.",
            "Hardware & Screen Security: When app passcode protection is enabled, Android FLAG_SECURE blocks screenshots, screen recordings, and Recent Apps previews.",
            "Device Permissions: Microphone permission is used solely for on-device voice notes; Storage Access Framework (SAF) / Media permission is used solely for user-initiated file imports, exports, and AES-256-GCM encrypted backups.",
        ),
        links = listOf(
            LegalLink("Wikimedia Foundation Privacy Policy (Wikipedia API)", "https://foundation.wikimedia.org/wiki/Policy:Privacy_policy"),
            LegalLink("Free Dictionary API Documentation", "https://dictionaryapi.dev/"),
            LegalLink("Datamuse Lexical API Documentation", "https://www.datamuse.com/api/"),
            LegalLink("LanguageTool Privacy Policy", "https://languagetool.org/legal/privacy"),
        ),
    ),
    LegalSection(
        id = "terms-of-service",
        heading = "Terms of Service",
        lastUpdated = "October 2026",
        paragraphs = listOf(
            "By installing and using RamLal (रामलाल), you agree that you are solely responsible for safeguarding your app passcodes, individual diary lock passcodes, spoiler passcodes, and Backup & Restore encryption keys.",
            "Because RamLal uses genuine client-side cryptographic isolation (PBKDF2 key derivation + AES-256-GCM authenticated encryption) with zero server-side key escrow, lost passcodes or forgotten encryption keys cannot be recovered by the developer.",
            "The application is provided \"as is\" without warranties of any kind. Users are encouraged to maintain regular encrypted backups via Settings → Backup & Restore or ZIP archives via the Export All Data option below.",
        ),
        bulletPoints = listOf(
            "You retain 100% ownership and copyright over all diaries, media, and documents you create or export in RamLal.",
            "External encyclopedia content fetched via the Wikipedia tool is subject to Wikimedia Creative Commons Attribution-ShareAlike (CC BY-SA) licensing.",
            "Anti-Brute-Force Cooldown (30 seconds on the 5th consecutive failed passcode attempt, multiplied by attempt count thereafter) is enforced to protect your local vault against unauthorized access.",
        ),
        links = listOf(
            LegalLink("Wikimedia Terms of Use", "https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use"),
            LegalLink("Creative Commons CC BY-SA 4.0 License", "https://creativecommons.org/licenses/by-sa/4.0/"),
        ),
    ),
    LegalSection(
        id = "about-section",
        heading = "About Section",
        lastUpdated = null,
        paragraphs = listOf(
            "RamLal (रामलाल) is a distraction-free, classic editorial diary and rich-media studio crafted for writers, students, researchers, and privacy-conscious thinkers who write in English and Hindi (हिन्दी).",
            "Designed with a timeless aesthetic with Cormorant typography and hair-thin editorial lines, RamLal combines a fluid Rich Text & Media Canvas with inline LaTeX mathematics, bilingual Wikipedia research, 6-category linguistic word analysis, multi-page A4 PDF publishing, and plausible-deniability Dual-Vault security.",
        ),
        bulletPoints = listOf(
            "Application Name: RamLal (रामलाल)",
            "Core Capabilities: Rich Text & Custom Font Canvas, Inline LaTeX Math Engine, Passcode-Protected Spoilers, Bilingual Wikipedia Live Search, 6-Category Word Analysis & Offline A–Z Dictionary, Dual-Vault Security, and Multi-Page A4 PDF Export.",
            "Supported Export Formats: .pdf (Standard Multi-Page A4), .docx, .html, .md, .txt, .rtf, .json, .csv, .tsv, .xml, and full-vault .zip archive.",
        ),
    ),
    LegalSection(
        id = "open-source-licenses",
        heading = "Open-Source Licenses",
        lastUpdated = null,
        paragraphs = listOf(
            "RamLal is built with modern open-source Android Jetpack Compose technologies. We gratefully acknowledge the authors and maintainers of the following open-source projects and APIs:",
        ),
        bulletPoints = listOf(
            "Android Jetpack Compose & Material 3 — Apache License 2.0 (Google LLC)",
            "Cormorant Font Family — SIL Open Font License (Christian Thalmann)",
            "Kotlin & Kotlin Coroutines — Apache License 2.0 (JetBrains s.r.o.)",
            "Wikipedia MediaWiki REST API — Content licensed under CC BY-SA 4.0",
        ),
        links = listOf(
            LegalLink("Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
            LegalLink("SIL Open Font License", "https://openfontlibrary.org/en/license/ofl"),
        ),
    ),
    LegalSection(
        id = "data-deletion-export",
        heading = "Data Deletion & Export Notice",
        lastUpdated = null,
        paragraphs = listOf(
            "You have complete, unrestricted control over your data in RamLal at all times. You can export your entire vault into a portable ZIP archive or permanently erase all application data from your device using the two actions below:",
            "1. Export All Data (.zip): Packages every diary entry as a clean Markdown (.md) document inside diaries/, extracts all profile pictures, canvas backgrounds, inline images, and floating canvas images in their original image formats inside images/, and saves all voice recordings and audio attachments in their original audio formats inside audio/.",
            "2. Clear All Data: Permanently deletes all diary entries, images, audio attachments, custom fonts, saved word analyses, passcodes, and vault databases from this device after confirmation.",
        ),
    ),
)
