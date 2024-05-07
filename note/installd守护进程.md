#### 前言

在[PMS启动流程](https://github.com/beyond667/study/blob/master/note/PMS%E5%90%AF%E5%8A%A8%E6%B5%81%E7%A8%8B.md)安装流程的最后一步commit时调用executePostCommitSteps，最终会执行到Installer.dexopt来通过JNI层去真正安装，PMS只是对apk做了拷贝和解析，真正干活的是在installd守护进程。为什么不直接在PMS里做呢？是因为PMS是运行在system_server进程，此进程只有systemy用户权限无root用户权限，无访问应用程序目录的权限，无法访问/data/data下的目录，而installd进程有root权限，可以访问此目录。

```shell
USER     PID  PPID VSZ       RSS     WCHAN                   ADDR NAME    
system   1717 615  19826348  296392  do_epoll_wait           0    system_server
root     1192 1    12499180  3248    binder_ioctl_write_read 0    installd
```



