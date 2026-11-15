# ColorOS Launcher Icon Resource Search

适用于 ColorOS 系统桌面图标选择面板的 LSPosed 模块。

## 功能

- 在图标选择面板顶部加入搜索框。
- 只匹配图标包条目的资源名，大小写不敏感。
- 合并桌面解析出的图标条目和图标包 `drawable.xml` 中声明的资源名。
- 搜索结果直接从图标包资源加载，不依赖当前已经滚动或显示的页面。
- 使用桌面自带的 COUI 搜索背景、图标、清除按钮和颜色资源。

## 兼容范围

- ColorOS 桌面 15.8.x：`com.oplus.uxicon.ui.util.d$a`
- ColorOS 桌面 16.6.x：`com.oplus.uxicon.ui.util.d$b`
- LSPosed / libxposed API 101、102
- Android 8.0 及以上

## 安装

1. 根据设备上的 LSPosed API 版本选择 `releases/` 中的 APK。
2. 在 LSPosed 中启用模块，并将作用域设置为 `com.android.launcher`。
3. 重启系统桌面。
4. 打开桌面图标更换面板，即可使用搜索框。

## 源码

核心实现位于 `module/src/com/codex/launchericonsearch/IconResourceSearchModule.java`。
