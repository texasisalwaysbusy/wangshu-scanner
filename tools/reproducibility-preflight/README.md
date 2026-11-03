# 源码预览的Windows离线预检准备

对应已发布提交f5423b0646d232a1b27b3b4ed6ed139408baa34b，tree099c2401b35c3fa556812fea4d3d09f438ff0ab2。本轮依照对应本地98文件和既有远端回读证据，只新增本目录，没有联网、下载、解压、安装、运行Gradle/SDK、ADB/模拟器、手机或远端写入。

这是静态构建准备检查器，不是一键安装器，也没有完成全新环境构建或位级APK复现。工具profile来自已有Windows x86_64环境，不覆盖其他系统。

## 默认只读用法

已有Python时运行：

~~~text
python -B preflight.py --project "<workspace>/repo/projects/scanner-app" --resources "<workspace>/resources.json"
~~~

以resources.example.json为配置起点；相对路径按配置文件所在目录解释。省略resources时使用随包示例，待准备文件不存在就明确失败，不下载、不创建目标目录。

退出0/PASS_STATIC_INPUTS_ONLY只说明列出的文件、字节/哈希、布局和人工review声明匹配，不是构建通过。退出2表示缺目标/资源、错误内容/布局/配置或未声明review，报告包含具体路径和原因。检查器不调用外部工具，不写资源，不接受协议，不读写SDK接受标记；拒绝manifest目录逃逸和符号链接/Windows重解析点。

license_reviews默认为空，只是调用者手工填写的review自报，不产生SDK许可文件或法律授权。--all-observed-maven可额外核对历史375文件，但不是传递闭包/依赖锁证明，本轮未全量重读该集合。

JDK及解包工具项是现有安装样本指纹，不是官方发行包签名。另一个可信JDK17可能不同；应先确定官方发行来源并形成审阅后的profile，不要盲改期望hash来过检查。

## 实际检查

当前源码预览检查按预期退出2：149项文件检查通过，其中98项源码、51项其他输入，缺API36 r2原始包和项目内三份OCR资产；另有vendor相对布局不匹配、新用户review未声明两项问题。旧完整环境有资产副本不代表本预览包含它们。

原tools/prepare_ocr_source.py只准备native源码/补丁，不准备eng.traineddata、chi_sim.traineddata、pdf.ttf。缺资产时打包可能成功但OCR打开失败，因此本预检单独阻止缺资产通过。

9项CLI合成合同用例及只读API静态检查通过，输入字节不变；符号链接用例因当前权限不足跳过。合成完整输入的退出0不代表任何SDK/JDK或新环境已验证。

## 最短可信的新环境路线（未执行）

1. 选择全新workspace，在<workspace>/repo/projects/scanner-app取得固定源码。若之后获准联网使用Git，可进程级使用core.autocrlf=false，clone --no-checkout后detach检出固定commit，保持Git原始字节，不改全局配置。先核对98文件。
2. 另行获得下载/安装范围，人工审阅实际包条款。按passive-downloads.json从官方来源取得指定Windows归档并保留回执；API36 r2仍需原始包与SHA256/内容比对，JDK需先选定有URL和校验的官方完整JDK17。不可用或hash不符就停止，不换镜像或升级来代替。
3. 工具、SDK、Gradle缓存、Android用户目录、临时/输出全部在新workspace，环境变量只对进程设置。不复用旧缓存成功冒充新环境完成；新用户独立处理Google SDK条款，本包不携带本机接受标记。
4. 固定Tesseract归档需位于repo/.tools/downloads/tesseract4android-4.9.0.tar.gz，源码位于同级.tools/sources/Tesseract4Android-4.9.0。原脚本依赖patch命令；前轮只读PATH查询未发现该命令，本轮预检不查找或执行它。本轮未执行或提供自动替代器。按原始/补丁/结果三个hash核对CJK补丁，不能把已解压当成已正确修改。
5. 另行显式准备三资产到app/src/main/assets/tessdata，检查字节/散列和对应许可。公开.gitignore未屏蔽这些模型/字体，只在不推送的独立构建副本准备，禁止把整个工作区纳入公开提交。本轮没有改变gitignore。
6. 对齐resources后预检；之后另行获准在全新缓存解析固定Google Maven/Maven Central依赖并留存图/哈希/许可。Annotation声明1.3.0，应用runtime实际升至1.8.1，不能只补1.3.0 JAR。当前缺正式Gradle锁/verification-metadata。
7. 再断网验证同输入的真实编译、资源、native、lint和APK，例如现有Gradle执行以下命令。native并行还须核对实际命令，不宣称单Gradle worker约束了所有native进程。

