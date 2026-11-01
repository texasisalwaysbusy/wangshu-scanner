# 第三方材料

本仓库自有代码使用 Apache-2.0；下列第三方组件和数据不因此改变其许可证。构建或分发时应复核实际使用的版本及完整传递依赖。

- [Tesseract4Android 4.9.0](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0)：Apache-2.0；固定归档哈希与本地补丁的来源、变化及散列见 `docs/OCR-DEPENDENCIES.json` 和 `third_party/patches/`。仓库不包含该上游完整源码。
- Tesseract、Leptonica、libjpeg/IJG、libpng、AndroidX ExifInterface 等的上游版权和许可证见 `app/src/main/assets/notices/`；分发二进制前必须复核实际链接版本及相应 notices。
- [tessdata_fast 4.1.0](https://github.com/tesseract-ocr/tessdata_fast) 模型及相关字体不含在此仓库。来源、预期哈希和许可证线索见 `docs/OCR-DEPENDENCIES.json` 与 notices；应在取得和分发前分别核对。

仓库不含用户扫描件、APK 或上游依赖的完整发布包。不能从这份清单推断所有传递依赖已完成法律审查。
