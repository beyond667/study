---
title: Hexo搭建github博客
date: 2019-04-13 22:32:37
tags: Hexo
cover_img: /img/背景1.jpg
---

### 前言

想静下心来专门写些博客，可以选择简书或者csdn等，甚至可以自己搭个服务器买个域名玩，不过最省钱最快速逼格再高一点点的我认为还是用github.io上面写。

花了一天时间终于玩通了Hexo，并且关联到github上，遇到一点点小坑，记录下来，希望后面玩的同学不要踩同样的坑。

### 准备工作

- 安装node.js，Git（省略，去官网下载安装）

- Github上创建新的项目 。
  
  注意在Setting中Repository name必须是<font color=red size=5>你的用户id+.github.io</font>命名，你不可以任性的起其他任何名字，否则你就会像我一样卡在这里浪费大量时间。
  
  ![1.1](/images/1.1.jpg)

### 搭建Hexo环境

1. 安装：npm install -g hexo 

2. 初始化：hexo init

3. 生成：hexo g  (g是generate的缩写)

4. 运行：hexo s (s是server的缩写)

5. 发布：hexo d (d是deploy的缩写)
   
   发布的时候需要修改根目录下_config.yml文件的deploy
   
   ```
   deploy:
     type: git
     repository: git@github.com:beyond667/beyond667.github.io.git
     branch: master
   ```

### 更换Hexo主题

我认为这个是Hexo的亮点了，可以去hexo的官网找一些漂亮的主题，这样你的博客就美观多了。[Hexo官网主题链接](https://hexo.io/themes/)

![1.2](/images/1.2.jpg)

使用起来也是超级简单。

```
  git clone https://github.com/huyingjie/hexo-theme-A-RSnippet.git themes/a-rsnippet
```

下载完的主题会在themes/a-rsnippet下，这时候需要修改根目录下_config.yml的theme。

```
  theme: a-rsnippet
```

应用完需要clean下hexo,再重新生成和运行就能看到新的主题。

```
  hexo clean
  hexo g
  hexo s
```

#### 修改模版

很多时候你下载的模版会带一些默认的信息，比如用户，背景图片，版权等，要修改这些信息，你需要到themes/你的模版/layout/_parrtial文件夹下修改 header footer等模版信息，你自己新加的图片资源需要放到themes/你的模版/source/img文件夹下，引用的时候直接/img/图片名字就可以了。


