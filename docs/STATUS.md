# 当前状态 · Status

更新于 / Updated: **2026-11-04 (UTC)**

**源码开发预览；扫描准备阶段问题尚未确认修复。 Source development preview; the scan-preparation issue is not confirmed fixed.**

## 版本边界 · Version boundaries

- **本次文档更新前 main 的代码基线 / Main code baseline before this documentation update:** [`86f3719`](https://github.com/texasisalwaysbusy/wangshu-scanner/commit/86f37191660e657790971c3211aa1a158ab8834d)。
- **恢复与 PDF 预览源码 / Recovery and PDF preview:** [draft PR #1](https://github.com/texasisalwaysbusy/wangshu-scanner/pull/1), `preview/vc6-source-integration`, head [`2407daf`](https://github.com/texasisalwaysbusy/wangshu-scanner/commit/2407dafd1ec2e7f4ea3d53a5d4550871636a97f7)。
- **较新 OCR 审阅源码 / Newer OCR-review source:** [draft PR #2](https://github.com/texasisalwaysbusy/wangshu-scanner/pull/2), `preview/ocr-page-review-bounds`, head [`3540297`](https://github.com/texasisalwaysbusy/wangshu-scanner/commit/3540297934a219eca3e7cfffeecdf5512b508022)。此 PR 以 PR #1 的分支为基础，并未合入 main。It builds on PR #1's branch and is not merged into main.
- **本地构建观察 / Local build observations:** 本地构建报告为 `0.2.5-ocr-review-phone-20261103`，versionCode 10，源码冻结基线为 `3540297`；另有准备阶段诊断 APK。公开 README 顶部仍沿用旧 code 6 标识，不能仅凭版本号确定这些源码。A local versionCode 10 build was reported against frozen source baseline `3540297`, alongside a preparation diagnostic APK. The public README retains an older code 6 header, so its version label alone does not identify these source candidates.

## 主要改进 · Current development work

PR #2 新增逐页文字纠错、带页码的 UTF-8 TXT 导出，以及保留旧修订的单页重识别候选比较。采用候选仍需单独保存页面；主机检查记录不等同于 Android UI、生命周期或设备通过。未声称中文识别精度提升。

PR #2 adds page-by-page text correction, page-numbered UTF-8 TXT export and single-page re-recognition comparison that retains the old revision. Adopting a candidate still requires a separate page save. Recorded host checks do not establish Android UI, lifecycle or device acceptance. No Chinese OCR accuracy improvement is claimed.

**人工修订／候选文字与 TXT 副本不会更新 PDF 文字层；PDF 生成仍重新 OCR。** 应用内 PDF 预览仅显示图像。文字搜索、选择与复制需在支持的外部阅读器中核对。

**Manual corrections, candidate text and TXT copies do not update the PDF text layer; PDF generation runs OCR again.** The in-app PDF preview is image-only. Verify text search, selection and copying in a compatible external reader.

## 既有观察与未验证范围 · Observations and limits

- 较早的 0.2.4 有限真机流程使用合成两页材料，观察到图片导入、页序调整、逐页保存、OCR、可搜索 PDF，以及外部阅读器中的搜索／选择／复制。中文较弱，公式不可靠；这些结果不验证较新构建。An earlier, bounded 0.2.4 phone workflow exercised those operations using a synthetic two-page document. Chinese recognition was weak and formulas unreliable; the observations do not validate newer builds.
- 最近一次特定扫描测试仍停在准备阶段。源码诊断复现了证据写入／记录失败机制，但尚未确认设备故障根因，也不能据此声称已经修复。The latest specific scan test remained stuck in preparation. Source-side diagnosis reproduced an evidence-sink / recording failure mechanism, not the confirmed device root cause or a verified fix.
- 相机完整流程、强制中断，以及独立恢复记录的正向手动载入尚未完成验收。Camera workflows, forced interruption and successful manual loading of an independent recovery record remain unvalidated.
- 尚未证明从公开仓库全新克隆可以完整构建。已有开发环境的构建证据不能替代这一验证。A complete build from a fresh public clone has not been demonstrated; builds in an existing development environment do not establish it.
- 普通 ZIP 不含独立恢复记录、生命周期快照及文字缓存／回执。参数副本也不等于包含原图和全文稿的完整备份。Ordinary ZIP exports exclude independent recovery records, lifecycle snapshots and text caches / receipts. A parameter copy is not a complete backup of originals and the whole document.

## 发布状态 · Release status

截至本页更新，尚无已核实的公开安装包。本页描述当前源码与验证范围，不把诊断 APK 或源码合并视为已通过 beta 验收。二进制可发布性、签名升级路径及所附第三方条款应以实际发行材料为准。

As of this update, no public installable package has been verified. This page describes source and validation scope; a diagnostic APK or source merge does not establish beta acceptance. Binary distributability, the signing / upgrade path and bundled third-party terms must be checked against the actual release artifacts.

## 来源 · Sources

- [Main README at 86f3719](https://github.com/texasisalwaysbusy/wangshu-scanner/blob/86f37191660e657790971c3211aa1a158ab8834d/README.md)
- [Draft PR #1](https://github.com/texasisalwaysbusy/wangshu-scanner/pull/1)
- [Draft PR #2](https://github.com/texasisalwaysbusy/wangshu-scanner/pull/2)
- [README at 3540297](https://github.com/texasisalwaysbusy/wangshu-scanner/blob/3540297934a219eca3e7cfffeecdf5512b508022/README.md)

较新本地观察据所提供的测试与诊断摘要整理；未附私人原始日志，不应当作公开可复现证据。

Newer local observations are summarized from the supplied test and diagnosis record. Private raw logs are not included; these summaries are not public reproducibility evidence.
