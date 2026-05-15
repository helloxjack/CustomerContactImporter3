不用 Android Studio 的云端打包方法（GitHub Actions）

适合情况：电脑上没有 Android Studio，也不想配置 Android SDK，但想生成可安装的 APK。

一、准备
1. 注册或登录 GitHub。
2. 新建一个仓库，例如 CustomerContactImporter。
3. 把本项目文件夹里的所有内容上传到仓库根目录。
   注意：上传后仓库根目录应直接能看到 app 文件夹、build.gradle、settings.gradle、.github 文件夹。

二、开始云端打包
1. 打开 GitHub 仓库页面。
2. 点击顶部 Actions。
3. 选择 Build Android APK。
4. 点击 Run workflow。
5. 等待任务运行完成，通常需要几分钟。

三、下载 APK
1. 打包成功后，打开刚运行完成的 workflow。
2. 页面底部找到 Artifacts。
3. 下载 customer-contact-importer-group-version-debug-apk。
4. 解压后得到 app-debug.apk。
5. 把 app-debug.apk 发到小米/安卓手机安装。

四、手机安装提示
1. 手机可能会提示“禁止安装未知来源应用”，需要允许当前文件管理器/微信/浏览器安装未知来源应用。
2. 第一次写入通讯录时，App 会申请通讯录权限，请选择允许。

五、如果打包失败
把 GitHub Actions 里红色报错截图发给 ChatGPT，我会帮你判断是 SDK、Gradle、权限还是代码问题。
