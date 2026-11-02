# Source development snapshot — 2026-11-02

This remains a development preview, not an accepted release. No installer is published.

## Changes since the initial public source snapshot

- Import/ZIP restore: stricter archive/manifest checks, bounded receive/write progress and conservative capacity checks. Received data and failure fragments are retained; importing the same archive still creates another independent document and consumes more storage.
- Recovery: inspect/export received ZIP fragments without representing them as complete ZIP backups; additional closed camera-batch recovery and receipt checks. Existing unresolved save requests must not be silently replaced.
- Crop safety: smaller valid crops are permitted; some low-contrast shadows now fall back to the full image. Pages must be explicitly reviewed before PDF export. **A shadow equal in brightness to the background can still produce a crop that cuts off the page.** Synthetic checks do not establish real-photo accuracy.
- OCR/PDF identity: length-framed revision keys avoid ambiguous concatenation; null recognition does not overwrite the prior text. Existing OCR text can be retained but marked stale after image edits.
- Editor: restore edit state before rebuilding controls; explicit on-disk edit checkpoints with baseline checks and separately confirmed metadata export. These are not autosave or complete backups.
- OCR preparation: pinned asset size/SHA-256 checks, unique retained preparation files, interruptible process-local serialization, and cleanup of failed native initialization ownership.
- Camera batch: queued lock acquisition is interruptible. Heavy work remains inside the batch lock; this is **not** proof of an Android ANR fix.

## Reproducible host checks

From the repository root, with an existing Python 3.10+ and a JDK with the compiler module available:

```sh
python3 tools/test_host.py
```

Set `JAVA` to an existing Java executable if needed. The runner does not download or install anything. It retains sources, synthetic fixtures and logs in a new `build/host-tests-*` directory. The suite has been exercised on Linux with Python 3.12 and host JDK 21; filesystem/symlink behavior on other platforms remains unverified. Host compilation is not Android API compatibility verification.

The public suite compiles actual pure Java helpers and extracts selected production methods into explicitly synthetic Android/model/JSON/decoder/persistence test seams. It covers geometry, detector counterexamples, OCR and PDF revision identity, null OCR preservation, image/ZIP import, storage-progress faults, editor restoration/checkpoint guards, checkpoint export through save receipts, lock cancellation, received-fragment handling and original core regressions. It also rejects lone-surrogate checkpoint text before writing. The PDF-revision fixture includes the current `cropReview` field and verifies that changing it invalidates a cached revision.

The OCR helper check here uses deliberately invalid synthetic assets to test rejection/retention/cancellation. It does **not** exercise valid pinned model preparation or native OCR. Those omitted assets are not silently fetched. The suite never labels an unavailable Android/native/real-asset stage as passed.

## Android build evidence and its limits

An independent audit of a dependency-equipped source snapshot matched all **48 public `app/src/main` files, including 30 Java sources**, against the source used for a completed Android build. The other three main-tree files were the OCR binary assets intentionally omitted here. The audit reported successful application/test APK generation, Android Java/resource processing, four-ABI native compilation and lint execution. The application APK SHA-256 was:

```
6bda318d4a4268e088d9b923dcb780863c25e6beeb1eadcf3f7d5f8d92814e9d
```

Lint completed with **0 errors and 18 warnings**: SetTextI18n (9), DrawAllocation (4), UsableSpace (2), DataExtractionRules (1), ObsoleteSdkInt (1), and AccessibilityWindowStateChangedEvent (1). AndroidTest compilation also reported five annotation-enum warnings. A successful build is not a warning-free build.

This hash identifies build evidence, **not an offered download, release or safety certification**. That build used separately prepared SDK/NDK, upstream source and assets. It does not demonstrate that a bare clone of this intentionally source-only repository builds reproducibly. The existing OCR build configuration still assumes the original relative `.tools` layout. Full dependency/bootstrap reproducibility remains open.

Android runtime acceptance is **not established by this snapshot**: Camera2, native OCR/JNI, API26/current-device behavior, actual `org.json`, SAF destinations, lifecycle/process death, backup/transfer, real-photo accuracy and hardware power-loss behavior require separate evidence. Do not interpret an in-progress or historical device test as a pass of this snapshot.

## Data safety and privacy limitations

- The manifest requests CAMERA, not INTERNET, and disables ordinary app backup; configured extraction rules exclude app data. System document providers and share targets can still be network services. A lint warning about missing legacy `fullBackupContent` is not evidence of a leak; actual platform backup/transfer behavior still needs testing.
- Checkpoints contain both old and edited Page JSON, potentially including historical OCR text and local image references. They contain **no images**. Ordinary ZIP exports omit them; uninstall/clear-data can lose them. At most 16 retained entries per document, each at most 4 MiB; no automatic cleanup or conflict merge is implemented.
- `tools/inspect_edit_draft.py FILE` validates an exported `.bin` record offline and hides OCR/parameter contents by default. `--json` deliberately prints the private stored strings. It does not upload, import, repair or modify the file.
- Received-fragment inspection requires a secure directory handle and verifiable file identity. Unsupported filesystem providers fail closed; Android provider behavior remains unverified. A fragment export is not complete recovery or resumable import.
- Existing empty/damaged OCR cache files are not replaced automatically. Four retained failed preparation fragments per asset block further preparation until a separate repair decision. Unresolved save receipts can similarly block new saves. No cleanup/repair UI is supplied.
- File force/readback is not directory fsync or a power-loss guarantee. Malicious same-UID path replacement and the interval between asset verification and native reopen are not fully defended. Single blocking calls and Store monitor waits are not forcibly timed out.

Never put private scans, OCR content, exported checkpoint files, absolute local paths, credentials or logs containing them into public issues or this repository.
