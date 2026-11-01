# 望舒扫描 · Wangshu Scanner

**开发预览：尚未完成验收。本仓库不提供安装包。**

Android 文档扫描项目，目标是本地拍摄或导入图片、识别页面边界并手动调整四角、透视校正、彩色／灰度／黑白处理、多页整理以及 PDF 导出。原图和用户文稿的安全恢复、复杂背景边界识别、OCR、相机生命周期和真实设备兼容性仍需验证；现阶段不能视为 Adobe Scan 的完整替代。

## 当前源码范围

- `app/src/main/`：Android 应用、扫描处理和资源。
- `tests/`：部分合成数据主机测试；它们不是完整的 Android 或真机验收。
- `ocrnative/`、`tools/prepare_ocr_source.py` 和 `docs/OCR-DEPENDENCIES.json`：固定来源的 OCR 原生依赖配置、获取说明与校验信息。
- `third_party/` 与 `app/src/main/assets/notices/`：上游补丁和第三方许可说明。

## 构建与隐私限制

源码快照不含 APK、OCR 模型和字体、Android SDK/NDK 或上游 OCR 原生源码；尚未在全新环境完成可复现构建。构建脚本目前沿用原开发工作区的 `.tools` 布局，不应假定克隆后即可直接构建；需先核对并准备 `docs/OCR-DEPENDENCIES.json` 中的固定来源、散列和相应许可，并审阅脚本。禁止把私人扫描件放进问题报告或仓库。

应用倾向本地处理，但系统文件提供者和分享目标可能是其他应用或网络服务；具体隐私与数据恢复行为仍需平台实测。卸载可能删除应用私有数据，应在操作前导出并核实备份。

## 许可证

项目原创代码按 [Apache-2.0](LICENSE) 许可；第三方源码、补丁、依赖、字体和模型各有独立条款，见 [THIRD_PARTY.md](THIRD_PARTY.md) 和附带 notices。许可证不代表本预览已完成安全或设备验收。
