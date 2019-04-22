package demo.beyond.com.blog.sync;

public class SynchronizedThread1 implements Runnable {
    private int count;

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
     * SyncThread1:0
     * SyncThread1:1
     * SyncThread1:2
     * SyncThread1:3
     * SyncThread1:4
     * SyncThread2:5
     * SyncThread2:6
     * SyncThread2:7
     * SyncThread2:8
     * SyncThread2:9
     * <p>
     * 如果锁的是同一个对象，那个同一时间只有一个线程可以访问同步代码块中的数据，这样就实现了多线程的同步
     * 注：这时候count是不是静态都能同步。
     */
    public static void main(String[] args) {
        SynchronizedThread1 syncThread = new SynchronizedThread1();
        //同一个对象
        Thread thread1 = new Thread(syncThread, "SyncThread1");
        Thread thread2 = new Thread(syncThread, "SyncThread2");
        thread1.start();
        thread2.start();
    }
}
