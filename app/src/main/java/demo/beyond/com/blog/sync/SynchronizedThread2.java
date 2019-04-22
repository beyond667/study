package demo.beyond.com.blog.sync;

public class SynchronizedThread2 implements Runnable {
    private static int count;

    @Override
    public void run() {
        //锁的是同一个对象
        synchronized (this) {
            for (int i = 0; i < 5000; i++) {
                System.out.println(Thread.currentThread().getName() + ":" + (count++));
            }
        }
    }

    /**
     * 中间出现线程不同步的情况：
     * SyncThread1:0
     * SyncThread2:0
     * SyncThread1:1
     * SyncThread2:1
     * SyncThread1:2
     * SyncThread2:3
     * SyncThread1:4
     * SyncThread2:5
     * SyncThread2:6
     * SyncThread1:6
     * <p>
     * 如果锁的是不同对象，对于静态常量由于是类级别的，这时候修改静态常量会出现线程不同步的情况
     * 对于非静态常量，由于访问的是对象自己的常量，这里不会有线程同步问题
     * (线程同步的问题是由于多线程访问同一个常量导致的，这里都不是同一个线程，所以也就不会有线程同步问题)。
     * 避免这种情况的方法就是锁定类 synchronized(SynchronizedThread2.class),而不是synchronized(this)
     * 因为这时候this指的是对象
     */
    public static void main(String[] args) {
        SynchronizedThread2 syncThread1 = new SynchronizedThread2();
        SynchronizedThread2 syncThread2 = new SynchronizedThread2();
        //不同对象
        Thread thread1 = new Thread(syncThread1, "SyncThread1");
        Thread thread2 = new Thread(syncThread2, "SyncThread2");
        thread1.start();
        thread2.start();
    }
}

