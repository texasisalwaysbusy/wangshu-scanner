> **CURRENT STATUS: PAUSED / 当前暂停**
>
> Do not start any new build, compilation, regression, host check or device test.
> Resume only after a new explicit instruction from the user. The combined
> Android build for repairs 001–003 has not been executed. Saved host results
> below are historical records, not newly authorized or newly run checks.
> The cause of the actual 420-second phone timeout remains unconfirmed.
>
> 当前不得启动新的构建、编译、回归、主机检查或真机测试；须获得用户新的明确指示后
> 才能继续。001–003最终组合的Android构建未执行。下方已有主机结果仅为历史记录，
> 不代表当前获准重跑，也不证明手机420秒现象的根因。所有运行命令仅供未来接续参考。
>
> See CURRENT_STATUS.md before using any instructions in this package.

# Scanner preparation diagnostics: source repair set 001–003

This is a standalone, reviewable **test-tool source package**, not a product APK.
It repairs two diagnostic reliability defects and preserves captured frames when
a diagnostic listener fails. It does not establish the cause of the reported
420-second phone timeout or a product acceptance result.

这是可独立恢复、复核的测试工具源码包，不是扫描器产品安装包。修复测试工具的
超时收尾、并发栈记录覆盖和已捕获栈丢失问题；没有把主机验证当作 Android 验证，
也没有认定这些问题就是此前手机 420 秒现象的根因。

## Changes

1. **001: bound stack capture outside the controller lock.** Target stack
   acquisition and the listener share the existing diagnostic budget. A stalled
   capture no longer holds the controller monitor and blocks its outer timeout.
2. **002: preserve separate per-phase diagnostic files.** The runner uses
   DiagnosticFiles with CREATE_NEW, never replacement or truncation of an
   existing file. An arrival and SETUP timeout can retain both records. A new
   read-only collector recognizes legacy and new formats and reports missing,
   malformed or unrecognized stack evidence rather than silently ignoring it.
3. **003: retain already captured frames on listener failure.** A local atomic
   reference publishes an immutable captured snapshot before invoking the
   listener. The exception path reads that snapshot. Budgets are unchanged,
   there is no controller-lock wait for capture, and already-thrown Timeout
   objects are never asynchronously modified.

Patch 001 is unchanged from its earlier standalone source package. That earlier
package does not contain 002, 003 or the strengthened capture-thread join test.
The optional monitor cleanup change is deliberately not included here.

## Layout and recovery

- `candidate/`: complete tooling source overlay and associated host tests.
- `baseline/`: relevant unchanged archived inputs, for identity comparison.
- `reference/after001/BoundedStage.java`: exact intermediate source for the 003
  red/green check.
- `patches/`: apply 001, 002, then 003 to the matching archived tooling overlay.
- `tests/`: independent fault probes shared across revisions.
- `run_checks.py`: one dependency-free host check entrypoint.
- `checks/`: selected synthetic host results only, no phone records.
- `docs/BUILD_AND_RECOVERY.md`: build inputs, integration and limitations.
- `docs/STACK_COLLECTION.md`: old/new diagnostic-file collection contract.
- `MANIFEST.json`: each packaged file's size and SHA-256.
- `PUBLIC_FILES.json`: explicit publication allowlist, excluding its own file
  and the subsequently generated manifest.

The frozen repository commit 3540297934a219eca3e7cfffeecdf5512b508022 did not contain
this complete tooling directory. Do not try to apply the patches to an absent
file. Restore the candidate tooling subtree, or first restore the matching
archived overlay and then apply the patches. Never copy a later product app tree
to make these test-tool patches apply. No `app/src/main` file is in this package.

## Reproduce

With an already installed Python 3 and JDK compiler module:

    python -B run_checks.py new-output-directory

The output directory must be new. All generated files are retained. The script
does not access a phone, run ADB, use the network, install dependencies, delete
files or perform Android compilation. It compiles unchanged baseline,
after-001 and final controllers separately and uses controlled host threads.
The capture probe waits for its recorded diagnostic caller to exit before
asserting that no late listener ran.

Saved final verification contains 14 command entries, all passed:

- Four controller capture fault cases across baseline and final revisions.
- Captured-frame red/green checks: listener exception, blocked listener,
  capture failure, late capture, immutable thrown timeout, and shared budget.
  Each revision performs 27 checks.
- Existing controller regression: 16 scenarios, 133 assertions.
- Existing evidence-boundary regression: 8 scaled cases.
- Production diagnostic writer: 25 checks, including two overlapping controller
  timeout callbacks and same-phase exclusive-create contention.
- Legacy/new collector: 11 tests, including records emitted by the Java writer.
- Standalone tooling source contracts: 9 tests.

## Limits

JDK21 source/target17 was used. This environment could not run a Java17 API fence.
Android compilation, installation, phone/UI tests, native OCR, real Binder,
filesystem power-loss safety and product acceptance are not established here.
The exact original synthetic A/B/C PNG assets are not included; no replacements
were generated. Android integration must use the separately verified originals.

The controller can retain a terminal when evidence delivery fails; those events
are not replayed. Framework finish receipt remains a separate boundary. A stack
stall alone emits END records before stalling, so it does not alone explain a
17-event exported prefix. Diagnostic files remain best effort and may be partial
on failure. The collector reports only a local directory snapshot, not complete
remote capture or confirmed durable storage. See the two detailed guides.
