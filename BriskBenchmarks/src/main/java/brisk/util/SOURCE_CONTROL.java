package brisk.util;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.locks.ReentrantLock;

import static applications.CONTROL.combo_bid_size;

public class SOURCE_CONTROL {

    static ReentrantLock counterLock = new ReentrantLock(true); // enable fairness policy

    private volatile long counter = 0;

    private CyclicBarrier wm;

    private static SOURCE_CONTROL ourInstance = new SOURCE_CONTROL();

    public static SOURCE_CONTROL getInstance() {
        return ourInstance;
    }


    public void setWM(int number_threads) {
        wm = new CyclicBarrier(number_threads);
    }

    //return the starting point of counter.
    public long GetAndUpdate() {
        counterLock.lock();
        long rt = counter;
        // Always good practice to enclose locks in a try-finally block
        try {
            counter += combo_bid_size;//increment counter by combo_bid_size times...
        } finally {
            counterLock.unlock();
        }
        return rt;
    }

    //return counter.
    public long Get() {
        return counter;
    }

    public CyclicBarrier getWm() {
        return wm;
    }
}
