# Source preview integration — 2026-11-03

This file records the original source-preview integration and its historical evidence. Later reviewed source changes are tracked in draft PR #2 and the feature notes; no APK is distributed here. Current stage gates and untested Android/emulator/device work are listed in `docs/STAGE_ACCEPTANCE.md`.

## Provenance and change boundary

Upstream public baseline: commit `86f37191660e657790971c3211aa1a158ab8834d`, tree `6a9ec16d649f3f0f87f457726f8498dbaff64d7b`, 85 files. File contents were verified against Git blob IDs and the complete tree was recomputed. Public-only license, notices, development material, host tests and runner are retained, with explicit harness/document updates rather than replacement by a private workspace tree.

The imported production files are byte-identical to the dependency-equipped vc6 source: MainActivity, Store, EditDraftStore, manifest, application Gradle configuration, and six new classes (EditorSaveFlow, EditRecoveryPolicy, EditRecoveryState, EditRecoveryCatalog, RecoverySourceGate, PdfPreviewActivity). The WED1/WED2 inspector is also imported. No production behavior was changed for this integration.

The original public import/restore checks, crop review, OCR/PDF revision framing, invalid-asset rejection, null OCR preservation and interruptible batch-lock behavior remain. Similar-brightness shadows and missing corners can still produce an incorrect crop. Manual review remains required; this is not a new automatic-crop algorithm.

## Editor and recovery behavior

A save-and-next request binds the document/page, baseline, editor session and operation token, freezes an edit copy and blocks repeat submission. Persistence must return successfully, and the durable document version/order plus current UI identity are rechecked before navigation. The last page returns to the document instead of wrapping. A completed writer does not become an unsaved operation merely because its Activity was paused or destroyed.

An independent recovery record is retained before the save transaction. A conflict is kept separate from the current saved page; old edited content is not automatically applied to a new baseline. Saved-state restoration may preserve raw state or a receipt reference. A restored reference remains unverified until background reading validates it. A record's submitted flag is not proof of successful saving.

Recovery labels use the **current** title and page number, not historical metadata. The timestamp is first retention time; deduplication does not refresh it. UUIDs remain identity fields. Clicking a record still rechecks owner/token/digest, document version, page identity and the real original file, in a worker. A callback ticket checks current document/page/session and observation cycle before publication. Source identity is rechecked around document reading under the app's immutable-original model; this does not make verification atomic against arbitrary external same-UID modifications.

The independent recovery directory has per-document limits of 256 entries, 64 MiB total ordinary-file bytes and 4 MiB per record. Damaged/unknown entries count; unsupported or unreadable inventory is not reported as zero. Usage is a snapshot, not a reservation. Full storage may still permit deduplication of an identical retained record, but no quota bypass or automatic deletion is implemented. Legacy explicit checkpoints remain separately limited to 16 entries of at most 4 MiB each.

Ordinary ZIP backups omit both recovery areas. A recovery export is only one record's parameter copy, possibly including old OCR strings; it contains neither originals nor a whole document or other records, has no automatic import, and frees no quota. Copy/source verification is not external-provider readback verification. At capacity, the user must separately decide how to preserve and handle records; this source preview adds no deletion/archive/export system.

`python3 tools/inspect_edit_draft.py FILE` validates an exported WED1/WED2 record offline and hides stored page/OCR strings by default. `--json` explicitly reveals private strings. The tool does not upload, import, repair or modify the file.

## Host validation

Requirements: an existing Python 3.10+ and JDK17+ with the compiler module. No network/bootstrap/install is performed:

```sh
python3 -B tools/test_host.py
```

Set `JAVA` to an existing Java executable when needed. `SCANNER_HOST_OUTPUT` can select a new retained output directory; otherwise a unique `build/host-tests-*` directory is used. Scripts retain generated sources, logs and synthetic fixtures rather than cleaning them. UTF-8 is explicit in the runner environment.

| Group | What actually executes | What it does not establish |
| --- | --- | --- |
| Coordinator | Production EditorSaveFlow; boundary, duplicate/callback and real-thread cases | Injected Writer is not Android Store/AtomicFile |
| Recovery | Production coordinator, journal codec/filesystem, identity policy and saved-state envelope | Host persisted-order fixture is not Android disk-fault or lifecycle execution |
| Catalog/quota | Production label/status policy and real host journal/usage files | Device full-disk/quota behavior |
| Source gate | Production source hashing, file-change checks and callback-ticket policy on real synthetic files | Android UI wiring or atomic defense against arbitrary external writers |
| Draft UI seam | Exact current four-argument restoreDraft, openEditor overloads, strictCheckpointPage and identity methods; real EditRecoveryPolicy | Models, JSON and controls are synthetic, not Android org.json or rendered UI |
| PDF preview contract | Exact resolver/sizing statements executed on JVM; static ownership/manifest/revision assertions | Android PdfRenderer behavior, actual page rendering, text search or selection |
| UTF-8 text core | Production Models/provenance mutation, formatter, SaveJobStore and text target protocol on real synthetic files | In-memory receipt persistence and host JSON/Android seams; not Android AtomicFile/SAF |
| Text UI callbacks | Extracted current source/confirmation/save-result/recovery methods, real formatter/receipts/files | UI, Store, scheduler and descriptor bridge are synthetic; bridge is structurally checked only |
| Single-page OCR candidate | Extracted recognition and UI callbacks; actual Models, candidate policy, preflight, recovery files and source hashing | Native OCR, image decoding/rendering, Android UI/clipboard, Store writer, JSON and scheduler are host seams; no recognition quality claim |
| Earlier public host suite | Pure Java helpers, actual filesystem helpers and selected production methods | Explicit model/decoder/persistence/Android seams remain substitutes |

