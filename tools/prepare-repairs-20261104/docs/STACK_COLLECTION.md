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

# Diagnostic-file collection compatibility

## Old and new

Legacy input is a single program-stacks.json with runId, stacks and
contains_document_text:false. It remains readable by the new collector.

The repaired writer emits one file per phase:

    program-stacks-preparation-activity-arrival.json
    program-stacks-preparation-setup.json

Stage is restricted to startup/preparation/case. The already validated ASCII
identifier is lower-cased with Locale.ROOT and underscores become hyphens. The
original identifier alphabet excludes hyphens, so this conversion does not
merge distinct valid identifiers. The file is created exclusively with
CREATE_NEW and never overwrites a prior file, including a legacy file.

New records add format=wangshu.scan.program-stacks.v2 and top-level stage/id.
They retain runId, stacks, the timed-out-operation row and the privacy marker.
Collector checks bind the name, top-level identity and target-row identity.

## Collection rule

Any external transport must preserve all files in the exact fresh run evidence
directory. A script that fetches only program-stacks.json must be updated before
it can collect the repaired format. No transport operation is implemented or
performed by this package. The available original host verifier did not contain
a stack-file fetch operation; it remains unchanged.

For already-collected local files:

    python -B candidate/tools/collect_prepare_stacks.py RUN_DIRECTORY --run-id RUN_UUID

When expected diagnostic phase IDs are independently known, add one or more
--expected-id options. Missing expected IDs cause failure. Without expectations,
the result explicitly describes only a directory snapshot, never complete
remote collection. A diagnostic can time out before producing any file, and an
already-started writer can finish after controller termination. Directory
observation cannot prove those operations were cancelled or fully captured.

The reader enumerates every program-stacks-prefixed entry. Unknown names,
non-files, invalid JSON, duplicate keys, non-standard numbers, wrong run/phase
identities, excessive sizes and absent files produce explicit errors and exit2.
It never silently discards an invalid stack-prefixed entry. Mixed legacy/new
directories preserve both records and flag overlapping phase records. It does
not write, copy, delete or report stack contents in its summary.

An exit0 means the encountered files satisfied these read checks. It is not a
product PASS, evidence durability guarantee or proof the remote directory was
fully copied. Stage/stdout/framework acceptance rules are unchanged. Original
logs and old archives remain valid inputs and must not be rewritten as v2.

## Failure behavior

Exclusive-create conflict or I/O failure propagates to the runner's existing
evidence-failure flag and controller diagnostic-error record. Partial files are
retained; they are not deleted or overwritten. There is no global writer lock
that holds the controller during I/O, and budgets are not increased. Keeping
files separate prevents overlapping timeout callbacks from replacing each
other, but does not make asynchronous I/O hard real-time or power-loss atomic.
