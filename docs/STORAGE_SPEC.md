# STORAGE SPEC: Likkho (Kotlin, Compose)

## GOAL
Diaries aur data kabhi khona, corrupt hona ya leak hona nahi chahiye. Sab kuch offline aur local. Pehle correctness, phir speed. Apna crypto invent mat karo: sirf javax.crypto (AES-256-GCM), Android Keystore, androidx.biometric, aur streaming ke liye Google Tink. Libraries ke naye stable versions use karo.

## RULES
1. UI Composables (HomeScreen, SettingsScreen, Workspace, etc.) ka design/layout mat badlo. Sirf storage wiring ke liye AppViewModel, FeatureHooks aur PasscodeScreen ki unlock logic badal sakte ho. Dikhne wala behaviour same rahe (dots, shake, cooldown, error text).
2. Sab disk/crypto kaam Dispatchers.IO par, har vault ke liye ek Mutex (single writer).
3. PIN, key, diary ka content ya heading kabhi log mat karo. Crash reports me bhi nahi.
4. Kisi bhi failure par purana data intact rehna chahiye. Pehle naya likho, verify karo, tab purana hatao.

## 1. VAULTS
- Do vaults: primary aur decoy. Folders ke naam neutral (`v1`, `v2`), koi file/key/metadata "decoy" na batayen.
- Har vault ka alag salt, alag DEK, alag Keystore alias, alag media aur backup folder.
- PIN se vault khulta hai. Jis PIN se jo khule wahi vault hai. Decoy PIN, primary PIN se alag ho.
- Passcode na ho to bhi data encrypted rahe (DEK sirf Keystore se wrap).
- Passcode set/change/remove par DEK wapas re-wrap karo. Diary files dobara encrypt mat karo.
- Decoy vault ki existence disk se pata na chale, jab tak uska PIN set na ho.

## 2. KEY HIERARCHY
- DEK: har vault ke liye random 256-bit AES key. Sirf memory me jab vault unlocked ho. Lock par ya app background jane par zero-fill karke hata do.
- DEK wrap (2 layers):
  a) PIN key = PBKDF2-HMAC-SHA256(PIN, random 16-byte salt, iterations >= 600000; Argon2id ho sake to behtar). Iterations header me save karo taaki baad me badhe.
  b) Android Keystore AES-GCM key (non-exportable, StrongBox mile to wahi). 
  Kholne ke liye dono chahiye.
- Biometric: alag Keystore key (setUserAuthenticationRequired, biometric-bound) aur DEK ki alag wrapped copy. Biometric off karne par wo copy aur key delete. Nayi fingerprint add hone se key invalid ho to PIN par wapas aao, crash nahi.
- PIN galat hai ya nahi: alag hash se nahi, DEK unwrap ka GCM tag fail hone se pata karo.
- PIN lengths (4–6) ek plain file me save ho, bina ye batae ki kaunsi vault ki hai (dots UI ke liye). Auto-unlock attempt tab ho jab typed length is set me ho. Failure sirf tab count ho jab aakhri applicable length par bhi koi vault na khule.
- Wrong-PIN counter disk par save ho, HMAC (Keystore key) se protected, elapsedRealtime/boot-count ke saath, taaki app restart ya clock change se bypass na ho. Rule: 5th wrong = 30s, 6th+ = 30s x attempts, sahi par reset.
- Clear All Data = crypto-shredding: saari wrapped keys, Keystore aliases aur files delete. Uske baad data recover nahi hona chahiye.

## 3. DIARY FILE FORMAT
- Har diary ek file: `<random-id>.lkd`.
- Header (plain): magic "LKD1", schemaVersion (int), IV (12 bytes random, har write par naya).
- Body: AES-256-GCM(DEK). AAD = magic + schemaVersion + diaryId, taaki files swap/downgrade na ho sakein.
- Plaintext JSON (kotlinx.serialization): id, heading, createdAt, updatedAt, pinned, reminderAt, diaryLock (hash+salt, plain PIN nahi), pfp, background (mediaId+opacity), images[] (mediaId, x, y, w, h, rotation, opacity, behindText), audios[] (mediaId, name, duration, x, y, w, h, rotation, rate, behindText), content.
- content = block list (JSON): paragraph, heading(1-3), list, todo, quote, code, divider, latex, spoiler. Har block me text + spans (start, end, style: bold/italic/underline/strike/color/highlight/size/font/link/sup/sub), alignment, indent. Alag se plainPreview nikalo.
- Unknown fields ignore karo (forward compatible). schemaVersion apne app se naya mile to file read-only kholo aur kabhi overwrite mat karo.

## 4. INDEX
- Har vault me ek encrypted index file (id, createdAt, updatedAt, pinned, flags, plainPreview, heading).
- Index kharab/missing ho to diary files se rebuild ho (self-healing). Source of truth hamesha diary files hain.
- Search unlock ke baad memory me.

