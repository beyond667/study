package demo.beyond.com.blog.sync;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 卖火车票的例子
 * ReentrantLock方式实现
 */
public class SaleTrainTestReentrantLock implements Runnable {
    private static int count = 1000;
    private static ReentrantLock lock = new ReentrantLock();
    @Override
    public void run() {
        while (count > 0) {
            lock.lock();
            try {
                System.out.println(Thread.currentThread().getName() + "=买后还剩=" + (count--));
            } finally {
                lock.unlock();
            }
        }
    }

    public static void main(String[] args) {
        SaleTrainTestReentrantLock syncThread1 = new SaleTrainTestReentrantLock();
        SaleTrainTestReentrantLock syncThread2 = new SaleTrainTestReentrantLock();
        //不同对象
        Thread thread1 = new Thread(syncThread1, "买家1");
        Thread thread2 = new Thread(syncThread2, "买家2");
        thread1.start();
        thread2.start();
    }
}

