package demo.beyond.com.blog.sync;

/**
 * 卖火车票的例子
 * synchronized方式实现
 */
public class SaleTrainTestSynchronized implements Runnable {
    private static int count = 100;

    @Override
    public void run() {
        while (count > 0) {
            synchronized (SaleTrainTestSynchronized.class) {
                System.out.println(Thread.currentThread().getName() + "=买后还剩=" + (count--));
            }
        }
    }

    public static void main(String[] args) {
        SaleTrainTestSynchronized syncThread1 = new SaleTrainTestSynchronized();
        SaleTrainTestSynchronized syncThread2 = new SaleTrainTestSynchronized();
        //不同对象
        Thread thread1 = new Thread(syncThread1, "买家1");
        Thread thread2 = new Thread(syncThread2, "买家2");
        thread1.start();
        thread2.start();
    }
}

