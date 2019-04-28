package demo.beyond.com.blog.sync;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 卖火车票的例子
 * Volatile || AtomicInteger 方式实现
 */
public class SaleTrainTestVolatile implements Runnable {

    //直接count--是不行的，volatile不具备原子性 (方式一)
    //可以用AtomicInteger来做自增自减 （方式二）

//    (方式一)
//    private static volatile int count = 10000;
//    @Override
//    public void run() {
//        while (count > 0) {
//
//            System.out.println(Thread.currentThread().getName() + "=买后还剩=" + (count--));
//        }
//    }


    //    (方式二)
    public static AtomicInteger count = new AtomicInteger(10000);
    @Override
    public void run() {
        while (count.get() > 0) {
            System.out.println(Thread.currentThread().getName() + "=买后还剩=" + (count.decrementAndGet()));
        }
    }


    public static void main(String[] args) {
        for (int i = 0; i < 10; i++) {
            new Thread(new SaleTrainTestVolatile(), "买家" + i).start();
        }
    }
}

