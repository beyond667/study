### 前言

在[深入理解WMS](https://github.com/beyond667/study/blob/master/note/%E6%B7%B1%E5%85%A5%E7%90%86%E8%A7%A3WMS.md)一节中，我们知道WMS只是负责窗口的管理，并不负责窗口里面的内容(具体的view)的渲染和显示，比如手机桌面上显示有状态栏，导航栏，桌面图标列表，壁纸等，这几个窗口是怎么合成并同时显示出来的？这就要靠SurfaceFlinger来负责。本文基于Android13。

### SurfaceFlinger原理