~~~text
--offline --no-daemon --max-workers=1 -Pandroid.builder.sdkDownload=false :app:compileDebugJavaWithJavac :app:processDebugResources :app:lintDebug :app:assembleDebug
~~~

这些步骤没有在本轮执行。完整instrumentation源/旧图片fixture缺失，不能宣称默认suite可跑。安装手机/模拟器另属授权范围。记录新APK/ABI与失败证据，新debug证书、时间戳和路径会影响散列，不能期待旧de8bc...成为必然产物。签名冲突先停，不以卸载/清数据解决。

分别记录A历史环境可运行、B独立新环境完成、C位级产物一致。本轮只整理A的输入证据并准备B，未达到B或C。

## 文件边界

后续可供审阅的最小集合：preflight.py、manifest.json、source-files.json、resources.example.json、passive-downloads.json、observed-maven-artifacts.json、maven-license-declarations.json、license-fingerprints.json、README.md、PROVENANCE.md、LICENSE。无工具、模型、字体或APK二进制。

evidence/和tools/是本机资料，含本地路径，不属于可公开payload；不要上传SDK接受标记、授权对话、原始日志或个人资源配置。本轮没有上传任何文件。许可细分见PROVENANCE.md；已有notices不等于所有二进制再分发审查完成。


## 后续获准时的精确源码位置示例（没有执行）

先在全新workspace创建repo/projects父目录；以下路径占位符需要用户替换，目标必须是全新目录。

~~~text
git -c core.autocrlf=false clone --no-checkout https://github.com/texasisalwaysbusy/wangshu-scanner.git "<workspace>/repo/projects/scanner-app"
git -C "<workspace>/repo/projects/scanner-app" -c core.autocrlf=false checkout --detach f5423b0646d232a1b27b3b4ed6ed139408baa34b
~~~

按示例resources配置，对齐布局：

~~~text
<workspace>/repo/projects/scanner-app
<workspace>/repo/.tools/downloads
<workspace>/repo/.tools/gradle-8.13
<workspace>/repo/.tools/sources/Tesseract4Android-4.9.0
<workspace>/inputs/sdk/platforms/android-36
<workspace>/inputs/sdk/build-tools/35.0.0
<workspace>/inputs/sdk/ndk/27.2.12479018
<workspace>/inputs/sdk/cmake/3.22.1
<workspace>/inputs/jdk17
<workspace>/inputs/gradle-cache
~~~

所有下载/解包/资产准备/依赖解析都仍属后续未执行步骤，不因本例而自动获准或自动进行。


## 本地路径与离线承诺的适用范围（审查修正）

仅支持受控Windows本地固定卷。脚本先纯词法拒绝UNC、设备UNC/NT命名空间、根相对/盘符相对路径，再用本地DOS设备命名空间元数据确认盘符直接指向单个HarddiskVolume，随后确认DRIVE_FIXED。网络重定向、SUBST/别名、未知/不可分类盘符和其他平台全部拒绝；不会把未知路径交给lstat/open/stat试探。参数、脚本自身、配置路径、资源根、manifest/source-list和嵌套条目均经过此入口。相对资源根只按已经确认本地的配置目录解释，不查CWD。只接受绝对DOS路径作为顶层参数。

Python解释器必须从可信本地位置启动，脚本/标准库也必须先作为可信本地副本取得；脚本无法撤销解释器在执行本段前读取脚本或导入模块的行为。检查器在进一步数据文件访问前审核自身路径。此策略不是对抗同权限进程并发更换映射/挂载/目录的原子无网沙箱，也不证明虚拟磁盘的底层物理来源；要求受控、本地卷和目录在检查期间不被替换。reparse检查到打开仍有TOCTOU边界。

patch命令目前仅文档告警，不在55项文件预检中执行或完整检测。即使PASS_STATIC_INPUTS_ONLY，也不表示prepare_ocr_source整条准备链可用；返回明确preparation_chain_validated=false及patch_tool_check=NOT_PERFORMED。

对带OFFLINE、RECALL_ON_OPEN、RECALL_ON_DATA_ACCESS或UNPINNED标记的文件/父目录也保守拒绝，避免把可延迟水合的占位文件当成已确认本地内容。这仍不证明任意系统过滤驱动或存储后端不联网。
