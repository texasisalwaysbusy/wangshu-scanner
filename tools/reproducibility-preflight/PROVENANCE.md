# 来源、版本和许可边界

来源：2026-11-02已有HTTP/校验回执、Google repository XML缓存、已安装工具和公开notices、2026-11-03本轮只读重核及父级独立源码审查。未联网实时核验，不声称上游签名认证。

| 原始包/模型 | 字节 | 身份 |
| --- | ---: | --- |
| gradle-8.13-bin.zip | 136983045 | SHA256 20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78 |
| Tesseract4Android-4.9.0.tar.gz | 41337000 | SHA256 4569f9093d641d13188ba2dd36a74396661eaa3f9e44637ff1cac9a36076a20e |
| build-tools_r35_windows.zip | 59878107 | SHA256 5753c679a1b90bcf6fbc9945a2ce39dfb9e74f1df0831a1e1866ae5b594326f0 |
| android-ndk-r27c-windows.zip | 781511249 | SHA256 27e49f11e0cee5800983d8af8f4acd5bf09987aa6f790d4439dda9f3643d2494 |
| cmake-3.22.1-windows.zip | 16116742 | SHA256 c9a9d568452a20cf27d703cb21ef9529dd67bda6be048c4d4b884acb3ac3a2b8 |
| chi_sim.traineddata | 2469156 | SHA256 a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730 |
| eng.traineddata | 4113088 | SHA256 7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2 |
| platform-36_r02.zip | 65878410 | 历史官方SHA1 2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576；SHA256未知 |

pdf.ttf是572字节Tesseract GlyphLessFont，SHA256 c7845420925a23d88ed830a63957b8af85a66a8daf8d9fc90e843673b2ef1a59，来自固定Tesseract归档的tessdata/pdf.ttf。父级独立审查确认等于pdf_ttf.h嵌入数据前572字节（另有尾随0）；头文件注明Google2020/Apache-2.0。本轮只读核了归属头部与现有字节，不重复提取或生成字体。

两模型与tessdata_fast/4.1.0 tag URL的HTTP200历史回执及当前vc6匹配；tag-to-commit尚未独立解析。固定内容hash不等于tag已经固定成commit。

Gradle8.13匹配历史官方SHA256。build-tools35.0.0、NDK27.2.12479018、Google CMake3.22.1的归档历史上匹配官方XML大小/SHA1，本轮再次计算SHA256。这些本地SHA256是归档内容身份，不是Google另行签名的校验文件。仅有SHA1的历史记录不是现代签名认证。

API36/revision2/extension17：android.jar与复制回执一致，SHA256 d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a。原副本来自既有游戏SDK。缓存官方XML列出platform-36_r02.zip/65878410字节/SHA1 2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576，但原包未取得和比对，不能把复制路径当官方下载安装证明。

JDK：历史Gradle输出为Eclipse Temurin17.0.19+10，现有release只有JAVA_VERSION/MODULES，具有java和javac。原发行包URL/hash/安装来源未知。清单只记录观察样本与GPL2/Classpath文本，不将它包装成来源已证的官方完整发行包。

固定上游版本：Tesseract4Android4.9.0、Tesseract5.5.1、Leptonica1.85.0、IJG9f、libpng1.6.48；来自父级独立源码审查和固定归档证据。已用CMake为Google的3.22.1-g37088a8-dirty，不应随意换成任意标称3.22.1版本。

## Maven

16项核心文件本轮重核，包括AGP8.13.2及插件marker、ExifInterface1.4.2和Annotation声明/解析版本。AGP主JAR13116808字节，SHA256 e494f7ce75ca6c1abff301d4a70b18fdd3d6af855875f4f85082bb7608f041de；ExifInterface AAR75550字节，SHA256 6bb2b68682a2023c55f35f941444ffea0157947b061e4fd7606ba935e2e77425。

历史375缓存文件均能关联到日志中的官方Google Maven/Maven Central下载URL。本轮没有全量重核，也没有据此建立最小依赖闭包或Gradle解析锁。观察集合含不同配置和metadata。应用runtime把Annotation1.3.0升为1.8.1/annotation-jvm1.8.1，ocrnative自身配置仍有1.3.0。详细身份与URL见observed-maven-artifacts.json。

## 已见许可证与再分发限制

| 组件 | 已见证据 | 边界 |
| --- | --- | --- |
| 项目原创/预检代码 | Apache-2.0 LICENSE | 保留版权许可与变更说明，不覆盖第三方 |
| Tesseract4Android/Tesseract | 固定归档/公开Apache-2.0 notices | 保留上游及补丁说明，附带库分别处理 |
| tessdata_fast | Apache-2.0 notice与固定内容来源 | 本包不分发模型；未来保留对应许可来源 |
| GlyphLessFont | Google2020/Apache-2.0头部和内容匹配 | 本包不分发字体，不省略归属 |
| Leptonica/IJG/libpng/LLVM | 各自公开notices | 按实际链接版本处理，不能改标项目Apache |
| Gradle | Apache-2.0 LICENSE/NOTICE | 工具及其依赖另有义务，本包不分发工具 |
| AGP/Exif/Annotation | POM声明Apache-2.0 | 不覆盖全部传递/打包内容 |
| CMake | Kitware三条款BSD核心文本 | 二进制须保留条件/免责，附带组件另审 |
| SDK/NDK | Google SDK条款引用和组件NOTICE | SDK整体不是项目Apache授权，开源组件按自身条款 |
| JDK | GPLv2/Classpath补充/Assembly Exception | 不可将整个JDK改标Apache，原发行来源仍未知 |

Google SDK缓存条款文本SHA256 1f8729233617b193fd619213792ae16a41b95d2bbbf525dfe66998252ba68b16；第2节要求使用者自行接受，第3.4/3.5区分SDK限制与开源组件。本轮不复制接受标记、不接受条款、不代新用户表态，实际包条款变化须重新审阅。

206份历史POM许可记录中58份需继承或bundle审查。license-fingerprints.json和maven-license-declarations.json仅为索引/声明，不是所有二进制分发已经合规的结论。

## 仍须补齐

官方JDK17发行包URL/散列与审阅profile；API36 r2原包/SHA256/内容对应；需要commit级追溯时的模型tag映射；正确外围布局和可用patch步骤；新用户SDK条款处理；全新缓存依赖解析/锁定/许可证据；完整instrumentation/合法合成fixture。然后才能验证独立新环境构建，位级复现还需另验签名、时间戳、工具与输出确定性。