The old draft harness's obsolete call signature and model fields are updated to the production interface; the production policy is compiled rather than duplicated. PDF revision extraction stops before the newly added preview method. No passing replacement for production logic is supplied.

Windows provider limits must remain visible: the original secure-directory IntakeHost positive path can fail closed because the provider does not supply the required verifiable identity. That case is reported as SKIP after the actual production rejection; other helper tests still run. Symlink fixture creation may be unavailable; those branches report SKIP, and no security or privilege setting is changed. A green available-stage result with skips is not full Linux/Android filesystem coverage. Initial failures remain part of local integration evidence.

OCR host checks use invalid synthetic assets and synthetic recognition seams. Real pinned model preparation, native OCR accuracy and JNI are not exercised by the host suite.

## Historical Android and runtime evidence

The full dependency-equipped vc6 source completed offline Android Java/resources/lint, four-ABI native packaging and debug application/test-APK compilation. That is historical evidence for the matched production source; this source-only integration does not repeat or claim a fresh-clone Android build. Lint success does not mean warning-free. Test-APK compilation does not mean instrumentation execution.

| Version | Limited actual runtime evidence | Boundary |
| --- | --- | --- |
| vc2 / 0.2.0 PDF preview candidate | User-run API36/ARM64/4KiB: synthetic A/B/C import, crop confirmation/save, English/Chinese OCR flow, ordinary/searchable PDF, in-app preview, external reading/search/copy, reopening data | OCR words/formulas unreliable; not vc6 phone acceptance |
| vc4 | Earlier bounded synthetic emulator save/next and recovery checks | Not automatically inherited as tests of every later APK |
| vc6 / 0.2.4-preview-recovery-verify | API36/x86_64/4KiB: one preserve-data upgrade from vc4, old synthetic files retained, readable records/normal quota, conflict and saved replay refusal, three-page save/next/last, cancel-back, actual process recreation of Activity gray draft, ordinary PDF preview pages 1–3 and return | Not a phone test or the independent record positive-load branch |

vc6 identity for distinguishing historical evidence: application SHA-256 `de8bc786c6366001e33d246c5f1251ffb05c9b2a1c27066237adeb86f03418b3`. This identifies an internal debug build; it is not a download or release. Original vc2 phone observations refer to the earlier build beginning `8daa7bd7`, not that vc6 hash.

A later bounded natural-sample observation inspected one existing synthetic document's four independent records: three already saved and one conflicting. No eligible baseline record existed, so none was selected. **Positive independent-record manual load remains device NOT_RUN.** Host Gate positive cases and saved-Activity draft restoration cannot substitute for it. Source corruption/replacement and callback mutation were not injected on a device. Full quota/disk faults, API26, ARM vc6, 16 KiB runtime and full instrumentation remain unverified.

## Fresh-clone and release gaps

This payload deliberately omits APK/AAB/AAR, OCR traineddata, pdf.ttf, SDK/NDK/CMake/Gradle binaries and caches, upstream vendor source, signing configuration, local.properties, private runtime evidence, scans and old image fixtures. It also does not contain the complete Android instrumentation source set; a runner name in Gradle does not supply that runner or its fixtures. The default fixture-dependent suite is not represented as runnable.

The build configuration retains the original relative `../../.tools/sources/Tesseract4Android-4.9.0/tesseract4android` layout. The preparation script also assumes that enclosing layout; do not execute it blindly from an arbitrary clone directory. No Gradle wrapper is included. Build prerequisites remain Gradle8.13, AGP8.13.2, JDK17, compile/target36, min26, build-tools35.0.0, NDK27.2.12479018, CMake3.22.1, ExifInterface1.4.2 and Annotation1.3.0 plus declared transitive dependencies. Consult the pinned source/asset hashes and patch guards in `docs/OCR-DEPENDENCIES.json`. This integration does not download them or change versions.

CAMERA is requested, INTERNET is not; normal backup is disabled and extraction exclusions are present. System document providers and sharing targets can still use networks. Actual platform transfer, low storage, interrupted native calls, heavy batches and power loss remain bounded or untested. File force/readback is not directory fsync or a power-loss guarantee.

Apache-2.0 covers original code. Existing third-party notices and patch attribution remain. Font/model/native dependencies each need their applicable terms; this work does not certify all binary distribution obligations. Formal beta naming, release channel, release signing and upgrade policy remain separate decisions. Never solve a signing conflict by uninstalling before preserving and checking data.

