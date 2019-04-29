package demo.beyond.com.blog.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 卖火车票的例子
 * wait notify 方式实现
 */
public class SaleTrainTestWait  {

    private Buffer mBuf = new Buffer();

    public void produce() {
        synchronized (this) {
            while (mBuf.isFull()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            mBuf.add();
            notifyAll();
        }
    }

    public void consume() {
        synchronized (this) {
            while (mBuf.isEmpty()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            mBuf.remove();
            notifyAll();
        }
    }

    private class Buffer {
        private static final int MAX_CAPACITY = 10;
        private List innerList = new ArrayList<>(MAX_CAPACITY);

        void add() {
            if (isFull()) {
                throw new IndexOutOfBoundsException();
            } else {
                innerList.add(new Object());
            }
            System.out.println(Thread.currentThread().toString() + " add");

        }

        void remove() {
            if (isEmpty()) {
                throw new IndexOutOfBoundsException();
            } else {
                innerList.remove(MAX_CAPACITY - 1);
            }
            System.out.println(Thread.currentThread().toString() + " remove");
        }

        boolean isEmpty() {
            return innerList.isEmpty();
        }

        boolean isFull() {
            return innerList.size() == MAX_CAPACITY;
        }
    }

    public static void main(String[] args) {
        final SaleTrainTestWait sth = new SaleTrainTestWait();
        Runnable runProduce = new Runnable() {
            int count = 10000;

            @Override
            public void run() {
                while (count-- > 0) {
//                    System.out.println(Thread.currentThread().getName()+"=出票后剩余=="+count);
                    sth.produce();
                }
            }
        };
        Runnable runConsume = new Runnable() {
            int count = 10000;

            @Override
            public void run() {
                while (count-- > 0) {
//                    System.out.println(Thread.currentThread().getName()+"=买后剩余=="+count);
                    sth.consume();
                }
            }
        };
        for (int i = 0; i < 10; i++) {
            new Thread(runConsume,"买家"+i).start();
        }
        for (int i = 0; i < 10; i++) {
            new Thread(runProduce,"售票员"+i).start();
        }
    }
}