## 5. SAFE WRITES AUR RECOVERY
- Write: same folder me `.tmp` me likho -> flush + fsync -> verify (wapas decrypt karke) -> purani file `.bak` me -> atomic rename -> baad me `.bak` hatao.
- App start par har vault ka recovery scan: bachi `.tmp` hatao, `.bak` hai aur main file kharab to `.bak` se wapas lao.
- Decrypt/tag fail ho to file ko `quarantine/` me move karo (delete mat karo), user ko result type se batao.
- Write se pehle free space check. Disk full par purana data safe rahe aur clear error mile.
- Delete: diary file + uski unreferenced media hatao + index update, ek transaction ki tarah.

## 6. MEDIA (images, audio, fonts, PFP, background)
- Random id se encrypted file, vault ke media folder me. Diary me sirf id.
- Streaming AEAD (Tink AesGcmHkdfStreaming) taaki badi audio memory me poori na aaye.
- Plaintext disk par kabhi nahi. Decode memory me, temp file zaroori ho to cache me aur turant delete.
- Import atomic: temp me encrypt -> verify -> rename.
- Orphan cleanup: startup par wo media jo kisi diary me referenced nahi aur 24 ghante se purani ho, hatao. Naye import ko race me mat hatao.

## 7. OTHER DATA
- Saved passwords aur saved word analyses: vault ke andar encrypted files (passwords sabse zyada sensitive).
- Settings jo secret nahi (dark mode, mic settings, fullscreen): DataStore plain.
- allowBackup=false hi rahe. Cloud/auto-backup se kuch na jaye.

## 8. BACKUP / RESTORE (single file `.lkb`)
- Format: header (magic "LKB1", version, KDF type + iterations + salt, IV) + Tink streaming AEAD encrypted container: manifest.json + diary JSONs + media. Key = KDF(user ki Encryption Key passphrase). Keystore se bandhi koi cheez backup me nahi, taaki dusre phone par khule.
- Manifest: schemaVersion, app version, count, har item ka SHA-256. Vault type kahin likha na ho.
- Save location: Storage Access Framework (user folder/file chuna), koi broad storage permission nahi. Pehle temp me banao, phir wapas padhke saare hashes verify, tab hi "success" dikhao. Media fail ho to count return karo (BackupResult.failedMediaCount).
- Restore: pehle passphrase check (tag), phir poora backup staging folder me decrypt aur saare hashes verify, schema migrate. Tab tak live data ko haath mat lagao.
- Restore se pehle current vault ka automatic snapshot (app-private) banao, restore fail ho to rollback.
- Merge rule: same id par naya updatedAt jeete, doosra copy ban kar rahe (data loss nahi). Skipped media count return karo.
- Backed-up Diaries screen: backup file memory me kholo, delete karna ho to naya file banake atomically replace karo.
- Share (.likkho, single diary): same container format. Encryption ke liye saved backup Encryption Key use karo; key set na ho to error "Set an Encryption Key in Backup & Restore first." Is behaviour par mujhse confirm karo, khud decide mat karo.

## 9. MIGRATION
- Har file par schemaVersion. Migrator chain v1->v2->...; har step pure function.
- Migration se pehle backup copy, naya format verify ho tab purana hatao.
- Har purane version ka fixture test me ho.

## 10. LOCK / MEMORY
- App background ya instant lock par DEK, decrypted caches aur bitmaps clear karo. Keys ByteArray me, use ke baad zero-fill.
- FLAG_SECURE jab passcode set ho.

## 11. HOOKS MAPPING
FeatureHooks ke ye functions real banao: persistLogs (granular ops me todo: saveDiary/deleteDiary/loadAll), loadPasswords/persistPasswords, loadSavedWords/deleteSavedWord, hashPassphrase, performBackup, performRestore (IncorrectKeyException), openBackupVault, deleteFromBackup, exportAllDataZip, clearAllData, pickPfpImage/pickImage/pickAudio/pickBackgroundImage/pickFont (sirf encrypted import wala hissa). Signatures aur error behaviour UI ke liye same rahein.

## 12. TESTS (zaroori, pass hone par hi aage)
Unit: encrypt/decrypt round trip, tampered byte se fail, AAD swap se fail, galat PIN, atomic write beech me crash simulate, .bak recovery, index rebuild, migration fixtures, backup->restore round trip, galat passphrase, corrupted backup, orphan cleanup, wrong-PIN counter restart ke baad.
Instrumented: Keystore wrap/unwrap, biometric key invalidation, decoy aur primary data isolation.

## DONE KAB
- Diary save karke app kill karne ke baad wapas wahi dikhe, aur kill beech write me bhi data na toote.
- Decoy PIN par sirf decoy data, primary par sirf primary.
- Backup 2 phone/fresh install par restore ho.
- Galat key, kharab file, bhara storage: koi crash ya data loss nahi.
