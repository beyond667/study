package demo.beyond.com.blog.sync;

/**
 * 卖火车票的例子
 * synchronized方式实现
 */
public class SaleTrainTestVolatile implements Runnable {
    private volatile int count = 10000;

    @Override
    public void run() {
        while (count > 0) {
            System.out.println(Thread.currentThread().getName() + "=买后还剩=" + (count--));
        }
    }

    public static void main(String[] args) {
        SaleTrainTestVolatile saleTrainTestVolatile = new SaleTrainTestVolatile();
        for (int i = 0; i < 10; i++) {
            new Thread(saleTrainTestVolatile, "买家" + i).start();
        }
    }
}

