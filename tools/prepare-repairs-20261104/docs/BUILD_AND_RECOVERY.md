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

# Build inputs and recovery

## Identity

The archived controller input is SHA-256
`d682707f15c85d0518fe6c74e3b427f48fd11a23a87b554d6d6ee38a3a49b7d9`.
The after-001 controller is
`15701ad717fc3a2fe34612e9c8194dcd554b594799ee94667e7ed9f4d965f90a`.
All final source hashes are in MANIFEST.json. Check them before and after copying.

The app reference is repository commit
`3540297934a219eca3e7cfffeecdf5512b508022`. Its committed build file declares
minSdk26, target/compileSdk36, Java17, build tools35.0.0 and NDK27.2.12479018.
The separately frozen phone build used local version code10; that version patch
is not the same as the committed Gradle version and is not supplied here.
Preserve the exact verified product source, version patch, native dependencies,
signing identity and fixture assets used by the chosen build. Do not infer them
from this tool package or silently substitute later product source.

## Full tooling inputs

Android test sources:

- tools/scanner_instrumentation/android/ScanInstrumentation.java
- tools/scanner_instrumentation/android/Fixtures.java
- tools/scanner_instrumentation/android/AndroidManifest.xml
- tools/scanner_instrumentation/java/org/wangshu/scan/BoundedStage.java
- tools/scanner_instrumentation/java/org/wangshu/scan/DiagnosticFiles.java

The manifest is an androidTest source overlay, not a standalone APK manifest.
The app's testInstrumentationRunner must remain
org.wangshu.scan.ScanInstrumentation. Fixtures declares
org.wangshu.scan.synthetic.Fixtures and the existing synthetic provider authority.
Both Java source directories must be included. Do not compile archived and
candidate classes together or include a duplicate older runner from another
androidTest directory.

The existing build may wire these directories through sourceSets or copy the
files into an isolated androidTest tree. Both approaches must preserve package
declarations and include the new DiagnosticFiles helper. The required runtime
asset paths are:

- assets/fixtures/A.png
- assets/fixtures/B.png
- assets/fixtures/C.png

The fixture provider uses openFd, so the packaged assets must remain accessible
as uncompressed asset-file descriptors, as in the verified original test build.
These three original PNGs and their original generator are absent from this
source package. No substitute was created. Their provenance and hashes must be
verified separately before a reproducible Android test APK claim. An APK that
merely compiles without these assets cannot establish runtime preparation.

This package does not include Gradle/SDK/NDK binaries, wrapper downloads, cached
dependencies, native libraries, keys, keystores, APKs or original phone evidence.
Use the existing authorized build environment and dependency inputs. If a
required input is absent, report it rather than replacing it or claiming that
build/runtime validation passed.

## Host and collection inputs

The existing tools/scanner_instrumentation_host.py remains unchanged. It validates
stage/stdout/framework protocol, not stack-file completeness. The additional
tools/collect_prepare_stacks.py reads an explicitly supplied local run directory.
The entire precise run directory must be retained by any separate transport
collector. See STACK_COLLECTION.md before reusing a fixed-filename collection
script. The saved source-contract helper has no dependence on app source or
Gradle files; the complete original navigation-tree source test is not required.

## Apply and verify

1. Verify packaged hashes and the destination's exact baseline.
2. In a fresh isolated build copy, restore candidate tooling. Alternatively,
   restore the archived tooling baseline and apply 001, 002, then 003.
3. Compare resulting source hashes against candidate. Keep product app inputs
   unchanged. No app-version, feature, permission or timeout-budget change is
   part of these patches.
4. Run the independent host entrypoint. It checks both baseline failures and
   final repairs and preserves every output.
5. Perform Android compile/lint/package identity verification separately. Check
   the merged instrumentation manifest, provider, all helper classes, exact
   fixture assets and the chosen product/signing/version identities.
6. Record compiler and Android verification outcomes separately. Host success or
   Android compilation alone is not a fresh phone test or product acceptance.

No device run is scheduled or implied by these steps.
