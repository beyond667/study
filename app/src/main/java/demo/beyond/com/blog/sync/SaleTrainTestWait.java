package demo.beyond.com.blog.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产者消费者问题
 * wait notify 方式实现
 */
public class SaleTrainTestWait {

    private Ticket mTicket = new Ticket();

    public void produce() {
        synchronized (this) {
            while (mTicket.isFull()) {
                try {
                    System.out.println(Thread.currentThread().getName() + "池子已经满了，售票员等待中。。。" + mTicket.innerList.size());
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            mTicket.add();
            notifyAll();
        }
    }

    public void consume() {
        synchronized (this) {
            while (mTicket.isEmpty()) {
                try {
                    System.out.println(Thread.currentThread().getName() + "池子已经空了,买家等待中。。。" + mTicket.innerList.size());
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            mTicket.remove();
            notifyAll();
        }
    }

    private class Ticket {
        private static final int MAX_CAPACITY = 10;
        private List innerList = new ArrayList<>(MAX_CAPACITY);

        void add() {
            if (isFull()) {
                throw new IndexOutOfBoundsException();
            } else {
                innerList.add(new Object());
            }
            System.out.println(Thread.currentThread().getName() + " 生产后池子剩余" + innerList.size());
        }

        void remove() {
            if (isEmpty()) {
                throw new IndexOutOfBoundsException();
            } else {
                innerList.remove((innerList.size() - 1));
            }
            System.out.println(Thread.currentThread().getName() + " 消费后池子剩余" + innerList.size());
        }

        boolean isEmpty() {
            return innerList.isEmpty();
        }

        boolean isFull() {
            return innerList.size() == MAX_CAPACITY;
        }
    }

    static SaleTrainTestWait sth = new SaleTrainTestWait();

    public static void main(String[] args) {
        Product productRun = new Product();
        Consume consumeRun = new Consume();
        //如果只有一个买家和售票员
//        new Thread(consumeRun, "买家").start();
//        new Thread(productRun, "售票员").start();

        //多个买家 多个售票员
        for (int i = 0; i < 10; i++) {
            new Thread(consumeRun,"买家"+i).start();
        }
        for (int i = 0; i < 10; i++) {
            new Thread(productRun,"售票员"+i).start();
        }
    }

    static class Product implements Runnable {
        int count = 10000;

        @Override
        public void run() {
            while (count-- > 0) {
                sth.produce();
            }
        }
    }

    static class Consume implements Runnable {
        int count = 10000;

        @Override
        public void run() {
            while (count-- > 0) {
                sth.consume();
            }
        }
    }
}

