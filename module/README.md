# 图标资源名搜索（现代 LSPosed API）

模块作用：在 `com.android.launcher` 的图标选择面板顶部加入搜索框，并且只对图标包条目的资源名进行不区分大小写的包含匹配。

资源名来自图标条目对象的字符串字段 `a`：

- 桌面 15.8.x：`com.oplus.uxicon.ui.util.d$a.a`
- 桌面 16.6.x：`com.oplus.uxicon.ui.util.d$b.a`

组件名、包名、Java 类名和图标包标题不会参与匹配。索引会读取桌面解析出的图标条目，并合并图标包标准 `drawable.xml` 中声明的完整资源名；搜索结果直接从图标包资源加载，因此与当前已经滚动或显示的页面无关。清空搜索框会从缓存的完整映射恢复全部图标。

搜索框使用桌面自带的 COUI 搜索背景、搜索图标、清除按钮和颜色资源，保持与系统面板一致，并为图标分页器保留剩余高度。

入口使用 `META-INF/xposed/java_init.list`，范围使用 `META-INF/xposed/scope.list`，默认构建目标为 libxposed API 102，另附 API 101 兼容构建。
